package dev.proximity.platform

import dev.proximity.core.ble.AdvCodec
import dev.proximity.core.ble.AdvFlags
import dev.proximity.core.ble.AdvertState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class S2_AdvUuidTest {
    @Test
    fun bytesAndUuidRoundTrip() {
        val bytes = AdvCodec.encode(1, AdvertState(0x11223344, AdvFlags(inSession = true, bumpSeq = 2), 0x7f3a1b2c))
        val uuid = AdvUuid.fromBytes(bytes)
        assertEquals("b1e7a6c0-9d4e-0111-2233-44217f3a1b2c", uuid.toString(), "UUID 표기 순서 = 바이트 순서 (ADR-007)")
        assertContentEquals(bytes, AdvUuid.toBytes(uuid))
    }

    @Test
    fun scanFilterUsesPrefixAndMask() {
        assertEquals("b1e7a6c0-9d4e-0000-0000-000000000000", AdvUuid.PREFIX_UUID.toString())
        assertEquals("ffffffff-ffff-0000-0000-000000000000", AdvUuid.PREFIX_MASK_UUID.toString())
    }

    @Test
    fun onlyPrefixedUuidsBecomePayloads() {
        val ours = AdvUuid.fromBytes(AdvCodec.encode(1, AdvertState(5, AdvFlags(), 0)))
        val other = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val payloads = AdvUuid.payloadsFrom(listOf(other, ours))
        assertEquals(1, payloads.size, "마스크를 무시하는 칩셋이어도 접두사가 다른 UUID는 버린다")
        assertContentEquals(AdvUuid.toBytes(ours), payloads.single())
    }
}
