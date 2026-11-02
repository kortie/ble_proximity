package dev.proximity.core.router

import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.LinkEvent
import dev.proximity.core.interfaces.SessionTransport
import dev.proximity.core.session.HostChannel
import dev.proximity.core.session.HostJoiner
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.JoinTicket
import dev.proximity.core.session.TokenResult
import dev.proximity.proto.Ack
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.Hello
import dev.proximity.proto.HelloBody
import dev.proximity.proto.Leave
import dev.proximity.proto.Ping
import dev.proximity.proto.TokenReq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.resume
import dev.proximity.proto.SessionInfo
import dev.proximity.proto.Role as WireRole

/** 클라이언트가 적용하는 순서 있는 메시지. [resynced]면 이 메시지 앞에서 건너뛴 것이 있을 수 있다. */
class ClientMessage(val channel: SessionChannel, val kind: Int, val seq: Long, val payload: ByteArray, val resynced: Boolean)

/** 호스트가 알린 남은 자리 (ctl/CAPACITY, S7). */
data class CapacityInfo(val accepting: Boolean, val playerSlots: Int, val spectatorSlots: Int)

/**
 * 링크 seq를 순서대로 적용한다 (I-11). 원격 클라이언트와 호스트의 loopback이 같은 것을 쓴다 (I-15).
 * - 기대 seq만 받고, 배치(BATCH_END)가 끝나야 한꺼번에 내보낸다 (중간 상태 없음)
 * - RESYNC가 붙은 메시지는 그 seq에서 다시 시작한다
 * - 공백이면 [onGap] (원격: 링크 고장 → 재연결)
 */
class ClientReceiver(private val onBatch: (List<ClientMessage>) -> Unit, private val onGap: () -> Unit) {
    private var expected = 1L
    private val pending = mutableListOf<ClientMessage>()
    private var pendingResync = false

    /** 마지막으로 적용(배치 완료)한 seq. resume·ACK에 쓴다. */
    var lastApplied = 0L
        private set

    fun accept(meta: MessageMeta, payload: ByteArray) {
        if (meta.resync) {
            pending.clear()
            expected = meta.seq
            pendingResync = true
        }
        when {
            meta.seq < expected -> return // 이미 받은 것 (resume 중복)
            meta.seq > expected -> return onGap()
        }
        pending += ClientMessage(meta.channel, meta.kind, meta.seq, payload, pendingResync && pending.isEmpty())
        expected++
        if (meta.batchEnd) {
            val batch = pending.toList()
            pending.clear()
            pendingResync = false
            lastApplied = meta.seq
            onBatch(batch)
        }
    }

    /** 끊긴 뒤: 끝나지 않은 배치는 버리고 마지막 적용 지점부터 다시 받는다. */
    fun rewind() {
        pending.clear()
        expected = lastApplied + 1
    }
}

/**
 * 세션 클라이언트 (S6): HELLO→WELCOME으로 합류하고, 업링크를 보내고, 다운링크를 순서대로 적용한다.
 * 끊기면 Paused로 두고 host_grace_ms 동안 같은 호스트(sessionTag)에 다시 붙어 lastApplied부터 이어 받는다.
 * S5 경계의 구현이기도 하다: [HostChannel](token 요청). 한 스레드(scope)에서만 동작한다.
 */
class SessionClient(
    private val config: ProximityConfig,
    private val transport: SessionTransport.Client,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : HostChannel, SessionPort {
    override var onBatch: (List<ClientMessage>) -> Unit = {}
    override var onRoster: (List<Participant>) -> Unit = {}
    override var onState: (ClientSessionState) -> Unit = {}

    /** 자리 변화. 멤버는 광고 accepting에 싣는다 (S7). */
    var onCapacity: (CapacityInfo) -> Unit = {}

    /** 호스트가 역할상 받지 않은 업링크 (ctl/DENIED). */
    override var onDenied: (SessionChannel, Int) -> Unit = { _, _ -> }

    override var clientState: ClientSessionState = ClientSessionState.Joining
        private set

    override var me: Participant? = null
        private set

    /** 호스트가 세션에서 내보낸 사유 (KICKED 등). 아니면 null. */
    var endReason: Int? = null
        private set

    private val version = config.ble.protocolVersion
    private var ticket: JoinTicket? = null
    private var secret: ByteArray? = null
    private var channels: LinkCrypto.LinkChannels? = null
    private var assembler = Frame.Assembler(version, MAX_MESSAGE_BYTES)
    private var msgId = 0
    private val sendLock = Mutex()
    private var welcome: CompletableDeferred<CtlMessage?>? = null
    private val tokenWaiters = ArrayDeque<CompletableDeferred<TokenResult>>()
    private val pongWaiters = ArrayDeque<CompletableDeferred<Long>>()
    private var unacked = 0
    private var ackTimer: Cancellable? = null
    private var lostAt: Long? = null

    /** 마지막으로 호스트에게서 무엇이든 받은 시각. link_timeout_ms 넘게 조용하면 죽은 링크로 본다 (04 §9). */
    private var lastHeard = 0L
    private var keepaliveTimer: Cancellable? = null
    private var leaving = false
    private var listening = false

    private val receiver = ClientReceiver(onBatch = ::applyBatch, onGap = ::linkFault)

    suspend fun join(ticket: JoinTicket, request: JoinRequest): JoinOutcome {
        this.ticket = ticket
        listen()
        if (transport.connect(HostRef(ticket.sessionTag)) == null) return JoinOutcome.Unreachable
        val body = HelloBody(
            new_join = HelloBody.NewJoin(
                token = ticket.token.toByteString(),
                join_nonce = request.joinNonce.toByteString(),
                role = request.role.toWire(),
                display_name = request.displayName,
                game_id = request.gameId,
                game_version = request.gameVersion,
                join_secret = request.joinSecret.toByteString(),
            ),
        )
        return finishJoin(handshake(body), ticket.sessionTag)
    }

    /**
     * 세션 코드로 합류한다 (04 §5.2.2). SESSION_INFO의 hostSessionPub을 믿고(TOFU) HELLO(code_join)를 보낸 뒤
     * 호스트 승인을 confirming_ms까지 기다린다. [onSas]는 이 기기 화면에 띄울 링크 확인 코드.
     */
    suspend fun joinWithCode(hostRef: HostRef, code: String, request: JoinRequest, onSas: (String) -> Unit): JoinOutcome {
        listen()
        val infoBytes = transport.connect(hostRef) ?: return JoinOutcome.Unreachable
        val info = SessionInfo.ADAPTER.runCatching { decode(infoBytes) }.getOrNull()
        val hostPub = info?.host_session_public_key?.toByteArray()?.takeIf { it.size == HOST_KEY_BYTES }
        if (hostPub == null) {
            transport.disconnect()
            return JoinOutcome.Unreachable
        }
        ticket = JoinTicket(hostRef.sessionTag, ByteArray(0), hostPub, 0)
        val body = HelloBody(
            code_join = HelloBody.CodeJoin(
                code = code,
                join_nonce = request.joinNonce.toByteString(),
                role = request.role.toWire(),
                display_name = request.displayName,
                game_id = request.gameId,
                game_version = request.gameVersion,
            ),
        )
        val reply = handshake(body, config.transport.helloTimeoutMs + config.timeouts.confirmingMs) { onSas(it.sas) }
        return finishJoin(reply, hostRef.sessionTag)
    }

    private fun finishJoin(reply: CtlMessage?, sessionTag: Int): JoinOutcome {
        if (reply == null) {
            transport.disconnect()
            return JoinOutcome.Unreachable
        }
        reply.reject?.let {
            transport.disconnect()
            return JoinOutcome.Refused(it.reason, it.game_code)
        }
        val w = reply.welcome ?: return JoinOutcome.Unreachable
        me = Participant(ParticipantId(w.participant_id), w.role.toRole(), w.display_name)
        secret = w.resume_secret.toByteArray()
        transition(ClientSessionEvent.Welcome)
        return JoinOutcome.Joined(me!!, sessionTag, this)
    }

    /** 관전자는 game을 보내지 않는다 (I-12 이중 방어의 기기 쪽). */
    override suspend fun send(channel: SessionChannel, kind: Int, payload: ByteArray): Boolean = sendLock.withLock {
        if (channel == SessionChannel.Game && me?.role != Role.Player) return@withLock false
        val c = channels ?: return@withLock false
        if (c.send.exhausted) {
            // nonce 카운터 상한: 링크를 다시 만들어 새 키로 (ADR-006 개정)
            linkFault()
            return@withLock false
        }
        val meta = MessageMeta(channel, kind, 0)
        val id = msgId++ and 0xffff
        val sealed = c.send.seal(payload, Frame.aad(version, meta, id))
        Frame.split(version, meta, id, sealed, transport.mtu()).all { transport.send(it) }
    }

    override suspend fun requestToken(joinNonce: ByteArray, role: Role, displayName: String, gameVersion: Int): TokenResult {
        val waiter = CompletableDeferred<TokenResult>()
        tokenWaiters.addLast(waiter)
        val req = CtlMessage(token_req = TokenReq(joinNonce.toByteString(), role.toWire(), gameVersion, displayName))
        if (!sendCtl(req)) {
            tokenWaiters.remove(waiter)
            return TokenResult.Refused(TokenResult.UNREACHABLE)
        }
        return waitFor(waiter, config.timeouts.joiningMs) ?: TokenResult.Refused(TokenResult.UNREACHABLE).also { tokenWaiters.remove(waiter) }
    }

    /** 왕복 지연(ms). 응답이 없으면 null. */
    suspend fun ping(): Long? {
        val waiter = CompletableDeferred<Long>()
        pongWaiters.addLast(waiter)
        if (!sendCtl(CtlMessage(ping = Ping(clock.now())))) return null
        val sentAt = waitFor(waiter, config.transport.helloTimeoutMs) ?: return null
        return clock.now() - sentAt
    }

    /** 호스트가 세션을 끝냈다 (game/END). 다시 붙지 않는다. */
    override fun endedByHost() {
        leaving = true
        transition(ClientSessionEvent.Ended)
        transport.disconnect()
    }

    suspend fun leave() {
        leaving = true
        sendCtl(CtlMessage(leave = Leave()))
        transport.disconnect()
        transition(ClientSessionEvent.Left)
    }

    // ---- 내부 ----

    private fun listen() {
        if (listening) return
        listening = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.inbound.collect(::onFrame) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.linkEvents.collect { if (it is LinkEvent.Down) onLinkDown() }
        }
    }

    /** HELLO를 보내고 WELCOME/REJECT를 기다린다. 새 링크마다 새 임시 키. */
    private suspend fun handshake(
        body: HelloBody,
        timeoutMs: Long = config.transport.helloTimeoutMs,
        onDerived: (LinkCrypto.LinkChannels) -> Unit = {},
    ): CtlMessage? {
        val t = ticket ?: return null
        val eph = crypto.x25519KeyPair()
        val c = LinkCrypto.derive(
            crypto, eph.privateKey, t.hostSessionPublicKey, eph.publicKey, t.hostSessionPublicKey, t.sessionTag, iAmHost = false,
            sasDigits = config.security.sasDigits,
        ) ?: return null
        onDerived(c)
        assembler = Frame.Assembler(version, MAX_MESSAGE_BYTES)
        msgId = 0
        val sealedBody = c.send.seal(HelloBody.ADAPTER.encode(body), LinkCrypto.HELLO_AAD)
        channels = c
        val waiter = CompletableDeferred<CtlMessage?>()
        welcome = waiter
        val hello = Hello(eph.publicKey.toByteString(), sealedBody.toByteString())
        val meta = MessageMeta(SessionChannel.Control, ControlKind.HELLO, 0)
        val ok = sendLock.withLock { Frame.split(version, meta, msgId++, Hello.ADAPTER.encode(hello), transport.mtu()).all { transport.send(it) } }
        if (!ok) return null
        return waitFor(waiter, timeoutMs)
    }

    private fun onFrame(bytes: ByteArray) {
        val c = channels ?: return
        val message = when (val r = assembler.accept(bytes)) {
            Frame.Result.Partial -> return
            is Frame.Result.Broken -> return linkFault()
            is Frame.Result.Complete -> r.message
        }
        val plain = c.receive.open(message.body, Frame.aad(version, message.meta, message.msgId)) ?: return linkFault()
        lastHeard = clock.now()
        if (message.meta.seq == 0L) {
            CtlMessage.ADAPTER.runCatching { decode(plain) }.getOrNull()?.let(::onUnsequenced)
            return
        }
        receiver.accept(message.meta, plain)
    }

    private fun onUnsequenced(ctl: CtlMessage) {
        if (ctl.welcome != null || ctl.reject != null) welcome?.complete(ctl)
        ctl.token?.let { t ->
            tokenWaiters.removeFirstOrNull()?.complete(
                TokenResult.Granted(JoinTicket(t.session_tag, t.token.toByteArray(), t.host_session_public_key.toByteArray(), t.expires_at_ms)),
            )
        }
        ctl.token_reject?.let { tokenWaiters.removeFirstOrNull()?.complete(TokenResult.Refused(it.reason, it.game_code)) }
        ctl.reject?.let { r ->
            // 세션 중 REJECT = 호스트가 내보냈다 (kick). 다시 붙지 않는다
            if (clientState == ClientSessionState.Live) {
                leaving = true
                endReason = r.reason
                transition(ClientSessionEvent.Ended)
            }
        }
        // 킵얼라이브 PONG(sent_at < 0)은 ping()을 기다리는 쪽에 넘기지 않는다
        ctl.pong?.takeIf { it.sent_at_ms >= 0 }?.let { pongWaiters.removeFirstOrNull()?.complete(it.sent_at_ms) }
        ctl.denied?.let { d -> SessionChannel.of(d.channel)?.let { onDenied(it, d.kind) } }
    }

    private fun applyBatch(batch: List<ClientMessage>) {
        val app = splitControl(batch, onRoster, onCapacity)
        if (app.isNotEmpty()) onBatch(app)
        unacked += batch.size
        if (unacked >= config.transport.ackEveryN) sendAck() else if (ackTimer == null) {
            ackTimer = clock.schedule(config.transport.ackIntervalMs) { scope.launch { sendAck() } }
        }
    }

    private fun sendAck() {
        ackTimer?.cancel()
        ackTimer = null
        unacked = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) { sendCtl(CtlMessage(ack = Ack(receiver.lastApplied))) }
    }

    private suspend fun sendCtl(ctl: CtlMessage) = send(SessionChannel.Control, ControlKind.CTL, CtlMessage.ADAPTER.encode(ctl))

    /** 복호화 실패·공백·형식 오류: AEAD 카운터가 어긋났으니 링크를 끊고 다시 붙는다 (S6). */
    private fun linkFault() {
        transport.disconnect()
    }

    private fun onLinkDown() {
        channels = null
        welcome?.complete(null)
        receiver.rewind()
        if (leaving || clientState == ClientSessionState.Ended || me == null) return
        if (clientState == ClientSessionState.Live) {
            transition(ClientSessionEvent.HostLost)
            lostAt = clock.now()
            scope.launch { reconnectLoop() }
        }
    }

    private suspend fun reconnectLoop() {
        val started = lostAt ?: return
        val t = ticket ?: return
        while (clientState == ClientSessionState.Paused) {
            if (clock.now() - started >= config.session.hostGraceMs) {
                transition(ClientSessionEvent.GraceExpired)
                return
            }
            if (transport.connect(HostRef(t.sessionTag)) != null) {
                val body = HelloBody(resume = HelloBody.Resume(me!!.id.value, secret!!.toByteString(), receiver.lastApplied))
                val reply = handshake(body)
                if (reply?.welcome != null) {
                    transition(ClientSessionEvent.Resumed)
                    return
                }
                if (reply?.reject != null) {
                    transition(ClientSessionEvent.Ended)
                    return
                }
                transport.disconnect()
            }
            delay(config.session.joinRetryBackoffMs)
        }
    }

    private fun transition(event: ClientSessionEvent) {
        val next = SessionTransitions.client(clientState, event)
        if (next != clientState) {
            clientState = next
            if (next == ClientSessionState.Live) startKeepalive() else stopKeepalive()
            onState(next)
        }
    }

    /**
     * 킵얼라이브 (04 §9, S11 실기기에서 추가): 앱이 죽어도 BLE 연결이 남으면 LinkDown이 오지 않는다.
     * Live 동안 keepalive_ms마다 PING을 보내고, link_timeout_ms 넘게 아무것도 못 받으면 링크 고장으로 끊는다.
     */
    private fun startKeepalive() {
        lastHeard = clock.now()
        stopKeepalive()
        keepaliveTimer = clock.schedule(config.transport.keepaliveMs) { keepaliveTick() }
    }

    private fun stopKeepalive() {
        keepaliveTimer?.cancel()
        keepaliveTimer = null
    }

    private fun keepaliveTick() {
        keepaliveTimer = null
        if (clientState != ClientSessionState.Live) return
        if (clock.now() - lastHeard >= config.transport.linkTimeoutMs) return linkFault()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { sendCtl(CtlMessage(ping = Ping(KEEPALIVE_PING))) }
        keepaliveTimer = clock.schedule(config.transport.keepaliveMs) { keepaliveTick() }
    }

    private suspend fun <T> waitFor(deferred: CompletableDeferred<T>, timeoutMs: Long): T? = suspendCancellableCoroutine { cont ->
        val timer = clock.schedule(timeoutMs) { if (cont.isActive) cont.resume(null) }
        deferred.invokeOnCompletion { if (cont.isActive) cont.resume(runCatching { deferred.getCompleted() }.getOrNull()) }
        cont.invokeOnCancellation { timer.cancel() }
    }

    private suspend fun delay(ms: Long) = suspendCancellableCoroutine { cont ->
        val handle = clock.schedule(ms) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { handle.cancel() }
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024
        const val HOST_KEY_BYTES = 32
        const val KEEPALIVE_PING = -1L

        fun Role.toWire() = if (this == Role.Player) WireRole.ROLE_PLAYER else WireRole.ROLE_SPECTATOR

        fun WireRole.toRole() = if (this == WireRole.ROLE_SPECTATOR) Role.Spectator else Role.Player
    }
}

/** 배치에서 라우터 control(참가자 목록·자리)을 처리하고 나머지를 돌려준다. 원격·loopback 공통. */
internal fun splitControl(
    batch: List<ClientMessage>,
    onRoster: (List<Participant>) -> Unit,
    onCapacity: (CapacityInfo) -> Unit,
): List<ClientMessage> = batch.filter { m ->
    if (m.channel != SessionChannel.Control) return@filter true
    val ctl = CtlMessage.ADAPTER.runCatching { decode(m.payload) }.getOrNull()
    ctl?.participants?.let { p ->
        onRoster(p.entries.map { Participant(ParticipantId(it.id), if (it.role == WireRole.ROLE_SPECTATOR) Role.Spectator else Role.Player, it.display_name) })
    }
    ctl?.capacity?.let { onCapacity(CapacityInfo(it.accepting, it.player_slots, it.spectator_slots)) }
    false
}

/** S5 경계 [HostJoiner]의 세션 링크 구현: 합류마다 새 [SessionClient]를 만든다. */
class SessionHostJoiner(
    private val config: ProximityConfig,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val transportFactory: () -> SessionTransport.Client,
) : HostJoiner {
    /** 마지막으로 합류에 성공한 클라이언트 (상위 계층이 다운링크를 받는다). */
    var lastClient: SessionClient? = null
        private set

    var onClient: (SessionClient) -> Unit = {}

    override suspend fun join(ticket: JoinTicket, request: JoinRequest): JoinOutcome {
        val client = SessionClient(config, transportFactory(), crypto, clock, scope)
        onClient(client)
        val outcome = client.join(ticket, request)
        if (outcome is JoinOutcome.Joined) lastClient = client
        return outcome
    }
}
