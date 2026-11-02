package dev.proximity.core.ble

import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox

/**
 * 광고용 임시 ID (I-7). [periodMs]마다 새 난수로 바꾼다. 기기·계정과 무관한 값이다.
 * 매칭 중에는 [pin]으로 회전을 미룬다 — 범프 직후 상대가 같은 tempId의 bumpSeq 변화를 봐야 하기 때문이다 (S4 F5).
 */
class IdRotator(private val periodMs: Long, private val clock: Clock, private val crypto: CryptoBox) {
    var current: Int = draw(previous = null)
        private set

    /** 회전할 때마다 새 ID로 불린다. */
    var onRotate: (Int) -> Unit = {}

    private var timer: Cancellable? = null
    private var pinned = false
    private var pending = false

    fun start() {
        if (timer == null) schedule()
    }

    fun stop() {
        timer?.cancel()
        timer = null
        pending = false
    }

    fun pin() {
        pinned = true
    }

    fun unpin() {
        pinned = false
        if (pending) {
            pending = false
            rotate()
            timer?.cancel()
            schedule()
        }
    }

    private fun schedule() {
        timer = clock.schedule(periodMs) {
            if (pinned) {
                pending = true
            } else {
                rotate()
                schedule()
            }
        }
    }

    private fun rotate() {
        current = draw(previous = current)
        onRotate(current)
    }

    private fun draw(previous: Int?): Int {
        while (true) {
            val b = crypto.randomBytes(4)
            val id = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (b[i].toInt() and 0xff) }
            if (id != previous) return id
        }
    }
}
