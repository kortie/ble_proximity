package dev.proximity.testing

import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.InboundFrame
import dev.proximity.core.interfaces.LinkEvent
import dev.proximity.core.interfaces.LinkId
import dev.proximity.core.interfaces.SessionTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 호스트 + 클라이언트 N의 가상 세션 망 (ADR-002 스타). 호스트가 여럿이면 sessionTag로 고른다 (S9 코드 합류 시험).
 * 유실([dropRule]), 지연([autoDeliver] = false 후 [deliverPending]), 끊김([disconnect])을 주입할 수 있다.
 */
class FakeSessionNetwork(private val defaultMtu: Int = 185) {
    /** 한 프레임의 경로. [toClient]가 false면 업링크(클라이언트 → 호스트). */
    class Route(val link: LinkId, val toClient: Boolean, val bytes: ByteArray)

    var dropRule: (Route) -> Boolean = { false }
    var autoDeliver = true

    /** false면 새 연결을 받지 않는다 (호스트가 사라진 상황). */
    var acceptingConnections = true

    private val gates = mutableMapOf<LinkId, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /** 이 링크로 가는 호스트 송신을 [unblock]까지 멈춘다 (느린 링크 흉내). */
    fun block(link: LinkId) {
        gates.getOrPut(link) { kotlinx.coroutines.CompletableDeferred() }
    }

    fun unblock(link: LinkId) {
        gates.remove(link)?.complete(Unit)
    }

    internal suspend fun awaitGate(link: LinkId) {
        gates[link]?.await()
    }

    private val hosts = mutableListOf<FakeHostTransport>()
    private val hostOf = mutableMapOf<LinkId, FakeHostTransport>()
    private val clients = mutableMapOf<LinkId, FakeClientTransport>()
    private val pending = ArrayDeque<Route>()
    private var nextLink = 1

    fun host(): FakeHostTransport = FakeHostTransport(this).also { hosts += it }

    fun client(): FakeClientTransport = FakeClientTransport(this)

    fun linkOf(client: FakeClientTransport): LinkId = client.link ?: error("연결되지 않은 클라이언트")

    fun disconnect(link: LinkId) {
        val client = clients.remove(link) ?: return
        client.onDown()
        hostOf.remove(link)?.onDown(link)
    }

    /** 호스트 프로세스가 사라진 것처럼: 그 호스트의 링크를 모두 끊고 더는 받지 않는다 (END·LEAVE 없음). */
    fun crash(host: FakeHostTransport) {
        host.crashed() // 먼저 죽인다: 끊긴 클라이언트가 곧바로 다시 붙지 못하게
        hostOf.filterValues { it === host }.keys.toList().forEach(::disconnect)
    }

    /** 보류된 프레임을 보낸 순서대로 전달한다. */
    fun deliverPending() {
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }

    internal fun connect(client: FakeClientTransport, ref: HostRef): ByteArray? {
        if (!acceptingConnections) return null
        val h = hosts.firstOrNull { it.sessionTag != null && it.sessionTag == ref.sessionTag } ?: return null
        val link = LinkId(nextLink++)
        clients[link] = client
        hostOf[link] = h
        client.onUp(link)
        h.onUp(link)
        return h.sessionInfo
    }

    internal fun isUp(link: LinkId) = link in clients

    internal fun mtu() = defaultMtu

    internal fun route(route: Route): Boolean {
        if (!isUp(route.link)) return false
        if (dropRule(route)) return true
        if (autoDeliver) deliver(route) else pending.addLast(route)
        return true
    }

    private fun deliver(route: Route) {
        if (!isUp(route.link)) return
        if (route.toClient) clients[route.link]?.receive(route.bytes) else hostOf[route.link]?.receive(route.link, route.bytes)
    }
}

class FakeHostTransport internal constructor(private val net: FakeSessionNetwork) : SessionTransport.Host {
    private val inboundFlow = MutableSharedFlow<InboundFrame>(extraBufferCapacity = BUFFER)
    private val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = BUFFER)
    override val inbound: Flow<InboundFrame> = inboundFlow
    override val linkEvents: Flow<LinkEvent> = events

    internal var sessionTag: Int? = null
        private set
    internal var sessionInfo: ByteArray = ByteArray(0)
        private set

    override suspend fun open(sessionTag: Int, sessionInfo: ByteArray) {
        this.sessionTag = sessionTag
        this.sessionInfo = sessionInfo.copyOf()
    }

    override fun updateSessionInfo(sessionInfo: ByteArray) {
        this.sessionInfo = sessionInfo.copyOf()
    }

    override suspend fun send(link: LinkId, frame: ByteArray): Boolean {
        if (dead) return false
        net.awaitGate(link)
        return net.route(FakeSessionNetwork.Route(link, toClient = true, bytes = frame.copyOf()))
    }

    override fun mtu(link: LinkId): Int = net.mtu()

    override fun close(link: LinkId) = net.disconnect(link)

    override suspend fun shutdown() {
        sessionTag = null
    }

    internal var dead = false
        private set

    internal fun crashed() {
        dead = true
        sessionTag = null
    }

    internal fun onUp(link: LinkId) = emit(events, LinkEvent.Up(link))

    internal fun onDown(link: LinkId) = emit(events, LinkEvent.Down(link))

    internal fun receive(link: LinkId, bytes: ByteArray) {
        if (!dead) emit(inboundFlow, InboundFrame(link, bytes))
    }
}

class FakeClientTransport internal constructor(private val net: FakeSessionNetwork) : SessionTransport.Client {
    private val inboundFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = BUFFER)
    private val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = BUFFER)
    override val inbound: Flow<ByteArray> = inboundFlow
    override val linkEvents: Flow<LinkEvent> = events

    internal var link: LinkId? = null
        private set

    override suspend fun connect(hostRef: HostRef): ByteArray? = net.connect(this, hostRef)

    override suspend fun send(frame: ByteArray): Boolean {
        val l = link ?: return false
        return net.route(FakeSessionNetwork.Route(l, toClient = false, bytes = frame.copyOf()))
    }

    override fun mtu(): Int = net.mtu()

    override fun disconnect() {
        link?.let(net::disconnect)
    }

    internal fun onUp(l: LinkId) {
        link = l
        emit(events, LinkEvent.Up(l))
    }

    internal fun onDown() {
        val l = link ?: return
        link = null
        emit(events, LinkEvent.Down(l))
    }

    internal fun receive(bytes: ByteArray) = emit(inboundFlow, bytes)
}

private const val BUFFER = 1024

private fun <T> emit(flow: MutableSharedFlow<T>, value: T) {
    check(flow.tryEmit(value)) { "Fake 버퍼가 찼다" }
}
