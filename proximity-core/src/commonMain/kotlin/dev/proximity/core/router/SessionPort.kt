package dev.proximity.core.router

import dev.proximity.core.api.Participant

/**
 * GameClient가 올라타는 세션 끝점 (S8). 원격 기기는 [SessionClient], 호스트 기기는 [LoopbackPort] (I-15: 같은 seq·배치 경로).
 * 콜백은 배치 단위로, 한 스레드에서 온다.
 */
interface SessionPort {
    val me: Participant?
    val clientState: ClientSessionState
    var onBatch: (List<ClientMessage>) -> Unit
    var onRoster: (List<Participant>) -> Unit
    var onState: (ClientSessionState) -> Unit
    var onDenied: (SessionChannel, Int) -> Unit

    suspend fun send(channel: SessionChannel, kind: Int, payload: ByteArray): Boolean

    /** 호스트가 세션을 끝냈다 (game/END). */
    fun endedByHost()
}

/** 호스트 기기 자신의 끝점. 라우터의 loopback 참가자로 붙어 원격과 같은 [ClientReceiver]로 받는다. */
class LoopbackPort(router: SessionRouter, host: Participant) : SessionPort {
    override val me: Participant = host
    override var clientState: ClientSessionState = ClientSessionState.Live
        private set
    override var onBatch: (List<ClientMessage>) -> Unit = {}
    override var onRoster: (List<Participant>) -> Unit = {}
    override var onState: (ClientSessionState) -> Unit = {}
    override var onDenied: (SessionChannel, Int) -> Unit = { _, _ -> }

    private val receiver = ClientReceiver(
        onBatch = { batch -> splitControl(batch, { onRoster(it) }, {}).let { if (it.isNotEmpty()) onBatch(it) } },
        onGap = { error("loopback에는 유실이 없다") },
    )
    private val endpoint = router.attachLoopback(host) { meta, payload -> if (meta.seq != 0L) receiver.accept(meta, payload) }

    override suspend fun send(channel: SessionChannel, kind: Int, payload: ByteArray): Boolean {
        if (clientState != ClientSessionState.Live) return false
        endpoint.send(channel, kind, payload)
        return true
    }

    override fun endedByHost() {
        clientState = SessionTransitions.client(clientState, ClientSessionEvent.Ended)
        onState(clientState)
    }
}
