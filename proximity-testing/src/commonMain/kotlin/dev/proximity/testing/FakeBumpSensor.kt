package dev.proximity.testing

import dev.proximity.core.interfaces.AccelSample
import dev.proximity.core.interfaces.BumpSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 가속도 샘플을 손으로 넣는 센서. 트레이스 재생(S3)도 이것으로 한다. */
class FakeBumpSensor : BumpSensor {
    private val flow = MutableSharedFlow<AccelSample>(extraBufferCapacity = 4096)
    override val samples: Flow<AccelSample> = flow

    var isRunning = false
        private set

    override fun start() {
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    /** 실행 중일 때만 전달한다. 실제 센서도 멈추면 값을 내지 않는다. */
    fun emit(sample: AccelSample) {
        if (isRunning) check(flow.tryEmit(sample)) { "센서 버퍼가 찼다" }
    }
}
