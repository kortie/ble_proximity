package dev.proximity.core.ble

/** 광고 플래그 (ADR-007 바이트 11). */
data class AdvFlags(
    val inSession: Boolean = false,
    val isHost: Boolean = false,
    val accepting: Boolean = false,
    val busy: Boolean = false,
    /** 범프할 때마다 1 증가하는 2비트 카운터 (04-session-flows §2). */
    val bumpSeq: Int = 0,
) {
    init {
        require(bumpSeq in 0..BUMP_MASK) { "bumpSeq는 0..3" }
    }

    fun nextBump(): AdvFlags = copy(bumpSeq = (bumpSeq + 1) and BUMP_MASK)

    internal companion object {
        const val BUMP_MASK = 0b11
    }
}

/** 내가 광고하는 내용. [sessionTag]는 세션에 있을 때만 의미가 있다 (아니면 0으로 보낸다). */
data class AdvertState(val tempId: Int, val flags: AdvFlags, val sessionTag: Int)

/**
 * 광고 형식 (ADR-007): 128비트 서비스 UUID 16바이트 = 접두사 6 + ver 1 + tempId 4 + flags 1 + sessionTag 4.
 * 바이트 순서는 UUID 표기 순서(big-endian)다. 플랫폼 코드는 이 16바이트를 UUID로 바꿔 광고할 뿐이다.
 */
object AdvCodec {
    const val SIZE = 16

    /** 고정 접두사 `b1e7a6c0-9d4e`. 스캔 필터에 쓴다. */
    val PREFIX: ByteArray = byteArrayOf(0xb1.toByte(), 0xe7.toByte(), 0xa6.toByte(), 0xc0.toByte(), 0x9d.toByte(), 0x4e)

    /** 스캔 필터 마스크: 접두사 6바이트만 비교한다. */
    val PREFIX_MASK: ByteArray = ByteArray(SIZE) { if (it < PREFIX.size) 0xff.toByte() else 0 }

    /** Android 연결 가능 레거시 광고 크기: Flags AD 3 + 128비트 UUID AD (길이·종류 2 + 16). */
    const val ANDROID_LEGACY_ADVERTISING_BYTES = 3 + 2 + SIZE

    /** iOS 광고 크기(향후): 서비스 UUID AD만. */
    const val IOS_ADVERTISING_BYTES = 2 + SIZE

    private const val VERSION_AT = 6
    private const val TEMP_ID_AT = 7
    private const val FLAGS_AT = 11
    private const val TAG_AT = 12

    fun encode(version: Int, state: AdvertState): ByteArray {
        require(version in 0..0xff) { "version은 1바이트" }
        val out = ByteArray(SIZE)
        PREFIX.copyInto(out)
        out[VERSION_AT] = version.toByte()
        putInt(out, TEMP_ID_AT, state.tempId)
        out[FLAGS_AT] = flagsByte(state.flags)
        putInt(out, TAG_AT, if (state.flags.inSession) state.sessionTag else 0)
        return out
    }

    /** 거절하면 null: 길이·접두사·버전 불일치(I-8), 모순된 플래그. */
    fun decode(version: Int, bytes: ByteArray): AdvertState? {
        if (bytes.size != SIZE || !hasPrefix(bytes)) return null
        if ((bytes[VERSION_AT].toInt() and 0xff) != version) return null
        val f = bytes[FLAGS_AT].toInt() and 0xff
        val flags = AdvFlags(
            inSession = f and 0b0001 != 0,
            isHost = f and 0b0010 != 0,
            accepting = f and 0b0100 != 0,
            busy = f and 0b1000 != 0,
            bumpSeq = (f shr 4) and AdvFlags.BUMP_MASK,
        )
        if (flags.isHost && !flags.inSession) return null
        val tag = if (flags.inSession) getInt(bytes, TAG_AT) else 0
        return AdvertState(getInt(bytes, TEMP_ID_AT), flags, tag)
    }

    /** 버전과 무관하게 tempId만 읽는다 (플랫폼이 광고 기기와 tempId를 짝짓는 용도, ADR-008). 형식이 아니면 null. */
    fun tempIdOf(bytes: ByteArray): Int? = if (bytes.size == SIZE && hasPrefix(bytes)) getInt(bytes, TEMP_ID_AT) else null

    /** 호스트 광고면 sessionTag (클라이언트가 호스트 기기를 찾는 용도, 04-session-flows §5). 아니면 null. */
    fun hostSessionTagOf(bytes: ByteArray): Int? {
        if (bytes.size != SIZE || !hasPrefix(bytes)) return null
        val f = bytes[FLAGS_AT].toInt()
        return if (f and 0b0011 == 0b0011) getInt(bytes, TAG_AT) else null
    }

    fun hasPrefix(bytes: ByteArray): Boolean =
        bytes.size >= PREFIX.size && PREFIX.indices.all { bytes[it] == PREFIX[it] }

    private fun flagsByte(flags: AdvFlags): Byte {
        var f = flags.bumpSeq shl 4
        if (flags.inSession) f = f or 0b0001
        if (flags.isHost) f = f or 0b0010
        if (flags.accepting) f = f or 0b0100
        if (flags.busy) f = f or 0b1000
        return f.toByte()
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) out[at + i] = (value ushr (24 - 8 * i)).toByte()
    }

    private fun getInt(bytes: ByteArray, at: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xff) }
}
