package dev.proximity.core.session

import dev.proximity.core.interfaces.CryptoBox

/** Rendezvous 키 합의 결과 (ADR-006): 암호화 키 K_rdv와 확인 코드(SAS). */
class RendezvousKeys(val key: ByteArray, val sas: String)

/**
 * ADR-006 1·2: X25519 공유 비밀 → HKDF-SHA256 → K_rdv(32) + SAS 재료(8).
 * salt = 요청자 nonce ‖ 응답자 nonce, info = 라벨 ‖ 요청자 공개키 ‖ 응답자 공개키.
 * 공개키를 info에 묶으므로 중간자가 키를 바꿔치기하면 두 기기의 키와 SAS가 달라진다.
 */
object RendezvousCrypto {
    private val LABEL = "ble-proximity rdv v1".encodeToByteArray()
    private const val KEY_BYTES = 32
    private const val SAS_BYTES = 8

    fun derive(
        crypto: CryptoBox,
        myPrivateKey: ByteArray,
        theirPublicKey: ByteArray,
        initiatorPublicKey: ByteArray,
        responderPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        sasDigits: Int,
    ): RendezvousKeys? {
        require(sasDigits in 1..18) { "sas_digits는 1..18" }
        val shared = crypto.x25519(myPrivateKey, theirPublicKey) ?: return null
        val okm = crypto.hkdfSha256(
            ikm = shared,
            salt = initiatorNonce + responderNonce,
            info = LABEL + initiatorPublicKey + responderPublicKey,
            length = KEY_BYTES + SAS_BYTES,
        )
        val material = okm.copyOfRange(KEY_BYTES, KEY_BYTES + SAS_BYTES).fold(0UL) { acc, b -> (acc shl 8) or b.toUByte().toULong() }
        var modulus = 1UL
        repeat(sasDigits) { modulus *= 10UL }
        return RendezvousKeys(okm.copyOfRange(0, KEY_BYTES), (material % modulus).toString().padStart(sasDigits, '0'))
    }
}

/**
 * 한 링크 위의 ChaCha20-Poly1305 채널. nonce = 방향 1바이트 + 0 3바이트 + 카운터 8바이트(big-endian).
 * 방향마다 카운터가 0부터 1씩 오르고, 받는 쪽은 기대 카운터만 연다 — 재생·순서 바꿈은 실패한다.
 */
class SealedChannel(
    private val crypto: CryptoBox,
    private val key: ByteArray,
    private val sendDirection: Byte,
    private val receiveDirection: Byte,
    private val aad: ByteArray,
    /** 이 키로 보낼 수 있는 메시지 수. 다 쓰면 [exhausted] — 링크를 다시 만들어야 한다 (nonce 재사용 방지, S10). */
    private val limit: Long = DEFAULT_LIMIT,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    val exhausted: Boolean get() = sendCounter >= limit

    /** [extraAad]: 메시지마다 덧붙이는 인증 데이터 (세션 프레임 머리, S6). */
    fun seal(plaintext: ByteArray, extraAad: ByteArray = EMPTY): ByteArray {
        check(!exhausted) { "nonce 카운터 상한" }
        return crypto.seal(key, nonce(sendDirection, sendCounter++), aad + extraAad, plaintext)
    }

    fun open(sealed: ByteArray, extraAad: ByteArray = EMPTY): ByteArray? {
        val opened = crypto.open(key, nonce(receiveDirection, receiveCounter), aad + extraAad, sealed) ?: return null
        receiveCounter++
        return opened
    }

    private fun nonce(direction: Byte, counter: Long): ByteArray {
        val n = ByteArray(NONCE_BYTES)
        n[0] = direction
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    companion object {
        const val NONCE_BYTES = 12
        const val DEFAULT_LIMIT = 1L shl 40
        const val INITIATOR: Byte = 1
        const val RESPONDER: Byte = 2
        const val CLIENT: Byte = 3
        const val HOST: Byte = 4
        private val EMPTY = ByteArray(0)
    }
}
