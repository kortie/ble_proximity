package dev.proximity.core.sensing

import dev.proximity.core.config.ConfigException
import dev.proximity.core.interfaces.AccelSample

/**
 * 가속도 트레이스 CSV (tests/fixtures/accel/README.md): `#` 주석, 머리글 `t_ms,x,y,z`, 단위 g.
 * Proximity Lab의 "센서 기록"과 S3 재생 테스트가 같은 형식을 쓴다.
 */
object AccelTrace {
    const val HEADER = "t_ms,x,y,z"

    fun format(samples: List<AccelSample>, comment: String? = null): String = buildString {
        comment?.let { append("# ").append(it.replace('\n', ' ')).append('\n') }
        append(HEADER).append('\n')
        samples.forEach { append(it.timestampMs).append(',').append(it.x).append(',').append(it.y).append(',').append(it.z).append('\n') }
    }

    fun parse(text: String): List<AccelSample> = text.lines().mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line == HEADER) return@mapIndexedNotNull null
        val parts = line.split(',')
        val t = parts.getOrNull(0)?.toLongOrNull()
        val v = parts.drop(1).map { it.toFloatOrNull() }
        if (parts.size != 4 || t == null || v.any { it == null }) throw ConfigException("${index + 1}행: `t_ms,x,y,z` 형식이 아니다")
        AccelSample(t, v[0]!!, v[1]!!, v[2]!!)
    }
}
