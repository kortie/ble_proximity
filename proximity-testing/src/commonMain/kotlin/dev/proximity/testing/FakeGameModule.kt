package dev.proximity.testing

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.api.Target
import dev.proximity.core.interfaces.GameModule

/**
 * 테스트용 게임 (S8). `core/api`와 `GameModule`만 쓴다 (I-16).
 * - 공개 값: 카운터. 플레이어별 비공개 값: `SECRET-<id>` (view에만, 본인에게만 — I-13 검사용)
 * - 액션 1바이트 v: 카운터 += v, `add:<v>` 이벤트를 전원에게, view 갱신. [turnBased]면 차례가 아닌 플레이어는 [NOT_YOUR_TURN]
 * - 액션 2바이트 [op, arg]: 아래 OP_* (시작·타이머·비공개 이벤트·예외·종료·kick·큰 view·큰 이벤트)
 * - [lateJoinRefusal]이 있으면 START 뒤의 합류를 그 코드로 거절한다 (진행 중 합류 불가 게임)
 */
class FakeGameModule(
    private val descriptor: GameDescriptor = GameDescriptor(
        gameId = "fake.game",
        gameVersion = 1,
        minCompatibleVersion = 1,
        minPlayers = 1,
        maxPlayers = 8,
        allowSpectators = true,
    ),
    private val turnBased: Boolean = false,
    private val lateJoinRefusal: Int? = null,
) : GameModule {
    var counter = 0
        private set
    var started = false
        private set
    val joined = mutableListOf<Participant>()
    val calls = mutableListOf<String>()
    val leaves = mutableListOf<Pair<ParticipantId, LeaveReason>>()
    private var turn = 0
    private var bigView = false

    override fun descriptor(): GameDescriptor = descriptor

    override fun onJoinRequest(role: Role, displayName: String): JoinDecision {
        calls += "onJoinRequest"
        if (started && lateJoinRefusal != null) return JoinDecision.Refuse(lateJoinRefusal)
        return JoinDecision.Admit
    }

    override fun onJoin(ctx: GameHostContext, participant: Participant) {
        calls += "onJoin"
        joined += participant
    }

    override fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult {
        calls += "onAction"
        return when (action.size) {
            1 -> add(ctx, sender, action[0].toInt())
            2 -> command(ctx, sender, action[0].toInt(), action[1].toInt() and 0xff)
            else -> ActionResult.Rejected(code = BAD_ACTION)
        }
    }

    private fun add(ctx: GameHostContext, sender: Participant, value: Int): ActionResult {
        if (turnBased) {
            val players = joined.filter { it.role == Role.Player }
            if (players.isEmpty() || players[turn % players.size].id != sender.id) return ActionResult.Rejected(NOT_YOUR_TURN)
            turn++
        }
        counter += value
        ctx.sendEvent(Target.All, "add:$value".encodeToByteArray())
        ctx.publishViews()
        return ActionResult.Accepted
    }

    private fun command(ctx: GameHostContext, sender: Participant, op: Int, arg: Int): ActionResult {
        when (op) {
            OP_START -> started = true
            OP_TIMER -> ctx.schedule(arg * 100L, TIMER)
            OP_CANCEL -> ctx.cancel(TIMER)
            OP_PRIVATE -> ctx.sendEvent(Target.One(sender.id), "pm:${secretOf(sender)}".encodeToByteArray())
            OP_THROW -> error("의도한 예외")
            OP_END -> ctx.endSession("end:$counter".encodeToByteArray())
            OP_KICK -> ctx.participants().firstOrNull { it.id.value == arg }?.let { ctx.kick(it, KICK_CODE) }
            OP_BIG_VIEW -> {
                bigView = true
                ctx.publishViews()
            }
            OP_BIG_EVENT -> ctx.sendEvent(Target.All, ByteArray(BIG))
            else -> return ActionResult.Rejected(code = BAD_ACTION)
        }
        return ActionResult.Accepted
    }

    override fun viewFor(participant: Participant): ByteArray {
        if (bigView) return ByteArray(BIG)
        val public = "c=$counter"
        val text = if (participant.role == Role.Player) "$public;s=${secretOf(participant)}" else public
        return text.encodeToByteArray()
    }

    override fun onTimer(ctx: GameHostContext, token: String) {
        calls += "onTimer"
        counter += TIMER_BONUS
        ctx.sendEvent(Target.All, "timer".encodeToByteArray())
        ctx.publishViews()
    }

    override fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason) {
        calls += "onLeave"
        leaves += participant.id to reason
        joined.removeAll { it.id == participant.id }
    }

    override fun onSessionEnd() {
        calls += "onSessionEnd"
    }

    companion object {
        const val BAD_ACTION = 1
        const val NOT_YOUR_TURN = 2
        const val KICK_CODE = 7
        const val TIMER_BONUS = 10
        const val OP_START = 0x10
        const val OP_TIMER = 0x11
        const val OP_CANCEL = 0x12
        const val OP_PRIVATE = 0x13
        const val OP_THROW = 0x14
        const val OP_END = 0x15
        const val OP_KICK = 0x16
        const val OP_BIG_VIEW = 0x17
        const val OP_BIG_EVENT = 0x18
        private const val TIMER = "t"
        private const val BIG = 8192

        /** 플레이어 비공개 값. 이 문자열이 다른 참가자의 프레임에 나오면 I-13 위반. */
        fun secretOf(p: Participant) = "SECRET-${p.id.value}-x9"

        fun op(code: Int, arg: Int = 0) = byteArrayOf(code.toByte(), arg.toByte())
    }
}
