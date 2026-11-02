package dev.proximity.platform

import dev.proximity.core.interfaces.AccelSample
import kotlin.test.Test
import kotlin.test.assertEquals

class S3_AccelConversionTest {
    @Test
    fun convertsMetersPerSecondSquaredToGAndNanosToMillis() {
        val s = AndroidBumpSensor.toSample(timestampNs = 12_345_678_900L, x = 9.80665f, y = 0f, z = -19.6133f)
        assertEquals(AccelSample(12_345, 1f, 0f, -2f), s, "센서 시각은 elapsedRealtimeNanos → AndroidClock과 같은 ms")
    }
}
