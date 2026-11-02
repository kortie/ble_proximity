package dev.proximity.crypto

import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.X25519KeyPair
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * BouncyCastle 경량 API 구현 (ADR-006). JCA 공급자를 쓰지 않아 Android 버전과 무관하게 같은 동작을 한다.
 */
class BouncyCastleCryptoBox(private val random: SecureRandom = SecureRandom()) : CryptoBox {
    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun x25519KeyPair(): X25519KeyPair {
        val private = X25519PrivateKeyParameters(random)
        return X25519KeyPair(private.encoded, private.generatePublicKey().encoded)
    }

    /** 개인키에서 공개키를 계산한다 (테스트 벡터 검증용). */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    override fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray? {
        val agreement = X25519Agreement().apply { init(X25519PrivateKeyParameters(privateKey, 0)) }
        val shared = ByteArray(agreement.agreementSize)
        return try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
            shared
        } catch (_: IllegalStateException) {
            // 상대 공개키가 소수 위수 점이라 결과가 전부 0이다 (RFC 7748 §6.1).
            null
        }
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = aead(forEncryption = true, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_BYTES) return null
        val cipher = aead(forEncryption = false, key, nonce, aad)
        val out = ByteArray(cipher.getOutputSize(sealed.size))
        return try {
            val written = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            cipher.doFinal(out, written)
            out
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    private fun aead(forEncryption: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): ChaCha20Poly1305 =
        ChaCha20Poly1305().apply { init(forEncryption, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad)) }

    private companion object {
        const val TAG_BYTES = 16
    }
}
