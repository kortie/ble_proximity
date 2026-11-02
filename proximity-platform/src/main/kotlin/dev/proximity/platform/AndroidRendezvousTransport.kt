package dev.proximity.platform

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.proximity.core.interfaces.RendezvousLink
import dev.proximity.core.interfaces.RendezvousTransport
import dev.proximity.core.state.PeerRef
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Rendezvous 링크 (ADR-008): GATT 서버에 양방향 서비스를 올려 받고, 상대 광고 기기로 클라이언트 접속한다.
 * 상대 기기는 [ble]가 스캔하며 짝지어 둔 tempId → BluetoothDevice로 찾는다.
 */
class AndroidRendezvousTransport internal constructor(
    private val context: Context,
    private val ble: AndroidBleTransport,
    private val hub: GattServerHub,
    private val connectTimeoutMs: Long,
) : RendezvousTransport {
    constructor(context: Context, ble: AndroidBleTransport, connectTimeoutMs: Long) :
        this(context, ble, GattServerHub(context), connectTimeoutMs)

    private val incomingFlow = MutableSharedFlow<RendezvousLink>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val serverLinks = ConcurrentHashMap<String, ServerLink>()
    private val spec = GattUuids.RENDEZVOUS

    override val incoming: Flow<RendezvousLink> = incomingFlow

    override fun openServer() {
        hub.addService(
            spec,
            object : DuplexServerListener {
                override fun onSubscribed(device: BluetoothDevice) {
                    val link = ServerLink(device)
                    serverLinks.put(device.address, link)?.closeLocally()
                    incomingFlow.tryEmit(link)
                }

                override fun onWrite(device: BluetoothDevice, value: ByteArray) {
                    serverLinks[device.address]?.receive(value)
                }

                override fun onDisconnected(device: BluetoothDevice) {
                    serverLinks.remove(device.address)?.closeLocally()
                }
            },
        )
    }

    override fun closeServer() {
        serverLinks.values.forEach { it.close() }
        serverLinks.clear()
        hub.removeService(spec)
    }

    override suspend fun connect(peer: PeerRef): RendezvousLink? {
        if (!hub.hasPermission()) return null
        val client = connectFollowingAddress(connectTimeoutMs, { ble.deviceFor(peer.tempId) }) { DuplexGattClient(context, it, spec) }
        return client?.let(::ClientLink)
    }

    private inner class ServerLink(private val device: BluetoothDevice) : RendezvousLink {
        private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
        private val closedFlow = MutableSharedFlow<Unit>(replay = 1)
        override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()
        override val closed: Flow<Unit> = closedFlow

        fun receive(value: ByteArray) {
            inboundChannel.trySend(value)
        }

        override suspend fun send(bytes: ByteArray): Boolean = hub.notify(device, spec, bytes)

        override fun mtu(): Int = hub.mtu(device)

        override fun close() {
            hub.disconnect(device)
            closeLocally()
        }

        fun closeLocally() {
            serverLinks.remove(device.address, this)
            inboundChannel.close()
            closedFlow.tryEmit(Unit)
        }
    }

    private class ClientLink(private val client: DuplexGattClient) : RendezvousLink {
        private val closedFlow = MutableSharedFlow<Unit>(replay = 1)
        override val inbound: Flow<ByteArray> = client.inbound.receiveAsFlow()
        override val closed: Flow<Unit> = closedFlow

        override suspend fun send(bytes: ByteArray): Boolean = client.write(bytes)

        override fun mtu(): Int = client.mtu

        override fun close() {
            client.close()
            closedFlow.tryEmit(Unit)
        }
    }
}
