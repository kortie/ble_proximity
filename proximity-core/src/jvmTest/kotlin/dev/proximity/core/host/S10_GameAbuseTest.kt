package dev.proximity.core.host

import dev.proximity.core.api.ActionOutcome
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.Role
import dev.proximity.core.api.SocialOutcome
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.game.GameKind
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionChannel
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.GameAction
import dev.proximity.testing.FakeClientTransport
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.test.assertTrue

/** 게임 액션 재생·사칭, social 스팸, 로그 정책 (S10). */
@OptIn(ExperimentalCoroutinesApi::class)
class S10_GameAbuseTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = BouncyCastleCryptoBox()

    private inner class Env(val scope: CoroutineScope) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)
        val module = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true))
        val gameHost = GameHost(config, module, clock)
        val logs = mutableListOf<String>()
        lateinit var host: SessionGameClient

        suspend fun start() {
            hostT.open(session.sessionTag, router.sessionInfo())
            router.log = { logs += it }
            gameHost.log = { logs += it }
            router.start()
            host = gameHost.attachHost(router, sessions, scope) as SessionGameClient
        }

        inner class Dev(val session: SessionClient, val game: SessionGameClient, val transport: FakeClientTransport) {
            val socials = mutableListOf<String>()

            init {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.socials.collect { socials += it.data.decodeToString() } }
            }
        }

        suspend fun join(name: String, role: Role = Role.Player): Dev {
            val nonce = crypto.randomBytes(16)
            val t = net.client()
            val sc = SessionClient(config, t, crypto, clock, scope)
            val dev = Dev(sc, SessionGameClient(config, sc, clock, scope), t)
            assertIs<JoinOutcome.Joined>(sc.join(sessions.issueToken(nonce, role)!!, JoinRequest(nonce, role, name, "fake.game", 1)))
            return dev
        }

        fun onActions() = module.calls.count { it == "onAction" }
    }

    private fun TestScope.env() = Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun capturedActionFramesReplayedOnAnyLinkAreNotApplied() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val aLink = e.net.linkOf(a.transport)
        val captured = mutableListOf<ByteArray>()
        e.net.dropRule = { r -> if (!r.toClient && r.link == aLink) captured += r.bytes.copyOf(); false }
        assertEquals(ActionOutcome.Accepted, a.game.sendAction(byteArrayOf(3)))
        e.net.dropRule = { false }
        assertEquals(1, e.onActions())
        captured.forEach { a.transport.send(it) } // 같은 링크로 재전송: AEAD 카운터가 맞지 않는다
        captured.forEach { b.transport.send(it) } // 다른 링크로 재전송: 다른 링크 키
        assertEquals(1, e.onActions(), "onAction 추가 호출 0")
        assertEquals(3, e.module.counter)
        assertEquals(ActionOutcome.Accepted, b.game.sendAction(byteArrayOf(1)), "재연결 뒤 정상")
    }

    @Test
    fun actionIdsAreScopedToTheSendersLink() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val sameId = { v: Byte -> GameAction.ADAPTER.encode(GameAction(action_id = 1, data_ = byteArrayOf(v).toByteString())) }
        a.session.send(SessionChannel.Game, GameKind.ACTION, sameId(2))
        // B가 A의 actionId를 써도 B 자신의 액션으로 처리되고, A의 기록을 건드리지 않는다
        b.session.send(SessionChannel.Game, GameKind.ACTION, sameId(5))
        assertEquals(2, e.onActions())
        assertEquals(7, e.module.counter)
        a.session.send(SessionChannel.Game, GameKind.ACTION, sameId(2))
        assertEquals(2, e.onActions(), "A의 재전송은 여전히 중복")
    }

    @Test
    fun spectatorSocialSpamDoesNotDelayTheGame() = runTest {
        val e = env().apply { start() }
        val p = e.join("P")
        val s = e.join("S", Role.Spectator)
        val outcomes = (0 until 100).map { s.game.sendSocial("spam$it".encodeToByteArray()) }
        assertEquals(config.social.maxMsgPerMin, outcomes.count { it == SocialOutcome.Delivered })
        assertEquals(100 - config.social.maxMsgPerMin, outcomes.count { it == SocialOutcome.RateLimited })
        // 기기 쪽 검사를 건너뛰어도 호스트가 막는다
        repeat(50) { s.session.send(SessionChannel.Social, 1, "raw$it".encodeToByteArray()) }
        assertEquals(config.social.maxMsgPerMin, p.socials.size, "분당 상한까지만 퍼진다")
        assertEquals(ActionOutcome.Accepted, p.game.sendAction(byteArrayOf(1)), "game은 그대로 진행")
    }

    @Test
    fun logsCarryNoPayloadsNamesOrSecrets() = runTest {
        val e = env().apply { start() }
        val a = e.join("홍길동")
        val s = e.join("관전자김", Role.Spectator)
        a.game.sendSocial("비밀 계획".encodeToByteArray())
        a.game.sendAction(byteArrayOf(1, 2, 3))
        a.game.sendAction(FakeGameModule.op(FakeGameModule.OP_THROW))
        a.game.sendAction(FakeGameModule.op(FakeGameModule.OP_BIG_VIEW))
        s.session.send(SessionChannel.Game, GameKind.ACTION, GameAction.ADAPTER.encode(GameAction(9, "관전자액션".encodeToByteArray().toByteString())))
        val raw = e.net.client()
        raw.connect(dev.proximity.core.interfaces.HostRef(e.session.sessionTag))
        raw.send(ByteArray(40) { 7 })
        assertTrue(e.logs.isNotEmpty())
        val all = e.logs.joinToString("\n")
        for (bad in listOf("비밀 계획", "관전자액션", "홍길동", "관전자김", "SECRET-", e.session.sessionTag.toString(), e.session.sessionTag.toUInt().toString(16))) {
            assertFalse(bad in all, "로그에 '$bad'가 있다:\n$all")
        }
    }
}
