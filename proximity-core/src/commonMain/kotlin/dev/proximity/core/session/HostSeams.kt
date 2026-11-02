package dev.proximity.core.session

import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role

/** 신규 기기를 위한 token 요청 결과 (ctl/TOKEN · TOKEN_REJECT). */
sealed interface TokenResult {
    class Granted(val ticket: JoinTicket) : TokenResult

    data class Refused(val reason: Int, val gameCode: Int = 0) : TokenResult

    companion object {
        const val NO_SESSION = 1
        const val SESSION_FULL = 2
        const val GAME_REFUSED = 3
        const val BAD_TOKEN = 4
        const val UNREACHABLE = 5
        const val PLAYER_SLOTS_FULL = 6
        const val SPECTATOR_SLOTS_FULL = 7
        const val GAME_MISMATCH = 8
        const val SPECTATORS_NOT_ALLOWED = 9
        const val DISPLAY_NAME_TOO_LONG = 10
        const val KICKED = 11
        const val BAD_CODE = 12
        const val CODE_DENIED = 13
    }
}

/**
 * 세션 멤버가 호스트에게 요청하는 통로 (core 내부 경계, S5).
 * 호스트 자신은 [LocalHostChannel], 클라이언트 멤버는 S6의 세션 링크 구현을 쓴다.
 */
interface HostChannel {
    suspend fun requestToken(joinNonce: ByteArray, role: Role, displayName: String, gameVersion: Int): TokenResult
}

class LocalHostChannel(private val sessions: SessionManager) : HostChannel {
    override suspend fun requestToken(joinNonce: ByteArray, role: Role, displayName: String, gameVersion: Int): TokenResult =
        sessions.requestToken(joinNonce, role, displayName, gameVersion)
}

/**
 * [joinSecret]: 이 합류 시도(재시도 포함)에서만 쓰는 비밀. 봉인된 HELLO 안에만 실린다.
 * 같은 token으로 다시 HELLO할 때 같은 값이어야 같은 참가자로 본다 (중개 멤버의 사칭 차단, S10).
 */
class JoinRequest(
    val joinNonce: ByteArray,
    val role: Role,
    val displayName: String,
    val gameId: String,
    val gameVersion: Int,
    val joinSecret: ByteArray = ByteArray(0),
)

sealed interface JoinOutcome {
    /** 합류했다. [channel]로 이후 다른 기기를 소개할 때 token을 요청한다. */
    class Joined(val participant: Participant, val sessionTag: Int, val channel: HostChannel) : JoinOutcome

    data class Refused(val reason: Int, val gameCode: Int = 0) : JoinOutcome

    /** 호스트를 찾지 못했거나 연결이 끊겼다 (재시도 대상). */
    data object Unreachable : JoinOutcome
}

/** 신규 기기가 JOIN_INFO의 호스트에 붙는 통로: HELLO → WELCOME (core 내부 경계, 실제 링크는 S6). */
interface HostJoiner {
    suspend fun join(ticket: JoinTicket, request: JoinRequest): JoinOutcome
}
