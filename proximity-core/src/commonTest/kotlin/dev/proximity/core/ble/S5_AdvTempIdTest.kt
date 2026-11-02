package dev.proximity.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S5_AdvTempIdTest {
    @Test
    fun readsTempIdRegardlessOfVersionButNeedsOurFormat() {
        val bytes = AdvCodec.encode(9, AdvertState(0x0badcafe, AdvFlags(), 0))
        assertEquals(0x0badcafe, AdvCodec.tempIdOf(bytes), "버전이 달라도 기기 짝짓기용 tempId는 읽는다")
        assertNull(AdvCodec.tempIdOf(bytes.copyOf(15)))
        assertNull(AdvCodec.tempIdOf(bytes.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun hostSessionTagOnlyFromHostAdvertisements() {
        val host = AdvCodec.encode(1, AdvertState(1, AdvFlags(inSession = true, isHost = true), 0x7f3a))
        val member = AdvCodec.encode(1, AdvertState(2, AdvFlags(inSession = true), 0x7f3a))
        assertEquals(0x7f3a, AdvCodec.hostSessionTagOf(host))
        assertNull(AdvCodec.hostSessionTagOf(member), "멤버 광고로는 호스트를 찾지 않는다")
    }
}
