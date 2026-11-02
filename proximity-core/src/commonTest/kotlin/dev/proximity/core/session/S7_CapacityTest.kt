package dev.proximity.core.session

import dev.proximity.core.api.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 04-session-flows §5.3 자리 계산 (S7, I-14). */
class S7_CapacityTest {
    private fun limits(gameMax: Int, maxLinks: Int = 7, maxPlayers: Int = 8, maxSpectators: Int = 4) =
        CapacityLimits.of(maxLinks = maxLinks, maxPlayers = maxPlayers, maxSpectators = maxSpectators, gameMaxPlayers = gameMax)

    @Test
    fun matchesTheExampleTable() {
        // (게임 인원, P, S) — max_links 7, max_spectators 4
        val table = listOf(Triple(2, 2, 4), Triple(4, 4, 4), Triple(6, 6, 2), Triple(8, 8, 0))
        for ((game, p, s) in table) {
            val l = limits(game)
            assertEquals(p, l.players, "${game}인 P")
            assertEquals(s, l.spectators, "${game}인 S")
            assertFalse(l.reducedByLinks)
        }
    }

    @Test
    fun fewLinksReduceEffectivePlayers() {
        val l = limits(8, maxLinks = 5)
        assertEquals(6, l.players, "max_links 5 → 유효 P = 6")
        assertEquals(0, l.spectators)
        assertTrue(l.reducedByLinks)
    }

    @Test
    fun spectatorsNeverTakePlayerSlots() {
        for (game in listOf(2, 4, 6, 8)) {
            val l = limits(game)
            var occ = Occupancy(players = 1, spectators = 0) // 호스트
            // 관전자를 먼저 S까지 채운다
            while (l.check(Role.Spectator, occ) == null) occ = occ.copy(spectators = occ.spectators + 1)
            assertEquals(l.spectators, occ.spectators)
            // 그래도 플레이어는 P까지 들어온다
            while (l.check(Role.Player, occ) == null) occ = occ.copy(players = occ.players + 1)
            assertEquals(l.players, occ.players, "${game}인: 관전자가 가득해도 플레이어 P명")
            assertTrue(occ.clients <= 7, "클라이언트 수 ≤ B")
            assertFalse(l.slots(occ).accepting)
        }
    }

    @Test
    fun eightPlayersFillSevenLinks() {
        val l = limits(8)
        var occ = Occupancy(1, 0)
        repeat(7) {
            assertNull(l.check(Role.Player, occ))
            occ = occ.copy(players = occ.players + 1)
        }
        assertEquals(8, occ.players)
        assertEquals(TokenRejection.SessionFull, l.check(Role.Spectator, occ))
        assertEquals(TokenRejection.SessionFull, l.check(Role.Player, occ))
    }

    @Test
    fun rejectionReasons() {
        val l = limits(2)
        assertEquals(TokenRejection.PlayerSlotsFull, l.check(Role.Player, Occupancy(2, 0)))
        assertEquals(TokenRejection.SpectatorSlotsFull, l.check(Role.Spectator, Occupancy(2, 4)))
        val tight = limits(4, maxLinks = 2)
        assertEquals(TokenRejection.SessionFull, tight.check(Role.Spectator, Occupancy(3, 0)))
    }

    @Test
    fun slotsCountWhatIsLeft() {
        val l = limits(4)
        assertEquals(Slots(3, 4), l.slots(Occupancy(1, 0)))
        assertEquals(Slots(0, 2), l.slots(Occupancy(4, 2)))
        assertEquals(Slots(1, 0), limits(4, maxLinks = 3).slots(Occupancy(3, 0)), "링크가 모자라면 관전자 자리부터 없다")
    }
}
