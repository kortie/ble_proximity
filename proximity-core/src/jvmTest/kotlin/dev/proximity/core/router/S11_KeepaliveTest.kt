package dev.proximity.core.router

import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.testing.FakeClientTransport
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 죽은 링크 감지 (S11 실기기에서 발견): 앱이 죽어도 BLE 연결이 남아 LinkDown이 오지 않는 경우.
 * Fake 망에서는 링크를 끊지 않고 한쪽의 프레임을 모두 버려 "말없이 사라진" 상대를 만든다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class S11_KeepaliveTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = BouncyCastleCryptoBox()

    private inner class Env(val scope: CoroutineScope) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope)

        suspend fun start() {
            hostT.open(session.sessionTag, ByteArray(0))
            router.start()
            router.attachLoopback(sessions.participants().first()) { _, _ -> }
        }

        suspend fun join(): Pair<SessionClient, FakeClientTransport> {
            val nonce = crypto.randomBytes(16)
            val t = net.client()
            val c = SessionClient(config, t, crypto, clock, scope)
            assertIs<JoinOutcome.Joined>(c.join(sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, "A", "g", 1)))
            return c to t
        }
    }

    private fun TestScope.env() = Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun aQuietLinkStaysUpThanksToKeepalive() = runTest {
        val e = env().apply { start() }
        val (c, _) = e.join()
        e.clock.advanceBy(config.transport.linkTimeoutMs * 5)
        assertEquals(ClientSessionState.Live, c.clientState, "아무도 말하지 않아도 PING/PONG으로 살아 있다")
        assertEquals(2, e.router.participants().size)
    }

    @Test
    fun aSilentHostIsDetectedByTheClient() = runTest {
        val e = env().apply { start() }
        val (c, _) = e.join()
        e.net.dropRule = { r -> r.toClient } // 호스트 앱이 죽음: 연결은 남았지만 아무것도 오지 않는다
        e.clock.advanceBy(config.transport.linkTimeoutMs + config.transport.keepaliveMs)
        assertEquals(ClientSessionState.Paused, c.clientState, "link_timeout_ms 안에 Paused (재연결 카운트다운)")
        e.clock.advanceBy(config.session.hostGraceMs + config.session.joinRetryBackoffMs * 2)
        assertEquals(ClientSessionState.Ended, c.clientState, "유예가 지나면 Ended")
    }

    @Test
    fun aSilentClientIsDroppedByTheHost() = runTest {
        val e = env().apply { start() }
        val (c, _) = e.join()
        // 클라이언트 앱이 죽음: 연결은 남았지만 아무것도 보내지 않고, 다시 붙지도 않는다
        e.net.dropRule = { r -> !r.toClient }
        e.net.acceptingConnections = false
        e.clock.advanceBy(config.transport.linkTimeoutMs + config.transport.keepaliveMs)
        e.clock.advanceBy(config.session.clientReconnectMs + config.transport.keepaliveMs)
        assertEquals(listOf("H"), e.router.participants().map { it.displayName }, "호스트도 죽은 클라이언트를 정리한다")
        assertIs<ClientSessionState>(c.clientState)
    }
}
