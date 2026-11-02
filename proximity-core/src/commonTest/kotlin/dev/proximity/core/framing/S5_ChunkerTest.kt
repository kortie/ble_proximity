package dev.proximity.core.framing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S5_ChunkerTest {
    @Test
    fun splitAndAssembleAcrossMtus() {
        val message = ByteArray(1000) { it.toByte() }
        for (mtu in listOf(23, 185, 517)) {
            val chunks = Chunker.split(message, mtu)
            assertTrue(chunks.all { it.size <= mtu - 3 }, "MTU $mtu")
            val asm = Chunker.Assembler(maxBytes = 4096)
            val out = chunks.mapNotNull(asm::accept)
            assertEquals(1, out.size)
            assertContentEquals(message, out.single())
        }
    }

    @Test
    fun emptyMessageIsOneChunk() {
        val chunks = Chunker.split(ByteArray(0), 23)
        assertEquals(1, chunks.size)
        assertContentEquals(ByteArray(0), Chunker.Assembler(100).accept(chunks.single()))
    }

    @Test
    fun strayMiddleChunkIsDroppedAndOversizedMessageRejected() {
        val chunks = Chunker.split(ByteArray(100) { 1 }, 23)
        val asm = Chunker.Assembler(maxBytes = 50)
        assertNull(asm.accept(chunks[1]), "FIRST 없이 온 조각")
        assertTrue(chunks.mapNotNull(asm::accept).isEmpty(), "상한을 넘는 메시지는 버린다")
        val ok = Chunker.Assembler(maxBytes = 200)
        assertEquals(1, chunks.mapNotNull(ok::accept).size, "다음 메시지는 정상")
    }
}
