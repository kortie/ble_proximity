package dev.proximity.core.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 세션 데이터 링크 (ADR-002 스타 구조). 프레임 바이트만 옮긴다.
 * 순서·재전송·암호화는 core(SessionRouter, S6)가 맡는다. BLE 외 구현으로 바꿀 수 있다.
 */
interface SessionTransport {
    /** 호스트 쪽: GATT 서버. 클라이언트 여러 개가 붙는다. */
    interface Host {
        val inbound: Flow<InboundFrame>
        val linkEvents: Flow<LinkEvent>

        /** 세션 서비스를 연다. [sessionInfo]는 클라이언트가 연결 전에 읽는 SESSION_INFO 내용이다. */
        suspend fun open(sessionTag: Int, sessionInfo: ByteArray)

        /** 세션 정보(빈 자리 등)가 바뀌면 갱신한다. */
        fun updateSessionInfo(sessionInfo: ByteArray)

        /** 흐름 제어로 보낼 수 있을 때까지 기다린다. 링크가 끊겼으면 false. */
        suspend fun send(link: LinkId, frame: ByteArray): Boolean

        /** 협상된 MTU. 프레임은 mtu-3 바이트를 넘지 않는다. */
        fun mtu(link: LinkId): Int

        fun close(link: LinkId)

        suspend fun shutdown()
    }

    /** 클라이언트 쪽: 호스트 하나에만 연결한다. */
    interface Client {
        val inbound: Flow<ByteArray>
        val linkEvents: Flow<LinkEvent>

        /** [hostRef]의 호스트에 연결하고 SESSION_INFO를 읽는다. 실패하면 null. */
        suspend fun connect(hostRef: HostRef): ByteArray?

        suspend fun send(frame: ByteArray): Boolean

        fun mtu(): Int

        fun disconnect()
    }
}

/** 호스트 쪽에서 링크(연결된 클라이언트 하나)를 가리키는 값. */
data class LinkId(val value: Int)

/** 호스트를 찾는 단서. 광고의 sessionTag + isHost (04-session-flows §2). */
data class HostRef(val sessionTag: Int)

class InboundFrame(val link: LinkId, val bytes: ByteArray)

sealed interface LinkEvent {
    val link: LinkId

    data class Up(override val link: LinkId) : LinkEvent

    data class Down(override val link: LinkId) : LinkEvent
}
