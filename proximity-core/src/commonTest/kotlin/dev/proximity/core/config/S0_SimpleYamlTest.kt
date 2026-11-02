package dev.proximity.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S0_SimpleYamlTest {
    @Test
    fun parsesSectionsScalarsAndComments() {
        val doc = SimpleYaml.parse(
            """
            # 머리 주석
            matching:
              window_ms: 300        # 끝 주석
              rssi_min_dbm: -70
              weight_time: 0.6

            security:
              aead: chacha20-poly1305
            """.trimIndent(),
        )
        assertEquals(setOf("matching", "security"), doc.keys)
        assertEquals("300", doc.getValue("matching").getValue("window_ms"))
        assertEquals("-70", doc.getValue("matching").getValue("rssi_min_dbm"))
        assertEquals("0.6", doc.getValue("matching").getValue("weight_time"))
        assertEquals("chacha20-poly1305", doc.getValue("security").getValue("aead"))
    }

    @Test
    fun rejectsDeeperNesting() {
        assertFailsWith<ConfigException> {
            SimpleYaml.parse("a:\n  b:\n    c: 1")
        }
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertFailsWith<ConfigException> { SimpleYaml.parse("a:\n  b: 1\n  b: 2") }
        assertFailsWith<ConfigException> { SimpleYaml.parse("a:\n  b: 1\na:\n  c: 2") }
    }

    @Test
    fun rejectsMalformedLines() {
        assertFailsWith<ConfigException> { SimpleYaml.parse("a:\n  b 1") }
        assertFailsWith<ConfigException> { SimpleYaml.parse("  b: 1") }
        assertFailsWith<ConfigException> { SimpleYaml.parse("a: 1") }
        assertFailsWith<ConfigException> { SimpleYaml.parse("a:\n  b:") }
    }
}
