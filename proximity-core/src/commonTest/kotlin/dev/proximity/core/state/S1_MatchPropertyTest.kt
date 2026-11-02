package dev.proximity.core.state

import dev.proximity.core.api.Role
import dev.proximity.testing.FakeClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * I-6 속성 테스트: 무작위 이벤트 열(시드 고정)에서 Matched 진입은 항상
 * 같은 확인 회차 안에서 그 상대의 ConfirmReceived와 내 ConfirmSent가 모두 있은 직후다.
 */
class S1_MatchPropertyTest {
    private val env = MatchEnv(maxAttempts = 2, candidateMs = 5000, attemptTimeoutMs = 3000, confirmingMs = 20000, joiningMs = 10000)
    private val peers = List(4) { PeerRef(it + 1) }

    @Test
    fun matchedOnlyAfterBothAcceptsInTheSameConfirmation() {
        var matchedCount = 0
        repeat(SEQUENCES) { seed ->
            val random = Random(seed)
            val clock = FakeClock()
            val machine = MatchStateMachine(env, clock, Role.Player)
            // 현재 확인 회차(같은 상대의 Confirming이 이어지는 동안)에 관측한 수락
            var localAccepted = false
            var peerAccepted = false
            repeat(STEPS) { step ->
                val before = machine.state
                val confirming = before as? MatchState.Confirming
                val event = randomEvent(random, machine).takeIf { random.nextInt(10) != 0 }
                if (event == null) clock.advanceBy(random.nextLong(0, 25_000)) else machine.dispatch(event)
                val after = machine.state

                // 이번 이벤트까지 포함한 수락 여부
                val localNow = localAccepted || (confirming != null && event == MatchEvent.ConfirmSent)
                val peerNow = peerAccepted ||
                    (confirming != null && event is MatchEvent.ConfirmReceived && event.peer == confirming.peer)

                if (after is MatchState.Matched && before !is MatchState.Matched) {
                    matchedCount++
                    if (confirming == null) fail("seed $seed step $step: ${before.kind} → Matched")
                    if (after.peer != confirming.peer || !localNow || !peerNow) {
                        fail("seed $seed step $step: 수락 없이 Matched (local=$localNow, peer=$peerNow)")
                    }
                }
                val sameRound = after is MatchState.Confirming && confirming != null && after.peer == confirming.peer
                localAccepted = sameRound && localNow
                peerAccepted = sameRound && peerNow
            }
        }
        assertTrue(matchedCount > 50, "무작위 열이 Matched에 충분히 도달하지 못했다 ($matchedCount) — 생성기를 고쳐야 한다")
    }

    private fun randomEvent(random: Random, machine: MatchStateMachine): MatchEvent {
        val peer = peers[random.nextInt(peers.size)]
        return when (random.nextInt(18)) {
            0 -> MatchEvent.StartAdvertising
            1 -> MatchEvent.RoleSelected(if (random.nextBoolean()) Role.Player else Role.Spectator)
            2 -> MatchEvent.BumpDetected(0)
            3 -> MatchEvent.CandidateFound(peers.shuffled(random).take(random.nextInt(0, 4)))
            4 -> MatchEvent.CandidatePicked(peer)
            5 -> MatchEvent.IncomingConfirm(peer)
            6 -> MatchEvent.RendezvousReady(currentPeer(machine) ?: peer)
            7, 8 -> MatchEvent.ConfirmSent
            9, 10 -> MatchEvent.ConfirmReceived(if (random.nextInt(4) == 0) peer else currentPeer(machine) ?: peer)
            11 -> MatchEvent.ConfirmRefused(currentPeer(machine) ?: peer, RefuseReason.entries.random(random))
            12 -> MatchEvent.ConfirmDeclined
            13 -> MatchEvent.HostDecided(listOf(HostDecision.BecomeHost, HostDecision.JoinNew, HostDecision.Introduce).random(random))
            14 -> MatchEvent.JoinInfoReceived
            15 -> MatchEvent.SessionJoined
            16 -> if (random.nextBoolean()) MatchEvent.Cancel else MatchEvent.IntroductionFinished
            else -> MatchEvent.Timeout(random.nextLong(0, 30))
        }
    }

    private fun currentPeer(machine: MatchStateMachine): PeerRef? = when (val s = machine.state) {
        is MatchState.Candidate -> (s.phase as? CandidatePhase.Attempting)?.peer
        is MatchState.Confirming -> s.peer
        else -> null
    }

    private companion object {
        const val SEQUENCES = 300
        const val STEPS = 200
    }
}
