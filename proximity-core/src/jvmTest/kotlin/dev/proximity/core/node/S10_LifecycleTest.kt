package dev.proximity.core.node

import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.ble.AdvCodec
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.Screen
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeHostTransport
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

/** 앱 종료·강제 종료 뒤 고아 세션 0 (S10). */
@OptIn(ExperimentalCoroutinesApi::class)
class S10_LifecycleTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork()
    private val crypto = BouncyCastleCryptoBox()

    private inner class Dev(name: String, scope: CoroutineScope) {
        val ble = air.device(name)
        val host: FakeHostTransport = sessionNet.host()
        lateinit var node: ProximityNode

        init {
            node = ProximityNode(config, NodePlatform(ble, rdv.endpoint { node.tempId!! }, host, { sessionNet.client() }), crypto, clock, scope,
                FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true)))
            node.setDisplayName(name)
            node.setRadio(RadioStatus.Ready)
        }

        val screen get() = node.ui.value.screen

        fun advertisedInSession(): Boolean = ble.advertising?.let { AdvCodec.decode(1, it)?.flags?.inSession } ?: false
    }

    private fun TestScope.dev(name: String) = Dev(name, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    private fun Dev.qr() = assertIs<Screen.InSession>(screen).host!!.qr

    @Test
    fun closingTheAppEndsTheSessionForEveryoneRightAway() = runTest {
        val a = dev("A")
        val b = dev("B")
        a.node.createRoom()
        b.node.joinWithQr(a.qr())
        assertIs<Screen.InSession>(b.screen)
        a.node.close()
        assertIs<Screen.Home>(b.screen, "호스트가 정상 종료하면 유예를 기다리지 않는다")
        assertNull(b.node.gameClient.value)
        assertFalse(b.advertisedInSession(), "멤버 광고에서 세션 정보가 빠진다")
        assertNull(a.ble.advertising, "닫힌 앱은 광고하지 않는다")

        val c = dev("C")
        val d = dev("D")
        c.node.createRoom()
        d.node.joinWithQr(c.qr())
        d.node.close()
        assertEquals(listOf("C"), assertIs<Screen.InSession>(c.screen).participants.map { it.displayName }, "멤버가 닫으면 LEAVE로 바로 빠진다")
    }

    @Test
    fun aCrashedHostLeavesNoOrphanSession() = runTest {
        val a = dev("A")
        val b = dev("B")
        a.node.createRoom()
        b.node.joinWithQr(a.qr())
        // 강제 종료: END도 LEAVE도 없이 프로세스가 사라진다
        a.ble.stopAdvertising()
        sessionNet.crash(a.host)
        assertNotNull(assertIs<Screen.InSession>(b.screen).paused)
        clock.advanceBy(config.session.hostGraceMs + config.session.joinRetryBackoffMs)
        assertIs<Screen.Home>(b.screen)
        assertFalse(b.advertisedInSession())

        val restarted = dev("A-restart") // 같은 기기에서 다시 실행 (Fake 망에서는 이름만 다르게)
        assertIs<Screen.Home>(restarted.screen)
        assertFalse(restarted.advertisedInSession(), "다시 실행한 앱은 세션 없이 시작한다 (세션을 저장하지 않는다)")
    }
}
