package dev.proximity.core.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 저장소의 실제 설정 파일을 읽는다 (경로는 루트 build.gradle.kts가 넘기는 proximity.repoRoot). */
class S0_DefaultConfigFileTest {
    @Test
    fun defaultYamlLoadsCompletely() {
        val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
        assertEquals(8, config.session.maxPlayers)
        assertEquals(7, config.session.maxLinks)
        assertEquals(1, config.ble.protocolVersion)
    }

    @Test
    fun testYamlHasSameKeysAsDefaultYaml() {
        // commonTest의 TestYaml이 실제 파일과 어긋나면 S0_ProximityConfigTest가 의미를 잃는다.
        val fromFile = SimpleYaml.parse(repoFile("config/default.yaml").readText()).mapValues { it.value.keys }
        val fromTest = SimpleYaml.parse(TestYaml.complete()).mapValues { it.value.keys }
        assertEquals(fromFile, fromTest)
    }
}

class S0_ThresholdsSyncTest {
    @Test
    fun everyThresholdKeyIsDocumented() {
        val yaml = SimpleYaml.parse(repoFile("config/thresholds.yaml").readText())
        val doc = repoFile("docs/thresholds.md").readText()
        val missing = yaml.flatMap { (section, values) -> values.keys.map { "$section.$it" } }
            .filterNot { "`$it`" in doc }
        assertTrue(missing.isEmpty(), "docs/thresholds.md에 없는 키: $missing")
    }

    @Test
    fun everyThresholdIsNumeric() {
        val yaml = SimpleYaml.parse(repoFile("config/thresholds.yaml").readText())
        val bad = yaml.flatMap { (section, values) ->
            values.filterValues { it.toDoubleOrNull() == null }.keys.map { "$section.$it" }
        }
        assertTrue(bad.isEmpty(), "숫자가 아닌 기준치: $bad")
    }
}

internal fun repoFile(path: String): File {
    val root = System.getProperty("proximity.repoRoot") ?: error("proximity.repoRoot 시스템 속성이 없다")
    return File(root, path)
}
