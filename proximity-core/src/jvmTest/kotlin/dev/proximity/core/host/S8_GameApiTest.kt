package dev.proximity.core.host

import dev.proximity.core.api.ActionOutcome
import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.ClientState
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameEvent
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Resynced
import dev.proximity.core.api.Role
import dev.proximity.core.api.SocialOutcome
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.game.GameKind
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionChannel
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.GameAction
import dev.proximity.testing.FakeClientTransport
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeGameModule.Companion.op
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/** 게임 데이터 API: GameHost + GameClient (S8, 03-game-data-api). */
@OptIn(ExperimentalCoroutinesApi::class)
class S8_GameApiTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = BouncyCastleCryptoBox()

    private inner class Env(val scope: CoroutineScope, val config: ProximityConfig, val module: FakeGameModule) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)
        val gameHost = GameHost(config, module, clock)
        val logs = mutableListOf<String>()

        /** (수신자, kind, 평문) — 링크로 나가는 모든 논리 메시지 */
        val tap = mutableListOf<Triple<ParticipantId, Int, ByteArray>>()
        val tapChannels = mutableListOf<Pair<ParticipantId, SessionChannel>>()
        lateinit var host: Dev

        suspend fun start() {
            hostT.open(session.sessionTag, ByteArray(0))
            router.tap = { id, meta, p ->
                tapChannels += id to meta.channel
                if (meta.channel == SessionChannel.Game) tap += Triple(id, meta.kind, p)
            }
            router.start()
            gameHost.log = { logs += it }
            host = Dev("H", gameHost.attachHost(router, sessions, scope) as SessionGameClient, null, null)
        }

        inner class Dev(val name: String, val game: SessionGameClient, val session: SessionClient?, val transport: FakeClientTransport?) {
            val events = mutableListOf<GameEvent>()
            val views = mutableListOf<String>()
            val resyncs = mutableListOf<Resynced>()
            val socials = mutableListOf<String>()
            val states = mutableListOf<ClientState>()
            var roster: List<Participant> = emptyList()

            init {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.events.collect { events += it } }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.views.collect { views += it.data.decodeToString() } }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.resyncs.collect { resyncs += it } }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.socials.collect { socials += "${it.from.displayName}:${it.data.decodeToString()}" } }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.state.collect { states += it } }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { game.participants.collect { roster = it } }
            }

            val me get() = game.me()

            fun eventTexts() = events.map { it.data.decodeToString() }

            suspend fun act(bytes: ByteArray) = game.sendAction(bytes)

            /** 결과를 기다리지 않고 보낸다 (동시 액션·시간 초과 시험). */
            fun actLater(bytes: ByteArray): Deferred<ActionOutcome> = scope.async(start = CoroutineStart.UNDISPATCHED) { game.sendAction(bytes) }
        }

        suspend fun tryJoin(
            name: String,
            role: Role = Role.Player,
            gameId: String = "fake.game",
            version: Int = 1,
            checked: Boolean = false,
        ): Pair<Dev?, JoinOutcome> {
            val nonce = crypto.randomBytes(16)
            val ticket = if (checked) {
                when (val r = sessions.requestToken(nonce, role, name, version)) {
                    is TokenResult.Granted -> r.ticket
                    is TokenResult.Refused -> return null to JoinOutcome.Refused(r.reason, r.gameCode)
                }
            } else {
                sessions.issueToken(nonce, role)!!
            }
            val transport = net.client()
            val sc = SessionClient(config, transport, crypto, clock, scope)
            val dev = Dev(name, SessionGameClient(config, sc, clock, scope), sc, transport)
            val outcome = sc.join(ticket, JoinRequest(nonce, role, name, gameId, version))
            return (if (outcome is JoinOutcome.Joined) dev else null) to outcome
        }

        suspend fun join(name: String, role: Role = Role.Player): Dev {
            val (dev, outcome) = tryJoin(name, role)
            assertIs<JoinOutcome.Joined>(outcome, "$name 합류")
            return dev!!
        }

        fun kindsTo(id: ParticipantId) = tap.filter { it.first == id }.map { it.second }

        fun payloadsTo(id: ParticipantId) = tap.filter { it.first == id }.map { it.third }
    }

    private fun TestScope.env(
        config: ProximityConfig = baseConfig,
        module: FakeGameModule = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true)),
    ) = Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config, module)

    private fun ok() = byteArrayOf(1)

    @Test
    fun actionResultGoesOnlyToTheSenderAndViewsToEveryone() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        assertEquals(ActionOutcome.Accepted, a.act(byteArrayOf(3)))
        assertEquals(1, e.kindsTo(a.me.id).count { it == GameKind.ACTION_RESULT })
        assertEquals(0, e.kindsTo(b.me.id).count { it == GameKind.ACTION_RESULT }, "결과는 송신자에게만")
        for (d in listOf(e.host, a, b)) assertTrue(d.views.last().startsWith("c=3"), "${d.name}: ${d.views.last()}")
        assertEquals(listOf("add:3"), b.eventTexts())
        assertIs<ActionOutcome.Rejected>(a.act(byteArrayOf(1, 2, 3))).also { assertEquals(FakeGameModule.BAD_ACTION, it.code) }
    }

    @Test
    fun oneActionIsOneBatchWithConsecutiveSeqAndOneViewEmission() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val before = a.views.size
        val seqs = mutableListOf<Pair<Int, Long>>()
        e.router.tap = { id, meta, _ -> if (id == a.me.id && meta.channel == SessionChannel.Game) seqs += meta.kind to meta.seq }
        a.act(ok())
        assertEquals(listOf(GameKind.ACTION_RESULT, GameKind.EVENT, GameKind.VIEW), seqs.map { it.first })
        assertEquals(listOf(seqs[0].second, seqs[0].second + 1, seqs[0].second + 2), seqs.map { it.second }, "연속 seq")
        assertEquals(before + 1, a.views.size, "배치마다 view 한 번 (중간 상태 없음)")
    }

    @Test
    fun concurrentActionsApplyInTheSameOrderEverywhere() = runTest {
        val e = env().apply { start() }
        val (a, b, c) = listOf("A", "B", "C").map { e.join(it) }
        val pending = listOf(a, b, c).mapIndexed { i, d -> d.actLater(byteArrayOf((i + 1).toByte())) }
        e.host.actLater(byteArrayOf(9))
        pending.forEach { assertEquals(ActionOutcome.Accepted, it.await()) }
        val order = e.host.eventTexts()
        assertEquals(4, order.size)
        for (d in listOf(a, b, c)) assertEquals(order, d.eventTexts(), "${d.name}: 호스트 UI(loopback)와 같은 순서 (I-11, I-15)")
        for (d in listOf(a, b, c)) assertEquals(e.host.views.last().substringBefore(';'), d.views.last().substringBefore(';'))
    }

    @Test
    fun duplicateActionIdIsAppliedOnce() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val raw = GameAction.ADAPTER.encode(GameAction(action_id = 77, data_ = byteArrayOf(5).toByteString()))
        a.session!!.send(SessionChannel.Game, GameKind.ACTION, raw)
        a.session.send(SessionChannel.Game, GameKind.ACTION, raw)
        assertEquals(1, e.module.calls.count { it == "onAction" }, "같은 actionId → onAction 1회")
        assertEquals(2, e.kindsTo(a.me.id).count { it == GameKind.ACTION_RESULT }, "결과는 다시 보낸다")
        assertEquals(5, e.module.counter)
    }

    @Test
    fun lostResultIsRecoveredAfterReconnectWithoutReapplying() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val aLink = e.net.linkOf(a.transport!!)
        var dropped = 0
        // 결과·이벤트·view가 담긴 배치를 통째로 잃는다 → 링크 고장 → resume
        e.net.dropRule = { r -> (r.toClient && r.link == aLink && dropped < 3).also { if (it) dropped++ } }
        val outcome = a.actLater(byteArrayOf(4))
        e.net.dropRule = { false }
        e.net.disconnect(aLink)
        assertEquals(ActionOutcome.Accepted, outcome.await())
        assertEquals(1, e.module.calls.count { it == "onAction" }, "재전송된 같은 actionId는 다시 적용하지 않는다")
        assertTrue(a.views.last().startsWith("c=4"))
    }

    @Test
    fun lostUplinkActionIsResentWithTheSameIdAfterReconnect() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val aLink = e.net.linkOf(a.transport!!)
        e.net.dropRule = { r -> !r.toClient && r.link == aLink }
        val outcome = a.actLater(byteArrayOf(6))
        assertEquals(0, e.module.calls.count { it == "onAction" })
        e.net.dropRule = { false }
        e.net.disconnect(aLink)
        assertEquals(ActionOutcome.Accepted, outcome.await(), "재연결 뒤 같은 actionId로 다시 보낸다")
        assertEquals(1, e.module.calls.count { it == "onAction" })
    }

    @Test
    fun privateValuesReachOnlyTheirOwner() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val s = e.join("S", Role.Spectator)
        a.act(ok())
        a.act(op(FakeGameModule.OP_PRIVATE))
        val secretA = FakeGameModule.secretOf(a.me).encodeToByteArray().toList()
        fun leaks(id: ParticipantId) = e.payloadsTo(id).count { p -> p.toList().windowed(secretA.size).any { it == secretA } }
        assertTrue(leaks(a.me.id) >= 2, "A 본인은 view와 비공개 이벤트로 받는다")
        assertEquals(0, leaks(b.me.id), "B에게 가는 프레임에 A의 비공개 값 0 (I-13)")
        assertEquals(0, leaks(s.me.id), "관전자에게 0")
        assertEquals(0, leaks(e.host.me.id), "호스트 UI도 같은 규칙 (I-15)")
        assertTrue(s.views.last() == "c=1", "관전자 view는 공개 값만: ${s.views.last()}")
        assertEquals(listOf("pm:${FakeGameModule.secretOf(a.me)}"), a.eventTexts().filter { it.startsWith("pm:") })
        assertTrue(b.eventTexts().none { it.startsWith("pm:") })
    }

    @Test
    fun spectatorActionsNeverReachTheModule() = runTest {
        val e = env().apply { start() }
        e.join("A")
        val s = e.join("S", Role.Spectator)
        assertEquals(ActionOutcome.NotAllowed, s.act(ok()), "관전자는 로컬에서 NotAllowed")
        assertFalse(s.session!!.send(SessionChannel.Game, GameKind.ACTION, GameAction.ADAPTER.encode(GameAction(1, byteArrayOf(1).toByteString()))))
        assertEquals(0, e.module.calls.count { it == "onAction" })
        assertEquals(SocialOutcome.Delivered, s.game.sendSocial("hi".encodeToByteArray()), "관전자도 social은 된다")
    }

    @Test
    fun limitsAreEnforcedOnBothSides() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        assertEquals(ActionOutcome.TooLarge, a.act(ByteArray(e.config.game.maxActionBytes + 1)))
        // 기기 검사를 건너뛴 큰 액션도 호스트가 막는다
        val big = GameAction.ADAPTER.encode(GameAction(900, ByteArray(e.config.game.maxActionBytes + 1).toByteString()))
        a.session!!.send(SessionChannel.Game, GameKind.ACTION, big)
        assertEquals(0, e.module.calls.count { it == "onAction" })

        repeat(e.config.game.maxActionsPerSec) { assertEquals(ActionOutcome.Accepted, b.act(ok())) }
        assertEquals(ActionOutcome.RateLimited, b.act(ok()), "기기 쪽 속도 제한")
        // 호스트 쪽: 기기 검사를 건너뛰어 같은 1초 안에 더 보낸다
        val raw = GameAction.ADAPTER.encode(GameAction(901, byteArrayOf(1).toByteString()))
        b.session!!.send(SessionChannel.Game, GameKind.ACTION, raw)
        assertEquals(e.config.game.maxActionsPerSec, e.module.calls.count { it == "onAction" }, "호스트도 초과분을 GameModule에 넘기지 않는다")

        e.clock.advanceBy(1000)
        assertIs<ActionOutcome.Rejected>(a.act(op(FakeGameModule.OP_BIG_VIEW))).also { assertEquals(ActionResult.INTERNAL, it.code) }
        assertTrue(e.logs.any { "max_view_bytes" in it }, "view 초과는 오류 로그")
        assertIs<ActionOutcome.Rejected>(a.act(op(FakeGameModule.OP_BIG_EVENT))).also { assertEquals(ActionResult.INTERNAL, it.code) }
        assertEquals(ClientState.Live, b.states.last(), "세션은 유지")
    }

    @Test
    fun noResultInTimeIsTimeout() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val aLink = e.net.linkOf(a.transport!!)
        e.net.dropRule = { r -> !r.toClient && r.link == aLink }
        val outcome = a.actLater(ok())
        e.clock.advanceBy(e.config.game.actionTimeoutMs)
        assertEquals(ActionOutcome.Timeout, outcome.await())
    }

    @Test
    fun lateJoinersStartFromASnapshot() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        a.act(byteArrayOf(2))
        a.act(byteArrayOf(3))
        val p = e.join("P")
        val s = e.join("S", Role.Spectator)
        assertEquals(GameKind.SNAPSHOT, e.kindsTo(p.me.id).first(), "첫 game 메시지는 SNAPSHOT")
        assertEquals(GameKind.SNAPSHOT, e.kindsTo(s.me.id).first())
        assertEquals("c=5;s=${FakeGameModule.secretOf(p.me)}", p.views.first())
        assertEquals("c=5", s.views.first())
        a.act(byteArrayOf(1))
        assertEquals("c=6", s.views.last())
        assertEquals(listOf("onJoin", "onJoin", "onJoin", "onJoin"), e.module.calls.filter { it == "onJoin" }, "호스트 포함 4명")
        assertEquals("H", e.module.joined.first().displayName, "호스트가 첫 onJoin")
    }

    @Test
    fun outOfWindowLossResyncsFromViewsAlone() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        e.net.acceptingConnections = false
        e.net.disconnect(e.net.linkOf(b.transport!!))
        // 액션마다 B에게 EVENT·VIEW 2개 → resend_window/2 + 2번이면 창을 넘는다. client_reconnect_ms 안, 송신자별 속도 제한 안에서
        repeat(e.config.transport.resendWindow / 2 + 2) { i ->
            (if (i % 2 == 0) a else e.host).act(ok())
            e.clock.advanceBy(200)
        }
        e.net.acceptingConnections = true
        e.clock.advanceBy(e.config.session.joinRetryBackoffMs)
        assertEquals(ClientSessionState.Live, b.session!!.clientState)
        assertEquals(1, b.resyncs.size, "Resynced 1회")
        assertEquals(e.module.viewFor(b.me).decodeToString(), b.views.last(), "view만으로 상태 일치")
    }

    @Test
    fun timersFireOnceCancelAndStopAfterEnd() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        a.act(op(FakeGameModule.OP_TIMER, 10))
        e.clock.advanceBy(1000)
        assertEquals(1, e.module.calls.count { it == "onTimer" })
        assertEquals(listOf("timer"), a.eventTexts().filter { it == "timer" })
        e.clock.advanceBy(5000)
        assertEquals(1, e.module.calls.count { it == "onTimer" }, "한 번만")

        a.act(op(FakeGameModule.OP_TIMER, 10))
        a.act(op(FakeGameModule.OP_CANCEL))
        e.clock.advanceBy(2000)
        assertEquals(1, e.module.calls.count { it == "onTimer" }, "취소하면 0회")

        a.act(op(FakeGameModule.OP_TIMER, 10))
        a.act(op(FakeGameModule.OP_END))
        e.clock.advanceBy(2000)
        assertEquals(1, e.module.calls.count { it == "onTimer" }, "세션 종료 후 0회")
    }

    @Test
    fun joinRefusals() = runTest {
        val e = env(module = FakeGameModule(GameDescriptor("fake.game", 3, 2, 1, 4, false), lateJoinRefusal = 42)).apply { start() }
        assertEquals(JoinOutcome.Refused(TokenResult.GAME_MISMATCH), e.tryJoin("X", gameId = "other.game").second, "gameId 다름 (HELLO)")
        assertEquals(JoinOutcome.Refused(TokenResult.GAME_MISMATCH), e.tryJoin("X", version = 1, checked = true).second, "minCompatibleVersion 미만 (TOKEN_REQ)")
        assertEquals(JoinOutcome.Refused(TokenResult.SPECTATORS_NOT_ALLOWED), e.tryJoin("X", Role.Spectator, version = 3).second)
        val (a, _) = e.tryJoin("A", version = 3)
        a!!.act(op(FakeGameModule.OP_START))
        assertEquals(JoinOutcome.Refused(TokenResult.GAME_REFUSED, 42), e.tryJoin("L", version = 3).second, "진행 중 합류 불가 게임")
        assertEquals(JoinOutcome.Refused(TokenResult.GAME_REFUSED, 42), e.tryJoin("L", version = 3, checked = true).second)
    }

    @Test
    fun kickRemovesTheParticipantOnceAndBlocksResume() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val bLink = e.net.linkOf(b.transport!!)
        // REJECT(KICKED)를 잃어도 같은 resumeSecret으로는 돌아오지 못한다
        e.net.dropRule = { r -> r.toClient && r.link == bLink && e.module.calls.contains("onAction") }
        a.act(op(FakeGameModule.OP_KICK, b.me.id.value))
        e.net.dropRule = { false }
        e.clock.advanceBy(e.config.session.joinRetryBackoffMs * 2)
        assertEquals(listOf(b.me.id to LeaveReason.Kicked), e.module.leaves, "onLeave(Kicked) 1회")
        assertEquals(listOf("H", "A"), e.router.participants().map { it.displayName })
        assertEquals(ClientSessionState.Ended, b.session!!.clientState, "resume이 거절되어 끝난다")

        a.act(op(FakeGameModule.OP_KICK, e.host.me.id.value))
        assertEquals(listOf("H", "A"), e.router.participants().map { it.displayName }, "호스트 자신은 kick할 수 없다")

        val c = e.join("C")
        a.act(op(FakeGameModule.OP_KICK, c.me.id.value))
        assertEquals(TokenResult.KICKED, c.session!!.endReason, "REJECT를 받으면 사유가 보인다")
    }

    @Test
    fun displayNames() = runTest {
        val e = env().apply { start() }
        val a = e.join("앨리스")
        assertEquals(listOf("H", "앨리스"), a.roster.map { it.displayName })
        val long = "가".repeat(e.config.session.maxDisplayNameBytes) // 한 글자 3바이트
        assertEquals(JoinOutcome.Refused(TokenResult.DISPLAY_NAME_TOO_LONG), e.tryJoin(long).second)
        assertEquals(JoinOutcome.Refused(TokenResult.DISPLAY_NAME_TOO_LONG), e.tryJoin(long, checked = true).second)
    }

    @Test
    fun moduleExceptionsAreIsolated() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        assertIs<ActionOutcome.Rejected>(a.act(op(FakeGameModule.OP_THROW))).also { assertEquals(ActionResult.INTERNAL, it.code) }
        assertEquals(ActionOutcome.Accepted, b.act(ok()), "다른 참가자 진행 계속")
        assertTrue(e.logs.any { "threw" in it })
    }

    @Test
    fun endSessionEndsEveryoneOnce() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val s = e.join("S", Role.Spectator)
        a.act(byteArrayOf(2))
        a.act(op(FakeGameModule.OP_END))
        for (d in listOf(e.host, a, s)) {
            val st = assertIs<ClientState.Ended>(d.states.last(), d.name)
            assertEquals("end:2", st.result?.decodeToString(), d.name)
        }
        assertEquals(1, e.module.calls.count { it == "onSessionEnd" })
        e.gameHost.end(null)
        e.clock.advanceBy(10_000)
        assertEquals(1, e.module.calls.count { it == "onSessionEnd" }, "정확히 1회")
        assertEquals(ClientSessionState.Ended, a.session!!.clientState, "다시 붙지 않는다")
    }

    @Test
    fun socialCarriesTheSenderFromTheLinkBinding() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val s = e.join("S", Role.Spectator)
        assertEquals(SocialOutcome.Delivered, a.game.sendSocial("안녕".encodeToByteArray()))
        assertEquals(SocialOutcome.Delivered, e.host.game.sendSocial("hi".encodeToByteArray()))
        for (d in listOf(e.host, a, s)) assertEquals(listOf("A:안녕", "H:hi"), d.socials, d.name)
        assertEquals(SocialOutcome.TooLarge, a.game.sendSocial(ByteArray(e.config.social.maxMsgBytes + 1)))
    }
}
