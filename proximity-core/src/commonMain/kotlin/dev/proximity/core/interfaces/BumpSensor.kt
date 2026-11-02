package dev.proximity.core.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 가속도 센서. 플랫폼은 샘플만 전달하고, 부딪힘 판정(임계치·디바운스)은 core의 BumpDetector가 한다 (S3).
 */
interface BumpSensor {
    val samples: Flow<AccelSample>

    fun start()

    fun stop()
}

/** 가속도 샘플. 단위는 g, 시각은 [Clock.now] 기준 밀리초. */
data class AccelSample(val timestampMs: Long, val x: Float, val y: Float, val z: Float)
