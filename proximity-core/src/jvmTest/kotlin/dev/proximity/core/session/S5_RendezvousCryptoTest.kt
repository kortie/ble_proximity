package dev.proximity.core.session

import dev.proximity.crypto.BouncyCastleCryptoBox
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S5_RendezvousCryptoTest {
    private val crypto = BouncyCastleCryptoBox()

    private class Side(val pub: ByteArray, val priv: ByteArray, val nonce: ByteArray)

    private fun side() = crypto.x25519KeyPair().let { Side(it.publicKey, it.privateKey, crypto.randomBytes(16)) }

    @Test
    fun bothSidesDeriveTheSameKeyAndSas() {
        val a = side()
        val b = side()
        val ka = RendezvousCrypto.derive(crypto, a.priv, b.pub, a.pub, b.pub, a.nonce, b.nonce, sasDigits = 2)!!
        val kb = RendezvousCrypto.derive(crypto, b.priv, a.pub, a.pub, b.pub, a.nonce, b.nonce, sasDigits = 2)!!
        assertContentEquals(ka.key, kb.key)
        assertEquals(ka.sas, kb.sas)
        assertEquals(2, ka.sas.length)
        assertEquals(4, RendezvousCrypto.derive(crypto, a.priv, b.pub, a.pub, b.pub, a.nonce, b.nonce, sasDigits = 4)!!.sas.length)
    }

    @Test
    fun manInTheMiddleProducesDifferentKeysAndMostlyDifferentSas() {
        var sameSas = 0
        repeat(TRIALS) {
            val a = side()
            val b = side()
            val m1 = side() // 중간자가 B에게 보낸 키
            val m2 = side() // 중간자가 A에게 보낸 키
            val ka = RendezvousCrypto.derive(crypto, a.priv, m2.pub, a.pub, m2.pub, a.nonce, b.nonce, 2)!!
            val kb = RendezvousCrypto.derive(crypto, b.priv, m1.pub, m1.pub, b.pub, a.nonce, b.nonce, 2)!!
            assertFalse(ka.key.contentEquals(kb.key))
            if (ka.sas == kb.sas) sameSas++
        }
        // 2자리 SAS는 우연히 같을 확률 1/100 (ADR-006). 500회에서 넉넉히 5% 미만이어야 한다
        assertTrue(sameSas < TRIALS * 5 / 100, "SAS가 우연히 같은 경우 $sameSas/$TRIALS")
    }

    @Test
    fun smallOrderKeyIsRejected() {
        val a = side()
        assertNull(RendezvousCrypto.derive(crypto, a.priv, ByteArray(32), a.pub, ByteArray(32), a.nonce, a.nonce, 2))
    }

    @Test
    fun sealedChannelHidesPlaintextAndRejectsTamperReplayAndReorder() {
        val key = crypto.randomBytes(32)
        val sender = SealedChannel(crypto, key, SealedChannel.INITIATOR, SealedChannel.RESPONDER, "rdv".encodeToByteArray())
        val receiver = SealedChannel(crypto, key, SealedChannel.RESPONDER, SealedChannel.INITIATOR, "rdv".encodeToByteArray())
        val name = "지영-닉네임".encodeToByteArray()
        val c1 = sender.seal(name)
        val c2 = sender.seal("둘째".encodeToByteArray())
        assertFalse(c1.toList().windowed(name.size).any { it == name.toList() }, "평문이 보이면 안 된다")

        assertNull(receiver.open(c2), "순서 바꿈")
        assertNull(receiver.open(c1.copyOf().also { it[0] = (it[0] + 1).toByte() }), "변조")
        assertContentEquals(name, assertNotNull(receiver.open(c1)))
        assertNull(receiver.open(c1), "재생")
        assertNotNull(receiver.open(c2))
        val reverse = SealedChannel(crypto, key, SealedChannel.INITIATOR, SealedChannel.RESPONDER, "rdv".encodeToByteArray())
        assertNull(reverse.open(c1), "방향이 다르면 열리지 않는다")
    }

    private companion object {
        const val TRIALS = 500
    }
}
