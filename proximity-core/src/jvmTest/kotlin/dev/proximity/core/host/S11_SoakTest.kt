package dev.proximity.core.host

import dev.proximity.core.api.ActionOutcome
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeClientTransport
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** max_links를 꽉 채운 세션을 오래 돌린다: 순서·목록 일치, 누수 없음 (S11 자동). */
@OptIn(ExperimentalCoroutinesApi::class)
class S11_SoakTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())

    private class Member(val name: String, val session: SessionClient?, val game: SessionGameClient, val transport: FakeClientTransport?) {
        val events = mutableListOf<String>()
        var roster: List<Participant> = emptyList()
    }

    @Test
    fun fullSessionStaysConsistentOverManyRounds() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val crypto = BouncyCastleCryptoBox()
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        hostT.open(session.sessionTag, ByteArray(0))
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)
        router.start()
        val module = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 8, true))
        val host = GameHost(config, module, clock).attachHost(router, sessions, scope) as SessionGameClient

        fun follow(m: Member) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { m.game.events.collect { m.events += it.data.decodeToString() } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { m.game.participants.collect { m.roster = it } }
        }
        val members = mutableListOf(Member("H", null, host, null).also(::follow))
        repeat(config.session.maxLinks) { i ->
            val nonce = crypto.randomBytes(16)
            val t = net.client()
            val sc = SessionClient(config, t, crypto, clock, scope)
            val m = Member("P$i", sc, SessionGameClient(config, sc, clock, scope), t).also(::follow)
            val ok = sc.join(sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, m.name, "fake.game", 1, crypto.randomBytes(16)))
            assertTrue(ok is JoinOutcome.Joined, "${m.name}: $ok")
            members += m
        }
        assertEquals(8, router.participants().size, "8인 게임 + max_links 7을 꽉 채움")
        val baseTimers = clock.pendingCount

        val random = Random(11)
        var accepted = 0
        repeat(ROUNDS) { round ->
            // 같은 순간에 여러 명이 보낸다
            val senders = members.shuffled(random).take(random.nextInt(1, 4))
            val results = senders.map { m -> scope.async(start = CoroutineStart.UNDISPATCHED) { m.game.sendAction(byteArrayOf((round % 5 + 1).toByte())) } }
            if (round % 25 == 0) members[random.nextInt(1, members.size)].transport?.let { net.disconnect(net.linkOf(it)) } // 끊김 → resume
            clock.advanceBy(STEP_MS)
            accepted += results.count { it.await() == ActionOutcome.Accepted }
        }
        clock.advanceBy(config.transport.ackIntervalMs * 2)

        assertEquals(accepted, module.calls.count { it == "onAction" }, "보낸 만큼 한 번씩 (중복·유실 없음)")
        val reference = members.first().events
        assertEquals(accepted, reference.count { it.startsWith("add:") })
        for (m in members) {
            assertEquals(reference, m.events, "${m.name}: 호스트와 같은 순서")
            assertEquals(members.first().roster, m.roster, "${m.name}: 같은 참가자 목록")
            m.session?.let { assertEquals(ClientSessionState.Live, it.clientState, m.name) }
        }
        for (p in router.participants()) assertTrue(router.storedCount(p.id) <= config.transport.resendWindow, "재전송 버퍼는 창을 넘지 않는다")
        assertTrue(clock.pendingCount <= baseTimers + members.size, "예약이 쌓이지 않는다: ${clock.pendingCount} (시작 $baseTimers)")
    }

    private companion object {
        const val ROUNDS = 400
        const val STEP_MS = 400L // 참가자별 초당 5건 안
    }
}
