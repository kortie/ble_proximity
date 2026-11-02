package dev.proximity.sample

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.api.Target
import dev.proximity.core.interfaces.GameModule

/**
 * Lab용 데모 게임 (HW-S8): 공개 카운터 + 플레이어마다 비공개 "패" 숫자.
 * 액션 1바이트 = 카운터에 더하기. view = "카운터 N" (+ 플레이어면 "내 패 K"). 관전자 view에는 패가 없다.
 * `core/api`와 `GameModule`만 쓴다 — 게임 저장소가 따라 할 예시다.
 */
class DemoGame : GameModule {
    private var counter = 0
    private val hands = mutableMapOf<Int, Int>()

    override fun descriptor() = GameDescriptor(
        gameId = "dev.proximity.demo",
        gameVersion = 1,
        minCompatibleVersion = 1,
        minPlayers = 1,
        maxPlayers = 4,
        allowSpectators = true,
    )

    override fun onJoinRequest(role: Role, displayName: String) = JoinDecision.Admit

    override fun onJoin(ctx: GameHostContext, participant: Participant) {
        if (participant.role == Role.Player) hands[participant.id.value] = (participant.id.value * 7) % 10
        ctx.sendEvent(Target.All, "${participant.displayName} 입장".encodeToByteArray())
        ctx.publishViews()
    }

    override fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult {
        if (action.size != 1) return ActionResult.Rejected(1)
        counter += action[0]
        ctx.sendEvent(Target.All, "${sender.displayName} +${action[0]}".encodeToByteArray())
        ctx.publishViews()
        return ActionResult.Accepted
    }

    override fun viewFor(participant: Participant): ByteArray {
        val hand = hands[participant.id.value]
        return (if (hand == null) "카운터 $counter" else "카운터 $counter · 내 패 $hand").encodeToByteArray()
    }

    override fun onTimer(ctx: GameHostContext, token: String) = Unit

    override fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason) {
        hands.remove(participant.id.value)
        ctx.sendEvent(Target.All, "${participant.displayName} 퇴장 ($reason)".encodeToByteArray())
        ctx.publishViews()
    }

    override fun onSessionEnd() = Unit
}
