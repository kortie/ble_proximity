package dev.proximity.core.session

import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S5_SessionManagerTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText()).session
    private val clock = FakeClock()
    private val crypto = BouncyCastleCryptoBox()
    // S7: 관전자 자리는 게임 인원에 따라 정해진다 (8인이면 0). 관전자를 쓰는 시험은 4인 게임으로
    private val manager = SessionManager(config, clock, crypto).also { it.gameMaxPlayers = 4 }
    private val nonceX = crypto.randomBytes(16)

    @Test
    fun createPutsHostFirstAndIsIdempotent() {
        val s = manager.create(Role.Player, "호스트")
        assertTrue(s.sessionTag != 0)
        assertEquals(32, s.hostKeys.publicKey.size)
        assertEquals(s, manager.create(Role.Player, "다시"))
        assertEquals(listOf("호스트"), manager.participants().map { it.displayName })
    }

    @Test
    fun noTokenWithoutSession() {
        assertNull(manager.issueToken(nonceX, Role.Player))
        assertEquals(Admission.Rejected(TokenRejection.NoSession), manager.redeem(ByteArray(16), nonceX, "x"))
    }

    @Test
    fun tokenAdmitsOnceBoundToJoinNonce() {
        manager.create(Role.Player, "호스트")
        val ticket = assertNotNull(manager.issueToken(nonceX, Role.Spectator))
        assertEquals(Admission.Rejected(TokenRejection.NonceMismatch), manager.redeem(ticket.token, crypto.randomBytes(16), "X"))
        // S10: 중복 HELLO는 join_secret이 같아야 같은 기기로 본다 (ADR-006 개정)
        val secret = crypto.randomBytes(16)
        val first = assertIs<Admission.Admitted>(manager.redeem(ticket.token, nonceX, "X", joinSecret = secret))
        assertEquals(Role.Spectator, first.participant.role)
        assertEquals(Admission.Rejected(TokenRejection.AlreadyUsed), manager.redeem(ticket.token, nonceX, "X"), "secret 없는 중복은 거절")
        val again = assertIs<Admission.Admitted>(manager.redeem(ticket.token, nonceX, "X", joinSecret = secret), "중복 HELLO")
        assertEquals(first.participant, again.participant)
        assertEquals(2, manager.participants().size, "참가자가 늘지 않는다")
        assertEquals(Admission.Rejected(TokenRejection.UnknownToken), manager.redeem(crypto.randomBytes(16), nonceX, "X"))
    }

    @Test
    fun tokenExpires() {
        manager.create(Role.Player, "호스트")
        val ticket = manager.issueToken(nonceX, Role.Player)!!
        clock.advanceBy(config.joinTokenTtlSec * 1000)
        assertEquals(Admission.Rejected(TokenRejection.Expired), manager.redeem(ticket.token, nonceX, "X"))
    }

    @Test
    fun removedParticipantCannotResumeWithTheSameToken() {
        manager.create(Role.Player, "호스트")
        val ticket = manager.issueToken(nonceX, Role.Player)!!
        val admitted = assertIs<Admission.Admitted>(manager.redeem(ticket.token, nonceX, "X"))
        manager.remove(admitted.participant.id)
        assertEquals(Admission.Rejected(TokenRejection.AlreadyUsed), manager.redeem(ticket.token, nonceX, "X"))
    }

    @Test
    fun sessionCodeIsSixDigitsAndStable() {
        assertNull(manager.sessionCode())
        manager.create(Role.Player, "호스트")
        val code = manager.sessionCode()!!
        assertTrue(Regex("\\d{6}").matches(code))
        assertEquals(code, manager.sessionCode())
        manager.end()
        assertNull(manager.session)
        assertTrue(manager.participants().isEmpty(), "세션 리소스 잔존 0")
    }
}
