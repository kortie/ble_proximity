package dev.proximity.core.session

import dev.proximity.core.api.Role
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.framing.Chunker
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.matching.Cooldown
import dev.proximity.core.matching.MatchCoordinator
import dev.proximity.core.matching.PeerMatcher
import dev.proximity.core.sensing.Bump
import dev.proximity.core.state.CandidatePhase
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.MatchEffect
import dev.proximity.core.state.MatchEnv
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.core.state.PeerRef
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.ConfirmRefuse
import dev.proximity.proto.ConfirmReq
import dev.proximity.proto.RdvMessage
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import dev.proximity.testing.FakeRendezvousNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 범프 → 매칭 → Rendezvous(키 합의·SAS·암호화) → 양측 수락 → decideHost → 합류 (S5, Fake 전체 스택). */
@OptIn(ExperimentalCoroutinesApi::class)
class S5_ConfirmJoinFlowTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val env = MatchEnv.from(config)
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork(mtu = 23) // 조각 나누기가 실제로 일어나게
    private val devices = mutableListOf<Dev>()

    /** HostJoiner Fake: sessionTag로 호스트 기기의 SessionManager를 찾아 HELLO 대신 redeem을 부른다 (실제 링크는 S6). */
    private val directory = object : HostJoiner {
        var unreachableBudget = 0
        val tokens = mutableListOf<ByteArray>()

        override suspend fun join(ticket: JoinTicket, request: JoinRequest): JoinOutcome {
            if (unreachableBudget > 0) {
                unreachableBudget--
                return JoinOutcome.Unreachable
            }
            tokens += ticket.token
            val host = devices.firstOrNull { it.sessions.session?.sessionTag == ticket.sessionTag } ?: return JoinOutcome.Unreachable
            return when (val a = host.sessions.redeem(ticket.token, request.joinNonce, request.displayName)) {
                is Admission.Admitted -> JoinOutcome.Joined(a.participant, ticket.sessionTag, LocalHostChannel(host.sessions))
                is Admission.Rejected -> JoinOutcome.Refused(TokenResult.BAD_TOKEN)
            }
        }
    }

    inner class Dev(val name: String, seed: Int, role: Role, gameId: String, crypto: CryptoBox, scope: CoroutineScope, hostCapable: Boolean) {
        val transport = air.device(name)
        val rotator = IdRotator(300_000, clock, FakeCryptoBox(seed))
        val advertiser = ProximityAdvertiser(transport, 1, rotator)
        val scanner = ProximityScanner(transport, 1, clock, config.ble.peerTtlMs, scope)
        val machine = MatchStateMachine(env, clock, role)
        // S7: 관전자 자리는 게임 인원에 따라 정해진다 (8인이면 0). 관전자를 쓰는 시험은 4인 게임으로
        val sessions = SessionManager(config.session, clock, crypto).also { it.gameMaxPlayers = 4 }
        val screens = mutableListOf<ConfirmScreen>()
        val failures = mutableListOf<FailureReason>()
        val external = mutableListOf<MatchEffect>()
        val engine = RendezvousEngine(
            machine, rdv.endpoint { advertiser.state.tempId }, crypto, clock, scope, config,
            JoinProfile(name, gameId, 1, hostCapable), advertiser, sessions, directory,
        )
        val coordinator = MatchCoordinator(machine, advertiser, rotator, scanner, PeerMatcher(config.matching), Cooldown(clock, 10_000), clock, config.matching) {
            external += it
            engine.handle(it)
        }
        val id get() = PeerRef(advertiser.state.tempId)

        init {
            engine.coordinator = coordinator
            engine.onConfirmScreen = { screens += it }
            engine.onFailed = { failures += it }
            engine.start()
            coordinator.dispatch(MatchEvent.StartAdvertising)
        }

        fun accept() = coordinator.dispatch(MatchEvent.ConfirmSent)

        fun sessionTag(): Int? = when (val m = engine.membership) {
            is Membership.Hosting -> m.session.sessionTag
            is Membership.Member -> m.sessionTag
            Membership.None -> null
        }
    }

    private fun TestScope.dev(
        name: String, seed: Int, role: Role = Role.Player, gameId: String = "game", crypto: CryptoBox = BouncyCastleCryptoBox(), hostCapable: Boolean = true,
    ) = Dev(name, seed, role, gameId, crypto, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), hostCapable)
        .also { devices += it }

    private fun bump(vararg ds: Dev) {
        ds.forEach { it.coordinator.onBump(Bump(clock.now(), 3.0)) }
        clock.advanceBy(config.matching.bumpFlagWaitMs + config.matching.rdvBackoffMs + 10)
    }

    private fun rssi(a: Dev, b: Dev, dbm: Int) = air.setRssi(a.name, b.name, dbm)

    @Test
    fun twoDevicesConfirmWithSameSasThenJoinOneSession() = runTest {
        val a = dev("민수", 1)
        val b = dev("지영", 2)
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)

        assertIs<MatchState.Confirming>(a.machine.state)
        assertIs<MatchState.Confirming>(b.machine.state)
        assertEquals(1, rdv.linksCreated, "서로 골라도 Rendezvous 링크는 하나")
        assertEquals("지영", a.screens.single().displayName)
        assertEquals("민수", b.screens.single().displayName)
        assertEquals(a.screens.single().sas, b.screens.single().sas, "양쪽 확인 코드가 같다")
        assertTrue(a.advertiser.state.flags.busy && b.advertiser.state.flags.busy)

        a.accept()
        assertIs<MatchState.Confirming>(a.machine.state, "I-6: 한쪽 수락만으로는 Matched 아님")
        assertIs<MatchState.Confirming>(b.machine.state)
        b.accept()

        assertEquals(MatchState.Advertising, a.machine.state, "합류 후 다시 범프를 받는다")
        assertEquals(MatchState.Advertising, b.machine.state)
        val tag = assertNotNull(a.sessionTag())
        assertEquals(tag, b.sessionTag(), "두 기기의 세션 ID 일치")
        val host = devices.single { it.engine.membership is Membership.Hosting }
        assertEquals(setOf("민수", "지영"), host.sessions.participants().map { it.displayName }.toSet())
        assertTrue(a.advertiser.state.flags.inSession && b.advertiser.state.flags.inSession)
        assertFalse(a.advertiser.state.flags.busy || b.advertiser.state.flags.busy)

        // CONFIRM_INFO·JOIN_INFO는 암호화되어 링크에 평문이 없다
        val wire = rdv.sniffed.flatMap { it.drop(1) }
        for (secret in listOf("민수", "지영").map { it.encodeToByteArray().toList() }) {
            assertFalse(wire.windowed(secret.size).any { it == secret }, "평문 닉네임이 링크에 보인다")
        }
        val token = directory.tokens.single().toList()
        // 조각 머리를 뺀 본문을 이어 붙여 본다 (조각 경계에 걸친 평문도 찾도록)
        assertFalse(wire.windowed(token.size).any { it == token }, "join token이 링크에 평문으로 보인다")
    }

    @Test
    fun oneSidedAcceptTimesOutOnBothSidesWithoutSession() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)
        a.accept()
        clock.advanceBy(env.confirmingMs)
        // 먼저 시간이 다 된 쪽이 링크를 놓으면, 남은 쪽은 거절로 보고 다음 후보를 찾다가(없음) 실패한다
        val reasons = setOf(FailureReason.Timeout, FailureReason.NoCandidate)
        assertTrue((a.machine.state as MatchState.Failed).reason in reasons)
        assertTrue((b.machine.state as MatchState.Failed).reason in reasons)
        assertTrue(a.sessions.session == null && b.sessions.session == null, "세션 리소스 잔존 0")
    }

    @Test
    fun declineOnScreenFailsWithoutMatching() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)
        a.accept()
        b.coordinator.dispatch(MatchEvent.ConfirmDeclined)
        assertIs<MatchState.Failed>(a.machine.state)
        assertIs<MatchState.Failed>(b.machine.state)
        assertTrue(devices.none { it.engine.membership != Membership.None })
    }

    @Test
    fun differentGamesAreRefusedBeforeTheConfirmScreen() = runTest {
        val a = dev("A", 1, gameId = "chess-like")
        val b = dev("B", 2, gameId = "other")
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)
        assertTrue(a.screens.isEmpty() && b.screens.isEmpty(), "확인 화면을 띄우지 않는다")
        assertTrue(FailureReason.GameMismatch in a.failures && FailureReason.GameMismatch in b.failures)
        assertFalse(a.machine.state is MatchState.Matched || b.machine.state is MatchState.Matched)
    }

    @Test
    fun newcomerJoinsViaNonHostMemberAndHostStaysTheSame() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        val c = dev("C", 3, role = Role.Spectator)
        rssi(a, b, -35); rssi(b, c, -35); rssi(a, c, -60)
        assertNotNull(a.engine.createRoom(), "A가 방을 만든다")
        clock.advanceBy(1000)
        bump(a, b)
        a.accept(); b.accept()
        val tag = a.sessionTag()!!
        assertEquals(tag, b.sessionTag())

        clock.advanceBy(2000)
        bump(b, c)
        assertEquals("B", c.screens.single().displayName)
        b.accept(); c.accept()
        assertEquals(tag, c.sessionTag(), "C는 범프 상대(B)가 아니라 A의 세션에 들어간다")
        assertIs<Membership.Hosting>(a.engine.membership)
        assertIs<Membership.Member>(b.engine.membership)
        assertEquals(listOf("A" to Role.Player, "B" to Role.Player, "C" to Role.Spectator), a.sessions.participants().map { it.displayName to it.role })
        assertEquals(MatchState.Advertising, b.machine.state, "중개를 마치면 B는 다시 범프를 받는다")
    }

    @Test
    fun busyDeviceRefusesAndRequesterMovesOn() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        val c = dev("C", 3)
        rssi(a, b, -31) // A에게는 B가 가장 가깝다
        rssi(a, c, -40)
        rssi(b, c, -30) // B·C는 서로 가장 가깝다
        clock.advanceBy(1000)
        bump(a, b, c)
        val bc = listOf(b.machine.state, c.machine.state)
        assertTrue(bc.all { it is MatchState.Confirming }, "B·C는 서로 확인 중: $bc")
        assertEquals(c.id, (b.machine.state as MatchState.Confirming).peer)
        assertTrue(MatchEffect.RequestConfirm(b.id) in a.external, "A는 먼저 B에게")
        assertTrue(MatchEffect.RequestConfirm(c.id) in a.external, "B가 BUSY로 거절하자 A는 C에게")
        assertTrue(MatchEffect.RefuseIncoming(a.id, dev.proximity.core.state.RefuseReason.Busy) in b.external)
    }

    @Test
    fun bystanderThatDidNotBumpAnswersNotBumpedOnTheWire() = runTest {
        val a = dev("A", 1)
        val c = dev("C", 3)
        rssi(a, c, -30)
        clock.advanceBy(1000)
        // 테스트가 직접 C에게 CONFIRM_REQ를 보낸다 (A는 F5 때문에 C에게 요청하지 않는다)
        val probe = rdv.endpoint { 0x7777 }
        val link = assertNotNull(probe.connect(c.id))
        val replies = mutableListOf<RdvMessage>()
        val asm = Chunker.Assembler(4096)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            link.inbound.collect { chunk -> asm.accept(chunk)?.let { replies += RdvMessage.ADAPTER.decode(it) } }
        }
        val req = RdvMessage(confirm_req = ConfirmReq(ByteArray(32) { 9 }.toByteString(), ByteArray(16).toByteString(), a.advertiser.state.tempId))
        Chunker.split(RdvMessage.ADAPTER.encode(req), link.mtu()).forEach { link.send(it) }
        assertEquals(ConfirmRefuse.Reason.REASON_NOT_BUMPED, replies.single().confirm_refuse?.reason)
    }

    @Test
    fun membersOfDifferentSessionsDoNotMatch() = runTest {
        val a = dev("A", 1)
        val d = dev("D", 4)
        rssi(a, d, -30)
        a.engine.createRoom()
        d.engine.createRoom()
        val tags = a.sessionTag() to d.sessionTag()
        clock.advanceBy(1000)
        bump(a, d)
        clock.advanceBy(env.candidateMs)
        assertEquals(MatchState.Failed(FailureReason.NoCandidate), a.machine.state)
        assertEquals(MatchState.Failed(FailureReason.NoCandidate), d.machine.state)
        assertEquals(tags, a.sessionTag() to d.sessionTag(), "두 세션 모두 그대로")
        assertEquals(1, a.sessions.participants().size)
    }

    @Test
    fun tamperedSealedMessageIsRejected() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        rssi(a, b, -35)
        var flipped = false
        rdv.tamper = { bytes ->
            val msg = runCatching { RdvMessage.ADAPTER.decode(bytes.copyOfRange(1, bytes.size)) }.getOrNull()
            if (!flipped && bytes.size > 10 && msg?.confirm_req == null && msg?.confirm_ack == null) {
                flipped = true
                bytes.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            } else {
                bytes
            }
        }
        clock.advanceBy(1000)
        bump(a, b)
        assertTrue(flipped)
        assertTrue(a.screens.isEmpty() || b.screens.isEmpty(), "변조된 쪽은 확인 화면까지 가지 못한다")
        assertFalse(a.machine.state is MatchState.Matched || b.machine.state is MatchState.Matched)
    }

    @Test
    fun hostBidTiesRerollThenFail() = runTest {
        // 두 기기의 4바이트 난수(hostBid)가 늘 같게 만든다
        fun tied() = object : CryptoBox by BouncyCastleCryptoBox() {
            override fun randomBytes(size: Int) = if (size == 4) byteArrayOf(0, 0, 0, 7) else BouncyCastleCryptoBox().randomBytes(size)
        }
        val a = dev("A", 1, crypto = tied())
        val b = dev("B", 2, crypto = tied())
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)
        a.accept(); b.accept()
        assertEquals(MatchState.Failed(FailureReason.HostBidTie), a.machine.state)
        assertEquals(MatchState.Failed(FailureReason.HostBidTie), b.machine.state)
    }

    @Test
    fun linkDropWhileConfirmingIsTreatedAsRefusal() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        rssi(a, b, -35)
        clock.advanceBy(1000)
        bump(a, b)
        rdv.tamper = { null } // 이후 모든 조각을 버린다
        // 링크를 끊는다: 응답 쪽에서 닫으면 양쪽 closed
        val responder = if (a.screens.isNotEmpty() && (a.machine.state as MatchState.Confirming).fallback == null) a else b
        responder.coordinator.dispatch(MatchEvent.Cancel)
        val other = if (responder === a) b else a
        assertIs<MatchState.Failed>(other.machine.state, "남은 쪽도 거절로 처리 (다음 후보 없음)")
    }

    @Test
    fun joinRetriesWhenHostIsUnreachableAtFirst() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        rssi(a, b, -35)
        directory.unreachableBudget = config.session.joinConnectRetries
        clock.advanceBy(1000)
        bump(a, b)
        a.accept(); b.accept()
        val joiner = devices.single { it.engine.membership !is Membership.Hosting }
        assertIs<MatchState.Joining>(joiner.machine.state, "첫 연결 실패 후 재시도 대기")
        clock.advanceBy(config.session.joinRetryBackoffMs * config.session.joinConnectRetries)
        assertIs<Membership.Member>(joiner.engine.membership)
    }

    @Test
    fun manualSelectionCandidatesStayAvailable() = runTest {
        // 자동 시도 대상이 아닌 후보는 수동 선택 목록으로 남는다 (S1 동작이 엔진 효과로 이어지는지)
        val a = dev("A", 1)
        assertTrue(a.machine.state == MatchState.Advertising)
        a.coordinator.onBump(Bump(clock.now(), 3.0))
        a.coordinator.dispatch(MatchEvent.CandidateFound(listOf(PeerRef(11), PeerRef(12), PeerRef(13))))
        repeat(2) { clock.advanceBy(config.matching.rdvBackoffMs) } // 후보마다 (tempId가 작지 않으면) backoff 뒤 접속
        // 11·12는 접속할 수 없다(없는 기기) → 재시도 정책대로 바로 다음 후보 → 자동 시도 소진 → 수동 선택
        assertEquals(MatchState.Candidate(CandidatePhase.Manual(listOf(PeerRef(13)))), a.machine.state)
        assertTrue(MatchEffect.ShowManualSelection(listOf(PeerRef(13))) in a.external)
    }
}
