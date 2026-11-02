package dev.proximity.core.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S2_AdvCodecTest {
    private val v = 1
    private val noSession = AdvertState(tempId = 0x0a0b0c0d, flags = AdvFlags(), sessionTag = 0)
    private val member = AdvertState(
        tempId = -2,
        flags = AdvFlags(inSession = true, accepting = true, bumpSeq = 2),
        sessionTag = 0x7f3a1b2c,
    )
    private val host = AdvertState(
        tempId = 0x11223344,
        flags = AdvFlags(inSession = true, isHost = true, accepting = false, busy = true, bumpSeq = 3),
        sessionTag = -1,
    )

    @Test
    fun roundTripForAllThreeForms() {
        listOf(noSession, member, host).forEach { state ->
            assertEquals(state, AdvCodec.decode(v, AdvCodec.encode(v, state)))
        }
    }

    @Test
    fun everyByteIsAccountedFor() {
        // I-7: 송출 바이트는 접두사 + ver + tempId + flags + sessionTag 뿐이다. 다른 식별자가 들어갈 자리가 없다.
        val bytes = AdvCodec.encode(v, host)
        assertEquals(16, bytes.size)
        assertContentEquals(AdvCodec.PREFIX, bytes.copyOfRange(0, 6))
        assertEquals(v.toByte(), bytes[6])
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), bytes.copyOfRange(7, 11))
        assertEquals(0b0011_1011.toByte(), bytes[11], "inSession·isHost·busy·bumpSeq=3, accepting=0")
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), bytes.copyOfRange(12, 16))
    }

    @Test
    fun noSessionSendsZeroTag() {
        val bytes = AdvCodec.encode(v, noSession.copy(sessionTag = 12345))
        assertContentEquals(ByteArray(4), bytes.copyOfRange(12, 16), "세션이 없으면 sessionTag 자리는 0")
        assertEquals(0, AdvCodec.decode(v, bytes)!!.sessionTag)
    }

    @Test
    fun noForeignIdentifiersInPayload() {
        // I-7: 계정 ID·MAC·기기 ID처럼 보이는 값이 입력 어디에도 없으므로 출력에도 나타날 수 없다 (구조로 보장).
        // 여기서는 대표 바이트 열을 넣어 우연히라도 포함되지 않는지 본다.
        val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val bytes = AdvCodec.encode(v, member)
        assertTrue(bytes.toList().windowed(mac.size).none { it == mac.toList() })
    }

    @Test
    fun rejectsVersionMismatch() {
        // I-8
        assertNull(AdvCodec.decode(2, AdvCodec.encode(1, member)))
    }

    @Test
    fun rejectsWrongLengthPrefixAndContradictions() {
        val good = AdvCodec.encode(v, member)
        assertNull(AdvCodec.decode(v, good.copyOf(15)))
        assertNull(AdvCodec.decode(v, good + byteArrayOf(0)))
        assertNull(AdvCodec.decode(v, good.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        val hostWithoutSession = good.copyOf().also { it[11] = 0b0000_0010 }
        assertNull(AdvCodec.decode(v, hostWithoutSession), "isHost인데 inSession 아님")
    }

    @Test
    fun reservedBitsAreIgnored() {
        val bytes = AdvCodec.encode(v, member).also { it[11] = (it[11].toInt() or 0b1100_0000).toByte() }
        assertEquals(member, AdvCodec.decode(v, bytes))
    }

    @Test
    fun bumpSeqWrapsInTwoBits() {
        assertEquals(0, AdvFlags(bumpSeq = 3).nextBump().bumpSeq)
        assertEquals(1, AdvFlags(bumpSeq = 0).nextBump().bumpSeq)
    }

    @Test
    fun advertisingFitsPlatformLimits() {
        assertEquals(21, AdvCodec.ANDROID_LEGACY_ADVERTISING_BYTES)
        assertTrue(AdvCodec.ANDROID_LEGACY_ADVERTISING_BYTES <= 31, "Android 레거시 광고 31바이트")
        assertTrue(AdvCodec.IOS_ADVERTISING_BYTES <= 28, "iOS 포그라운드 광고 약 28바이트")
    }
}
