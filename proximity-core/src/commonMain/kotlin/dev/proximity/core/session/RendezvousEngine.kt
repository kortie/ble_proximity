package dev.proximity.core.session

import dev.proximity.core.api.Role
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.framing.Chunker
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.RendezvousLink
import dev.proximity.core.interfaces.RendezvousTransport
import dev.proximity.core.matching.MatchCoordinator
import dev.proximity.core.state.CandidatePhase
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.HostDecision
import dev.proximity.core.state.MatchEffect
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.core.state.PeerRef
import dev.proximity.core.state.RefuseReason
import dev.proximity.proto.Accept
import dev.proximity.proto.BidReroll
import dev.proximity.proto.ConfirmAck
import dev.proximity.proto.ConfirmInfo
import dev.proximity.proto.ConfirmRefuse
import dev.proximity.proto.ConfirmReq
import dev.proximity.proto.JoinInfo
import dev.proximity.proto.JoinReject
import dev.proximity.proto.RdvMessage
import dev.proximity.proto.RdvSecret
import dev.proximity.proto.SealedBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.resume
import dev.proximity.proto.Role as WireRole

/** 내 합류 정보 (확인 화면·CONFIRM_INFO에 쓰인다). */
data class JoinProfile(val displayName: String, val gameId: String, val gameVersion: Int, val hostCapable: Boolean)

/** 확인 화면에 보일 것 (ADR-006 2): 상대 닉네임과 확인 코드. */
data class ConfirmScreen(val peer: PeerRef, val displayName: String, val sas: String)

/** 이 기기가 지금 속한 세션. */
sealed interface Membership {
    data object None : Membership

    class Hosting(val session: HostedSession) : Membership

    class Member(val sessionTag: Int, val outcome: JoinOutcome.Joined) : Membership
}

/**
 * 확인 단계와 합류를 실행한다 (S5). [MatchCoordinator]가 넘긴 바깥 효과를 Rendezvous 링크 위의 프로토콜로 바꾼다.
 * - 접속 방향(04 §4.2): 고른 쪽이 접속, 서로 고르면 tempId 작은 쪽이 바로·큰 쪽은 rdv_backoff_ms 뒤. 양방향 링크가 동시에 생기면 작은 쪽 것만 남긴다
 * - 키 합의·SAS·암호화(ADR-006): CONFIRM_REQ/ACK(평문 공개키) → K_rdv → 나머지는 SealedChannel
 * - Matched 뒤: decideHost → 호스트면 세션을 만들고 JOIN_INFO, 멤버면 호스트에게 token을 받아 중개, 신규면 [HostJoiner]로 합류
 * 한 루프(단일 스레드 [scope])에서만 동작한다.
 */
class RendezvousEngine(
    private val machine: MatchStateMachine,
    private val transport: RendezvousTransport,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: ProximityConfig,
    private val profile: JoinProfile,
    private val advertiser: ProximityAdvertiser,
    private val sessions: SessionManager,
    private val joiner: HostJoiner,
) {
    lateinit var coordinator: MatchCoordinator

    var onConfirmScreen: (ConfirmScreen) -> Unit = {}
    var onJoined: (Membership) -> Unit = {}
    var onFailed: (FailureReason) -> Unit = {}
    var onManualSelection: (List<PeerRef>) -> Unit = {}

    /** 이 기기가 세션을 열었다 (방 만들기·결정표 승자). 세션 라우터를 여기서 시작한다 (S6). */
    var onHostingStarted: (HostedSession) -> Unit = {}

    /** 호스트일 때 새 Rendezvous를 열어도 되는가 (클라이언트 + 진행 중 Rendezvous < max_links, S7). 세션 라우터가 넣는다. */
    var hostRendezvousAllowed: () -> Boolean = { true }

    /** 호스트일 때 열려 있는 Rendezvous 수가 바뀜 (세션 라우터의 링크 계산에 넣는다). */
    var onRendezvousCount: (Int) -> Unit = {}

    var membership: Membership = Membership.None
        private set

    private val conversations = mutableMapOf<PeerRef, Conversation>()

    /** 받은 CONFIRM_REQ nonce → 받은 시각. nonce_ttl_sec 동안 같은 nonce를 거절한다 (재생 차단, S10). */
    private val seenNonces = LinkedHashMap<String, Long>()
    private val incomingTimes = ArrayDeque<Long>()
    private val ttlMs get() = config.security.nonceTtlSec * MILLIS
    private var matched: Conversation? = null

    fun start() {
        transport.openServer()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.incoming.collect(::onIncomingLink) }
    }

    /** "방 만들기" (04 §3.1): 범프 없이 혼자 세션을 만들고 호스트가 된다. Player이고 hostCapable일 때만. */
    fun createRoom(): HostedSession? {
        if (!profile.hostCapable || machine.context.role != Role.Player || membership != Membership.None) return null
        val session = sessions.create(Role.Player, profile.displayName)
        becomeHosting(session)
        coordinator.dispatch(MatchEvent.StartAdvertising)
        return session
    }

    /** 범프 밖(QR·세션 코드, S9)으로 세션에 들어갔다. 이후 범프는 이 세션으로 소개한다. */
    fun joinedOutside(outcome: JoinOutcome.Joined) {
        if (membership != Membership.None) return
        membership = Membership.Member(outcome.sessionTag, outcome)
        advertiser.joinSession(outcome.sessionTag, isHost = false, accepting = lastAccepting ?: true)
    }

    /** 세션에서 나왔다 (나가기·세션 종료). 광고에서 세션 정보를 뺀다. */
    fun leaveSession() {
        if (membership == Membership.None) return
        membership = Membership.None
        lastAccepting = null
        advertiser.leaveSession()
    }

    /** 세션의 남은 자리에 따라 광고 accepting을 바꾼다 (호스트: 라우터, 멤버: ctl/CAPACITY). */
    fun setAccepting(accepting: Boolean) {
        lastAccepting = accepting
        if (membership != Membership.None) advertiser.setAccepting(accepting)
    }

    /** 합류 중(WELCOME 직후) 먼저 도착한 CAPACITY를 잃지 않도록 기억한다. */
    private var lastAccepting: Boolean? = null

    /** MatchCoordinator의 바깥 효과. */
    fun handle(effect: MatchEffect) {
        when (effect) {
            is MatchEffect.RequestConfirm -> requestConfirm(effect.peer)
            is MatchEffect.AcceptIncoming -> conversations[effect.peer]?.acceptAsResponder()
            is MatchEffect.RefuseIncoming -> conversations[effect.peer]?.let { it.sendPlain(refuse(effect.reason)); it.finish() }
            is MatchEffect.SendAccept -> conversations[effect.peer]?.sendSealed(RdvSecret(accept = Accept()))
            is MatchEffect.SendRefuse -> conversations[effect.peer]?.let { it.sendPlain(refuse(effect.reason)); it.finish() }
            is MatchEffect.ReleaseRendezvous -> conversations[effect.peer]?.let {
                if (it === matched) matched = null
                it.finish()
            }
            is MatchEffect.DecideHost -> conversations[effect.peer]?.let { matched = it; it.decide() }
            MatchEffect.CreateSession -> matched?.hostNewSession()
            is MatchEffect.AwaitJoinInfo -> (matched ?: conversations[effect.peer])?.joinInfoIfReceived()
            MatchEffect.ConnectToHost -> matched?.joinHost()
            is MatchEffect.StartIntroduction -> matched?.introduce()
            MatchEffect.NotifyJoined -> onJoined(membership)
            is MatchEffect.NotifyFailed -> onFailed(effect.reason)
            is MatchEffect.ShowManualSelection -> onManualSelection(effect.candidates)
            else -> Unit
        }
    }

    // ---- 접속 ----

    private fun requestConfirm(peer: PeerRef) {
        if (conversations.containsKey(peer)) return
        val myId = advertiser.state.tempId
        val delayMs = if (myId.toUInt() < peer.tempId.toUInt()) 0L else config.matching.rdvBackoffMs
        clock.schedule(delayMs) {
            // 기다리는 사이 상대가 먼저 접속했으면 그 링크로 간다
            if (!conversations.containsKey(peer) && isAttempting(peer)) scope.launch { dial(peer) }
        }
    }

    private suspend fun dial(peer: PeerRef) {
        if (hostFull()) {
            coordinator.dispatch(MatchEvent.ConfirmRefused(peer, RefuseReason.Rejected))
            return
        }
        val link = transport.connect(peer)
        if (link == null) {
            if (isAttempting(peer)) coordinator.dispatch(MatchEvent.ConfirmRefused(peer, RefuseReason.Rejected))
            return
        }
        if (conversations.containsKey(peer)) {
            // 접속하는 사이에 상대 링크가 먼저 자리 잡았다
            link.close()
            return
        }
        val conv = Conversation(link, initiator = true).also { it.peer = peer }
        conversations[peer] = conv
        rendezvousChanged()
        conv.listen()
        conv.sendPlain(RdvMessage(confirm_req = ConfirmReq(conv.myKeys.publicKey.bs(), conv.myNonce.bs(), advertiser.state.tempId)))
    }

    private fun onIncomingLink(link: RendezvousLink) {
        // 꽉 찬 호스트는 Rendezvous 링크도 받지 않는다 (물리 링크 상한, 04 §5.3)
        if (hostFull() || !incomingAllowed()) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { link.close() }
            return
        }
        Conversation(link, initiator = false).listen()
    }

    private fun hostFull() = membership is Membership.Hosting && !hostRendezvousAllowed()

    /** 연결 속도 제한 (S10): 최근 60초 max_connect_per_min개의 Rendezvous incoming까지. */
    private fun incomingAllowed(): Boolean {
        val now = clock.now()
        while (incomingTimes.isNotEmpty() && now - incomingTimes.first() >= RATE_WINDOW_MS) incomingTimes.removeFirst()
        if (incomingTimes.size >= config.security.maxConnectPerMin) return false
        incomingTimes.addLast(now)
        return true
    }

    /** 처음 보는 nonce면 기억하고 true. 같은 nonce가 nonce_ttl_sec 안에 다시 오면 false. */
    private fun freshNonce(nonce: ByteArray): Boolean {
        val now = clock.now()
        val it = seenNonces.entries.iterator()
        while (it.hasNext()) if (now - it.next().value >= ttlMs) it.remove() else break
        val key = nonce.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (seenNonces.containsKey(key)) return false
        seenNonces[key] = now
        return true
    }

    private fun rendezvousChanged() {
        if (membership is Membership.Hosting) onRendezvousCount(conversations.size)
    }

    private fun isAttempting(peer: PeerRef): Boolean {
        val s = machine.state
        return s is MatchState.Candidate && (s.phase as? CandidatePhase.Attempting)?.peer == peer
    }

    private fun becomeHosting(session: HostedSession) {
        membership = Membership.Hosting(session)
        advertiser.joinSession(session.sessionTag, isHost = true, accepting = true)
        onHostingStarted(session)
        rendezvousChanged()
    }

    // ---- 한 링크 위의 대화 ----

    private inner class Conversation(val link: RendezvousLink, val initiator: Boolean) {
        var peer: PeerRef? = null

        /** 이 대화(와 K_rdv)는 받은 쪽 시계로 nonce_ttl_sec까지만 쓴다 (기기 시계는 서로 비교할 수 없다). */
        private val startedAt = clock.now()
        val myKeys = crypto.x25519KeyPair()
        val myNonce: ByteArray = crypto.randomBytes(NONCE_BYTES)
        private val assembler = Chunker.Assembler(MAX_MESSAGE_BYTES)
        private var channel: SealedChannel? = null
        private var sas: String? = null
        private var myInfo: ConfirmInfo? = null
        var theirInfo: ConfirmInfo? = null
        private var myBid = 0
        private var round = 0
        private val theirRerolls = mutableMapOf<Int, Int>()
        private var ticket: JoinTicket? = null
        private var done = false
        private val jobs = mutableListOf<Job>()

        /** 보내는 순서를 지키고, 닫기 전에 보낼 것을 다 보낸다. */
        private var sending: Job? = null

        fun listen() {
            // inbound가 끝나는 것(이미 받은 것을 다 처리한 뒤)을 연결 종료로 본다 (ADR-008 계약)
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.inbound.collect { chunk -> assembler.accept(chunk)?.let(::onMessage) }
                onClosed()
            }
        }

        /** JOIN_INFO가 Joining 전에 먼저 와 있었으면 지금 적용한다. */
        fun joinInfoIfReceived() {
            if (ticket != null && machine.state is MatchState.Joining) coordinator.dispatch(MatchEvent.JoinInfoReceived)
        }

        fun acceptAsResponder() {
            sendPlain(RdvMessage(confirm_ack = ConfirmAck(myKeys.publicKey.bs(), myNonce.bs())))
            establish(initiatorPub = theirPub!!, responderPub = myKeys.publicKey, initiatorNonce = theirNonce!!, responderNonce = myNonce)
        }

        private var theirPub: ByteArray? = null
        private var theirNonce: ByteArray? = null

        private fun onMessage(bytes: ByteArray) {
            if (clock.now() - startedAt >= ttlMs) return abort() // 만료된 대화의 뒤늦은 메시지
            val msg = runCatching { RdvMessage.ADAPTER.decode(bytes) }.getOrNull() ?: return abort()
            msg.confirm_req?.let { return onConfirmReq(it) }
            msg.confirm_ack?.let { ack ->
                if (!initiator || channel != null) return
                theirPub = ack.public_key.toByteArray()
                theirNonce = ack.nonce.toByteArray()
                establish(initiatorPub = myKeys.publicKey, responderPub = theirPub!!, initiatorNonce = myNonce, responderNonce = theirNonce!!)
                return
            }
            msg.confirm_refuse?.let { r ->
                val p = peer ?: return finish()
                finish()
                coordinator.dispatch(MatchEvent.ConfirmRefused(p, r.reason.toRefuse()))
                return
            }
            msg.sealed_body?.let { sealed ->
                val opened = channel?.open(sealed.ciphertext.toByteArray()) ?: return abort()
                val secret = runCatching { RdvSecret.ADAPTER.decode(opened) }.getOrNull() ?: return abort()
                onSecret(secret)
            }
        }

        private fun onConfirmReq(req: ConfirmReq) {
            if (initiator || peer != null) return
            if (!freshNonce(req.nonce.toByteArray())) {
                // 캡처한 확인 요청의 재전송
                sendPlain(refuse(RefuseReason.Rejected))
                finish()
                return
            }
            val from = PeerRef(req.temp_id)
            theirPub = req.public_key.toByteArray()
            theirNonce = req.nonce.toByteArray()
            val existing = conversations[from]
            if (existing != null) {
                val iWin = advertiser.state.tempId.toUInt() < from.tempId.toUInt()
                if (existing.initiator && iWin) {
                    // 서로 고른 경우: tempId 작은 쪽(나)이 시작한 링크만 남긴다
                    finish()
                    return
                }
                existing.finish()
            }
            peer = from
            conversations[from] = this
            rendezvousChanged()
            coordinator.onIncomingConfirm(from)
        }

        private fun establish(initiatorPub: ByteArray, responderPub: ByteArray, initiatorNonce: ByteArray, responderNonce: ByteArray) {
            val keys = RendezvousCrypto.derive(
                crypto, myKeys.privateKey, theirPub!!, initiatorPub, responderPub, initiatorNonce, responderNonce, config.security.sasDigits,
            ) ?: return abort()
            val (send, receive) = if (initiator) SealedChannel.INITIATOR to SealedChannel.RESPONDER else SealedChannel.RESPONDER to SealedChannel.INITIATOR
            channel = SealedChannel(crypto, keys.key, send, receive, AAD)
            sas = keys.sas
            myBid = randomInt()
            val info = myConfirmInfo(myBid)
            myInfo = info
            sendSealed(RdvSecret(confirm_info = info))
            maybeReady()
        }

        private fun onSecret(secret: RdvSecret) {
            val p = peer ?: return
            secret.confirm_info?.let {
                if (theirInfo != null) return
                theirInfo = it
                maybeReady()
                return
            }
            secret.accept?.let { coordinator.dispatch(MatchEvent.ConfirmReceived(p)); return }
            secret.bid_reroll?.let {
                theirRerolls[it.round] = it.host_bid
                if (it.round == round) decide()
                return
            }
            secret.join_info?.let {
                ticket = JoinTicket(it.session_tag, it.token.toByteArray(), it.host_session_public_key.toByteArray(), Long.MAX_VALUE)
                done = true
                // 상대가 먼저 Matched가 되어 내가 Joining에 들어가기 전에 올 수 있다: 그때는 AwaitJoinInfo에서 적용
                joinInfoIfReceived()
                return
            }
            secret.join_reject?.let {
                finish()
                coordinator.dispatch(MatchEvent.Error)
            }
        }

        private fun maybeReady() {
            val p = peer ?: return
            val theirs = theirInfo ?: return
            val code = sas ?: return
            if (myInfo == null) return
            if (theirs.game_id != profile.gameId) {
                // 결정표 #1은 확인 화면 전에 판정한다 (04 §4.2): 양쪽이 각자 거절한다
                finish()
                onFailed(FailureReason.GameMismatch)
                coordinator.dispatch(MatchEvent.ConfirmRefused(p, RefuseReason.Rejected))
                return
            }
            if (initiator && isAttempting(p)) coordinator.dispatch(MatchEvent.RendezvousReady(p))
            onConfirmScreen(ConfirmScreen(p, theirs.display_name, code))
        }

        /** decideHost. 동률이면 bid를 새로 주고받는다 (host_bid_retries회까지). */
        fun decide() {
            val mine = myInfo ?: return
            val theirs = theirInfo ?: return
            val theirBid = if (round == 0) theirs.host_bid else theirRerolls[round] ?: return
            when (val outcome = HostDecider.decide(facts(mine, myBid), facts(theirs, theirBid))) {
                is DecideOutcome.Decided -> coordinator.dispatch(MatchEvent.HostDecided(outcome.decision))
                DecideOutcome.Reroll -> {
                    round++
                    if (round > config.session.hostBidRetries) {
                        coordinator.dispatch(MatchEvent.HostDecided(HostDecision.Fail(FailureReason.HostBidTie)))
                        return
                    }
                    myBid = randomInt()
                    sendSealed(RdvSecret(bid_reroll = BidReroll(host_bid = myBid, round = round)))
                    if (theirRerolls.containsKey(round)) decide()
                }
            }
        }

        /** 결정표 #6·#7 승자: 세션을 만들고 상대에게 JOIN_INFO. */
        fun hostNewSession() {
            val session = sessions.session ?: sessions.create(machine.context.role, profile.displayName)
            if (membership == Membership.None) becomeHosting(session)
            val theirs = theirInfo!!
            when (val r = sessions.requestToken(theirs.join_nonce.toByteArray(), theirs.role.toRole(), theirs.display_name, theirs.game_version)) {
                is TokenResult.Granted -> sendJoinInfo(r.ticket)
                is TokenResult.Refused -> sendSealed(RdvSecret(join_reject = JoinReject(reason = r.reason, game_code = r.gameCode)))
            }
            coordinator.dispatch(MatchEvent.StartAdvertising)
        }

        /** 결정표 #4 세션 있는 쪽: 호스트면 직접, 멤버면 호스트에게 token을 받아 중개한다. */
        fun introduce() {
            val theirs = theirInfo ?: return
            scope.launch {
                val result = when (val m = membership) {
                    is Membership.Hosting -> LocalHostChannel(sessions)
                        .requestToken(theirs.join_nonce.toByteArray(), theirs.role.toRole(), theirs.display_name, theirs.game_version)
                    is Membership.Member -> m.outcome.channel
                        .requestToken(theirs.join_nonce.toByteArray(), theirs.role.toRole(), theirs.display_name, theirs.game_version)
                    Membership.None -> TokenResult.Refused(TokenResult.NO_SESSION)
                }
                when (result) {
                    is TokenResult.Granted -> sendJoinInfo(result.ticket)
                    is TokenResult.Refused -> sendSealed(RdvSecret(join_reject = JoinReject(reason = result.reason, game_code = result.gameCode)))
                }
                matched = null
                finish()
                coordinator.dispatch(MatchEvent.IntroductionFinished)
            }
        }

        /** 신규 기기: 받은 JOIN_INFO로 호스트에 붙는다. 연결 실패는 join_connect_retries회까지 다시. */
        fun joinHost() {
            val t = ticket ?: return
            val mine = myInfo ?: return
            // 재시도해도 같은 join_secret (중복 HELLO를 이 기기로 묶는다, S10)
            val request = JoinRequest(mine.join_nonce.toByteArray(), mine.role.toRole(), profile.displayName, profile.gameId, profile.gameVersion, crypto.randomBytes(NONCE_BYTES))
            lastAccepting = null
            scope.launch {
                var attempt = 0
                while (true) {
                    when (val outcome = joiner.join(t, request)) {
                        is JoinOutcome.Joined -> {
                            membership = Membership.Member(outcome.sessionTag, outcome)
                            advertiser.joinSession(outcome.sessionTag, isHost = false, accepting = lastAccepting ?: true)
                            matched = null
                            // 끝난 확인 대화를 표에서 지운다: 남아 있으면 같은 tempId 상대와 다시 범프할 때 접속을 건너뛴다 (S11 실기기에서 발견)
                            finish()
                            coordinator.dispatch(MatchEvent.SessionJoined)
                            coordinator.dispatch(MatchEvent.StartAdvertising)
                            return@launch
                        }
                        is JoinOutcome.Refused -> {
                            matched = null
                            finish()
                            coordinator.dispatch(MatchEvent.Error)
                            return@launch
                        }
                        JoinOutcome.Unreachable -> {
                            if (attempt++ >= config.session.joinConnectRetries) {
                                matched = null
                                finish()
                                return@launch // joining_ms 시간 초과로 정리된다
                            }
                            delay(config.session.joinRetryBackoffMs)
                        }
                    }
                }
            }
        }

        private fun sendJoinInfo(t: JoinTicket?) {
            if (t == null) {
                sendSealed(RdvSecret(join_reject = JoinReject(reason = TokenResult.NO_SESSION)))
            } else {
                sendSealed(RdvSecret(join_info = JoinInfo(t.sessionTag, t.token.bs(), t.hostSessionPublicKey.bs())))
            }
            done = true
        }

        fun sendSealed(secret: RdvSecret) {
            val c = channel ?: return
            sendPlain(RdvMessage(sealed_body = SealedBody(c.seal(RdvSecret.ADAPTER.encode(secret)).bs())))
        }

        fun sendPlain(msg: RdvMessage) {
            val chunks = Chunker.split(RdvMessage.ADAPTER.encode(msg), link.mtu())
            val previous = sending
            sending = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                previous?.join()
                for (chunk in chunks) if (!link.send(chunk)) return@launch
            }
        }

        /** 정상 종료: 보낼 것을 다 보낸 뒤 링크를 닫고, 잃음 처리는 하지 않는다. */
        fun finish() {
            done = true
            peer?.let { if (conversations[it] === this) conversations.remove(it) }
            rendezvousChanged()
            val pending = sending
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                pending?.join()
                link.close()
            }
        }

        /** 복호화 실패·형식 오류: 변조로 보고 거절과 같게 처리한다. */
        private fun abort() {
            val p = peer
            finish()
            if (p != null && (isAttempting(p) || (machine.state as? MatchState.Confirming)?.peer == p)) {
                coordinator.dispatch(MatchEvent.ConfirmRefused(p, RefuseReason.Rejected))
            }
        }

        private fun onClosed() {
            val p = peer
            val s = machine.state
            val afterMatch = p != null && ((s is MatchState.Matched && s.peer == p) || (s is MatchState.Joining && s.peer == p) ||
                (s is MatchState.Introducing && s.peer == p))
            if (afterMatch) {
                // decideHost·JOIN_INFO 처리에 이 대화가 아직 필요하다 (링크만 끝났다)
                matched = this
                return
            }
            if (p != null && conversations[p] === this) conversations.remove(p)
            rendezvousChanged()
            if (done || p == null) return
            // 재시도 정책: 시도·확인 중 끊김은 거절과 같게 (요청자는 다음 후보, 응답자는 Failed)
            if (isAttempting(p) || (s is MatchState.Confirming && s.peer == p)) {
                coordinator.dispatch(MatchEvent.ConfirmRefused(p, RefuseReason.Rejected))
            }
        }

        private fun myConfirmInfo(bid: Int): ConfirmInfo {
            val m = membership
            val tag = when (m) {
                is Membership.Hosting -> m.session.sessionTag
                is Membership.Member -> m.sessionTag
                Membership.None -> 0
            }
            return ConfirmInfo(
                role = machine.context.role.toWire(),
                display_name = profile.displayName,
                game_id = profile.gameId,
                game_version = profile.gameVersion,
                in_session = m != Membership.None,
                session_tag = tag,
                is_host = m is Membership.Hosting,
                host_capable = profile.hostCapable,
                host_bid = bid,
                join_nonce = crypto.randomBytes(NONCE_BYTES).bs(),
            )
        }
    }

    private suspend fun delay(ms: Long) = suspendCancellableCoroutine { cont ->
        val handle = clock.schedule(ms) { cont.resume(Unit) }
        cont.invokeOnCancellation { handle.cancel() }
    }

    private fun refuse(reason: RefuseReason) = RdvMessage(confirm_refuse = ConfirmRefuse(reason.toWire()))

    private fun randomInt(): Int = crypto.randomBytes(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

    private fun facts(info: ConfirmInfo, bid: Int) = ConfirmFacts(
        role = info.role.toRole(),
        gameId = info.game_id,
        gameVersion = info.game_version,
        sessionTag = info.session_tag.takeIf { info.in_session },
        isHost = info.is_host,
        hostCapable = info.host_capable,
        hostBid = bid,
    )

    private companion object {
        const val NONCE_BYTES = 16
        const val MAX_MESSAGE_BYTES = 4096
        const val MILLIS = 1000L
        const val RATE_WINDOW_MS = 60_000L
        val AAD = "ble-proximity rdv".encodeToByteArray()

        fun ByteArray.bs(): ByteString = toByteString()

        fun Role.toWire() = if (this == Role.Player) WireRole.ROLE_PLAYER else WireRole.ROLE_SPECTATOR

        fun WireRole.toRole() = if (this == WireRole.ROLE_SPECTATOR) Role.Spectator else Role.Player

        fun RefuseReason.toWire() = when (this) {
            RefuseReason.Busy -> ConfirmRefuse.Reason.REASON_BUSY
            RefuseReason.NotBumped -> ConfirmRefuse.Reason.REASON_NOT_BUMPED
            RefuseReason.Rejected -> ConfirmRefuse.Reason.REASON_REJECTED
        }

        fun ConfirmRefuse.Reason.toRefuse() = when (this) {
            ConfirmRefuse.Reason.REASON_BUSY -> RefuseReason.Busy
            ConfirmRefuse.Reason.REASON_NOT_BUMPED -> RefuseReason.NotBumped
            else -> RefuseReason.Rejected
        }
    }
}
