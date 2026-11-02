package dev.proximity.core.router

/** 호스트 세션 상태 (04-session-flows §5.4). */
enum class HostSessionState { Forming, Active, Ended }

enum class HostSessionEvent { FirstWelcome, End }

/** 클라이언트 세션 상태 (04-session-flows §5.4). GameClient.state가 이것을 따른다 (S8). */
enum class ClientSessionState { Joining, Live, Paused, Ended }

enum class ClientSessionEvent { Welcome, HostLost, Resumed, GraceExpired, Ended, Left }

/**
 * 세션 상태 전이표 (I-4': 상태는 transition으로만 바뀐다). 표에 없는 조합은 그대로 둔다.
 */
object SessionTransitions {
    private val host = mapOf(
        (HostSessionState.Forming to HostSessionEvent.FirstWelcome) to HostSessionState.Active,
        (HostSessionState.Forming to HostSessionEvent.End) to HostSessionState.Ended,
        (HostSessionState.Active to HostSessionEvent.End) to HostSessionState.Ended,
    )

    private val client = mapOf(
        (ClientSessionState.Joining to ClientSessionEvent.Welcome) to ClientSessionState.Live,
        (ClientSessionState.Joining to ClientSessionEvent.Ended) to ClientSessionState.Ended,
        (ClientSessionState.Joining to ClientSessionEvent.Left) to ClientSessionState.Ended,
        (ClientSessionState.Live to ClientSessionEvent.HostLost) to ClientSessionState.Paused,
        (ClientSessionState.Live to ClientSessionEvent.Ended) to ClientSessionState.Ended,
        (ClientSessionState.Live to ClientSessionEvent.Left) to ClientSessionState.Ended,
        (ClientSessionState.Paused to ClientSessionEvent.Resumed) to ClientSessionState.Live,
        (ClientSessionState.Paused to ClientSessionEvent.GraceExpired) to ClientSessionState.Ended,
        (ClientSessionState.Paused to ClientSessionEvent.Ended) to ClientSessionState.Ended,
        (ClientSessionState.Paused to ClientSessionEvent.Left) to ClientSessionState.Ended,
    )

    fun host(state: HostSessionState, event: HostSessionEvent): HostSessionState = host[state to event] ?: state

    fun client(state: ClientSessionState, event: ClientSessionEvent): ClientSessionState = client[state to event] ?: state

    /** 전수 테스트용: 정의된 행. */
    val hostRows: Set<Pair<HostSessionState, HostSessionEvent>> get() = host.keys
    val clientRows: Set<Pair<ClientSessionState, ClientSessionEvent>> get() = client.keys
}
