package dev.proximity.core.router

/** 세션 채널 (01-architecture §4). 코드값은 프레임 머리에 실린다. */
enum class SessionChannel(val code: Int) {
    Control(0), Game(1), Social(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** control 채널 kind. */
object ControlKind {
    /** HELLO: 링크 키가 아직 없어 평문 프레임 (안쪽 본문은 봉인). */
    const val HELLO = 1

    /** 그 밖의 control: 봉인된 CtlMessage. */
    const val CTL = 0
}

/** 논리 메시지의 머리. 프레임마다 같은 값이 실리고, 봉인 때 AAD가 된다. */
data class MessageMeta(
    val channel: SessionChannel,
    val kind: Int,
    val seq: Long,
    val resync: Boolean = false,
    val batchEnd: Boolean = false,
) {
    internal fun flags(): Int = (if (resync) Frame.RESYNC else 0) or (if (batchEnd) Frame.BATCH_END else 0)
}

/**
 * 세션 프레임 (01-architecture §4): `ver(1) | channel(1) | kind(1) | flags(1) | msgId(2) | seq(4) | 본문`.
 * 논리 메시지 하나(이미 봉인된 바이트)를 `mtu-3` 이하 프레임으로 나눈다. 봉인은 메시지 단위다 (ADR-006 S6 개정).
 */
object Frame {
    const val HEADER_BYTES = 10
    const val FIRST = 0b0001
    const val LAST = 0b0010
    const val RESYNC = 0b0100
    const val BATCH_END = 0b1000
    private const val ATT_OVERHEAD = 3

    /** 봉인할 때 쓰는 AAD: 버전·채널·kind·flags(FIRST/LAST 제외)·msgId·seq. */
    fun aad(version: Int, meta: MessageMeta, msgId: Int): ByteArray = header(version, meta, meta.flags(), msgId)

    fun split(version: Int, meta: MessageMeta, msgId: Int, body: ByteArray, mtu: Int): List<ByteArray> {
        val room = mtu - ATT_OVERHEAD - HEADER_BYTES
        require(room >= 1) { "MTU가 너무 작다: $mtu" }
        val starts = if (body.isEmpty()) listOf(0) else (body.indices step room).toList()
        return starts.mapIndexed { i, start ->
            var flags = meta.flags()
            if (i == 0) flags = flags or FIRST
            if (i == starts.lastIndex) flags = flags or LAST
            header(version, meta, flags, msgId) + body.copyOfRange(start, minOf(start + room, body.size))
        }
    }

    private fun header(version: Int, meta: MessageMeta, flags: Int, msgId: Int): ByteArray {
        val h = ByteArray(HEADER_BYTES)
        h[0] = version.toByte()
        h[1] = meta.channel.code.toByte()
        h[2] = meta.kind.toByte()
        h[3] = flags.toByte()
        h[4] = (msgId ushr 8).toByte()
        h[5] = msgId.toByte()
        for (i in 0 until 4) h[6 + i] = (meta.seq ushr (24 - 8 * i)).toByte()
        return h
    }

    /** 다 모은 논리 메시지. [body]는 아직 봉인된 상태다. */
    class Assembled(val meta: MessageMeta, val msgId: Int, val body: ByteArray)

    sealed interface Result {
        data object Partial : Result

        class Complete(val message: Assembled) : Result

        /** 형식·버전 오류. 링크 고장으로 본다. */
        data class Broken(val why: String) : Result
    }

    /** 한 링크(한 방향)의 프레임을 순서대로 모은다. */
    class Assembler(private val version: Int, private val maxBytes: Int) {
        private var current: Assembled? = null

        fun accept(frame: ByteArray): Result {
            if (frame.size < HEADER_BYTES) return Result.Broken("짧은 프레임")
            if ((frame[0].toInt() and 0xff) != version) return Result.Broken("버전 불일치") // I-8
            val channel = SessionChannel.of(frame[1].toInt() and 0xff) ?: return Result.Broken("모르는 채널")
            val kind = frame[2].toInt() and 0xff
            val flags = frame[3].toInt() and 0xff
            val msgId = ((frame[4].toInt() and 0xff) shl 8) or (frame[5].toInt() and 0xff)
            val seq = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (frame[6 + i].toLong() and 0xff) }
            val meta = MessageMeta(channel, kind, seq, resync = flags and RESYNC != 0, batchEnd = flags and BATCH_END != 0)
            val body = frame.copyOfRange(HEADER_BYTES, frame.size)
            val sofar = if (flags and FIRST != 0) {
                if (current != null) return Result.Broken("메시지 중간에 새 메시지")
                Assembled(meta, msgId, body)
            } else {
                val prev = current ?: return Result.Broken("FIRST 없는 프레임")
                if (prev.msgId != msgId || prev.meta != meta) return Result.Broken("머리가 바뀐 이어지는 프레임")
                Assembled(meta, msgId, prev.body + body)
            }
            if (sofar.body.size > maxBytes) {
                current = null
                return Result.Broken("메시지 상한 초과")
            }
            return if (flags and LAST != 0) {
                current = null
                Result.Complete(sofar)
            } else {
                current = sofar
                Result.Partial
            }
        }
    }
}
