package dev.proximity.core.router

import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SealedChannel
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.HelloBody
import dev.proximity.proto.JoinInfo
import dev.proximity.proto.QrTicket
import dev.proximity.proto.Token
import dev.proximity.proto.Welcome
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** join token 재사용·사칭, 연결 속도 제한, nonce 카운터 상한, 비밀 필드 노출 (S10). */
@OptIn(ExperimentalCoroutinesApi::class)
class S10_SessionHardeningTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = RecordingCrypto(BouncyCastleCryptoBox())

    private class RecordingCrypto(private val inner: CryptoBox) : CryptoBox by inner {
        val sealed = mutableListOf<Pair<List<Byte>, List<Byte>>>()

        override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            sealed += key.toList() to nonce.toList()
            return inner.seal(key, nonce, aad, plaintext)
        }
    }

    @AfterTest
    fun restoreLimit() {
        LinkCrypto.messageLimit = LinkCrypto.DEFAULT_MESSAGE_LIMIT
    }

    private inner class Env(val scope: CoroutineScope, val config: ProximityConfig) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto).also { it.gameMaxPlayers = 4 }
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)

        suspend fun start() {
            hostT.open(session.sessionTag, router.sessionInfo())
            router.start()
            router.attachLoopback(sessions.participants().first()) { _, _ -> }
        }

        fun client() = SessionClient(config, net.client(), crypto, clock, scope)
    }

    private fun TestScope.env(config: ProximityConfig = baseConfig) =
        Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config)

    @Test
    fun anIntroducerCannotReuseTheNewcomersTokenToImpersonateIt() = runTest {
        val e = env().apply { start() }
        // 중개 멤버 M은 X의 joinNonce와 token을 안다. join_secret은 X의 봉인된 HELLO에만 있다
        val nonce = crypto.randomBytes(16)
        val ticket = e.sessions.issueToken(nonce, Role.Player)!!
        val secret = crypto.randomBytes(16)
        val x = e.client()
        val joined = assertIs<JoinOutcome.Joined>(x.join(ticket, JoinRequest(nonce, Role.Player, "X", "g", 1, joinSecret = secret)))
        val m = e.client()
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), m.join(ticket, JoinRequest(nonce, Role.Player, "X", "g", 1)), "join_secret 없이 같은 token")
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), e.client().join(ticket, JoinRequest(nonce, Role.Player, "X", "g", 1, joinSecret = crypto.randomBytes(16))))
        // X 본인의 재시도(WELCOME을 잃은 경우)는 같은 secret이라 같은 참가자로
        val retry = assertIs<JoinOutcome.Joined>(e.client().join(ticket, JoinRequest(nonce, Role.Player, "X", "g", 1, joinSecret = secret)))
        assertEquals(joined.participant, retry.participant)
        assertEquals(2, e.router.participants().size)
    }

    @Test
    fun expiredOrUsedTokensAreRejected() = runTest {
        val e = env().apply { start() }
        val nonce = crypto.randomBytes(16)
        val ticket = e.sessions.issueToken(nonce, Role.Player)!!
        e.clock.advanceBy(e.config.session.joinTokenTtlSec * 1000)
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), e.client().join(ticket, JoinRequest(nonce, Role.Player, "X", "g", 1)), "만료")
        val n2 = crypto.randomBytes(16)
        val t2 = e.sessions.issueToken(n2, Role.Player)!!
        assertEquals(JoinOutcome.Refused(TokenResult.BAD_TOKEN), e.client().join(t2, JoinRequest(crypto.randomBytes(16), Role.Player, "X", "g", 1)), "joinNonce가 다르면 거부")
    }

    @Test
    fun failedLinksTriggerTheRateLimitButNormalReconnectsDoNot() = runTest {
        val e = env().apply { start() }
        val max = e.config.security.maxConnectPerMin
        // 정상 재연결은 아무리 많아도 세지 않는다
        val nonce = crypto.randomBytes(16)
        val a = e.client()
        val aTransport = e.net.client()
        val member = SessionClient(e.config, aTransport, crypto, e.clock, e.scope)
        assertIs<JoinOutcome.Joined>(member.join(e.sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, "A", "g", 1)))
        repeat(max * 2) { e.net.disconnect(e.net.linkOf(aTransport)) }
        assertEquals(ClientSessionState.Live, member.clientState, "정상 resume은 막지 않는다")
        // HELLO 없이 시간 초과되는 링크(인증 실패)가 max개 쌓이면 새 링크를 바로 닫는다
        repeat(max) { assertNotNull(e.net.client().connect(HostRef(e.session.sessionTag))) }
        e.clock.advanceBy(e.config.transport.helloTimeoutMs)
        val late = e.net.client()
        late.connect(HostRef(e.session.sessionTag))
        assertTrue(runCatching { e.net.linkOf(late) }.isFailure, "인증 실패가 쌓이면 새 링크를 받지 않는다")
        e.clock.advanceBy(60_000)
        val n2 = crypto.randomBytes(16)
        assertIs<JoinOutcome.Joined>(a.join(e.sessions.issueToken(n2, Role.Player)!!, JoinRequest(n2, Role.Player, "B", "g", 1)), "1분 뒤 정상")
    }

    @Test
    fun exhaustedNonceCounterRenegotiatesTheLink() = runTest {
        LinkCrypto.messageLimit = 12
        // 호스트 쪽 카운터도 같은 상한이라 재연결이 잦다: 이 시험에서는 연결 속도 제한을 푼다 (속도 제한은 위 시험이 본다)
        val e = env(baseConfig.copy(security = baseConfig.security.copy(maxConnectPerMin = 1000))).apply { start() }
        val nonce = crypto.randomBytes(16)
        val a = e.client()
        assertIs<JoinOutcome.Joined>(a.join(e.sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, "A", "g", 1)))
        crypto.sealed.clear()
        val sent = (0 until 20).count { a.send(SessionChannel.Social, 1, "m$it".encodeToByteArray()) }
        assertTrue(sent >= 14, "상한에 닿으면 재연결하고 이어서 보낸다: $sent")
        assertEquals(ClientSessionState.Live, a.clientState)
        assertEquals(crypto.sealed.size, crypto.sealed.toSet().size, "같은 키에서 nonce 재사용 0")
    }

    @Test
    fun sealedChannelRefusesPastItsLimit() {
        val c = BouncyCastleCryptoBox()
        val ch = SealedChannel(c, ByteArray(32), SealedChannel.CLIENT, SealedChannel.HOST, ByteArray(0), limit = 2)
        ch.seal(byteArrayOf(1))
        assertFalse(ch.exhausted)
        ch.seal(byteArrayOf(2))
        assertTrue(ch.exhausted)
        assertFailsWith<IllegalStateException> { ch.seal(byteArrayOf(3)) }
    }

    @Test
    fun secretFieldsNeverAppearInDebugOutput() {
        val secret = ByteArray(16) { (0xA0 + it).toByte() }
        val hex = secret.toByteString().hex()
        val texts = listOf(
            Welcome(participant_id = 1, resume_secret = secret.toByteString()).toString(),
            Token(token = secret.toByteString()).toString(),
            JoinInfo(token = secret.toByteString()).toString(),
            QrTicket(token = secret.toByteString()).toString(),
            HelloBody.NewJoin(token = secret.toByteString(), join_secret = secret.toByteString()).toString(),
            HelloBody.Resume(participant_id = 1, resume_secret = secret.toByteString()).toString(),
        )
        for (t in texts) assertFalse(hex in t || secret.toByteString().base64() in t, t)
    }
}
