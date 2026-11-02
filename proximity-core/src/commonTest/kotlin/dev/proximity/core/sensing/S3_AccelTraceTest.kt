package dev.proximity.core.sensing

import dev.proximity.core.config.ConfigException
import dev.proximity.core.interfaces.AccelSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S3_AccelTraceTest {
    @Test
    fun formatAndParseRoundTrip() {
        val samples = listOf(AccelSample(0, 0.01f, -0.02f, 1.0f), AccelSample(10, 3.5f, 0f, 0.98f))
        val text = AccelTrace.format(samples, comment = "기기 A")
        assertTrue(text.startsWith("# 기기 A\nt_ms,x,y,z\n"))
        assertEquals(samples, AccelTrace.parse(text))
    }

    @Test
    fun skipsCommentsAndHeader() {
        assertEquals(listOf(AccelSample(5, 1f, 2f, 3f)), AccelTrace.parse("# a\n\nt_ms,x,y,z\n# b\n5,1,2,3\n"))
    }

    @Test
    fun malformedLineReportsLineNumber() {
        val e = assertFailsWith<ConfigException> { AccelTrace.parse("t_ms,x,y,z\n0,1,2,3\n10,1,2\n") }
        assertTrue("3행" in e.message.orEmpty(), e.message)
    }
}
