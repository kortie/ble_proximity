package dev.proximity.testing

import dev.proximity.core.interfaces.RendezvousLink
import dev.proximity.core.interfaces.RendezvousTransport
import dev.proximity.core.state.PeerRef
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Rendezvous 임시 링크의 가상 망 (ADR-008). 기기는 자기 현재 tempId([tempId])로 찾아진다.
 * 링크로 오간 모든 바이트를 [sniffed]에 남긴다 (평문 노출 검사용). [failConnectTo]에 넣은 tempId로는 접속이 실패한다.
 */
class FakeRendezvousNetwork(private val mtu: Int = 185) {
    private val endpoints = mutableListOf<FakeRendezvousTransport>()
    val sniffed = mutableListOf<ByteArray>()
    val failConnectTo = mutableSetOf<Int>()

    /** 링크로 가는 바이트를 바꿔치기한다 (변조 시험). null을 돌려주면 그 조각을 버린다. */
    var tamper: ((ByteArray) -> ByteArray?)? = null

    /** 지금까지 만들어진 링크 쌍 수 (중복 연결 검사용). */
    var linksCreated = 0
        private set

    fun endpoint(tempId: () -> Int): FakeRendezvousTransport = FakeRendezvousTransport(this, tempId).also { endpoints += it }

    internal fun connect(from: FakeRendezvousTransport, peer: PeerRef): RendezvousLink? {
        if (peer.tempId in failConnectTo) return null
        val target = endpoints.firstOrNull { it !== from && it.serving && it.tempId() == peer.tempId } ?: return null
        val client = FakeRendezvousLink(this)
        val server = FakeRendezvousLink(this)
        client.other = server
        server.other = client
        linksCreated++
        target.accept(server)
        return client
    }

    internal fun mtu() = mtu
}

class FakeRendezvousTransport internal constructor(
    private val net: FakeRendezvousNetwork,
    internal val tempId: () -> Int,
) : RendezvousTransport {
    private val flow = MutableSharedFlow<RendezvousLink>(extraBufferCapacity = 64)
    override val incoming: Flow<RendezvousLink> = flow
    internal var serving = false
        private set

    override fun openServer() {
        serving = true
    }

    override fun closeServer() {
        serving = false
    }

    override suspend fun connect(peer: PeerRef): RendezvousLink? = net.connect(this, peer)

    internal fun accept(link: FakeRendezvousLink) {
        check(flow.tryEmit(link)) { "incoming 버퍼가 찼다" }
    }
}

/** 받는 쪽이 아직 듣기 전에 온 데이터도 잃지 않도록 채널에 쌓는다 (실제 링크도 같은 경쟁이 있다). */
class FakeRendezvousLink internal constructor(private val net: FakeRendezvousNetwork) : RendezvousLink {
    internal var other: FakeRendezvousLink? = null
    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val closedFlow = MutableSharedFlow<Unit>(replay = 1)
    var isOpen = true
        private set

    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()
    override val closed: Flow<Unit> = closedFlow

    override suspend fun send(bytes: ByteArray): Boolean {
        val peer = other ?: return false
        if (!isOpen || !peer.isOpen) return false
        net.sniffed += bytes.copyOf()
        val delivered = net.tamper?.invoke(bytes.copyOf()) ?: if (net.tamper == null) bytes.copyOf() else return true
        peer.inboundChannel.trySend(delivered)
        return true
    }

    override fun mtu(): Int = net.mtu()

    override fun close() {
        if (!isOpen) return
        isOpen = false
        inboundChannel.close() // 이미 받은 조각은 다 내보낸 뒤 inbound가 끝난다
        closedFlow.tryEmit(Unit)
        other?.close()
    }
}
