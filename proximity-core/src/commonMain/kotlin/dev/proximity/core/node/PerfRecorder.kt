package dev.proximity.core.node

/** S11 성능 기준(docs/thresholds.md, config/thresholds.yaml e2e·session)과 같은 이름의 측정값. */
enum class Metric(val key: String) {
    /** 부딪힘 → 확인 화면 (`e2e.bump_to_confirm_p95_ms`) */
    BumpToConfirm("bump_to_confirm_ms"),

    /** 이 기기가 수락 → 세션 Live (`e2e.accept_to_welcome_p95_ms`) */
    AcceptToWelcome("accept_to_welcome_ms"),

    /** sendAction → 결과(같은 배치의 view 포함) (`e2e.action_roundtrip_p95_ms`) */
    ActionRoundTrip("action_roundtrip_ms"),

    /** ctl/PING 왕복. 다운링크 지연(`session.downlink_p95_ms`)의 참고값 */
    LinkRtt("link_rtt_ms"),
}

/**
 * 실기기 측정 도구 (S11). 시간만 담고 기기·사람 정보는 담지 않는다.
 * [csv]는 `scripts/perf-report.py`가 읽어 p95를 기준치와 비교한다.
 */
class PerfRecorder {
    private val samples = mutableMapOf<Metric, MutableList<Long>>()
    private val battery = mutableListOf<Pair<Long, Int>>()

    fun record(metric: Metric, ms: Long) {
        samples.getOrPut(metric) { mutableListOf() } += ms
    }

    /** 배터리 잔량(%) 표본. 1시간 소모량은 perf-report.py가 계산한다. */
    fun recordBattery(atMs: Long, percent: Int) {
        battery += atMs to percent
    }

    fun count(metric: Metric): Int = samples[metric]?.size ?: 0

    /** nearest-rank p95. 표본이 없으면 null. */
    fun p95(metric: Metric): Long? {
        val sorted = samples[metric]?.sorted()?.takeIf { it.isNotEmpty() } ?: return null
        val rank = (P95 * sorted.size + RANK_EPSILON).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun csv(): String = buildString {
        appendLine("metric,value")
        for (m in Metric.entries) samples[m]?.forEach { appendLine("${m.key},$it") }
        battery.forEach { (at, pct) -> appendLine("battery_pct@$at,$pct") }
    }

    private companion object {
        const val P95 = 0.95
        const val RANK_EPSILON = 0.999_999 // ceil(0.95 × n) — 부동소수 오차 없이
    }
}
