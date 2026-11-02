package dev.proximity.core.interfaces

import dev.proximity.core.state.PeerRef
import kotlinx.coroutines.flow.Flow

/**
 * 확인 단계 임시 링크 (ADR-008, 04-session-flows §4.2). 바이트만 옮긴다.
 * 메시지 조각 나누기·암호화·프로토콜은 core(RendezvousEngine)가 맡는다.
 */
interface RendezvousTransport {
    /** 다른 기기가 내 Rendezvous 서비스에 접속하면 나온다. */
    val incoming: Flow<RendezvousLink>

    fun openServer()

    fun closeServer()

    /** [peer]의 Rendezvous 서비스에 central로 접속한다. 실패하면 null. */
    suspend fun connect(peer: PeerRef): RendezvousLink?
}

interface RendezvousLink {
    /** 받은 조각. 링크가 끊기면 이미 받은 것을 다 내보낸 뒤 끝난다 — core는 이 끝을 연결 종료로 본다. */
    val inbound: Flow<ByteArray>

    /** 링크가 끊기면 한 번 나온다. */
    val closed: Flow<Unit>

    /** 흐름 제어로 보낼 수 있을 때까지 기다린다. 끊겼으면 false. */
    suspend fun send(bytes: ByteArray): Boolean

    /** 협상된 MTU. 조각은 mtu-3 바이트를 넘지 않는다. */
    fun mtu(): Int

    fun close()
}
