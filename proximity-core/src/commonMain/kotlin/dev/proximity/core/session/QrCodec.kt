package dev.proximity.core.session

import dev.proximity.proto.QrTicket
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** QR 한 장의 내용 (04 §5.2.1). [joinNonce]는 HELLO에 그대로 쓴다. */
class QrJoin(val ticket: JoinTicket, val joinNonce: ByteArray, val gameId: String) {
    val sessionTag: Int get() = ticket.sessionTag
}

/** "BP1." + base64url(QrTicket). 형식·버전·gameId가 맞지 않으면 null. */
object QrCodec {
    private const val PREFIX = "BP1."

    fun encode(q: QrJoin): String {
        val msg = QrTicket(
            session_tag = q.ticket.sessionTag,
            token = q.ticket.token.toByteString(),
            join_nonce = q.joinNonce.toByteString(),
            host_session_public_key = q.ticket.hostSessionPublicKey.toByteString(),
            expires_at_ms = q.ticket.expiresAtMs,
            game_id = q.gameId,
        )
        return PREFIX + QrTicket.ADAPTER.encode(msg).toByteString().base64Url().trimEnd('=')
    }

    fun decode(text: String, expectedGameId: String): QrJoin? {
        if (!text.startsWith(PREFIX)) return null
        val bytes = text.removePrefix(PREFIX).decodeBase64() ?: return null
        val msg = QrTicket.ADAPTER.runCatching { decode(bytes) }.getOrNull() ?: return null
        if (msg.game_id != expectedGameId || msg.session_tag == 0 || msg.token.size == 0 || msg.host_session_public_key.size != KEY_BYTES) return null
        val ticket = JoinTicket(msg.session_tag, msg.token.toByteArray(), msg.host_session_public_key.toByteArray(), msg.expires_at_ms)
        return QrJoin(ticket, msg.join_nonce.toByteArray(), msg.game_id)
    }

    private const val KEY_BYTES = 32
}
