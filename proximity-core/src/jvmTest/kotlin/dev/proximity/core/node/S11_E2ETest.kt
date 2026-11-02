package dev.proximity.core.node

import dev.proximity.core.api.ActionOutcome
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.Role
import dev.proximity.core.api.SocialOutcome
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.LinkId
import dev.proximity.core.sensing.Bump
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.Screen
import dev.proximity.core.ux.UiAction
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeGameModule.Companion.op
import dev.proximity.testing.FakeRendezvousNetwork
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** E2E 시나리오 A~D를 파사드 전체 경로로 (S11 자동 부분. 실기기는 HW-S11). */
@OptIn(ExperimentalCoroutinesApi::class)
class S11_E2ETest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork()
    private val crypto = BouncyCastleCryptoBox()

    private inner class Dev(val name: String, val scope: CoroutineScope, val game: FakeGameModule, role: Role) {
        val ble = air.device(name)
        lateinit var node: ProximityNode
        val views = mutableListOf<String>()
        val events = mutableListOf<String>()
        val socials = mutableListOf<String>()

        init {
            node = ProximityNode(config, NodePlatform(ble, rdv.endpoint { node.tempId!! }, sessionNet.host(), { sessionNet.client() }), crypto, clock, scope, game)
            node.setDisplayName(name)
            node.selectRole(role)
            node.setRadio(RadioStatus.Ready)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                node.gameClient.collect { gc ->
                    gc ?: return@collect
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { gc.views.collect { views += it.data.decodeToString() } }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { gc.events.collect { events += it.data.decodeToString() } }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { gc.socials.collect { socials += "${it.from.displayName}:${it.data.decodeToString()}" } }
                }
            }
        }

        val screen get() = node.ui.value.screen
        val gc get() = assertNotNull(node.gameClient.value, "$name: GameClient")

        fun participants() = assertIs<Screen.InSession>(screen, name).participants.map { it.displayName to it.role }
    }

    private fun TestScope.dev(name: String, game: FakeGameModule = game(), role: Role = Role.Player) =
        Dev(name, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), game, role)

    private fun game(turnBased: Boolean = false) = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true), turnBased = turnBased)

    private fun bump(vararg ds: Dev) {
        clock.advanceBy(1_000)
        air.rebroadcast() // 실제 광고는 계속 반복된다: Fake 망에서 관측 표가 TTL로 비지 않게
        ds.forEach { it.node.onBump(Bump(clock.now(), 3.0)) }
        clock.advanceBy(config.matching.bumpFlagWaitMs + config.matching.rdvBackoffMs + 10)
    }

    private fun acceptAll(vararg ds: Dev) = ds.forEach { it.node.accept() }

    @Test
    fun scenarioA_createBumpConfirmPlayEnd() = runTest {
        val host = dev("호스트")
        val guest = dev("손님")
        air.setRssi("호스트", "손님", -35)
        air.rebroadcast()
        host.node.createRoom()
        bump(host, guest)
        assertIs<Screen.Confirm>(guest.screen)
        guest.node.accept()
        clock.advanceBy(3_000) // 상대가 늦게 수락: 이 기다림은 "양측 수락→WELCOME"에 들어가지 않는다
        host.node.accept()
        assertEquals(listOf("호스트" to Role.Player, "손님" to Role.Player), guest.participants())
        assertEquals(ActionOutcome.Accepted, guest.gc.sendAction(byteArrayOf(2)))
        assertEquals(ActionOutcome.Accepted, host.gc.sendAction(byteArrayOf(3)))
        assertEquals(listOf("add:2", "add:3"), host.events)
        assertEquals(host.events, guest.events, "같은 순서")
        host.node.endSession()
        assertIs<Screen.Home>(guest.screen, "세션 종료 → 처음 화면")
        assertIs<Screen.Home>(host.screen)
        assertEquals(1, host.game.calls.count { it == "onSessionEnd" })
        // 측정 도구가 지연을 기록했다
        assertTrue(guest.node.perf.count(Metric.BumpToConfirm) >= 1)
        assertTrue(guest.node.perf.count(Metric.AcceptToWelcome) >= 1)
        assertTrue(guest.node.perf.p95(Metric.AcceptToWelcome)!! < 3_000, "양측 수락 시점부터 잰다 (실기기 S11에서 발견한 측정 오류)")
    }

    @Test
    fun aHostCanRunSessionsBackToBack() = runTest {
        // 실기기에서 발견: 첫 세션을 끝낸 뒤 다시 연 방에는 아무도 들어오지 못했다
        val host = dev("H")
        val guest = dev("G")
        air.setRssi("H", "G", -35)
        air.rebroadcast()
        val codes = mutableListOf<String>()
        repeat(3) { round ->
            host.node.createRoom()
            val tag = (assertIs<Screen.InSession>(host.screen).host!!.sessionCode)
            codes += tag
            bump(host, guest)
            acceptAll(host, guest)
            assertEquals(listOf("H" to Role.Player, "G" to Role.Player), guest.participants(), "라운드 $round")
            assertEquals(ActionOutcome.Accepted, guest.gc.sendAction(byteArrayOf(1)))
            host.node.endSession()
            assertIs<Screen.Home>(guest.screen)
            assertIs<Screen.Home>(host.screen)
            clock.advanceBy(config.matching.retryCooldownMs + 1_000)
            assertTrue(tag.isNotEmpty())
        }
        assertEquals(3, codes.toSet().size, "방마다 새 세션 (이전 세션의 태그·키·코드를 다시 쓰지 않는다)")
    }

    @Test
    fun scenarioB_spectatorJoinsViaANonHostMemberChatsAndLeaves() = runTest {
        val g = game()
        val a = dev("A", g)
        val b = dev("B")
        val c = dev("C", role = Role.Spectator)
        air.setRssi("A", "B", -35); air.setRssi("B", "C", -35); air.setRssi("A", "C", -75)
        air.rebroadcast()
        a.node.createRoom()
        bump(a, b)
        acceptAll(a, b)
        assertEquals(ActionOutcome.Accepted, b.gc.sendAction(byteArrayOf(1)), "진행 중인 세션")
        bump(b, c)
        assertEquals("B", assertIs<Screen.Confirm>(c.screen).peerName, "관전자는 비호스트 멤버 B와 부딪힌다")
        acceptAll(b, c)
        assertEquals(listOf("A" to Role.Player, "B" to Role.Player, "C" to Role.Spectator), c.participants(), "A의 세션에 들어갔다")
        assertTrue(c.views.first().startsWith("c=1"), "중간 합류: 현재 상태 스냅샷부터")
        assertTrue(UiAction.SendAction !in c.node.ui.value.actions)
        assertEquals(SocialOutcome.Delivered, c.gc.sendSocial("안녕".encodeToByteArray()))
        assertEquals(SocialOutcome.Delivered, a.gc.sendSocial("어서와".encodeToByteArray()))
        for (d in listOf(a, b, c)) assertEquals(listOf("C:안녕", "A:어서와"), d.socials, d.name)
        c.node.leave()
        assertEquals(listOf("A" to Role.Player, "B" to Role.Player), a.participants())
        assertIs<Screen.Home>(c.screen)
    }

    @Test
    fun scenarioC_reconnectResendsTheUnconfirmedActionAndHostLossEndsTheSession() = runTest {
        val g = game()
        val a = dev("A", g)
        val b = dev("B")
        a.node.createRoom()
        b.node.joinWithQr(assertIs<Screen.InSession>(a.screen).host!!.qr)
        // B의 업링크를 잃는 동안 보낸 액션 → 끊김 → 재연결 뒤 같은 actionId로 다시
        sessionNet.dropRule = { r -> !r.toClient }
        val pending = b.scope.async(start = CoroutineStart.UNDISPATCHED) { b.gc.sendAction(byteArrayOf(4)) }
        sessionNet.dropRule = { false }
        sessionNet.disconnect(LinkId(1))
        assertEquals(ActionOutcome.Accepted, pending.await())
        assertEquals(1, g.calls.count { it == "onAction" }, "한 번만 적용")
        assertTrue(b.views.last().startsWith("c=4"))
        // 호스트가 사라진다
        sessionNet.acceptingConnections = false
        sessionNet.disconnect(LinkId(2))
        assertNotNull(assertIs<Screen.InSession>(b.screen).paused, "Paused + 카운트다운")
        clock.advanceBy(config.session.hostGraceMs + config.session.joinRetryBackoffMs)
        assertIs<Screen.Home>(b.screen, "유예가 지나면 끝")
    }

    @Test
    fun scenarioD_turnsPrivateInfoTimerAndResult() = runTest {
        val g = game(turnBased = true)
        val a = dev("A", g)
        val b = dev("B")
        val s = dev("S", role = Role.Spectator)
        a.node.createRoom()
        val qr = { assertIs<Screen.InSession>(a.screen).host!!.qr }
        b.node.joinWithQr(qr())
        s.node.joinWithQr(qr())
        // 차례: A(호스트) → B
        assertIs<ActionOutcome.Rejected>(b.gc.sendAction(byteArrayOf(1))).also { assertEquals(FakeGameModule.NOT_YOUR_TURN, it.code) }
        assertEquals(ActionOutcome.Accepted, a.gc.sendAction(byteArrayOf(1)))
        assertEquals(ActionOutcome.Accepted, b.gc.sendAction(byteArrayOf(1)))
        // 비공개 정보: 각자 자기 값만, 관전자는 공개 값만
        val me = { d: Dev -> d.gc.me() }
        assertTrue(a.views.last().endsWith(FakeGameModule.secretOf(me(a))))
        assertTrue(b.views.last().endsWith(FakeGameModule.secretOf(me(b))))
        assertTrue(listOf(a, b).all { d -> s.views.none { "SECRET" in it } && d.views.none { v -> listOf(a, b).filter { it !== d }.any { FakeGameModule.secretOf(me(it)) in v } } })
        // 타이머
        assertEquals(ActionOutcome.Accepted, a.gc.sendAction(op(FakeGameModule.OP_TIMER, 10)))
        clock.advanceBy(1_000)
        assertTrue("timer" in s.events)
        // 결과 전달
        assertEquals(ActionOutcome.Accepted, a.gc.sendAction(op(FakeGameModule.OP_END)))
        assertEquals(1, g.calls.count { it == "onSessionEnd" })
        for (d in listOf(b, s)) assertIs<Screen.Home>(d.screen, d.name)
    }
}
