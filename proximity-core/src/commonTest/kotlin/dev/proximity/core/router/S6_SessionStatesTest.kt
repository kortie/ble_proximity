package dev.proximity.core.router

import kotlin.test.Test
import kotlin.test.assertEquals

class S6_SessionStatesTest {
    @Test
    fun hostTable() {
        assertEquals(HostSessionState.Active, SessionTransitions.host(HostSessionState.Forming, HostSessionEvent.FirstWelcome))
        assertEquals(HostSessionState.Active, SessionTransitions.host(HostSessionState.Active, HostSessionEvent.FirstWelcome), "두 번째 WELCOME은 그대로")
        HostSessionEvent.entries.forEach { assertEquals(HostSessionState.Ended, SessionTransitions.host(HostSessionState.Ended, it), "Ended는 끝") }
    }

    @Test
    fun clientTableExhaustive() {
        val expected = mapOf(
            ClientSessionState.Joining to mapOf(ClientSessionEvent.Welcome to ClientSessionState.Live, ClientSessionEvent.Ended to ClientSessionState.Ended, ClientSessionEvent.Left to ClientSessionState.Ended),
            ClientSessionState.Live to mapOf(ClientSessionEvent.HostLost to ClientSessionState.Paused, ClientSessionEvent.Ended to ClientSessionState.Ended, ClientSessionEvent.Left to ClientSessionState.Ended),
            ClientSessionState.Paused to mapOf(
                ClientSessionEvent.Resumed to ClientSessionState.Live, ClientSessionEvent.GraceExpired to ClientSessionState.Ended,
                ClientSessionEvent.Ended to ClientSessionState.Ended, ClientSessionEvent.Left to ClientSessionState.Ended,
            ),
            ClientSessionState.Ended to emptyMap(),
        )
        for (s in ClientSessionState.entries) for (e in ClientSessionEvent.entries) {
            assertEquals(expected.getValue(s)[e] ?: s, SessionTransitions.client(s, e), "$s × $e")
        }
    }
}
