package dev.proximity.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.proximity.core.interfaces.AccelSample
import dev.proximity.core.interfaces.BumpSensor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 가속도 샘플을 전달만 한다 (S3). 부딪힘 판정은 core BumpDetector가 한다.
 * TYPE_ACCELEROMETER(중력 포함)를 g 단위로, 센서 시각(elapsedRealtimeNanos)을 [AndroidClock]과 같은 ms로 바꾼다.
 */
class AndroidBumpSensor(context: Context, private val samplePeriodUs: Int) : BumpSensor, SensorEventListener {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val flow = MutableSharedFlow<AccelSample>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var running = false

    override val samples: Flow<AccelSample> = flow

    /** 기기에 가속도계가 없으면 false. */
    val isAvailable: Boolean get() = sensor != null

    override fun start() {
        val s = sensor ?: return
        if (running) return
        running = manager?.registerListener(this, s, samplePeriodUs) == true
    }

    override fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        flow.tryEmit(toSample(event.timestamp, event.values[0], event.values[1], event.values[2]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    internal companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        fun toSample(timestampNs: Long, x: Float, y: Float, z: Float) = AccelSample(
            timestampMs = timestampNs / NANOS_PER_MILLI,
            x = x / SensorManager.GRAVITY_EARTH,
            y = y / SensorManager.GRAVITY_EARTH,
            z = z / SensorManager.GRAVITY_EARTH,
        )
    }
}
