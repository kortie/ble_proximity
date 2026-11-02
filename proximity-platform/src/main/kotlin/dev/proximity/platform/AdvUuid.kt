package dev.proximity.platform

import dev.proximity.core.ble.AdvCodec
import java.nio.ByteBuffer
import java.util.UUID

/**
 * core 광고 16바이트 ↔ 128비트 서비스 UUID 변환 (ADR-007). UUID 표기 순서가 바이트 순서다.
 * 프로토콜 판단(버전·플래그)은 하지 않는다 — 그건 core AdvCodec 몫이다.
 */
internal object AdvUuid {
    val PREFIX_UUID: UUID = fromBytes(AdvCodec.PREFIX.copyOf(AdvCodec.SIZE))
    val PREFIX_MASK_UUID: UUID = fromBytes(AdvCodec.PREFIX_MASK)

    fun fromBytes(bytes: ByteArray): UUID {
        require(bytes.size == AdvCodec.SIZE) { "UUID는 16바이트" }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun toBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(AdvCodec.SIZE).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    /** 광고의 서비스 UUID 중 우리 접두사를 가진 것만 바이트로. 칩셋이 필터 마스크를 무시해도 여기서 거른다. */
    fun payloadsFrom(uuids: List<UUID>): List<ByteArray> = uuids.map(::toBytes).filter(AdvCodec::hasPrefix)
}
