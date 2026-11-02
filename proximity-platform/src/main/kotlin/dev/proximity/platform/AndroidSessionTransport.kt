package dev.proximity.platform

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.InboundFrame
import dev.proximity.core.interfaces.LinkEvent
import dev.proximity.core.interfaces.LinkId
import dev.proximity.core.interfaces.SessionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 세션 링크 (S6, ADR-002 스타). 호스트는 GATT 서버의 세션 서비스, 클라이언트는 그 서비스에 접속한다.
 * 프레임 바이트만 옮긴다 — 순서·재전송·암호화는 core SessionRouter/SessionClient가 맡는다.
 */
internal class AndroidSessionHost(private val hub: GattServerHub) : SessionTransport.Host {
    private val inboundFlow = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 4096)
    private val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 256)
    private val linkOf = ConcurrentHashMap<String, LinkId>()
    private val deviceOf = ConcurrentHashMap<LinkId, BluetoothDevice>()
    private val nextLink = AtomicInteger(1)

    @Volatile
    private var info = ByteArray(0)

    override val inbound: Flow<InboundFrame> = inboundFlow
    override val linkEvents: Flow<LinkEvent> = events

    override suspend fun open(sessionTag: Int, sessionInfo: ByteArray) {
        info = sessionInfo.copyOf()
        hub.addService(
            GattUuids.SESSION,
            object : DuplexServerListener {
                override fun onSubscribed(device: BluetoothDevice) {
                    linkOf.remove(device.address)?.let { old -> deviceOf.remove(old); events.tryEmit(LinkEvent.Down(old)) }
                    val id = LinkId(nextLink.getAndIncrement())
                    linkOf[device.address] = id
                    deviceOf[id] = device
                    events.tryEmit(LinkEvent.Up(id))
                }

                override fun onWrite(device: BluetoothDevice, value: ByteArray) {
                    linkOf[device.address]?.let { inboundFlow.tryEmit(InboundFrame(it, value)) }
                }

                override fun onDisconnected(device: BluetoothDevice) {
                    linkOf.remove(device.address)?.let { id ->
                        deviceOf.remove(id)
                        events.tryEmit(LinkEvent.Down(id))
                    }
                }

                override fun onRead(device: BluetoothDevice): ByteArray = info
            },
        )
    }

    override fun updateSessionInfo(sessionInfo: ByteArray) {
        info = sessionInfo.copyOf()
    }

    override suspend fun send(link: LinkId, frame: ByteArray): Boolean {
        val device = deviceOf[link] ?: return false
        return hub.notify(device, GattUuids.SESSION, frame)
    }

    override fun mtu(link: LinkId): Int = deviceOf[link]?.let(hub::mtu) ?: GattServerHub.DEFAULT_MTU

    override fun close(link: LinkId) {
        deviceOf[link]?.let(hub::disconnect)
    }

    override suspend fun shutdown() {
        deviceOf.values.forEach(hub::disconnect)
        hub.removeService(GattUuids.SESSION)
    }
}

internal class AndroidSessionClient(
    private val context: Context,
    private val ble: AndroidBleTransport,
    private val hub: GattServerHub,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Long,
) : SessionTransport.Client {
    private val inboundFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
    private val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
    private val nextLink = AtomicInteger(1)

    @Volatile
    private var current: DuplexGattClient? = null

    override val inbound: Flow<ByteArray> = inboundFlow
    override val linkEvents: Flow<LinkEvent> = events

    override suspend fun connect(hostRef: HostRef): ByteArray? {
        if (!hub.hasPermission()) return null
        val client = connectFollowingAddress(connectTimeoutMs, { ble.hostDeviceFor(hostRef.sessionTag) }) {
            DuplexGattClient(context, it, GattUuids.SESSION)
        } ?: return null
        current?.close()
        current = client
        val id = LinkId(nextLink.getAndIncrement())
        events.tryEmit(LinkEvent.Up(id))
        scope.launch {
            // 이 연결의 알림을 계속 넘긴다. 채널이 닫히면(끊김) Down
            for (frame in client.inbound) inboundFlow.emit(frame)
            events.tryEmit(LinkEvent.Down(id))
        }
        return client.readValue ?: ByteArray(0)
    }

    override suspend fun send(frame: ByteArray): Boolean = current?.write(frame) ?: false

    override fun mtu(): Int = current?.mtu ?: GattServerHub.DEFAULT_MTU

    override fun disconnect() {
        current?.close()
    }
}

/**
 * Android 무선 한 벌: 광고·스캔, Rendezvous, 세션 호스트/클라이언트가 GATT 서버 하나를 같이 쓴다.
 * 앱은 이것 하나를 만들어 core에 인터페이스로 넘긴다.
 */
class AndroidRadio(context: Context, clock: dev.proximity.core.interfaces.Clock, scope: CoroutineScope, connectTimeoutMs: Long) {
    private val appContext = context.applicationContext
    private val hub = GattServerHub(appContext)
    val ble = AndroidBleTransport(appContext, clock)
    val rendezvous: dev.proximity.core.interfaces.RendezvousTransport = AndroidRendezvousTransport(appContext, ble, hub, connectTimeoutMs)
    val sessionHost: SessionTransport.Host = AndroidSessionHost(hub)
    private val clientFactory = { AndroidSessionClient(appContext, ble, hub, scope, connectTimeoutMs) }

    /** 합류마다 새 클라이언트 링크 (SessionHostJoiner의 transportFactory). */
    fun newSessionClient(): SessionTransport.Client = clientFactory()
}
