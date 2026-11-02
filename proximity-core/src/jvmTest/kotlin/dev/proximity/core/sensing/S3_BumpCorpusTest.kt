package dev.proximity.core.sensing

import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.SimpleYaml
import dev.proximity.core.config.repoFile
import dev.proximity.testing.SyntheticAccel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 트레이스 재생과 코퍼스 판정 (S3). 결과 요약은 build/reports/s3-corpus.txt (검증 기록용). */
class S3_BumpCorpusTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText()).bump
    private val thresholds = SimpleYaml.parse(repoFile("config/thresholds.yaml").readText()).getValue("bump")
    private val detectMin = thresholds.getValue("detect_success_min").toDouble()
    private val fpMax = thresholds.getValue("false_positive_max").toDouble()
    private val report = File(repoFile("proximity-core/build/reports"), "s3-corpus.txt").also { it.parentFile.mkdirs() }

    private fun detect(text: String) = BumpDetector(config).let { d -> AccelTrace.parse(text).mapNotNull(d::feed) }

    @Test
    fun replayedBumpFileIsDetectedOnceAtExpectedTime() {
        val bumps = detect(repoFile("tests/fixtures/accel/synthetic/bump_replay.csv").readText())
        assertEquals(1, bumps.size)
        assertTrue(abs(bumps.single().atMs - 1215) <= 20, "기대 1215ms, 실제 ${bumps.single().atMs}")
    }

    @Test
    fun replayedWalkFileHasNoBump() {
        assertTrue(detect(repoFile("tests/fixtures/accel/synthetic/walk_replay.csv").readText()).isEmpty())
    }

    @Test
    fun syntheticCorpusMeetsThresholds() {
        val bumpResults = SyntheticAccel.bumpCorpus().map { trace ->
            val d = BumpDetector(config)
            val found = trace.samples.mapNotNull(d::feed)
            found.size == 1 && abs(found.single().atMs - trace.expectedBumpAtMs!!) <= 20
        }
        val negatives = SyntheticAccel.negativeCorpus()
        val falseByKind = negatives.groupBy { it.kind }.mapValues { (_, traces) ->
            traces.count { t -> BumpDetector(config).let { d -> t.samples.any { d.feed(it) != null } } } to traces.size
        }
        val detected = bumpResults.count { it }
        val falsePositives = falseByKind.values.sumOf { it.first }
        report.writeText(
            buildString {
                appendLine("S3 synthetic corpus")
                appendLine("bump: $detected/${bumpResults.size} detected (min ${detectMin})")
                falseByKind.forEach { (kind, r) -> appendLine("$kind: ${r.first}/${r.second} false positives") }
                appendLine("false positives: $falsePositives/${negatives.size} (max ${fpMax})")
            },
        )
        assertTrue(detected.toDouble() / bumpResults.size >= detectMin, report.readText())
        assertTrue(falsePositives.toDouble() / negatives.size <= fpMax, report.readText())
    }

    @Test
    fun realTracesWhenPresentMeetTheSameThresholds() {
        val files = repoFile("tests/fixtures/accel/real").listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
        if (files.isEmpty()) return // HW-S3에서 채운다
        val (bumps, others) = files.partition { it.name.startsWith("bump_") }
        val detected = bumps.count { detect(it.readText()).size == 1 }
        val falsePositives = others.count { detect(it.readText()).isNotEmpty() }
        if (bumps.isNotEmpty()) assertTrue(detected.toDouble() / bumps.size >= detectMin, "실측 부딪힘 $detected/${bumps.size}")
        if (others.isNotEmpty()) assertTrue(falsePositives.toDouble() / others.size <= fpMax, "실측 오탐 $falsePositives/${others.size}")
    }
}
