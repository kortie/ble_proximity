package dev.proximity.core.interfaces

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role

/**
 * 게임 규칙. 게임이 구현하고 호스트 기기에서만 실행된다 (03-game-data-api §3).
 * 모든 호출은 HostInbox 루프에서 하나씩 일어난다.
 */
interface GameModule {
    fun descriptor(): GameDescriptor

    /** TOKEN_REQ 사전 검사와 HELLO 때 호출된다. 진행 중 합류 허용 여부 등은 게임이 정한다. */
    fun onJoinRequest(role: Role, displayName: String): JoinDecision

    /** 플레이어·관전자 모두. 호스트 자신이 첫 번째. */
    fun onJoin(ctx: GameHostContext, participant: Participant)

    /** Player의 액션만 온다 (I-12). */
    fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult

    /** 이 참가자가 화면을 그릴 수 있는 전체 view. 비공개 정보는 여기서 걸러낸다 (I-13). */
    fun viewFor(participant: Participant): ByteArray

    fun onTimer(ctx: GameHostContext, token: String)

    fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason)

    fun onSessionEnd()
}
