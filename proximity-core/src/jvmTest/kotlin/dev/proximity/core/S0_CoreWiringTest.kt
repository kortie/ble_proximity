package dev.proximity.core

import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.host.GameHost
import dev.proximity.core.matching.PeerMatcher
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.SessionManager
import dev.proximity.core.state.MatchEnv
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeSessionNetwork
import kotlin.test.Test
import kotlin.test.assertNotNull

/** I-3': core 구성 요소는 인터페이스를 주입받아 Fake만으로 조립된다. */
class S0_CoreWiringTest {
    @Test
    fun coreComponentsAreAssembledFromInterfacesOnly() {
        val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
        val clock = FakeClock()
        val inbox = HostInbox(clock)
        val crypto = BouncyCastleCryptoBox()
        val sessions = SessionManager(config.session, clock, crypto)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val parts = listOf(
            MatchStateMachine(MatchEnv.from(config), clock, Role.Player),
            PeerMatcher(config.matching),
            sessions,
            inbox,
            SessionRouter(config, FakeSessionNetwork().host(), inbox, sessions, crypto, clock, scope),
            GameHost(config, FakeGameModule(), clock),
        )
        parts.forEach { assertNotNull(it) }
    }
}
