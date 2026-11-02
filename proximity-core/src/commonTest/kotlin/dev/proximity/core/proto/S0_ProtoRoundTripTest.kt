package dev.proximity.core.proto

import dev.proximity.proto.ConfirmInfo
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.Hello
import dev.proximity.proto.HelloBody
import dev.proximity.proto.RdvMessage
import dev.proximity.proto.RdvSecret
import dev.proximity.proto.Role
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class S0_ProtoRoundTripTest {
    @Test
    fun rdvSecretRoundTrip() {
        val original = RdvSecret(
            confirm_info = ConfirmInfo(
                role = Role.ROLE_SPECTATOR,
                display_name = "지영",
                game_id = "example.game",
                game_version = 3,
                in_session = true,
                session_tag = 0x7f3a1b2c,
                host_capable = true,
                host_bid = -1,
                join_nonce = ByteArray(16) { it.toByte() }.toByteString(),
            ),
        )
        assertEquals(original, RdvSecret.ADAPTER.decode(RdvSecret.ADAPTER.encode(original)))
    }

    @Test
    fun ctlHelloRoundTrip() {
        val body = HelloBody(resume = HelloBody.Resume(participant_id = 4, last_seq = 1234567890123L))
        val original = CtlMessage(
            hello = Hello(
                client_public_key = ByteArray(32) { 9 }.toByteString(),
                sealed_body = HelloBody.ADAPTER.encode(body).toByteString(),
            ),
        )
        val decoded = CtlMessage.ADAPTER.decode(CtlMessage.ADAPTER.encode(original))
        assertEquals(original, decoded)
        assertEquals(body, HelloBody.ADAPTER.decode(decoded.hello!!.sealed_body))
    }

    @Test
    fun unknownFieldsAreTolerated() {
        // 새 버전이 필드를 추가해도 이전 버전이 읽을 수 있다 (ADR-005 호환 규칙).
        val withExtra = RdvMessage.ADAPTER.encode(RdvMessage()) + byteArrayOf(0xF8.toByte(), 0x07, 0x01)
        assertEquals(RdvMessage().confirm_req, RdvMessage.ADAPTER.decode(withExtra).confirm_req)
    }
}
