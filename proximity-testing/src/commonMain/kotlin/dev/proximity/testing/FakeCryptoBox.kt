package dev.proximity.testing

import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.X25519KeyPair
import kotlin.random.Random

/**
 * 시드 고정 난수만 주는 CryptoBox. 난수를 쓰는 로직(IdRotator 등)을 결정적으로 테스트할 때 쓴다.
 * 암호 연산은 하지 않는다 — 그런 테스트는 :proximity-crypto의 BouncyCastleCryptoBox로 한다.
 */
class FakeCryptoBox(seed: Int = 0) : CryptoBox {
    private val random = Random(seed)

    /** 다음 randomBytes 호출이 돌려줄 값을 미리 정한다 (지정한 순서대로 소비). */
    val scripted = ArrayDeque<ByteArray>()

    override fun randomBytes(size: Int): ByteArray {
        val next = scripted.removeFirstOrNull() ?: return random.nextBytes(size)
        require(next.size == size) { "scripted 값 길이 ${next.size} ≠ 요청 $size" }
        return next
    }

    override fun x25519KeyPair(): X25519KeyPair = unsupported()

    override fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray? = unsupported()

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray = unsupported()

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = unsupported()

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeCryptoBox는 난수만 지원한다. 암호 연산은 BouncyCastleCryptoBox로 테스트한다")
}
