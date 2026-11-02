package dev.proximity.core.router

import dev.proximity.crypto.BouncyCastleCryptoBox
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S6_FramerTest {
    private val crypto = BouncyCastleCryptoBox()
    private val v = 1

    private fun channels(): Pair<LinkCrypto.LinkChannels, LinkCrypto.LinkChannels> {
        val host = crypto.x25519KeyPair()
        val client = crypto.x25519KeyPair()
        val h = LinkCrypto.derive(crypto, host.privateKey, client.publicKey, client.publicKey, host.publicKey, 42, iAmHost = true)!!
        val c = LinkCrypto.derive(crypto, client.privateKey, host.publicKey, client.publicKey, host.publicKey, 42, iAmHost = false)!!
        return h to c
    }

    @Test
    fun sealedMessagesRoundTripAcrossMtus() {
        for (mtu in listOf(23, 185, 517)) for (size in listOf(0, 1024, 4096)) {
            val (host, client) = channels()
            val meta = MessageMeta(SessionChannel.Game, 3, seq = 77, batchEnd = true)
            val payload = ByteArray(size) { it.toByte() }
            val sealed = host.send.seal(payload, Frame.aad(v, meta, 9))
            val frames = Frame.split(v, meta, 9, sealed, mtu)
            assertTrue(frames.all { it.size <= mtu - 3 }, "mtu $mtu")
            val asm = Frame.Assembler(v, 16 * 1024)
            val done = frames.map(asm::accept).last()
            val msg = assertIs<Frame.Result.Complete>(done).message
            assertEquals(meta, msg.meta)
            assertContentEquals(payload, client.receive.open(msg.body, Frame.aad(v, msg.meta, msg.msgId)))
        }
    }

    @Test
    fun versionMismatchAndOversizeAreBroken() {
        val meta = MessageMeta(SessionChannel.Social, 1, 1, batchEnd = true)
        val frames = Frame.split(2, meta, 0, ByteArray(10), 185)
        assertIs<Frame.Result.Broken>(Frame.Assembler(1, 1024).accept(frames.single()), "I-8")
        val big = Frame.split(1, meta, 0, ByteArray(2000), 185)
        assertTrue(big.map(Frame.Assembler(1, 1024)::accept).any { it is Frame.Result.Broken })
    }

    @Test
    fun headerTamperingFailsAuthentication() {
        val (host, client) = channels()
        val meta = MessageMeta(SessionChannel.Game, 1, seq = 5, batchEnd = true)
        val sealed = host.send.seal("x".encodeToByteArray(), Frame.aad(v, meta, 0))
        val forged = meta.copy(seq = 6)
        assertNull(client.receive.open(sealed, Frame.aad(v, forged, 0)), "머리(seq)를 바꾸면 열리지 않는다")
    }

    @Test
    fun strayContinuationIsBroken() {
        val frames = Frame.split(1, MessageMeta(SessionChannel.Social, 1, 1, batchEnd = true), 0, ByteArray(500), 23)
        assertIs<Frame.Result.Broken>(Frame.Assembler(1, 4096).accept(frames[1]))
    }
}
