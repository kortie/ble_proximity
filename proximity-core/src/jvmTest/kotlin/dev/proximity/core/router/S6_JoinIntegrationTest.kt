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
import kotlin.test.assertIs

/** S5 확인·합류 + S6 실제 세션 링크(HELLO→WELCOME, TOKEN_REQ→TOKEN)를 이어서 (S5 경계의 S6 구현 검증). */
@OptIn(ExperimentalCoroutinesApi::class)
class S6_JoinIntegrationTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork() // 호스트 하나(A)의 세션 망
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
            engine.onHostingStarted = { session ->
                val host = sessionNet.host()
                router = SessionRouter(config, host, HostInbox(clock), sessions, crypto, clock, scope).also { r ->
                    scope.launch { host.open(session.sessionTag, ByteArray(0)) }
                    r.start()
                    r.attachLoopback(sessions.participants().first()) { _, _ -> } // 호스트 UI 자리 (S8에서 GameClient)
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
    fun roomHostThenTwoJoinsOverRealSessionLinks() = runTest {
        val a = dev("A", 1)
        val b = dev("B", 2)
        val c = dev("C", 3)
        air.setRssi("A", "B", -35); air.setRssi("B", "C", -35); air.setRssi("A", "C", -60)
        a.engine.createRoom()
        clock.advanceBy(1000)
        bump(a, b)
        a.coordinator.dispatch(MatchEvent.ConfirmSent); b.coordinator.dispatch(MatchEvent.ConfirmSent)
        assertIs<Membership.Member>(b.engine.membership, "B는 실제 HELLO→WELCOME으로 합류")
        assertEquals(listOf("A", "B"), a.router!!.participants().map { it.displayName })

        clock.advanceBy(2000)
        bump(b, c)
        b.coordinator.dispatch(MatchEvent.ConfirmSent); c.coordinator.dispatch(MatchEvent.ConfirmSent)
        assertIs<Membership.Member>(c.engine.membership, "C는 B가 세션 링크로 받은 token으로 합류")
        assertEquals(listOf("A", "B", "C"), a.router!!.participants().map { it.displayName })
        assertEquals(ClientSessionState.Live, c.joiner.lastClient!!.clientState)
    }
}
