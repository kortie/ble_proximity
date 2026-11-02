package dev.proximity.core.state

import dev.proximity.core.api.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 전이표 전수 테스트. 기대값은 구현과 독립적으로 아래 표에 직접 적는다 (구현 표를 베끼지 않는다).
 * 칸의 값: 결과 상태 라벨, 또는 "-" = 무시 (상태·컨텍스트 그대로, 효과 없음).
 */
class S1_TransitionTableTest {
    private val env = MatchEnv(maxAttempts = 2, candidateMs = 5000, attemptTimeoutMs = 3000, confirmingMs = 20000, joiningMs = 10000)
    private val p1 = PeerRef(1)
    private val p2 = PeerRef(2)
    private val p3 = PeerRef(3)
    private val token = 7L

    /** 표의 행 = 출발 상태 샘플. 타이머가 있는 단계는 현재 토큰 7을 가진다. */
    private val samples: Map<String, Pair<MatchState, MatchContext>> = linkedMapOf(
        "Idle" to (MatchState.Idle to ctx(timer = false)),
        "Adv" to (MatchState.Advertising to ctx(timer = false)),
        "Col" to (MatchState.Candidate(CandidatePhase.Collecting) to ctx()),
        "Await" to (MatchState.Candidate(CandidatePhase.AwaitingIncoming) to ctx()),
        "Att" to (MatchState.Candidate(CandidatePhase.Attempting(p1, AttemptPlan(listOf(p2), listOf(p3)))) to ctx()),
        "Man" to (MatchState.Candidate(CandidatePhase.Manual(listOf(p1, p2))) to ctx()),
        "ConfI" to (MatchState.Confirming(p1, AttemptPlan(listOf(p2), emptyList()), false, false) to ctx()),
        "ConfR" to (MatchState.Confirming(p1, null, false, false) to ctx()),
        "Match" to (MatchState.Matched(p1) to ctx()),
        "Join" to (MatchState.Joining(p1, joinInfoReceived = false) to ctx()),
        "JoinI" to (MatchState.Joining(p1, joinInfoReceived = true) to ctx()),
        "Intro" to (MatchState.Introducing(p1) to ctx()),
        "Joined" to (MatchState.Joined to ctx(timer = false)),
        "Fail" to (MatchState.Failed(FailureReason.Timeout) to ctx(timer = false)),
    )

    /** 표의 열 = 이벤트 샘플. 상대가 있는 이벤트는 p1에서 온다. */
    private val events: Map<String, MatchEvent> = linkedMapOf(
        "StartAdv" to MatchEvent.StartAdvertising,
        "Role" to MatchEvent.RoleSelected(Role.Spectator),
        "Bump" to MatchEvent.BumpDetected(atMs = 100),
        "Found" to MatchEvent.CandidateFound(listOf(p1, p2, p3)),
        "Picked" to MatchEvent.CandidatePicked(p1),
        "Incoming" to MatchEvent.IncomingConfirm(p1),
        "Ready" to MatchEvent.RendezvousReady(p1),
        "Sent" to MatchEvent.ConfirmSent,
        "Recv" to MatchEvent.ConfirmReceived(p1),
        "Refused" to MatchEvent.ConfirmRefused(p1, RefuseReason.Busy),
        "Declined" to MatchEvent.ConfirmDeclined,
        "Host" to MatchEvent.HostDecided(HostDecision.JoinNew),
        "Info" to MatchEvent.JoinInfoReceived,
        "Joined" to MatchEvent.SessionJoined,
        "Intro" to MatchEvent.IntroductionFinished,
        "Timeout" to MatchEvent.Timeout(token),
        "Cancel" to MatchEvent.Cancel,
        "Error" to MatchEvent.Error,
    )

    private val expected = """
        |        StartAdv Role Bump Found Picked Incoming Ready Sent  Recv  Refused Declined Host Info  Joined Intro Timeout Cancel Error
        |Idle    Adv      Idle -    -     -      Idle     -     -     -     -       -        -    -     -      -     -       -      -
        |Adv     -        Adv  Col  -     -      Adv      -     -     -     -       -        -    -     -      -     -       Idle   Fail
        |Col     -        -    -    Att   -      ConfR    -     -     -     -       -        -    -     -      -     Fail    Idle   Fail
        |Await   -        -    -    -     -      ConfR    -     -     -     -       -        -    -     -      -     Fail    Idle   Fail
        |Att     -        -    -    -     -      ConfI    ConfI -     -     Att     -        -    -     -      -     Att     Idle   Fail
        |Man     -        -    -    -     Att    ConfR    -     -     -     -       -        -    -     -      -     Fail    Idle   Fail
        |ConfI   -        -    -    -     -      -        -     ConfI ConfI Att     Att      -    -     -      -     Fail    Idle   Fail
        |ConfR   -        -    -    -     -      -        -     ConfR ConfR Fail    Fail     -    -     -      -     Fail    Idle   Fail
        |Match   -        -    -    -     -      Match    -     -     -     -       -        Join -     -      -     Fail    Idle   Fail
        |Join    -        -    -    -     -      Join     -     -     -     -       -        -    JoinI -      -     Fail    Idle   Fail
        |JoinI   -        -    -    -     -      JoinI    -     -     -     -       -        -    -     Joined -     Fail    Idle   Fail
        |Intro   -        -    -    -     -      Intro    -     -     -     -       -        -    -     -      Adv   Adv     Idle   Fail
        |Joined  Adv      -    -    -     -      Joined   -     -     -     -       -        -    -     -      -     -       Idle   Fail
        |Fail    Adv      -    -    -     -      Fail     -     -     -     -       -        -    -     -      -     -       Idle   -
    """.trimMargin()

    @Test
    fun everyStateEventCombinationMatchesTheIndependentTable() {
        val lines = expected.lines()
        val header = lines.first().trim().split(Regex("\\s+"))
        assertEquals(events.keys.toList(), header, "표의 열과 이벤트 샘플이 어긋난다")
        assertEquals(samples.keys.toList(), lines.drop(1).map { it.trim().split(Regex("\\s+")).first() })

        val mismatches = mutableListOf<String>()
        lines.drop(1).forEach { line ->
            val cells = line.trim().split(Regex("\\s+"))
            val (state, ctx) = samples.getValue(cells.first())
            header.forEachIndexed { i, eventName ->
                val want = cells[i + 1]
                val result = MatchTransitions.transition(env, state, ctx, events.getValue(eventName))
                val got = if (result.state == state && result.ctx == ctx && result.effects.isEmpty()) "-" else label(result.state)
                if (got != want) mismatches += "${cells.first()} × $eventName: 기대 $want, 실제 $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun onlyConfirmingCanDeclareMatchedAsTarget() {
        // I-6 (정적): 다른 상태에서 Matched로 들어가는 행은 Confirming 출발뿐이다.
        val entering = MatchTransitions.rows.filter { StateKind.Matched in it.to && it.from != StateKind.Matched }
        assertTrue(entering.isNotEmpty())
        entering.forEach { assertEquals(StateKind.Confirming, it.from, "Matched로 들어가는 행: ${it.from} × ${it.on}") }
        // Matched 안에 머무는 행(다른 요청을 Busy로 거절 등)은 Matched 말고 다른 곳으로 새지 않는지 따로 본다.
        MatchTransitions.rows.filter { it.from == StateKind.Matched && StateKind.Matched in it.to }
            .forEach { assertEquals(setOf(StateKind.Matched), it.to, "Matched 자기 루프 행: ${it.on}") }
    }

    @Test
    fun rowsAreUniquePerStateAndEvent() {
        val dup = MatchTransitions.rows.groupBy { it.from to it.on }.filterValues { it.size > 1 }.keys
        assertTrue(dup.isEmpty(), "중복 행: $dup")
    }

    @Test
    fun ruleResultOutsideDeclaredTargetsIsAnError() {
        // 모든 샘플 × 이벤트에서 결과는 해당 행이 선언한 도착 상태 안에 있다 (transition이 검사하지만 직접도 확인).
        samples.values.forEach { (state, ctx) ->
            events.values.forEach { event ->
                val result = MatchTransitions.transition(env, state, ctx, event)
                val row = MatchTransitions.rows.firstOrNull { it.from == state.kind && it.on == event.kind }
                if (row == null) {
                    assertEquals(state, result.state)
                } else if (!(result.state == state && result.effects.isEmpty())) {
                    assertTrue(result.state.kind in row.to, "${state.kind} × ${event.kind} → ${result.state.kind} (선언: ${row.to})")
                }
            }
        }
    }

    private fun ctx(timer: Boolean = true) =
        MatchContext(role = Role.Player, timerToken = if (timer) token else null, tokenSeq = token)

    private fun label(state: MatchState): String = when (state) {
        MatchState.Idle -> "Idle"
        MatchState.Advertising -> "Adv"
        is MatchState.Candidate -> when (state.phase) {
            CandidatePhase.Collecting -> "Col"
            CandidatePhase.AwaitingIncoming -> "Await"
            is CandidatePhase.Attempting -> "Att"
            is CandidatePhase.Manual -> "Man"
        }
        is MatchState.Confirming -> if (state.fallback != null) "ConfI" else "ConfR"
        is MatchState.Matched -> "Match"
        is MatchState.Joining -> if (state.joinInfoReceived) "JoinI" else "Join"
        is MatchState.Introducing -> "Intro"
        MatchState.Joined -> "Joined"
        is MatchState.Failed -> "Fail"
        else -> fail("라벨 없는 상태 $state")
    }
}
