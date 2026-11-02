package dev.proximity.core.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S6_ClientReceiverTest {
    private val batches = mutableListOf<List<ClientMessage>>()
    private var gaps = 0
    private val r = ClientReceiver(onBatch = { batches += it }, onGap = { gaps++ })

    private fun m(seq: Long, end: Boolean = true, resync: Boolean = false) = MessageMeta(SessionChannel.Social, 1, seq, resync = resync, batchEnd = end)

    @Test
    fun appliesInOrderAndHoldsUntilBatchEnd() {
        r.accept(m(1, end = false), byteArrayOf(1))
        assertTrue(batches.isEmpty(), "배치가 끝나기 전에는 내보내지 않는다")
        r.accept(m(2), byteArrayOf(2))
        assertEquals(listOf(listOf(1L, 2L)), batches.map { b -> b.map { it.seq } })
        assertEquals(2, r.lastApplied)
    }

    @Test
    fun gapIsReportedAndDuplicatesIgnored() {
        r.accept(m(1), byteArrayOf())
        r.accept(m(1), byteArrayOf())
        assertEquals(1, batches.size, "중복은 무시")
        r.accept(m(3), byteArrayOf())
        assertEquals(1, gaps, "공백은 링크 고장으로 알린다 (AEAD가 못 잡는 seq 점프 대비)")
        assertEquals(1, batches.size)
    }

    @Test
    fun resyncRestartsAtTheGivenSeqAndMarksTheFirstMessage() {
        r.accept(m(1), byteArrayOf())
        r.accept(m(40, resync = true), byteArrayOf())
        assertEquals(0, gaps)
        assertTrue(batches.last().single().resynced)
        r.accept(m(41), byteArrayOf())
        assertEquals(false, batches.last().single().resynced)
    }

    @Test
    fun rewindDropsUnfinishedBatch() {
        r.accept(m(1), byteArrayOf())
        r.accept(m(2, end = false), byteArrayOf())
        r.rewind()
        r.accept(m(2, end = false), byteArrayOf())
        r.accept(m(3), byteArrayOf())
        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), batches.map { b -> b.map { it.seq } }, "끝나지 않은 배치는 처음부터 다시")
    }
}
