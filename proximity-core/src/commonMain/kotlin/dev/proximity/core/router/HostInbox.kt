package dev.proximity.core.router

import dev.proximity.core.interfaces.Clock

/**
 * 호스트의 모든 입력을 한 줄로 세운다 (04-session-flows §7.1, I-11). 처리 순서가 곧 전역 순서다.
 * 처리 중에 새 입력이 들어와도(재진입) 줄 끝에 넣고 지금 것이 끝난 뒤 처리한다. 한 스레드에서만 쓴다.
 */
class HostInbox(private val clock: Clock) {
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    /** 처리한 입력 수 (결정성·순서 시험용). */
    var processed = 0L
        private set

    fun post(input: () -> Unit) {
        queue.addLast(input)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                queue.removeFirst().invoke()
                processed++
            }
        } finally {
            draining = false
        }
    }

    fun now(): Long = clock.now()
}
