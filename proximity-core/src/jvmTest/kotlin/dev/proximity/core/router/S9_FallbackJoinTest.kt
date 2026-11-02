package dev.proximity.core.router

import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.QrCodec
import dev.proximity.core.session.QrJoin
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 폴백 합류: QR(열린 1회용 token), 세션 코드(TOFU + 호스트 승인) (S9, 04 §5.2.1·§5.2.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class S9_FallbackJoinTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = BouncyCastleCryptoBox()

    private inner class Env(val scope: CoroutineScope, val config: ProximityConfig) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto).also { it.gameMaxPlayers = 4; it.gameId = "g" }
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)
        val requests = mutableListOf<CodeJoinRequest>()

        suspend fun start() {
            router.onCodeJoinRequest = { requests += it }
            hostT.open(session.sessionTag, router.sessionInfo())
            router.start()
            router.attachLoopback(sessions.participants().first()) { _, _ -> }
        }

        fun client() = SessionClient(config, net.client(), crypto, clock, scope)

        fun request(name: String, role: Role) = JoinRequest(crypto.randomBytes(16), role, name, "g", 1)

        fun qr(): QrJoin = QrCodec.decode(sessions.qrFor()!!, "g")!!
    }

    private fun TestScope.env(config: ProximityConfig = baseConfig) =
        Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config)

    @Test
    fun qrTicketRoundTripAndValidation() = runTest {
        val e = env().apply { start() }
        val text = e.sessions.qrFor()!!
        assertTrue(text.startsWith("BP1."))
        val q = assertNotNull(QrCodec.decode(text, "g"))
        assertEquals(e.session.sessionTag, q.sessionTag)
        assertNull(QrCodec.decode(text, "other.game"), "다른 게임의 QR")
        assertNull(QrCodec.decode("BP1.!!!", "g"))
        assertNull(QrCodec.decode("hello", "g"))
        assertNull(QrCodec.decode(text.replace("BP1.", "BP9."), "g"), "모르는 버전")
    }

    @Test
    fun qrJoinChoosesItsRoleAndIsSingleUse() = runTest {
        val e = env().apply { start() }
        val q = e.qr()
        val first = e.client().join(q.ticket, JoinRequest(q.joinNonce, Role.Spectator, "S", "g", 1))
        assertEquals(Role.Spectator, assertIs<JoinOutcome.Joined>(first).participant.role, "역할은 찍은 기기가 고른다")
        val second = e.client().join(q.ticket, JoinRequest(q.joinNonce, Role.Player, "X", "g", 1))
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), second, "같은 QR은 한 번만 (다른 기기가 같은 참가자로 들어올 수 없다)")
        assertNotEquals(q.ticket.token.toList(), e.qr().ticket.token.toList(), "새 QR은 새 token")
    }

    @Test
    fun qrJoinFollowsCapacityAndExpiry() = runTest {
        val e = env(baseConfig.copy(session = baseConfig.session.copy(maxSpectators = 0))).apply { start() }
        val q = e.qr()
        assertEquals(JoinOutcome.Refused(TokenResult.SPECTATOR_SLOTS_FULL), e.client().join(q.ticket, JoinRequest(q.joinNonce, Role.Spectator, "S", "g", 1)))
        val old = e.qr()
        e.clock.advanceBy(e.config.session.joinTokenTtlSec * 1000)
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), e.client().join(old.ticket, JoinRequest(old.joinNonce, Role.Player, "P", "g", 1)))
    }

    @Test
    fun codeJoinWaitsForHostApprovalWithMatchingSas() = runTest {
        val e = env().apply { start() }
        val code = e.sessions.sessionCode()!!
        var mySas: String? = null
        val c = e.client()
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            c.joinWithCode(HostRef(e.session.sessionTag), code, e.request("코드손님", Role.Player)) { mySas = it }
        }
        val req = e.requests.single()
        assertEquals("코드손님", req.displayName)
        assertEquals(req.sas, mySas, "호스트 화면과 신규 기기 화면의 확인 코드가 같다")
        assertEquals(listOf("H"), e.router.participants().map { it.displayName }, "승인 전에는 참가자가 아니다")
        e.router.answerCodeJoin(req.id, accept = true)
        assertIs<JoinOutcome.Joined>(outcome.await())
        assertEquals(listOf("H", "코드손님"), e.router.participants().map { it.displayName })
        assertEquals(ClientSessionState.Live, c.clientState)
    }

    @Test
    fun declinedOrTimedOutCodeJoinIsRefused() = runTest {
        val e = env().apply { start() }
        val code = e.sessions.sessionCode()!!
        val declined = async(start = CoroutineStart.UNDISPATCHED) { e.client().joinWithCode(HostRef(e.session.sessionTag), code, e.request("A", Role.Player)) {} }
        e.router.answerCodeJoin(e.requests.last().id, accept = false)
        assertEquals(JoinOutcome.Refused(TokenResult.CODE_DENIED), declined.await())
        val ignored = async(start = CoroutineStart.UNDISPATCHED) { e.client().joinWithCode(HostRef(e.session.sessionTag), code, e.request("B", Role.Player)) {} }
        e.clock.advanceBy(e.config.timeouts.confirmingMs)
        assertEquals(JoinOutcome.Refused(TokenResult.CODE_DENIED), ignored.await(), "confirming_ms 안에 답이 없으면 거절")
        assertEquals(listOf("H"), e.router.participants().map { it.displayName })
    }

    @Test
    fun wrongCodesAreRejectedAndRotateTheCode() = runTest {
        val e = env().apply { start() }
        val code = e.sessions.sessionCode()!!
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(e.config.security.maxAuthFailures) {
            assertEquals(JoinOutcome.Refused(TokenResult.BAD_CODE), e.client().joinWithCode(HostRef(e.session.sessionTag), wrong, e.request("X", Role.Player)) {})
        }
        assertTrue(e.requests.isEmpty(), "틀린 코드는 호스트에게 묻지 않는다")
        assertNotEquals(code, e.sessions.sessionCode(), "틀린 코드가 쌓이면 코드를 새로 만든다")
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_CODE), e.client().joinWithCode(HostRef(e.session.sessionTag), code, e.request("X", Role.Player)) {}, "옛 코드는 무효")
    }

    @Test
    fun codeJoinFollowsCapacityBeforeAskingTheHost() = runTest {
        val e = env(baseConfig.copy(session = baseConfig.session.copy(maxLinks = 1))).apply { start() }
        val q = e.qr()
        assertIs<JoinOutcome.Joined>(e.client().join(q.ticket, JoinRequest(q.joinNonce, Role.Player, "P", "g", 1)))
        val out = e.client().joinWithCode(HostRef(e.session.sessionTag), e.sessions.sessionCode()!!, e.request("X", Role.Spectator)) {}
        assertEquals(JoinOutcome.Refused(TokenResult.SESSION_FULL), out)
        assertTrue(e.requests.isEmpty())
    }

    @Test
    fun linkSasDependsOnTheHostKey() {
        val host = crypto.x25519KeyPair()
        val fake = crypto.x25519KeyPair()
        val me = crypto.x25519KeyPair()
        val real = LinkCrypto.derive(crypto, me.privateKey, host.publicKey, me.publicKey, host.publicKey, 7, iAmHost = false, sasDigits = 2)!!
        val hostSide = LinkCrypto.derive(crypto, host.privateKey, me.publicKey, me.publicKey, host.publicKey, 7, iAmHost = true, sasDigits = 2)!!
        assertEquals(real.sas, hostSide.sas)
        assertEquals(2, real.sas.length)
        val sasSet = (0 until 20).map {
            val f = crypto.x25519KeyPair()
            LinkCrypto.derive(crypto, me.privateKey, f.publicKey, me.publicKey, f.publicKey, 7, iAmHost = false, sasDigits = 2)!!.sas
        }.toSet()
        assertTrue(sasSet.size > 1, "호스트 키가 바뀌면 확인 코드도 바뀐다")
        assertNotNull(fake)
    }
}
