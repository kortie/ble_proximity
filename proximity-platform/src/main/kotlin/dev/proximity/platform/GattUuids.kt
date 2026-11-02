package dev.proximity.platform

import java.util.UUID

/**
 * 양방향 GATT 서비스 UUID (ADR-008, 04-session-flows §6). 광고 UUID와 접두사를 공유한다.
 * 양방향 서비스 = 쓰기 특성(클라이언트 → 서버) + 알림 특성(서버 → 클라이언트) + 선택적으로 읽기 특성.
 */
internal object GattUuids {
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val RENDEZVOUS = DuplexService(
        service = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000001"),
        write = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000002"),
        notify = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000003"),
        read = null,
    )

    /** 세션 서비스 (호스트만): SESSION_INFO(read) · UPLINK(write) · DOWNLINK(notify), 04-session-flows §6. */
    val SESSION = DuplexService(
        service = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000011"),
        write = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000012"),
        notify = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000013"),
        read = UUID.fromString("b1e7a6c0-9d4e-4000-8000-000000000014"),
    )
}

internal data class DuplexService(val service: UUID, val write: UUID, val notify: UUID, val read: UUID?)
