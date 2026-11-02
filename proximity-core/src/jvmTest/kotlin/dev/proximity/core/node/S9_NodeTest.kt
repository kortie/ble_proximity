package dev.proximity.core.node

import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.sensing.Bump
import dev.proximity.core.session.TokenResult
import dev.proximity.core.state.FailureReason
import dev.proximity.core.ux.Proximity
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.Screen
import dev.proximity.core.ux.UiAction
import dev.proximity.core.ux.UxCopy
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeRendezvousNetwork
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 파사드 ProximityNode로 기기 여럿을 Fake 위에서 돌린다 (S9 폴백·UX). */
@OptIn(ExperimentalCoroutinesApi::class)
class S9_NodeTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork()
    private val crypto = BouncyCastleCryptoBox()

    private inner class Dev(val name: String, scope: CoroutineScope, config: ProximityConfig, game: FakeGameModule) {
        val ble = air.device(name)
        lateinit var node: ProximityNode
        val platform = NodePlatform(ble, rdv.endpoint { node.tempId!! }, sessionNet.host(), { sessionNet.client() })

        init {
            node = ProximityNode(config, platform, crypto, clock, scope, game)
        }

        val screen get() = node.ui.value.screen
        val actions get() = node.ui.value.actions

        fun ready(role: Role = Role.Player): Dev {
            node.setDisplayName(name)
            node.selectRole(role)
            node.setRadio(RadioStatus.Ready)
            return this
        }
    }

    private fun TestScope.dev(name: String, config: ProximityConfig = baseConfig, game: FakeGameModule = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true))) =
        Dev(name, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config, game)

    private fun bump(vararg ds: Dev) {
        ds.forEach { it.node.onBump(Bump(clock.now(), 3.0)) }
        clock.advanceBy(baseConfig.matching.bumpFlagWaitMs + baseConfig.matching.rdvBackoffMs + 10)
    }

    private fun Dev.hostPanel() = assertIs<Screen.InSession>(screen).host!!

    @Test
    fun bluetoothOffShowsGuidanceAndStartsNothing() = runTest {
        val a = dev("A")
        a.node.setDisplayName("A")
        a.node.setRadio(RadioStatus.BluetoothOff)
        assertIs<Screen.RadioProblem>(a.screen)
        assertEquals(setOf(UiAction.TurnOnBluetooth), a.actions)
        a.node.createRoom()
        a.node.onBump(Bump(clock.now(), 3.0))
        a.node.joinWithCode("123456")
        assertFalse(a.node.joinWithQr("BP1.x"))
        assertNull(a.node.tempId, "무선이 꺼져 있으면 광고하지 않는다")
        a.node.setRadio(RadioStatus.Ready)
        assertIs<Screen.Home>(a.screen)
        assertNotNull(a.node.tempId)
    }

    @Test
    fun permissionDeniedOffersTheRetryPath() = runTest {
        val a = dev("A")
        a.node.setDisplayName("A")
        a.node.setRadio(RadioStatus.PermissionNeeded)
        assertEquals(setOf(UiAction.RequestPermission), a.actions)
        a.node.setRadio(RadioStatus.PermissionDeniedForever)
        assertEquals(setOf(UiAction.OpenSettings), a.actions)
        a.node.setRadio(RadioStatus.Ready)
        assertIs<Screen.Home>(a.screen)
    }

    @Test
    fun nicknameComesFirstAndIsLimited() = runTest {
        val a = dev("A")
        assertIs<Screen.NeedsName>(a.screen)
        assertFalse(a.node.setDisplayName("가".repeat(baseConfig.session.maxDisplayNameBytes)))
        assertEquals(UxCopy.nameTooLong(baseConfig.session.maxDisplayNameBytes), a.node.ui.value.notice)
        assertFalse(a.node.setDisplayName("   "))
        assertTrue(a.node.setDisplayName("지영"))
        a.node.setRadio(RadioStatus.Ready)
        assertIs<Screen.Home>(a.screen)
    }

    @Test
    fun confirmNeedsBothTapsThenJoins() = runTest {
        val a = dev("A").ready()
        val b = dev("B").ready()
        air.setRssi("A", "B", -35)
        a.node.createRoom()
        clock.advanceBy(1000)
        bump(a, b)
        val ca = assertIs<Screen.Confirm>(a.screen)
        val cb = assertIs<Screen.Confirm>(b.screen)
        assertEquals("B", ca.peerName)
        assertEquals("A", cb.peerName)
        assertEquals(ca.sas, cb.sas, "두 화면의 확인 코드가 같다")
        a.node.accept()
        assertTrue(assertIs<Screen.Confirm>(a.screen).waitingForPeer, "한쪽만 수락: 상대 대기")
        assertIs<Screen.Confirm>(b.screen)
        assertTrue(a.node.ui.value.actions == setOf(UiAction.Decline))
        b.node.accept()
        val sb = assertIs<Screen.InSession>(b.screen)
        assertEquals(listOf("A", "B"), sb.participants.map { it.displayName })
        assertNotNull(b.node.gameClient.value)
        assertEquals(listOf("A", "B"), assertIs<Screen.InSession>(a.screen).participants.map { it.displayName })
        assertTrue(UiAction.SendAction in b.actions)
    }

    @Test
    fun confirmTimeoutFailsWithNextSteps() = runTest {
        val a = dev("A").ready()
        val b = dev("B").ready()
        air.setRssi("A", "B", -35)
        a.node.createRoom()
        clock.advanceBy(1000)
        bump(a, b)
        assertIs<Screen.Confirm>(a.screen)
        clock.advanceBy(baseConfig.timeouts.confirmingMs)
        val f = assertIs<Screen.Failed>(b.screen)
        assertTrue(UiAction.Retry in f.next)
        b.node.retry()
        assertIs<Screen.Home>(b.screen)
    }

    @Test
    fun declineMovesOnToTheNextCandidate() = runTest {
        val x = dev("X").ready()
        val y = dev("Y").ready()
        // 서로 고르면 tempId가 작은 쪽이 접속한다. 다음 후보 계획은 접속한 쪽에만 있다
        val (d, o) = if (x.node.tempId!!.toUInt() < y.node.tempId!!.toUInt()) x to y else y to x
        val c = dev("C").ready()
        air.setRssi(d.name, o.name, -35); air.setRssi(d.name, "C", -45); air.setRssi(o.name, "C", -95)
        air.rebroadcast()
        clock.advanceBy(1000)
        val wait = baseConfig.matching.bumpFlagWaitMs
        val offset = baseConfig.matching.windowMs / 2
        d.node.onBump(Bump(clock.now(), 3.0))
        o.node.onBump(Bump(clock.now(), 3.0))
        clock.advanceBy(offset)
        c.node.onBump(Bump(clock.now(), 3.0)) // 범프 창 안이라 d의 후보에는 들지만, C가 고를 때는 d가 이미 확인 중(busy)
        clock.advanceBy(wait - offset + 10)
        assertEquals(o.name, assertIs<Screen.Confirm>(d.screen).peerName, "가장 가까운 상대부터")
        clock.advanceBy(wait)
        assertIs<Screen.Searching>(c.screen, "C는 요청을 기다린다")
        o.node.decline()
        clock.advanceBy(baseConfig.matching.rdvBackoffMs + 10)
        assertEquals("C", assertIs<Screen.Confirm>(d.screen).peerName, "거절되면 다음 후보로")
    }

    @Test
    fun manualPickListsByStrengthAndStillNeedsBothTaps() = runTest {
        val manual = baseConfig.copy(matching = baseConfig.matching.copy(maxAttempts = 0))
        val a = dev("A", manual).ready()
        val b = dev("B", manual).ready()
        val c = dev("C", manual).ready()
        air.setRssi("A", "B", -40); air.setRssi("A", "C", -70); air.setRssi("B", "C", -70)
        air.rebroadcast()
        a.node.createRoom()
        clock.advanceBy(1000)
        bump(a, b, c)
        val pick = assertIs<Screen.ManualPick>(a.screen)
        assertEquals(listOf(Proximity.Near, Proximity.Medium), pick.candidates.map { it.proximity }, "신호 센 순, 거리 표시")
        a.node.pick(pick.candidates.first().peer)
        clock.advanceBy(baseConfig.matching.rdvBackoffMs + 10)
        assertEquals("B", assertIs<Screen.Confirm>(a.screen).peerName, "고른 뒤 확인 화면에 상대 닉네임")
        a.node.accept()
        assertFalse(b.screen is Screen.InSession, "상대가 수락하기 전에는 합류하지 않는다 (I-6)")
        assertIs<Screen.Confirm>(b.screen)
        b.node.accept()
        assertIs<Screen.InSession>(b.screen)
    }

    @Test
    fun codeJoinTriesNearbyHostsAndNeedsHostApproval() = runTest {
        val a = dev("A").ready()
        val d = dev("D").ready()
        val c = dev("C").ready()
        air.setRssi("C", "D", -30); air.setRssi("C", "A", -60); air.setRssi("A", "D", -60)
        a.node.createRoom()
        d.node.createRoom()
        clock.advanceBy(1000)
        air.rebroadcast()
        val code = a.hostPanel().sessionCode
        c.node.joinWithCode(code)
        val waiting = assertIs<Screen.CodeWaiting>(c.screen, "가까운 D는 코드가 틀려 A로 넘어간다")
        val req = a.hostPanel().pendingCodeJoins.single()
        assertEquals("C", req.displayName)
        assertEquals(req.sas, waiting.sas, "호스트 화면과 같은 확인 코드")
        assertTrue(UiAction.ApproveCodeJoin in a.actions)
        a.node.answerCodeJoin(req.id, accept = true)
        assertEquals(listOf("A", "C"), assertIs<Screen.InSession>(c.screen).participants.map { it.displayName })
        assertTrue(a.hostPanel().pendingCodeJoins.isEmpty())
        assertEquals(listOf("D"), assertIs<Screen.InSession>(d.screen).participants.map { it.displayName })
    }

    @Test
    fun qrJoinAsSpectatorHasNoGameInputAndUsesTheQrOnce() = runTest {
        val a = dev("A").ready()
        val s = dev("S").ready(Role.Spectator)
        a.node.createRoom()
        val qr = a.hostPanel().qr
        assertTrue(s.node.joinWithQr(qr))
        val screen = assertIs<Screen.InSession>(s.screen)
        assertEquals(Role.Spectator, screen.role)
        assertFalse(UiAction.SendAction in s.actions, "관전자 화면에 game 입력 없음")
        assertTrue(UiAction.SendSocial in s.actions)
        assertTrue(a.hostPanel().qr != qr, "쓰인 QR은 바뀐다")
        assertFalse(dev("X").ready().node.joinWithQr("not a qr"))
    }

    @Test
    fun theDisplayedQrStaysValid() = runTest {
        // 실기기에서 발견: 화면의 QR이 join_token_ttl_sec(30초) 뒤 무효가 되는데도 그대로 보였다
        val a = dev("A").ready()
        val b = dev("B").ready()
        a.node.createRoom()
        val first = a.hostPanel().qr
        clock.advanceBy(baseConfig.session.joinTokenTtlSec * 1000 + 5_000)
        assertTrue(a.hostPanel().qr != first, "유효 시간 전에 새 QR로 바뀐다")
        assertTrue(b.node.joinWithQr(a.hostPanel().qr))
        assertIs<Screen.InSession>(b.screen, "화면에 보이는 QR은 언제 찍어도 들어갈 수 있다")
    }

    @Test
    fun qrAndCodeJoinsFollowCapacity() = runTest {
        val two = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 2, true))
        val a = dev("A", game = two).ready()
        val p = dev("P").ready()
        val x = dev("X").ready()
        a.node.createRoom()
        p.node.joinWithQr(a.hostPanel().qr)
        assertIs<Screen.InSession>(p.screen)
        x.node.joinWithQr(a.hostPanel().qr)
        assertEquals(UxCopy.refusal(TokenResult.PLAYER_SLOTS_FULL), x.node.ui.value.notice, "2인 게임이 찼다")
        assertIs<Screen.Home>(x.screen)
        air.rebroadcast()
        x.node.joinWithCode(a.hostPanel().sessionCode)
        assertEquals(UxCopy.refusal(TokenResult.PLAYER_SLOTS_FULL), x.node.ui.value.notice, "코드 합류도 같은 자리 정책")
    }

    @Test
    fun hostLossShowsAReconnectCountdown() = runTest {
        val a = dev("A").ready()
        val b = dev("B").ready()
        a.node.createRoom()
        b.node.joinWithQr(a.hostPanel().qr)
        sessionNet.acceptingConnections = false
        sessionNet.disconnect(dev.proximity.core.interfaces.LinkId(1))
        val paused = assertIs<Screen.InSession>(b.screen).paused
        assertEquals(baseConfig.session.hostGraceMs, paused!!.remainingMs)
        clock.advanceBy(5_000)
        b.node.refresh()
        assertEquals(baseConfig.session.hostGraceMs - 5_000, assertIs<Screen.InSession>(b.screen).paused!!.remainingMs)
        assertFalse(UiAction.SendAction in b.actions)
        clock.advanceBy(baseConfig.session.hostGraceMs)
        assertIs<Screen.Home>(b.screen, "유예가 지나면 세션이 끝나고 처음 화면으로")
        assertNull(b.node.gameClient.value)
    }

    @Test
    fun failedScreensComeFromTheMachine() = runTest {
        val a = dev("A").ready()
        a.node.onBump(Bump(clock.now(), 3.0))
        clock.advanceBy(baseConfig.timeouts.candidateMs + baseConfig.matching.bumpFlagWaitMs)
        val f = assertIs<Screen.Failed>(a.screen, "혼자 부딪히면 상대 없음")
        assertEquals(FailureReason.NoCandidate, f.reason)
        assertTrue(UiAction.EnterCode in f.next, "다음 행동: 코드로 들어가기")
    }
}
