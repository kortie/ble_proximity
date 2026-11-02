package dev.proximity.core.interfaces

/**
 * 암호 기본 요소 (ADR-006). 프로토콜 로직은 core에 있고, 이 인터페이스는 계산만 한다.
 * 구현: :proximity-crypto (BouncyCastle). 키·비밀 값은 로그에 남기지 않는다.
 */
interface CryptoBox {
    fun randomBytes(size: Int): ByteArray

    fun x25519KeyPair(): X25519KeyPair

    /** 공유 비밀 32바이트. 상대 공개키가 소수 위수 점이면 null (RFC 7748 §6.1 all-zero 검사). */
    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray?

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** ChaCha20-Poly1305. 결과 = 암호문 ‖ 16바이트 태그. nonce는 12바이트, 같은 키로 재사용 금지. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** 태그 검증에 실패하면 null. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
}

class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
