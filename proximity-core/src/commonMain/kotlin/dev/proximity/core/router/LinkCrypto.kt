package dev.proximity.core.router

import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.session.SealedChannel

/**
 * 세션 링크 키 (ADR-006 4): X25519(호스트 세션 키, 클라이언트 임시 키) → HKDF.
 * salt = sessionTag, info = 라벨 ‖ 클라이언트 공개키 ‖ 호스트 공개키. 방향마다 SealedChannel 카운터가 따로 오른다.
 */
object LinkCrypto {
    private val LABEL = "ble-proximity link v1".encodeToByteArray()
    private val AAD = "ble-proximity session".encodeToByteArray()
    private val SAS_LABEL = "ble-proximity link sas".encodeToByteArray()

    const val DEFAULT_MESSAGE_LIMIT = SealedChannel.DEFAULT_LIMIT

    /** 방향마다 보낼 수 있는 메시지 수 (2^40). 시험에서만 줄인다. */
    internal var messageLimit = DEFAULT_MESSAGE_LIMIT

    /** HELLO 안쪽 본문의 추가 AAD. */
    val HELLO_AAD = "hello".encodeToByteArray()

    /** [sas]: 링크 키에서 만든 확인 코드. 세션 코드 합류에서 양쪽 화면에 띄운다 (04 §5.2.2). */
    class LinkChannels(val send: SealedChannel, val receive: SealedChannel, val sas: String)

    fun derive(
        crypto: CryptoBox,
        myPrivateKey: ByteArray,
        theirPublicKey: ByteArray,
        clientPublicKey: ByteArray,
        hostPublicKey: ByteArray,
        sessionTag: Int,
        iAmHost: Boolean,
        sasDigits: Int = DEFAULT_SAS_DIGITS,
    ): LinkChannels? {
        val shared = crypto.x25519(myPrivateKey, theirPublicKey) ?: return null
        val salt = ByteArray(4) { (sessionTag ushr (24 - 8 * it)).toByte() }
        val key = crypto.hkdfSha256(shared, salt, LABEL + clientPublicKey + hostPublicKey, KEY_BYTES)
        val (send, receive) = if (iAmHost) SealedChannel.HOST to SealedChannel.CLIENT else SealedChannel.CLIENT to SealedChannel.HOST
        // 송신·수신 카운터가 따로 오르도록 방향마다 채널을 둔다
        val sasBytes = crypto.hkdfSha256(shared, salt, SAS_LABEL + clientPublicKey + hostPublicKey, SAS_BYTES)
        val material = sasBytes.fold(0UL) { acc, b -> (acc shl 8) or (b.toULong() and 0xffUL) }
        var modulus = 1UL
        repeat(sasDigits) { modulus *= 10UL }
        return LinkChannels(
            send = SealedChannel(crypto, key, send, receive, AAD, messageLimit),
            receive = SealedChannel(crypto, key, send, receive, AAD, messageLimit),
            sas = (material % modulus).toString().padStart(sasDigits, '0'),
        )
    }

    private const val KEY_BYTES = 32
    private const val SAS_BYTES = 4
    private const val DEFAULT_SAS_DIGITS = 2
}
