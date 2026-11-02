package dev.proximity.core.host

import dev.proximity.core.config.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** I-15·I-16 정적 확인 (S8). */
class S8_ApiBoundaryTest {
    @Test
    fun fakeGameModuleDependsOnlyOnTheGameApi() {
        val src = repoFile("proximity-testing/src/commonMain/kotlin/dev/proximity/testing/FakeGameModule.kt").readText()
        val imports = src.lines().filter { it.startsWith("import ") }
        assertTrue(imports.isNotEmpty())
        val outside = imports.filterNot { it.startsWith("import dev.proximity.core.api.") || it == "import dev.proximity.core.interfaces.GameModule" }
        assertEquals(emptyList(), outside, "I-16: core/api·GameModule 외 의존 0")
    }

    @Test
    fun uiDoesNotTouchGameModule() {
        val ui = repoFile("sample-android/src").walkTopDown().filter { it.isFile && it.extension == "kt" && "/ui/" in it.path }.toList()
        val hits = ui.filter { "GameModule" in it.readText() || "GameHost" in it.readText() }
        assertEquals(emptyList(), hits.map { it.path }, "I-15: ui는 GameClient만")
    }
}
