package dev.proximity.core.router

import dev.proximity.core.api.Role
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.matching.Cooldown
import dev.proximity.core.matching.MatchCoordinator
import dev.proximity.core.matching.PeerMatcher
import dev.proximity.core.sensing.Bump
import dev.proximity.core.session.JoinProfile
import dev.proximity.core.session.Membership
import dev.proximity.core.session.RendezvousEngine
import dev.proximity.core.session.SessionManager
import dev.proximity.core.state.MatchEnv
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import dev.proximity.testing.FakeRendezvousNetwork
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 자리가 다 차면 호스트·멤버 광고가 accepting=false가 되고, 새 기기가 F3로 거른다 (S7). */
@OptIn(ExperimentalCoroutinesApi::class)
class S7_CapacityIntegrationTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText()).let {
        it.copy(session = it.session.copy(maxLinks = 1))
    }
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork()
    private val crypto = BouncyCastleCryptoBox()

    private inner class Dev(val name: String, seed: Int, scope: CoroutineScope) {
        val transport = air.device(name)
        val rotator = IdRotator(300_000, clock, FakeCryptoBox(seed))
        val advertiser = ProximityAdvertiser(transport, 1, rotator)
        val scanner = ProximityScanner(transport, 1, clock, config.ble.peerTtlMs, scope)
        val machine = MatchStateMachine(MatchEnv.from(config), clock, Role.Player)
        val sessions = SessionManager(config.session, clock, crypto)
        val joiner = SessionHostJoiner(config, crypto, clock, scope) { sessionNet.client() }
        var router: SessionRouter? = null
        val engine = RendezvousEngine(machine, rdv.endpoint { advertiser.state.tempId }, crypto, clock, scope, config,
            JoinProfile(name, "game", 1, hostCapable = true), advertiser, sessions, joiner)
        val coordinator = MatchCoordinator(machine, advertiser, rotator, scanner, PeerMatcher(config.matching), Cooldown(clock, 10_000), clock, config.matching) {
            engine.handle(it)
        }

        init {
            engine.coordinator = coordinator
            joiner.onClient = { c -> c.onCapacity = { engine.setAccepting(it.accepting) } }
            engine.onHostingStarted = { session ->
                val host = sessionNet.host()
                router = SessionRouter(config, host, HostInbox(clock), sessions, crypto, clock, scope).also { r ->
                    r.onCapacityChanged = engine::setAccepting
                    engine.hostRendezvousAllowed = r::rendezvousAllowed
                    engine.onRendezvousCount = r::setRendezvousInProgress
                    scope.launch { host.open(session.sessionTag, ByteArray(0)) }
                    r.start()
                    r.attachLoopback(sessions.participants().first()) { _, _ -> }
                }
            }
            engine.start()
            coordinator.dispatch(MatchEvent.StartAdvertising)
        }
    }

    private fun TestScope.dev(name: String, seed: Int) =
        Dev(name, seed, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    private fun bump(vararg ds: Dev) {
        ds.forEach { it.coordinator.onBump(Bump(clock.now(), 3.0)) }
        clock.advanceBy(config.matching.bumpFlagWaitMs + config.matching.rdvBackoffMs + 10)
    }

    @Test
    fun fullSessionStopsAcceptingAndNewDevicesFilterIt() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        val c = dev("C", 3)
        air.setRssi("A", "B", -35); air.setRssi("B", "C", -35); air.setRssi("A", "C", -40)
        a.engine.createRoom()
        assertTrue(a.advertiser.state.flags.accepting)
        clock.advanceBy(1000)
        bump(a, b)
        a.coordinator.dispatch(MatchEvent.ConfirmSent); b.coordinator.dispatch(MatchEvent.ConfirmSent)
        assertIs<Membership.Member>(b.engine.membership)
        assertFalse(a.advertiser.state.flags.accepting, "max_links 1이 찼다 → 호스트 accepting=false")
        assertFalse(b.advertiser.state.flags.accepting, "멤버도 CAPACITY를 따라 accepting=false")

        clock.advanceBy(2000)
        bump(b, c)
        bump(a, c)
        assertEquals(Membership.None, c.engine.membership, "C는 꽉 찬 세션을 F3로 거른다")
        assertEquals(listOf("A", "B"), a.router!!.participants().map { it.displayName }, "호스트 링크 수는 max_links를 넘지 않는다")
    }
}
