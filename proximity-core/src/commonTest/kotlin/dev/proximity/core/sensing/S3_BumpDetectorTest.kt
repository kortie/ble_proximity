package dev.proximity.core.sensing

import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.AccelSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S3_BumpDetectorTest {
    private val config = ProximityConfig.Bump(
        thresholdG = 2.5, debounceMs = 500, spikeMaxMs = 80, preQuietMs = 300, quietG = 0.6, gravityAlpha = 0.05, samplePeriodUs = 10_000,
    )

    /** 10ms 간격 샘플. [extraX]는 시각별 x축 추가 가속도(g), 중력은 z축 1g. */
    private fun trace(durationMs: Long, extraX: (Long) -> Double): List<AccelSample> =
        (0 until durationMs / 10).map { i -> val t = i * 10; AccelSample(t, extraX(t).toFloat(), 0f, 1f) }

    private fun halfSine(t: Long, start: Long, width: Long, amp: Double) =
        if (t in start..start + width) amp * sin(PI * (t - start) / width) else 0.0

    private fun detect(samples: List<AccelSample>, detector: BumpDetector = BumpDetector(config)) = samples.mapNotNull(detector::feed)

    @Test
    fun detectsSharpSpikeAfterQuiet() {
        val bumps = detect(trace(2000) { halfSine(it, 1000, 30, 3.5) })
        assertEquals(1, bumps.size)
        assertTrue(abs(bumps.single().atMs - 1015) <= 20, "최대 충격 시점 근처: ${bumps.single().atMs}")
        assertTrue(bumps.single().peakG >= 2.5)
    }

    @Test
    fun ignoresSpikeBelowThreshold() {
        assertTrue(detect(trace(2000) { halfSine(it, 1000, 30, 2.0) }).isEmpty())
    }

    @Test
    fun rejectsSlowMotionEvenIfStrong() {
        // 300ms에 걸친 3g 움직임: 폭이 spike_max_ms보다 길다
        assertTrue(detect(trace(2500) { halfSine(it, 1000, 300, 3.0) }).isEmpty())
    }

    @Test
    fun rejectsSpikeWithoutQuietBefore() {
        // 직전 300ms 안에 1g 흔들림이 있으면 (걷기·뛰기) 부딪힘으로 보지 않는다
        val samples = trace(2000) { halfSine(it, 800, 100, 1.0) + halfSine(it, 1000, 30, 3.5) }
        assertTrue(detect(samples).isEmpty())
    }

    @Test
    fun rejectsSpikeRightAfterStartBecauseThereIsNoHistory() {
        assertTrue(detect(trace(1000) { halfSine(it, 100, 30, 3.5) }).isEmpty(), "직전 정지를 확인할 기록이 없다")
    }

    @Test
    fun debounceCollapsesSpikesWithinInterval() {
        // 1000ms, 1400ms(디바운스 500ms 안), 2000ms
        val samples = trace(3000) { halfSine(it, 1000, 30, 3.5) + halfSine(it, 1400, 30, 3.5) + halfSine(it, 2000, 30, 3.5) }
        val bumps = detect(samples)
        assertEquals(2, bumps.size, "1400ms 스파이크는 디바운스로 무시: ${bumps.map { it.atMs }}")
        assertTrue(bumps[1].atMs >= 2000)
    }

    @Test
    fun regressingOrDuplicateTimestampsAreDroppedAndOutputIsMonotonic() {
        val base = trace(3000) { halfSine(it, 1000, 30, 3.5) + halfSine(it, 2200, 30, 3.5) }
        // 역행·중복 샘플을 끼워 넣는다
        val noisy = base.flatMapIndexed { i, s ->
            when (i % 37) {
                0 -> listOf(s, s)                                       // 중복
                5 -> listOf(s, AccelSample(s.timestampMs - 500, 4f, 0f, 1f)) // 역행 + 큰 값
                else -> listOf(s)
            }
        }
        val detector = BumpDetector(config)
        val bumps = detect(noisy, detector)
        assertEquals(2, bumps.size)
        assertTrue(bumps.zipWithNext().all { (a, b) -> a.atMs < b.atMs }, "출력 시각 역행 0건")
        assertTrue(detector.droppedSamples > 0)
    }
}
