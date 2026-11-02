package dev.proximity.core.interfaces

/** 단조 증가 시간. 코어는 시스템 시계를 직접 읽지 않고 이것만 쓴다 (테스트는 FakeClock). */
interface Clock {
    /** 단조 증가 밀리초. 기준점은 구현마다 다르며 차이만 의미가 있다. */
    fun now(): Long

    /** [delayMs] 뒤에 [action]을 한 번 실행한다. */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}
