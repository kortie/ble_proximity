package dev.proximity.core.matching

import dev.proximity.core.ble.AdvFlags
import dev.proximity.core.ble.PeerEntry
import dev.proximity.core.ble.PeerSeen
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.state.PeerRef
import dev.proximity.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S4_PeerMatcherTest {
    private val config = ProximityConfig.Matching(
        windowMs = 300, rssiMinDbm = -70, rssiStrongDbm = -30, weightTime = 0.3, weightRssi = 0.7, bumpFlagWaitMs = 800,
        maxAttempts = 2, attemptTimeoutMs = 3000, retryCooldownMs = 10_000, rdvBackoffMs = 300,
    )
    private val clock = FakeClock(2000)
    private val cooldown = Cooldown(clock, config.retryCooldownMs)
    private val matcher = PeerMatcher(config)
    private val me = MatchSelf(sessionTag = null, bumpAtMs = 1000)

    private fun entry(id: Int, rssi: Int = -40, changeAt: Long? = 1100, flags: AdvFlags = AdvFlags(bumpSeq = 1), tag: Int? = null) =
        PeerEntry(PeerSeen(id, rssi, 1900, flags, tag), firstSeenMs = 0, lastBumpChangeAtMs = changeAt)

    private fun select(vararg peers: PeerEntry, self: MatchSelf = me) = matcher.select(self, peers.toList(), cooldown)

    @Test
    fun ranksByRssiAndTimeCloseness() {
        val s = select(entry(1, rssi = -60), entry(2, rssi = -35), entry(3, rssi = -35, changeAt = 1700))
        assertEquals(listOf(PeerRef(2), PeerRef(3), PeerRef(1)), s.peers, "가까울수록, 범프 시각이 가까울수록 앞")
        assertTrue(s.candidates.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun tiesAreBrokenByTempIdForDeterminism() {
        assertEquals(listOf(PeerRef(3), PeerRef(7)), select(entry(7), entry(3)).peers)
    }

    @Test
    fun f1SameSessionAndF2MemberSeesAnyMember() {
        val member = MatchSelf(sessionTag = 42, bumpAtMs = 1000)
        val s = select(
            entry(1, flags = AdvFlags(inSession = true, accepting = true, bumpSeq = 1), tag = 42),
            entry(2, flags = AdvFlags(inSession = true, accepting = true, bumpSeq = 1), tag = 99),
            entry(3),
            self = member,
        )
        assertEquals(listOf(PeerRef(3)), s.peers)
        assertEquals(Exclusion.SameSession, s.excluded[PeerRef(1)])
        assertEquals(Exclusion.MemberSeesMember, s.excluded[PeerRef(2)])
    }

    @Test
    fun f3FullSessionMemberIsExcludedForNewcomers() {
        val s = select(
            entry(1, flags = AdvFlags(inSession = true, accepting = false, bumpSeq = 1), tag = 5),
            entry(2, flags = AdvFlags(inSession = true, accepting = true, bumpSeq = 1), tag = 5),
        )
        assertEquals(listOf(PeerRef(2)), s.peers)
        assertEquals(Exclusion.FullSession, s.excluded[PeerRef(1)])
    }

    @Test
    fun f4BusyF5NotBumpedF6WeakF7Cooldown() {
        cooldown.mark(PeerRef(4))
        val s = select(
            entry(1, flags = AdvFlags(busy = true, bumpSeq = 1)),
            entry(2, rssi = -25, changeAt = null),
            entry(3, rssi = -71),
            entry(4),
            entry(5),
        )
        assertEquals(listOf(PeerRef(5)), s.peers)
        assertEquals(Exclusion.Busy, s.excluded[PeerRef(1)])
        assertEquals(Exclusion.NotBumped, s.excluded[PeerRef(2)], "RSSI가 가장 커도 범프 안 했으면 제외")
        assertEquals(Exclusion.Weak, s.excluded[PeerRef(3)])
        assertEquals(Exclusion.Cooldown, s.excluded[PeerRef(4)])
    }

    @Test
    fun cooldownExpires() {
        cooldown.mark(PeerRef(1))
        assertTrue(select(entry(1)).peers.isEmpty())
        clock.advanceBy(config.retryCooldownMs)
        assertEquals(listOf(PeerRef(1)), select(entry(1)).peers)
    }

    @Test
    fun windowBoundaries() {
        // 범프 1000ms, window 300, wait 800 → 변경 시각 [700, 1800]
        val s = select(entry(1, changeAt = 699), entry(2, changeAt = 700), entry(3, changeAt = 1800), entry(4, changeAt = 1801))
        assertEquals(setOf(PeerRef(2), PeerRef(3)), s.peers.toSet())
        assertEquals(Exclusion.NotBumped, s.excluded[PeerRef(1)])
        assertEquals(Exclusion.NotBumped, s.excluded[PeerRef(4)])
    }

    @Test
    fun onlyWeakCandidatesMeansNoCandidate() {
        assertTrue(select(entry(1, rssi = -75), entry(2, rssi = -90)).peers.isEmpty())
    }

    @Test
    fun duplicateEntriesAreMerged() {
        assertEquals(listOf(PeerRef(1)), select(entry(1), entry(1, rssi = -50)).peers, "F8: 같은 tempId는 하나")
    }

    @Test
    fun acceptIncomingIgnoresBusyButAppliesOtherFilters() {
        assertEquals(IncomingDecision.Accept, matcher.acceptIncoming(me, entry(1, flags = AdvFlags(busy = true, bumpSeq = 1)), cooldown),
            "요청자는 확인을 시작하며 busy를 켠다")
        assertEquals(IncomingDecision.Refuse(Exclusion.NotBumped), matcher.acceptIncoming(me, entry(2, changeAt = null), cooldown))
        assertEquals(IncomingDecision.Refuse(Exclusion.Weak), matcher.acceptIncoming(me, entry(3, rssi = -80), cooldown))
        assertEquals(IncomingDecision.Refuse(Exclusion.Unknown), matcher.acceptIncoming(me, null, cooldown), "광고를 본 적 없는 요청자")
        val member = MatchSelf(sessionTag = 9, bumpAtMs = 1000)
        assertEquals(IncomingDecision.Refuse(Exclusion.SameSession),
            matcher.acceptIncoming(member, entry(4, flags = AdvFlags(inSession = true, bumpSeq = 1), tag = 9), cooldown))
    }
}
