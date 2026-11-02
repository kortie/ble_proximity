package dev.proximity.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RFC 원문의 테스트 벡터 (ADR-006). */
class S0_CryptoVectorsTest {
    private val box = BouncyCastleCryptoBox()

    @Test
    fun x25519Rfc7748Section61() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertContentEquals(alicePub, box.x25519PublicKey(alicePriv))
        assertContentEquals(bobPub, box.x25519PublicKey(bobPriv))
        assertContentEquals(shared, box.x25519(alicePriv, bobPub))
        assertContentEquals(shared, box.x25519(bobPriv, alicePub))
    }

    @Test
    fun x25519RejectsSmallOrderPoint() {
        val priv = box.x25519KeyPair().privateKey
        assertNull(box.x25519(priv, ByteArray(32)))
    }

    @Test
    fun generatedKeyPairsAgree() {
        val a = box.x25519KeyPair()
        val b = box.x25519KeyPair()
        assertEquals(32, a.publicKey.size)
        assertContentEquals(box.x25519(a.privateKey, b.publicKey), box.x25519(b.privateKey, a.publicKey))
    }

    @Test
    fun hkdfRfc5869TestCase1() {
        val okm = box.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun chacha20Poly1305Rfc8439Section282() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
            ).encodeToByteArray()
        val ciphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116",
        )
        val tag = hex("1ae10b594f09e26a7e902ecbd0600691")

        val sealed = box.seal(key, nonce, aad, plaintext)
        assertContentEquals(ciphertext + tag, sealed)
        assertContentEquals(plaintext, box.open(key, nonce, aad, sealed))
    }

    @Test
    fun openRejectsTampering() {
        val key = box.randomBytes(32)
        val nonce = box.randomBytes(12)
        val sealed = box.seal(key, nonce, byteArrayOf(1), "hello".encodeToByteArray())
        assertNotNull(box.open(key, nonce, byteArrayOf(1), sealed))

        val flipped = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(box.open(key, nonce, byteArrayOf(1), flipped), "암호문 변조")
        assertNull(box.open(key, nonce, byteArrayOf(2), sealed), "AAD 변조")
        assertNull(box.open(box.randomBytes(32), nonce, byteArrayOf(1), sealed), "다른 키")
        assertNull(box.open(key, nonce, byteArrayOf(1), sealed.copyOf(8)), "태그보다 짧음")
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
