package dev.proximity.core.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 성능 측정 도구 (S11): p95는 nearest-rank, CSV는 perf-report.py가 읽는다. */
class S11_PerfRecorderTest {
    @Test
    fun p95IsNearestRank() {
        val r = PerfRecorder()
        assertNull(r.p95(Metric.BumpToConfirm))
        (1..100).forEach { r.record(Metric.BumpToConfirm, it.toLong()) }
        assertEquals(95, r.p95(Metric.BumpToConfirm))
        r.record(Metric.LinkRtt, 7)
        assertEquals(7, r.p95(Metric.LinkRtt))
        assertEquals(100, r.count(Metric.BumpToConfirm))
    }

    @Test
    fun csvHasOneLinePerSampleWithStableKeys() {
        val r = PerfRecorder()
        r.record(Metric.AcceptToWelcome, 1200)
        r.record(Metric.ActionRoundTrip, 80)
        r.recordBattery(atMs = 0, percent = 91)
        val lines = r.csv().trim().lines()
        assertEquals("metric,value", lines.first())
        assertTrue("accept_to_welcome_ms,1200" in lines)
        assertTrue("action_roundtrip_ms,80" in lines)
        assertTrue("battery_pct@0,91" in lines)
        assertEquals(Metric.entries.map { it.key }.toSet().size, Metric.entries.size, "키 중복 없음")
    }
}
