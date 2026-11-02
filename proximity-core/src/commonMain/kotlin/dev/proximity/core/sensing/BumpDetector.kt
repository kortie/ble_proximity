package dev.proximity.core.sensing

import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.AccelSample
import kotlin.math.sqrt

/** 감지한 부딪힘. [atMs]는 최대 충격 시점(센서 시각), [peakG]는 그때의 동적 가속도. */
data class Bump(val atMs: Long, val peakG: Double)

/**
 * 가속도 샘플에서 부딪힘을 찾는다 (S3 판정 규칙).
 * 중력 제거 → 임계치 → 날카로움(폭) → 직전 정지 → 디바운스. 역행·중복 시각 샘플은 버린다.
 * 상태를 가지며 한 스레드에서만 [feed]한다.
 */
class BumpDetector(private val config: ProximityConfig.Bump) {
    private val half = config.thresholdG / 2
    private val historyMs = config.preQuietMs + config.spikeMaxMs + HISTORY_MARGIN_MS

    private var gravity: DoubleArray? = null
    private var lastTs: Long? = null
    private var lastBumpAt: Long? = null
    private val history = ArrayDeque<Pair<Long, Double>>()

    private var spikeStart: Long? = null
    private var spikePeak = 0.0
    private var spikePeakAt = 0L
    private var quietBeforeSpike = false

    /** 역행·중복 시각이라 버린 샘플 수. */
    var droppedSamples = 0
        private set

    fun feed(sample: AccelSample): Bump? {
        val ts = sample.timestampMs
        val prev = lastTs
        if (prev != null && ts <= prev) {
            droppedSamples++
            return null
        }
        lastTs = ts
        val a = doubleArrayOf(sample.x.toDouble(), sample.y.toDouble(), sample.z.toDouble())
        val g = gravity ?: a.copyOf().also { gravity = it }
        val dyn = sqrt((0..2).sumOf { (a[it] - g[it]) * (a[it] - g[it]) })

        val start = spikeStart
        val result = if (start == null) {
            if (dyn >= half) beginSpike(ts, dyn)
            null
        } else {
            if (dyn > spikePeak) {
                spikePeak = dyn
                spikePeakAt = ts
            }
            if (dyn < half) endSpike(ts - start) else null
        }

        // 스파이크 동안에는 중력 추정을 멈춘다. 단, 너무 긴 움직임(회전 등)이면 다시 따라간다.
        val inShortSpike = spikeStart?.let { ts - it <= config.spikeMaxMs } ?: false
        if (!inShortSpike) for (i in 0..2) g[i] += config.gravityAlpha * (a[i] - g[i])

        history.addLast(ts to dyn)
        while (history.isNotEmpty() && ts - history.first().first > historyMs) history.removeFirst()
        return result
    }

    private fun beginSpike(ts: Long, dyn: Double) {
        spikeStart = ts
        spikePeak = dyn
        spikePeakAt = ts
        val windowStart = ts - config.preQuietMs
        val covered = history.isNotEmpty() && history.first().first <= windowStart
        quietBeforeSpike = covered && history.filter { it.first >= windowStart }.all { it.second <= config.quietG }
    }

    private fun endSpike(widthMs: Long): Bump? {
        spikeStart = null
        val debounced = lastBumpAt?.let { spikePeakAt - it < config.debounceMs } ?: false
        if (spikePeak < config.thresholdG || widthMs > config.spikeMaxMs || !quietBeforeSpike || debounced) return null
        lastBumpAt = spikePeakAt
        return Bump(spikePeakAt, spikePeak)
    }

    private companion object {
        const val HISTORY_MARGIN_MS = 100L
    }
}
