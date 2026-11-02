package dev.proximity.core.session

import dev.proximity.core.api.Role
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.HostDecision

/** decideHost 입력: 확인 화면에서 서로 주고받은 값 (CONFIRM_INFO, 04-session-flows §3.2). */
data class ConfirmFacts(
    val role: Role,
    val gameId: String,
    val gameVersion: Int,
    /** 내가 속한 세션 (없으면 null). */
    val sessionTag: Int?,
    val isHost: Boolean,
    val hostCapable: Boolean,
    val hostBid: Int,
)

sealed interface DecideOutcome {
    data class Decided(val decision: HostDecision) : DecideOutcome

    /** 결정표 #7 hostBid 동률: 새 값으로 다시 주고받는다. */
    data object Reroll : DecideOutcome
}

/**
 * 호스트 결정표 (04-session-flows §3.3). 순수 함수이고, 두 기기가 서로 바꿔 넣어도 같은 결론을 낸다.
 * 위에서부터 처음 맞는 줄을 쓴다.
 */
object HostDecider {
    fun decide(mine: ConfirmFacts, theirs: ConfirmFacts): DecideOutcome {
        fun fail(reason: FailureReason) = DecideOutcome.Decided(HostDecision.Fail(reason))
        val my = mine.sessionTag
        val their = theirs.sessionTag
        return when {
            // #1
            mine.gameId != theirs.gameId -> fail(FailureReason.GameMismatch)
            // #2, #3 (#3은 S4 F1로 걸러져 보통 오지 않는다)
            my != null && their != null -> fail(FailureReason.SessionConflict)
            // #4 세션 있는 쪽이 소개하고, 없는 쪽이 그 세션에 합류한다
            my != null -> DecideOutcome.Decided(HostDecision.Introduce)
            their != null -> DecideOutcome.Decided(HostDecision.JoinExisting)
            // #5
            mine.role == Role.Spectator && theirs.role == Role.Spectator -> fail(FailureReason.NoSessionToWatch)
            // #6 Player가 호스트
            mine.role != theirs.role -> {
                val player = if (mine.role == Role.Player) mine else theirs
                when {
                    !player.hostCapable -> fail(FailureReason.NoHostCapable)
                    mine.role == Role.Player -> DecideOutcome.Decided(HostDecision.BecomeHost)
                    else -> DecideOutcome.Decided(HostDecision.JoinNew)
                }
            }
            // #7 둘 다 Player
            !mine.hostCapable && !theirs.hostCapable -> fail(FailureReason.NoHostCapable)
            mine.hostCapable != theirs.hostCapable ->
                DecideOutcome.Decided(if (mine.hostCapable) HostDecision.BecomeHost else HostDecision.JoinNew)
            mine.hostBid == theirs.hostBid -> DecideOutcome.Reroll
            mine.hostBid.toUInt() > theirs.hostBid.toUInt() -> DecideOutcome.Decided(HostDecision.BecomeHost)
            else -> DecideOutcome.Decided(HostDecision.JoinNew)
        }
    }
}
