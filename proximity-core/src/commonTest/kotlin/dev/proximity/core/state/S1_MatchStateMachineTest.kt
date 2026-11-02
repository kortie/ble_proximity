package dev.proximity.core.state

import dev.proximity.core.api.Role
import dev.proximity.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class S1_MatchStateMachineTest {
    private val env = MatchEnv(maxAttempts = 2, candidateMs = 5000, attemptTimeoutMs = 3000, confirmingMs = 20000, joiningMs = 10000)
    private val clock = FakeClock()
    private val machine = MatchStateMachine(env, clock, Role.Player)
    private val p1 = PeerRef(0x11111111)
    private val p2 = PeerRef(0x22222222)
    private val p3 = PeerRef(0x33333333)

    private fun dispatch(vararg events: MatchEvent): List<MatchEffect> = events.flatMap { machine.dispatch(it) }

    private fun bumpAndFind(vararg candidates: PeerRef) {
        dispatch(MatchEvent.StartAdvertising, MatchEvent.BumpDetected(clock.now()), MatchEvent.CandidateFound(candidates.toList()))
    }

    @Test
    fun initiatorHappyPathToJoined() {
        bumpAndFind(p1)
        assertEquals(MatchState.Candidate(CandidatePhase.Attempting(p1, AttemptPlan(emptyList(), emptyList()))), machine.state)
        dispatch(MatchEvent.RendezvousReady(p1))
        val sent = dispatch(MatchEvent.ConfirmSent)
        assertTrue(MatchEffect.SendAccept(p1) in sent)
        assertIs<MatchState.Confirming>(machine.state, "한쪽 수락만으로는 Matched가 아니다 (I-6)")
        val matched = dispatch(MatchEvent.ConfirmReceived(p1))
        assertEquals(MatchState.Matched(p1), machine.state)
        assertTrue(MatchEffect.DecideHost(p1) in matched)

        dispatch(MatchEvent.HostDecided(HostDecision.JoinNew), MatchEvent.JoinInfoReceived)
        val joined = dispatch(MatchEvent.SessionJoined)
        assertEquals(MatchState.Joined, machine.state)
        assertTrue(MatchEffect.NotifyJoined in joined)
    }

    @Test
    fun peerAcceptFirstThenLocalAcceptAlsoMatches() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmReceived(p1))
        assertIs<MatchState.Confirming>(machine.state)
        dispatch(MatchEvent.ConfirmSent)
        assertEquals(MatchState.Matched(p1), machine.state)
    }

    @Test
    fun becomeHostGoesStraightToJoinedAndIntroduceReturnsToAdvertising() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        assertTrue(MatchEffect.CreateSession in dispatch(MatchEvent.HostDecided(HostDecision.BecomeHost)))
        assertEquals(MatchState.Joined, machine.state)

        dispatch(MatchEvent.StartAdvertising, MatchEvent.BumpDetected(clock.now()), MatchEvent.CandidateFound(listOf(p2)))
        dispatch(MatchEvent.RendezvousReady(p2), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p2))
        assertTrue(MatchEffect.StartIntroduction(p2) in dispatch(MatchEvent.HostDecided(HostDecision.Introduce)))
        assertEquals(MatchState.Introducing(p2), machine.state)
        dispatch(MatchEvent.IntroductionFinished)
        assertEquals(MatchState.Advertising, machine.state)
    }

    @Test
    fun hostDecisionFailureCarriesReason() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        dispatch(MatchEvent.HostDecided(HostDecision.Fail(FailureReason.GameMismatch)))
        assertEquals(MatchState.Failed(FailureReason.GameMismatch), machine.state)
    }

    @Test
    fun refusalMovesToNextCandidateThenManualSelection() {
        bumpAndFind(p1, p2, p3)
        val first = dispatch(MatchEvent.ConfirmRefused(p1, RefuseReason.Busy))
        assertTrue(MatchEffect.MarkCooldown(p1) in first)
        assertTrue(MatchEffect.RequestConfirm(p2) in first)
        assertEquals(p2, (machine.state.phaseOrNull() as CandidatePhase.Attempting).peer)

        // 2순위도 확인 화면에서 거절 → max_attempts(2) 소진 → 남은 p3로 수동 선택
        dispatch(MatchEvent.RendezvousReady(p2))
        val manual = dispatch(MatchEvent.ConfirmDeclined)
        assertTrue(MatchEffect.SendRefuse(p2, RefuseReason.Rejected) in manual)
        assertTrue(MatchEffect.ShowManualSelection(listOf(p3)) in manual)
        assertEquals(MatchState.Candidate(CandidatePhase.Manual(listOf(p3))), machine.state)

        dispatch(MatchEvent.CandidatePicked(p3), MatchEvent.ConfirmRefused(p3, RefuseReason.Rejected))
        assertEquals(MatchState.Failed(FailureReason.NoCandidate), machine.state)
    }

    @Test
    fun refusalFromAnotherPeerIsIgnored() {
        bumpAndFind(p1, p2)
        assertTrue(dispatch(MatchEvent.ConfirmRefused(p2, RefuseReason.Busy)).isEmpty())
        assertEquals(p1, (machine.state.phaseOrNull() as CandidatePhase.Attempting).peer)
    }

    @Test
    fun emptyCandidateListWaitsForIncomingThenFails() {
        bumpAndFind()
        assertEquals(MatchState.Candidate(CandidatePhase.AwaitingIncoming), machine.state, "상대가 나를 봤을 수 있다")
        clock.advanceBy(env.candidateMs - 1)
        assertEquals(MatchState.Candidate(CandidatePhase.AwaitingIncoming), machine.state)
        clock.advanceBy(1)
        assertEquals(MatchState.Failed(FailureReason.NoCandidate), machine.state)
    }

    @Test
    fun awaitingIncomingAcceptsTheRequestThatArrives() {
        bumpAndFind()
        assertTrue(MatchEffect.AcceptIncoming(p1) in dispatch(MatchEvent.IncomingConfirm(p1)))
        assertEquals(MatchState.Confirming(p1, null, false, false), machine.state)
    }

    @Test
    fun responderRules() {
        dispatch(MatchEvent.StartAdvertising)
        assertEquals(listOf(MatchEffect.RefuseIncoming(p1, RefuseReason.NotBumped)), dispatch(MatchEvent.IncomingConfirm(p1)))
        assertEquals(MatchState.Advertising, machine.state)

        dispatch(MatchEvent.BumpDetected(clock.now()))
        assertTrue(MatchEffect.AcceptIncoming(p1) in dispatch(MatchEvent.IncomingConfirm(p1)))
        assertEquals(MatchState.Confirming(p1, null, false, false), machine.state)
        assertEquals(listOf(MatchEffect.RefuseIncoming(p2, RefuseReason.Busy)), dispatch(MatchEvent.IncomingConfirm(p2)))
    }

    @Test
    fun mutualSelectionMergesIntoOneConfirmation() {
        bumpAndFind(p1, p2)
        val merged = dispatch(MatchEvent.IncomingConfirm(p1))
        assertTrue(MatchEffect.AcceptIncoming(p1) in merged)
        val state = machine.state as MatchState.Confirming
        assertEquals(p1, state.peer)
        assertEquals(AttemptPlan(listOf(p2), emptyList()), state.fallback, "거절되면 이어갈 후보 계획을 유지한다")
        assertTrue(dispatch(MatchEvent.RendezvousReady(p1)).isEmpty(), "이미 확인 중이면 RendezvousReady는 무시")
    }

    @Test
    fun timeoutsPerPhaseOnFakeClock() {
        bumpAndFind(p1, p2)
        clock.advanceBy(env.attemptTimeoutMs)
        assertEquals(p2, (machine.state.phaseOrNull() as CandidatePhase.Attempting).peer, "시도 시간 초과 → 다음 후보")

        dispatch(MatchEvent.RendezvousReady(p2))
        clock.advanceBy(env.confirmingMs - 1)
        assertIs<MatchState.Confirming>(machine.state)
        clock.advanceBy(1)
        assertEquals(MatchState.Failed(FailureReason.Timeout), machine.state)

        dispatch(MatchEvent.StartAdvertising, MatchEvent.BumpDetected(clock.now()))
        clock.advanceBy(env.candidateMs)
        assertEquals(MatchState.Failed(FailureReason.Timeout), machine.state, "후보 수집 시간 초과")
    }

    @Test
    fun matchedJoiningAndIntroducingTimeouts() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        clock.advanceBy(env.joiningMs)
        assertEquals(MatchState.Failed(FailureReason.Timeout), machine.state, "Matched에서 HostDecided가 오지 않음")

        dispatch(MatchEvent.StartAdvertising)
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        dispatch(MatchEvent.HostDecided(HostDecision.JoinExisting))
        clock.advanceBy(env.joiningMs)
        assertEquals(MatchState.Failed(FailureReason.Timeout), machine.state, "Joining 시간 초과")

        dispatch(MatchEvent.StartAdvertising)
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        dispatch(MatchEvent.HostDecided(HostDecision.Introduce))
        clock.advanceBy(env.joiningMs)
        assertEquals(MatchState.Advertising, machine.state, "중개 시간 초과 → 세션은 유지하고 Advertising")
    }

    @Test
    fun acceptFlagsDoNotRestartConfirmTimer() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1))
        clock.advanceBy(env.confirmingMs - 100)
        dispatch(MatchEvent.ConfirmSent)
        clock.advanceBy(100)
        assertEquals(MatchState.Failed(FailureReason.Timeout), machine.state)
    }

    @Test
    fun staleTimeoutTokenIsIgnored() {
        bumpAndFind(p1)
        val staleToken = machine.context.timerToken!!
        dispatch(MatchEvent.RendezvousReady(p1))
        assertTrue(dispatch(MatchEvent.Timeout(staleToken)).isEmpty())
        assertIs<MatchState.Confirming>(machine.state)
    }

    @Test
    fun bumpIsIgnoredWhileJoiningOrIntroducing() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        dispatch(MatchEvent.HostDecided(HostDecision.JoinNew))
        assertTrue(dispatch(MatchEvent.BumpDetected(clock.now())).isEmpty())
        assertIs<MatchState.Joining>(machine.state)
    }

    @Test
    fun duplicateEventsDoNotAdvanceTwice() {
        bumpAndFind(p1, p2)
        assertTrue(dispatch(MatchEvent.CandidateFound(listOf(p2))).isEmpty(), "두 번째 CandidateFound")
        dispatch(MatchEvent.RendezvousReady(p1))
        assertEquals(1, dispatch(MatchEvent.ConfirmSent).count { it is MatchEffect.SendAccept })
        assertTrue(dispatch(MatchEvent.ConfirmSent).isEmpty(), "두 번째 수락 탭은 다시 보내지 않는다")
        dispatch(MatchEvent.ConfirmReceived(p1))
        assertTrue(dispatch(MatchEvent.ConfirmReceived(p1)).isEmpty())
        dispatch(MatchEvent.HostDecided(HostDecision.JoinNew))
        assertTrue(dispatch(MatchEvent.HostDecided(HostDecision.BecomeHost)).isEmpty())
        dispatch(MatchEvent.JoinInfoReceived)
        assertTrue(dispatch(MatchEvent.JoinInfoReceived).isEmpty())
        dispatch(MatchEvent.SessionJoined)
        assertTrue(dispatch(MatchEvent.SessionJoined).isEmpty())
        assertEquals(MatchState.Joined, machine.state)
    }

    @Test
    fun roleIsFixedAfterBump() {
        dispatch(MatchEvent.RoleSelected(Role.Spectator))
        assertEquals(Role.Spectator, machine.context.role, "Idle에서는 바뀐다")
        dispatch(MatchEvent.StartAdvertising, MatchEvent.RoleSelected(Role.Player))
        assertEquals(Role.Player, machine.context.role, "Advertising에서도 바뀐다")
        dispatch(MatchEvent.BumpDetected(clock.now()))
        assertTrue(dispatch(MatchEvent.RoleSelected(Role.Spectator)).isEmpty())
        dispatch(MatchEvent.CandidateFound(listOf(p1)), MatchEvent.RendezvousReady(p1), MatchEvent.RoleSelected(Role.Spectator))
        assertEquals(Role.Player, machine.context.role)
    }

    @Test
    fun cancelReleasesRendezvousAndStopsTimer() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1))
        val effects = dispatch(MatchEvent.Cancel)
        assertTrue(MatchEffect.ReleaseRendezvous(p1) in effects)
        assertEquals(MatchState.Idle, machine.state)
        assertEquals(null, machine.context.timerToken)
        clock.advanceBy(env.confirmingMs * 2)
        assertEquals(MatchState.Idle, machine.state, "취소 후 타이머가 남아 있으면 안 된다")
    }

    @Test
    fun logHasNamesOnlyAndNoTemporaryIds() {
        bumpAndFind(p1)
        dispatch(MatchEvent.RendezvousReady(p1), MatchEvent.ConfirmSent, MatchEvent.ConfirmReceived(p1))
        val log = machine.log()
        assertEquals(listOf(StateKind.Idle, StateKind.Advertising, StateKind.Candidate, StateKind.Candidate, StateKind.Confirming, StateKind.Confirming),
            log.map { it.from })
        assertEquals(StateKind.Matched, log.last().to)
        val text = log.joinToString("\n")
        listOf(p1.tempId).forEach { id ->
            assertFalse(id.toString() in text || id.toString(16) in text, "로그에 임시 ID가 있다: $text")
        }
        assertFalse("PeerRef(" in text && "***" !in text)
    }

    private fun MatchState.phaseOrNull(): CandidatePhase? = (this as? MatchState.Candidate)?.phase
}
