package dev.proximity.core.api

/** 호스트에서 GameModule이 쓰는 API (03-game-data-api §3). core가 구현한다 (S8). */
interface GameHostContext {
    fun participants(): List<Participant>

    /** 상태가 바뀌었음을 알린다. core가 참가자마다 viewFor()를 불러 보낸다. */
    fun publishViews()

    fun sendEvent(to: Target, event: ByteArray)

    fun now(): Long

    /** [delayMs] 뒤에 GameModule.onTimer([token]). 같은 token이면 교체한다. */
    fun schedule(delayMs: Long, token: String)

    fun cancel(token: String)

    /** 링크를 끊고 onLeave(Kicked). 호스트 자신은 kick할 수 없다. */
    fun kick(participant: Participant, code: Int)

    fun endSession(result: ByteArray?)
}

sealed interface Target {
    data class One(val participant: ParticipantId) : Target

    data object AllPlayers : Target

    data object AllSpectators : Target

    data object All : Target
}

sealed interface ActionResult {
    data object Accepted : ActionResult

    /** [code]의 의미는 게임이 정한다. core는 [INTERNAL]만 쓴다. */
    class Rejected(val code: Int, val detail: ByteArray? = null) : ActionResult

    companion object {
        /** GameModule 예외·view 크기 초과 (03 §3·§6). */
        const val INTERNAL = -1
    }
}

sealed interface JoinDecision {
    data object Admit : JoinDecision

    data class Refuse(val code: Int) : JoinDecision
}

enum class LeaveReason { Left, Disconnected, Kicked }
