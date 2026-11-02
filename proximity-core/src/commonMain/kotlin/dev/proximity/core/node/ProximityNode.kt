package dev.proximity.core.node

import dev.proximity.core.api.ClientState
import dev.proximity.core.api.GameClient
import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.host.GameHost
import dev.proximity.core.interfaces.BleTransport
import dev.proximity.core.interfaces.BumpSensor
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.GameModule
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.RendezvousTransport
import dev.proximity.core.interfaces.SessionTransport
import dev.proximity.core.matching.Cooldown
import dev.proximity.core.matching.MatchCoordinator
import dev.proximity.core.matching.PeerMatcher
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.CodeJoinRequest
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionHostJoiner
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.sensing.Bump
import dev.proximity.core.sensing.BumpDetector
import dev.proximity.core.session.ConfirmScreen
import dev.proximity.core.session.HostedSession
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinProfile
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.Membership
import dev.proximity.core.session.QrCodec
import dev.proximity.core.session.RendezvousEngine
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.core.state.MatchEnv
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.core.state.PeerRef
import dev.proximity.core.ux.CodeJoinView
import dev.proximity.core.ux.ConfirmInfo
import dev.proximity.core.ux.HostPanel
import dev.proximity.core.ux.NameError
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.SessionView
import dev.proximity.core.ux.UiInput
import dev.proximity.core.ux.UiRender
import dev.proximity.core.ux.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 플랫폼이 주는 것 (Android: `AndroidRadio`). core는 인터페이스만 받는다 (I-3'). */
class NodePlatform(
    val ble: BleTransport,
    val rendezvous: RendezvousTransport,
    val sessionHost: SessionTransport.Host,
    val newSessionClient: () -> SessionTransport.Client,
    val bumpSensor: BumpSensor? = null,
)

/**
 * 이 기기 하나 (S9 파사드). 앱은 이것만 쓴다: 화면은 [ui]를 그리고, 게임 UI에는 [gameClient]만 넘긴다 (I-15·I-16).
 * S1~S8 구성 요소(상태 기계·매칭·확인·세션·게임 호스트)를 배선하고, 폴백 합류(세션 코드·QR·수동 선택)를 제공한다.
 * 모든 호출은 [scope]의 한 스레드에서 한다.
 */
class ProximityNode(
    private val config: ProximityConfig,
    private val platform: NodePlatform,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val game: GameModule,
    private val hostCapable: Boolean = true,
) {
    private val descriptor = game.descriptor()

    private var displayName: String? = null
    private var nameError: NameError? = null
    private var radio = RadioStatus.PermissionNeeded
    private var role = Role.Player
    private var confirm: ConfirmInfo? = null
    private var codeJoin: CodeJoinView? = null
    private var refusal: Int? = null

    private var parts: Parts? = null
    private var router: SessionRouter? = null
    private var gameHost: GameHost? = null
    private var client: SessionClient? = null
    private var roster: List<Participant> = emptyList()
    private var clientState: ClientSessionState? = null
    private var pausedSince: Long? = null
    private var currentQr: String? = null
    private var qrTimer: dev.proximity.core.interfaces.Cancellable? = null
    private val pendingCodeJoins = mutableListOf<CodeJoinRequest>()
    private var clientJobs = mutableListOf<Job>()
    private var bumpAt: Long? = null
    private var acceptAt: Long? = null

    /** 양측이 다 수락한 시각 (Matched로 넘어간 때). 수락→WELCOME은 여기서부터 잰다 (thresholds: "양측 수락→WELCOME"). */
    private var bothAcceptedAt: Long? = null

    /** 진단 로그 (매칭 제외 사유 등). 임시 ID·이름·본문은 넣지 않는다. */
    var debugLog: (String) -> Unit = {}

    /** 실기기 측정 (S11). Lab이 CSV로 내보낸다. */
    val perf = PerfRecorder()

    // 위의 상태가 모두 초기화된 뒤에 첫 화면을 그린다
    private val uiFlow = MutableStateFlow(UiRender.render(input()))
    private val gameClientFlow = MutableStateFlow<GameClient?>(null)

    val ui: StateFlow<UiState> = uiFlow
    val gameClient: StateFlow<GameClient?> = gameClientFlow

    /** 이름이 정해지고 무선이 준비되면 만든다 (JoinProfile에 이름이 들어간다). */
    private inner class Parts(name: String) {
        val rotator = IdRotator(config.ble.idRotateSec * MILLIS, clock, crypto)
        val advertiser = ProximityAdvertiser(platform.ble, config.ble.protocolVersion, rotator)
        val scanner = ProximityScanner(platform.ble, config.ble.protocolVersion, clock, config.ble.peerTtlMs, scope)
        val machine = MatchStateMachine(MatchEnv.from(config), clock, role)
        val sessions = SessionManager(config.session, clock, crypto)
        val joiner = SessionHostJoiner(config, crypto, clock, scope, platform.newSessionClient)
        val engine = RendezvousEngine(
            machine, platform.rendezvous, crypto, clock, scope, config,
            JoinProfile(name, descriptor.gameId, descriptor.gameVersion, hostCapable), advertiser, sessions, joiner,
        )
        val coordinator = MatchCoordinator(
            machine, advertiser, rotator, scanner, PeerMatcher(config.matching), Cooldown(clock, config.matching.retryCooldownMs),
            clock, config.matching,
        ) { effect ->
            engine.handle(effect)
            render()
        }
        val detector = BumpDetector(config.bump)

        init {
            // 타이머로 일어난 전이(시간 초과 등)도 화면에 반영한다
            val inner = machine.listener
            machine.listener = { effects ->
                inner?.invoke(effects)
                render()
            }
            engine.coordinator = coordinator
            coordinator.onSelection = { n, excluded -> debugLog("select candidates=$n excluded=$excluded") }
            coordinator.onIncomingDecision = { debugLog("incoming $it") }
            engine.onConfirmScreen = ::onConfirmScreen
            engine.onJoined = { render() }
            engine.onFailed = { render() }
            engine.onManualSelection = { render() }
            engine.onHostingStarted = ::startHosting
            joiner.onClient = ::attachClient
        }
    }

    /** 지금 광고 중인 임시 ID. Rendezvous 플랫폼 구현이 접속 상대에게 자기를 알릴 때만 쓴다 (로그 금지, I-7). */
    val tempId: Int? get() = parts?.advertiser?.state?.tempId

    // ---- 앱이 부르는 것 ----

    /** 첫 실행 닉네임 (≤ session.max_display_name_bytes). 확인 화면·참가자 목록에만 쓰고 광고에는 싣지 않는다 (I-7). */
    fun setDisplayName(name: String): Boolean {
        val trimmed = name.trim()
        nameError = when {
            trimmed.isEmpty() -> NameError.Blank
            trimmed.encodeToByteArray().size > config.session.maxDisplayNameBytes -> NameError.TooLong
            else -> null
        }
        if (nameError == null && parts == null) displayName = trimmed
        bootIfReady()
        render()
        return nameError == null
    }

    /** 플랫폼 무선 상태 (블루투스·권한). Ready가 되면 광고·스캔을 시작한다. */
    fun setRadio(status: RadioStatus) {
        radio = status
        bootIfReady()
        render()
    }

    fun selectRole(newRole: Role) {
        if (parts?.engine?.membership.let { it != null && it != Membership.None }) return
        role = newRole
        parts?.coordinator?.dispatch(MatchEvent.RoleSelected(newRole))
        render()
    }

    /** "방 만들기" (Player + hostCapable). */
    fun createRoom() = withParts { it.engine.createRoom() }

    fun onBump(bump: Bump) = withParts {
        refusal = null
        bumpAt = clock.now()
        it.coordinator.onBump(bump)
    }

    fun accept() = withParts {
        acceptAt = clock.now()
        it.coordinator.dispatch(MatchEvent.ConfirmSent)
    }

    /** 게임 액션을 보내고 왕복 시간을 잰다 (S11). 게임 UI가 GameClient.sendAction 대신 써도 된다. */
    suspend fun timedAction(action: ByteArray): dev.proximity.core.api.ActionOutcome? {
        val gc = gameClient.value ?: return null
        val started = clock.now()
        val outcome = gc.sendAction(action)
        if (outcome == dev.proximity.core.api.ActionOutcome.Accepted) perf.record(Metric.ActionRoundTrip, clock.now() - started)
        return outcome
    }

    /** 호스트까지 왕복 지연 (멤버만). */
    suspend fun ping(): Long? = client?.ping()?.also { perf.record(Metric.LinkRtt, it) }

    fun decline() = withParts { it.coordinator.dispatch(MatchEvent.ConfirmDeclined) }

    fun pick(peer: PeerRef) = withParts { it.coordinator.dispatch(MatchEvent.CandidatePicked(peer)) }

    fun cancel() = withParts {
        codeJoin = null
        it.coordinator.dispatch(MatchEvent.Cancel)
    }

    /** Failed 화면의 "다시": 범프 대기로. */
    fun retry() = withParts {
        refusal = null
        it.coordinator.dispatch(MatchEvent.StartAdvertising)
    }

    /** 세션 코드 합류 (04 §5.2.2): 근처 호스트를 신호 센 순으로, 코드가 맞는 곳까지. */
    fun joinWithCode(code: String) = withParts { p ->
        if (p.engine.membership != Membership.None) return@withParts
        refusal = null
        codeJoin = CodeJoinView(sas = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val hosts = p.scanner.peers()
                .filter { it.latest.flags.inSession && it.latest.flags.isHost && it.latest.flags.accepting && it.latest.sessionTag != null }
                .sortedByDescending { it.latest.rssi }
                .map { it.latest.sessionTag!! }
                .distinct()
            var last = TokenResult.UNREACHABLE
            for (tag in hosts) {
                if (codeJoin == null) return@launch // 취소
                val c = SessionClient(config, platform.newSessionClient(), crypto, clock, scope)
                attachClient(c)
                val request = JoinRequest(crypto.randomBytes(NONCE_BYTES), role, displayName!!, descriptor.gameId, descriptor.gameVersion)
                when (val o = c.joinWithCode(HostRef(tag), code, request) { sas -> codeJoin = CodeJoinView(sas); render() }) {
                    is JoinOutcome.Joined -> {
                        codeJoin = null
                        p.engine.joinedOutside(o)
                        render()
                        return@launch
                    }
                    is JoinOutcome.Refused -> {
                        detachClient()
                        last = o.reason
                        // 코드가 틀린 호스트면 다음 호스트로, 그 밖의 거절은 여기서 멈춘다
                        if (o.reason != TokenResult.BAD_CODE) break
                    }
                    JoinOutcome.Unreachable -> detachClient()
                }
            }
            codeJoin = null
            refusal = last
            render()
        }
    }

    /** QR 합류 (04 §5.2.1). 형식이 틀리거나 다른 게임의 QR이면 false. */
    fun joinWithQr(text: String): Boolean {
        val p = parts ?: return false
        if (p.engine.membership != Membership.None) return false
        val q = QrCodec.decode(text.trim(), descriptor.gameId) ?: return false
        refusal = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val request = JoinRequest(q.joinNonce, role, displayName!!, descriptor.gameId, descriptor.gameVersion, crypto.randomBytes(NONCE_BYTES))
            when (val o = p.joiner.join(q.ticket, request)) {
                is JoinOutcome.Joined -> p.engine.joinedOutside(o)
                is JoinOutcome.Refused -> {
                    detachClient()
                    refusal = o.reason
                }
                JoinOutcome.Unreachable -> {
                    detachClient()
                    refusal = TokenResult.UNREACHABLE
                }
            }
            render()
        }
        return true
    }

    /** 호스트: 세션 코드 합류 승인 (확인 코드가 같을 때만 수락하라고 안내한다). */
    fun answerCodeJoin(id: Int, accept: Boolean) {
        router?.answerCodeJoin(id, accept)
    }

    /** 호스트: QR을 새로 만든다 (한 번 쓰이면 무효). */
    fun refreshQr() {
        currentQr = parts?.sessions?.qrFor()
        render()
    }

    /** 나가기. 호스트면 세션을 끝낸다. */
    fun leave() = withParts { p ->
        val c = client
        if (gameHost != null) {
            endSession()
            return@withParts
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c?.leave() }
        sessionOver(p)
    }

    fun endSession() = withParts { p ->
        gameHost?.end(null)
        sessionOver(p)
    }

    /**
     * 앱을 닫는다 (S10): 호스트면 END를 보내고 링크를 정리, 멤버면 LEAVE. 그 뒤 광고·스캔·센서를 끈다.
     * 세션은 저장하지 않으므로 다시 실행하면 세션 없이 시작한다 (고아 세션 0).
     */
    fun close() {
        val p = parts ?: return
        val c = client
        if (gameHost != null) gameHost?.end(null) else if (c != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { c.leave() }
        sessionOver(p)
        p.scanner.stop()
        p.advertiser.stop()
        platform.bumpSensor?.stop()
        platform.rendezvous.closeServer()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { platform.sessionHost.shutdown() }
        parts = null
        render()
    }

    /** 카운트다운 같은 시간 표시를 다시 그린다 (화면이 1초마다 부른다). */
    fun refresh() = render()

    // ---- 배선 ----

    private fun bootIfReady() {
        val name = displayName ?: return
        if (parts != null || radio != RadioStatus.Ready) return
        val p = Parts(name)
        parts = p
        p.engine.start()
        p.coordinator.dispatch(MatchEvent.RoleSelected(role))
        p.coordinator.dispatch(MatchEvent.StartAdvertising)
        platform.bumpSensor?.let { sensor ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) { sensor.samples.collect { s -> p.detector.feed(s)?.let(::onBump) } }
            sensor.start()
        }
    }

    private fun startHosting(session: HostedSession) {
        val p = parts ?: return
        val r = SessionRouter(config, platform.sessionHost, HostInbox(clock), p.sessions, crypto, clock, scope)
        router = r
        r.onCapacityChanged = p.engine::setAccepting
        p.engine.hostRendezvousAllowed = r::rendezvousAllowed
        p.engine.onRendezvousCount = r::setRendezvousInProgress
        r.onCodeJoinRequest = { req ->
            pendingCodeJoins += req
            render()
        }
        r.onCodeJoinClosed = { id ->
            pendingCodeJoins.removeAll { it.id == id }
            currentQr = p.sessions.qrFor() // 합류가 있었을 수 있다: 쓰인 QR을 바꾼다
            render()
        }
        r.start()
        val host = GameHost(config, game, clock)
        gameHost = host
        val hostClient = host.attachHost(r, p.sessions, scope)
        gameClientFlow.value = hostClient
        clientJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hostClient.participants.collect {
                // 누군가 들어오면 그 QR이 쓰였을 수 있다
                if (it.size != roster.size) currentQr = p.sessions.qrFor()
                roster = it
                render()
            }
        }
        currentQr = p.sessions.qrFor()
        scheduleQrRefresh(p)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { platform.sessionHost.open(session.sessionTag, r.sessionInfo()) }
        render()
    }

    /**
     * 화면의 QR은 token 유효 시간(join_token_ttl_sec)의 4/5마다 새로 만든다 (실기기에서 발견: 30초 뒤 무효인 QR이 그대로 보였다).
     */
    private fun scheduleQrRefresh(p: Parts) {
        qrTimer?.cancel()
        qrTimer = clock.schedule(config.session.joinTokenTtlSec * MILLIS * QR_REFRESH_NUM / QR_REFRESH_DEN) {
            if (gameHost == null) return@schedule
            currentQr = p.sessions.qrFor()
            render()
            scheduleQrRefresh(p)
        }
    }

    private fun attachClient(c: SessionClient) {
        val p = parts ?: return
        detachClient()
        client = c
        val gc = SessionGameClient(config, c, clock, scope)
        c.onCapacity = { p.engine.setAccepting(it.accepting) }
        gameClientFlow.value = gc
        clientJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gc.participants.collect {
                roster = it
                render()
            }
        }
        clientJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gc.state.collect { s ->
                clientState = c.clientState
                if (s == ClientState.Live) (bothAcceptedAt ?: acceptAt)?.let {
                    perf.record(Metric.AcceptToWelcome, clock.now() - it)
                    acceptAt = null
                    bothAcceptedAt = null
                }
                pausedSince = if (s == ClientState.Paused) pausedSince ?: clock.now() else null
                if (s is ClientState.Ended && p.engine.membership is Membership.Member) {
                    refusal = c.endReason
                    sessionOver(p)
                }
                render()
            }
        }
    }

    private fun detachClient() {
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()
        client = null
        clientState = null
        pausedSince = null
        roster = emptyList()
        gameClientFlow.value = null
    }

    private fun sessionOver(p: Parts) {
        // 호스트였다면 세션(태그·키·참가자)을 비운다: 다음 "방 만들기"는 새 세션이어야 한다
        if (gameHost != null) p.sessions.end()
        p.engine.leaveSession()
        detachClient()
        router = null
        gameHost = null
        qrTimer?.cancel()
        qrTimer = null
        currentQr = null
        pendingCodeJoins.clear()
        render()
    }

    private fun onConfirmScreen(screen: ConfirmScreen) {
        bumpAt?.let { perf.record(Metric.BumpToConfirm, clock.now() - it) }
        bumpAt = null
        confirm = ConfirmInfo(screen.displayName, screen.sas, clock.now())
        render()
    }

    private inline fun withParts(block: (Parts) -> Unit) {
        val p = parts ?: return render()
        block(p)
        render()
    }

    // ---- 화면 ----

    private fun render() {
        uiFlow.value = UiRender.render(input())
    }

    private fun input(): UiInput {
        val p = parts
        val match = p?.machine?.state
        if (match !is MatchState.Confirming) confirm = null
        if (acceptAt != null && bothAcceptedAt == null && (match is MatchState.Matched || match is MatchState.Joining)) bothAcceptedAt = clock.now()
        return UiInput(
            displayName = displayName,
            maxNameBytes = config.session.maxDisplayNameBytes,
            radio = radio,
            role = role,
            hostCapable = hostCapable,
            match = match,
            nowMs = clock.now(),
            nameError = nameError,
            confirm = confirm,
            confirmingMs = config.timeouts.confirmingMs,
            rssi = p?.scanner?.peers()?.associate { PeerRef(it.latest.tempId) to it.latest.rssi } ?: emptyMap(),
            rssiStrongDbm = config.matching.rssiStrongDbm,
            rssiMinDbm = config.matching.rssiMinDbm,
            session = sessionView(p),
            hostGraceMs = config.session.hostGraceMs,
            codeJoin = codeJoin,
            joinRefusal = refusal,
        )
    }

    private fun sessionView(p: Parts?): SessionView? = when (p?.engine?.membership) {
        is Membership.Hosting -> {
            val limits = p.sessions.limits
            SessionView(
                isHost = true,
                myRole = Role.Player,
                participants = router?.participants() ?: roster,
                clientState = ClientSessionState.Live,
                hostPanel = HostPanel(
                    sessionCode = p.sessions.sessionCode() ?: "",
                    qr = currentQr ?: "",
                    pendingCodeJoins = pendingCodeJoins.toList(),
                    maxPlayersNote = if (limits.reducedByLinks) "이 기기는 최대 ${limits.players}명까지 받을 수 있습니다." else null,
                ),
            )
        }
        is Membership.Member -> SessionView(
            isHost = false,
            myRole = client?.me?.role ?: role,
            participants = roster,
            clientState = client?.clientState ?: ClientSessionState.Live,
            pausedSinceMs = pausedSince,
        )
        else -> null
    }

    private companion object {
        const val MILLIS = 1000L
        const val NONCE_BYTES = 16
        const val QR_REFRESH_NUM = 4L
        const val QR_REFRESH_DEN = 5L
    }
}
