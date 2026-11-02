package dev.proximity.core.state

import dev.proximity.core.api.Role
import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock

/**
 * 매칭 상태 기계 (S1). 현재 상태를 들고 [MatchTransitions.transition]으로만 바꾼다 (I-4').
 * 타이머 효과는 [Clock]으로 직접 처리하고, 나머지 효과는 호출자에게 돌려준다.
 * 타이머가 일으킨 전이(Timeout)의 효과는 돌려받을 호출자가 없으므로 [listener]로도 알린다.
 * 스레드 안전하지 않다: 한 루프(스레드)에서만 [dispatch]를 부른다.
 */
class MatchStateMachine(private val env: MatchEnv, private val clock: Clock, role: Role) {
    private var current: MatchState = MatchState.Idle
    private var currentCtx: MatchContext = MatchContext.initial(role)
    private var timer: Cancellable? = null
    private val records = ArrayDeque<TransitionRecord>()

    val state: MatchState get() = current

    /** 상태가 바뀔 때마다(타이머 전이 포함) 바깥 효과와 함께 불린다. */
    var listener: ((List<MatchEffect>) -> Unit)? = null
    val context: MatchContext get() = currentCtx

    /** 이벤트를 적용하고, 바깥에서 실행할 효과를 돌려준다. 무시된 이벤트면 빈 목록. */
    fun dispatch(event: MatchEvent): List<MatchEffect> {
        val before = current
        val t = MatchTransitions.transition(env, current, currentCtx, event)
        if (t.state == current && t.ctx == currentCtx && t.effects.isEmpty()) return emptyList()
        current = t.state
        currentCtx = t.ctx
        record(TransitionRecord(clock.now(), before.kind, event.kind, t.state.kind, t.effects.map { it.logName }, (t.state as? MatchState.Failed)?.reason))
        t.effects.forEach(::applyTimer)
        val external = t.effects.filterNot { it is MatchEffect.ArmTimer || it is MatchEffect.DisarmTimer }
        listener?.invoke(external)
        return external
    }

    /** 최근 전이 기록 (오래된 것부터). 이름만 담는다. */
    fun log(): List<TransitionRecord> = records.toList()

    private fun applyTimer(effect: MatchEffect) {
        when (effect) {
            is MatchEffect.ArmTimer -> {
                timer?.cancel()
                val token = effect.token
                timer = clock.schedule(effect.delayMs) { dispatch(MatchEvent.Timeout(token)) }
            }
            MatchEffect.DisarmTimer -> {
                timer?.cancel()
                timer = null
            }
            else -> Unit
        }
    }

    private fun record(r: TransitionRecord) {
        if (records.size == LOG_CAPACITY) records.removeFirst()
        records.addLast(r)
    }

    private companion object {
        const val LOG_CAPACITY = 256
    }
}

/** 상태 변경 로그 한 줄. 임시 ID·닉네임 등 개인정보를 담지 않는다 (원본 S1, S10 로그 정책). */
data class TransitionRecord(
    val atMs: Long,
    val from: StateKind,
    val event: EventKind,
    val to: StateKind,
    val effects: List<String>,
    val failure: FailureReason?,
)
