package dev.proximity.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock

/** 단조 시계: 부팅 후 경과 시간(절전 포함). 예약 작업은 [handler]의 스레드(기본: 메인)에서 실행된다. */
class AndroidClock(private val handler: Handler = Handler(Looper.getMainLooper())) : Clock {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
