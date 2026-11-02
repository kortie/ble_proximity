package dev.proximity.core.ble

import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class S2_IdRotatorTest {
    private val period = 300_000L
    private val clock = FakeClock()
    private val crypto = FakeCryptoBox(seed = 1)

    private fun bytes(i: Int) = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    @Test
    fun keepsIdWithinPeriodAndRotatesAtBoundary() {
        crypto.scripted += listOf(bytes(1), bytes(2))
        val rotator = IdRotator(period, clock, crypto)
        val changes = mutableListOf<Int>()
        rotator.onRotate = { changes += it }
        rotator.start()
        assertEquals(1, rotator.current)

        clock.advanceBy(period - 1)
        assertEquals(1, rotator.current, "주기 안에서는 유지")
        clock.advanceBy(1)
        assertEquals(2, rotator.current, "경계에서 새 ID")
        assertEquals(listOf(2), changes)
    }

    @Test
    fun newIdDiffersFromPrevious() {
        crypto.scripted += listOf(bytes(5), bytes(5), bytes(5), bytes(9))
        val rotator = IdRotator(period, clock, crypto)
        rotator.start()
        clock.advanceBy(period)
        assertNotEquals(5, rotator.current)
        assertEquals(9, rotator.current)
    }

    @Test
    fun pinnedRotationIsDeferredUntilUnpin() {
        crypto.scripted += listOf(bytes(1), bytes(2))
        val rotator = IdRotator(period, clock, crypto)
        rotator.start()
        rotator.pin()
        clock.advanceBy(period * 2)
        assertEquals(1, rotator.current, "매칭 중에는 회전하지 않는다")
        rotator.unpin()
        assertEquals(2, rotator.current, "밀린 회전을 바로 한다")
        clock.advanceBy(period - 1)
        assertEquals(2, rotator.current, "새 주기는 회전한 시점부터")
    }

    @Test
    fun stopCancelsRotation() {
        crypto.scripted += listOf(bytes(1), bytes(2))
        val rotator = IdRotator(period, clock, crypto)
        rotator.start()
        rotator.stop()
        clock.advanceBy(period * 3)
        assertEquals(1, rotator.current)
    }
}
