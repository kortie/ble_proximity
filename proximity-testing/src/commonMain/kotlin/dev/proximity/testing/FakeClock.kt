package dev.proximity.testing

import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock

/** 손으로 진행시키는 시계. [advanceBy] 동안 만기된 작업을 시각 순서대로 실행한다. */
class FakeClock(private var nowMs: Long = 0) : Clock {
    private class Task(val dueMs: Long, val order: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var nextOrder = 0L

    override fun now(): Long = nowMs

    /** 아직 실행되지 않았고 취소되지 않은 예약 수 (누수 검사용, S11). */
    val pendingCount: Int get() = tasks.count { !it.cancelled }

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        require(delayMs >= 0) { "delayMs < 0" }
        val task = Task(nowMs + delayMs, nextOrder++, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0) { "시간은 거꾸로 가지 않는다" }
        val target = nowMs + deltaMs
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueMs <= target }
                .minWithOrNull(compareBy<Task>({ it.dueMs }, { it.order })) ?: break
            tasks -= next
            nowMs = next.dueMs
            next.action()
        }
        tasks.removeAll { it.cancelled }
        nowMs = target
    }
}
