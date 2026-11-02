package dev.proximity.core.framing

/**
 * 메시지 하나를 `mtu-3` 이하 조각으로 나누고 합친다 (Rendezvous, ADR-008).
 * 조각 = 머리 1바이트(bit0 FIRST, bit1 LAST) + 본문. 링크가 순서를 지킨다는 전제다(GATT write·notify).
 */
object Chunker {
    private const val FIRST = 0b01
    private const val LAST = 0b10
    private const val ATT_OVERHEAD = 3

    fun split(message: ByteArray, mtu: Int): List<ByteArray> {
        val room = mtu - ATT_OVERHEAD - 1
        require(room >= 1) { "MTU가 너무 작다: $mtu" }
        if (message.isEmpty()) return listOf(byteArrayOf((FIRST or LAST).toByte()))
        val chunks = (message.indices step room).map { start ->
            val end = minOf(start + room, message.size)
            var flags = 0
            if (start == 0) flags = flags or FIRST
            if (end == message.size) flags = flags or LAST
            byteArrayOf(flags.toByte()) + message.copyOfRange(start, end)
        }
        return chunks
    }

    /** 조각을 받아 메시지가 완성되면 돌려준다. 순서가 어긋난 조각(FIRST 없이 시작 등)은 버리고 다시 맞춘다. */
    class Assembler(private val maxBytes: Int) {
        private var buffer: ByteArray? = null

        fun accept(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) return null
            val flags = chunk[0].toInt()
            val body = chunk.copyOfRange(1, chunk.size)
            val current = if (flags and FIRST != 0) body else (buffer ?: return null) + body
            if (current.size > maxBytes) {
                buffer = null
                return null
            }
            return if (flags and LAST != 0) {
                buffer = null
                current
            } else {
                buffer = current
                null
            }
        }
    }
}
