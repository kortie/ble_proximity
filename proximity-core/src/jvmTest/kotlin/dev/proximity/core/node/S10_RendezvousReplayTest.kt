package dev.proximity.core.node

import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.framing.Chunker
import dev.proximity.core.interfaces.RendezvousLink
import dev.proximity.core.sensing.Bump
import dev.proximity.core.session.RendezvousCrypto
import dev.proximity.core.session.SealedChannel
import dev.proximity.core.state.PeerRef
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.Screen
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.Accept
import dev.proximity.proto.ConfirmInfo
import dev.proximity.proto.ConfirmReq
import dev.proximity.proto.RdvMessage
import dev.proximity.proto.RdvSecret
import dev.proximity.proto.SealedBody
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeRendezvousNetwork
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 확인 메시지 재생·만료 (S10, ADR-006 개정): 받는 쪽 시계로 판정한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class S10_RendezvousReplayTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)
    private val rdv = FakeRendezvousNetwork()
    private val sessionNet = FakeSessionNetwork()
    private val crypto = BouncyCastleCryptoBox()

    /** 범프 흉내를 내며 프로토콜을 직접 말하는 공격 기기. */
    private inner class Attacker(private val scope: CoroutineScope) {
        val ble = air.device("E")
        val advertiser = ProximityAdvertiser(ble, 1, IdRotator(300_000, clock, FakeCryptoBox(99)))
        val transport = rdv.endpoint { advertiser.state.tempId }
        val keys = crypto.x25519KeyPair()
        val nonce = crypto.randomBytes(16)

        init {
            advertiser.start()
        }

        fun request() = RdvMessage.ADAPTER.encode(
            RdvMessage(confirm_req = ConfirmReq(keys.publicKey.toByteString(), nonce.toByteString(), advertiser.state.tempId)),
        )

        inner class Conn(val link: RendezvousLink) {
            val received = mutableListOf<RdvMessage>()
            private val asm = Chunker.Assembler(16 * 1024)

            init {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    link.inbound.collect { c -> asm.accept(c)?.let { received += RdvMessage.ADAPTER.decode(it) } }
                }
            }

            suspend fun send(bytes: ByteArray) = Chunker.split(bytes, link.mtu()).forEach { link.send(it) }

            var channel: SealedChannel? = null

            /** ACK를 받은 뒤 K_rdv를 만들고 CONFIRM_INFO를 보낸다 (정상 기기처럼). */
            suspend fun completeHandshake() {
                val ack = assertNotNull(received.firstNotNullOfOrNull { it.confirm_ack }, "ACK")
                val theirPub = ack.public_key.toByteArray()
                val k = RendezvousCrypto.derive(crypto, keys.privateKey, theirPub, keys.publicKey, theirPub, nonce, ack.nonce.toByteArray(), baseConfig.security.sasDigits)!!
                channel = SealedChannel(crypto, k.key, SealedChannel.INITIATOR, SealedChannel.RESPONDER, "ble-proximity rdv".encodeToByteArray())
                sealed(RdvSecret(confirm_info = ConfirmInfo(display_name = "E", game_id = "fake.game", game_version = 1, host_capable = true, host_bid = 1, join_nonce = crypto.randomBytes(16).toByteString())))
            }

            suspend fun sealed(secret: RdvSecret) =
                send(RdvMessage.ADAPTER.encode(RdvMessage(sealed_body = SealedBody(channel!!.seal(RdvSecret.ADAPTER.encode(secret)).toByteString()))))
        }

        suspend fun connect(victimTempId: Int) = Conn(transport.connect(PeerRef(victimTempId))!!)
    }

    private fun TestScope.scope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.victim(config: ProximityConfig = baseConfig): ProximityNode {
        lateinit var node: ProximityNode
        val platform = NodePlatform(air.device("V"), rdv.endpoint { node.tempId!! }, sessionNet.host(), { sessionNet.client() })
        node = ProximityNode(config, platform, crypto, clock, scope(), FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true)))
        node.setDisplayName("V")
        node.setRadio(RadioStatus.Ready)
        return node
    }

    private fun bumpBoth(v: ProximityNode, e: Attacker) {
        clock.advanceBy(1000)
        v.onBump(Bump(clock.now(), 3.0))
        e.advertiser.bump()
        clock.advanceBy(50) // 아직 Collecting: 남이 걸어온 확인을 받는다
    }

    @Test
    fun capturedConfirmRequestCannotBeReplayed() = runTest {
        val v = victim()
        val e = Attacker(scope())
        air.rebroadcast()
        bumpBoth(v, e)
        val request = e.request()
        val first = e.connect(v.tempId!!)
        first.send(request)
        assertNotNull(first.received.firstNotNullOfOrNull { it.confirm_ack }, "처음 요청은 받는다")
        val replay = e.connect(v.tempId!!)
        replay.send(request) // 같은 바이트 그대로
        assertNotNull(replay.received.firstNotNullOfOrNull { it.confirm_refuse }, "같은 nonce는 nonce_ttl_sec 동안 거절")
        assertTrue(replay.received.none { it.confirm_ack != null })
    }

    @Test
    fun lateMessagesOfAnExpiredConversationAreIgnored() = runTest {
        val short = baseConfig.copy(security = baseConfig.security.copy(nonceTtlSec = 5))
        val v = victim(short)
        val e = Attacker(scope())
        air.rebroadcast()
        bumpBoth(v, e)
        val c = e.connect(v.tempId!!)
        c.send(e.request())
        c.completeHandshake()
        assertEquals("E", assertIs<Screen.Confirm>(v.ui.value.screen).peerName)
        v.accept()
        clock.advanceBy(6_000) // nonce_ttl_sec(5초) 지남, confirming_ms(20초) 전
        c.sealed(RdvSecret(accept = Accept()))
        val s = v.ui.value.screen
        assertFalse(s is Screen.InSession || s is Screen.Joining, "만료된 대화의 ACCEPT로는 합류하지 않는다: $s")
        assertIs<Screen.Failed>(s, "만료된 대화는 버린다 (거절과 같게)")
    }

    @Test
    fun timelyAcceptStillWorks() = runTest {
        val v = victim()
        val e = Attacker(scope())
        air.rebroadcast()
        bumpBoth(v, e)
        val c = e.connect(v.tempId!!)
        c.send(e.request())
        c.completeHandshake()
        v.accept()
        c.sealed(RdvSecret(accept = Accept()))
        assertFalse(v.ui.value.screen is Screen.Confirm, "제때 온 ACCEPT는 받는다 (Matched 이후)")
    }

    @Test
    fun rendezvousConnectionsAreRateLimited() = runTest {
        val v = victim()
        val e = Attacker(scope())
        val max = baseConfig.security.maxConnectPerMin
        val links = (0 until max + 3).map { e.transport.connect(PeerRef(v.tempId!!))!! }
        val closed = links.count { l -> (l as dev.proximity.testing.FakeRendezvousLink).isOpen.not() }
        assertEquals(3, closed, "최근 60초 max_connect_per_min개를 넘는 Rendezvous 링크는 바로 닫는다")
        clock.advanceBy(60_000)
        val later = e.transport.connect(PeerRef(v.tempId!!))!! as dev.proximity.testing.FakeRendezvousLink
        assertTrue(later.isOpen, "1분이 지나면 다시 받는다")
    }
}
