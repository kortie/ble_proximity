package dev.proximity.core.session

import dev.proximity.core.api.Role
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.HostDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class S5_HostDeciderTest {
    private fun facts(
        role: Role = Role.Player, game: String = "g", tag: Int? = null, host: Boolean = false, capable: Boolean = true, bid: Int = 1,
    ) = ConfirmFacts(role, game, 1, tag, host, capable, bid)

    private fun decided(d: HostDecision) = DecideOutcome.Decided(d)

    @Test
    fun tableRows() {
        assertEquals(decided(HostDecision.Fail(FailureReason.GameMismatch)), HostDecider.decide(facts(game = "a"), facts(game = "b")), "#1")
        assertEquals(decided(HostDecision.Fail(FailureReason.SessionConflict)), HostDecider.decide(facts(tag = 1), facts(tag = 2)), "#2")
        assertEquals(decided(HostDecision.Introduce), HostDecider.decide(facts(tag = 1), facts()), "#4 세션 있는 쪽")
        assertEquals(decided(HostDecision.JoinExisting), HostDecider.decide(facts(), facts(tag = 1, host = true)), "#4 세션 없는 쪽")
        assertEquals(decided(HostDecision.Fail(FailureReason.NoSessionToWatch)),
            HostDecider.decide(facts(role = Role.Spectator), facts(role = Role.Spectator)), "#5")
        assertEquals(decided(HostDecision.BecomeHost), HostDecider.decide(facts(), facts(role = Role.Spectator)), "#6 Player")
        assertEquals(decided(HostDecision.JoinNew), HostDecider.decide(facts(role = Role.Spectator), facts()), "#6 Spectator")
        assertEquals(decided(HostDecision.Fail(FailureReason.NoHostCapable)),
            HostDecider.decide(facts(role = Role.Spectator), facts(capable = false)), "#6 Player가 호스트 불가")
        assertEquals(decided(HostDecision.BecomeHost), HostDecider.decide(facts(capable = true), facts(capable = false)), "#7 capable")
        assertEquals(decided(HostDecision.Fail(FailureReason.NoHostCapable)), HostDecider.decide(facts(capable = false), facts(capable = false)))
        assertEquals(decided(HostDecision.BecomeHost), HostDecider.decide(facts(bid = 9), facts(bid = 3)), "#7 bid 큰 쪽")
        assertEquals(decided(HostDecision.BecomeHost), HostDecider.decide(facts(bid = -1), facts(bid = 3)), "bid는 부호 없는 비교")
        assertEquals(DecideOutcome.Reroll, HostDecider.decide(facts(bid = 5), facts(bid = 5)), "#7 동률")
    }

    @Test
    fun bothSidesAlwaysAgree() {
        val space = sequence {
            for (role in Role.entries) for (game in listOf("g", "h")) for (session in listOf(null to false, 1 to true, 1 to false, 2 to false))
                for (capable in listOf(true, false)) for (bid in listOf(1, 2)) yield(ConfirmFacts(role, game, 1, session.first, session.second, capable, bid))
        }.toList()
        var pairs = 0
        for (a in space) for (b in space) {
            pairs++
            val ab = HostDecider.decide(a, b)
            val ba = HostDecider.decide(b, a)
            val consistent = when (ab) {
                DecideOutcome.Reroll -> ba == DecideOutcome.Reroll
                is DecideOutcome.Decided -> when (val d = ab.decision) {
                    is HostDecision.Fail -> ba == ab
                    HostDecision.BecomeHost -> ba == decided(HostDecision.JoinNew)
                    HostDecision.JoinNew -> ba == decided(HostDecision.BecomeHost)
                    HostDecision.Introduce -> ba == decided(HostDecision.JoinExisting)
                    HostDecision.JoinExisting -> ba == decided(HostDecision.Introduce)
                }
            }
            if (!consistent) fail("a=$a b=$b → $ab / $ba")
        }
        assertEquals(64 * 64, pairs)
    }
}
