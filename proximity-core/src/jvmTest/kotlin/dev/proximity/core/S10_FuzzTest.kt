package dev.proximity.core

import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.Role
import dev.proximity.core.ble.AdvCodec
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.framing.Chunker
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.host.GameHost
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.router.Frame
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.Outgoing
import dev.proximity.core.router.Recipients
import dev.proximity.core.router.SessionChannel
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.QrCodec
import dev.proximity.core.session.SessionManager
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.GameAction
import dev.proximity.proto.HelloBody
import dev.proximity.proto.RdvMessage
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeGameModule
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 퍼징: 잘못된 입력에 크래시 0, 그 뒤에도 정상 동작 (S10). 시드 고정이라 결정적이다. */
@OptIn(ExperimentalCoroutinesApi::class)
class S10_FuzzTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val random = Random(20261030)

    private fun junk(max: Int = 300) = random.nextBytes(random.nextInt(0, max))

    @Test
    fun decodersNeverThrow() {
        repeat(5_000) {
            AdvCodec.decode(1, junk(40))
            AdvCodec.tempIdOf(junk(40))
            AdvCodec.hostSessionTagOf(junk(40))
            Frame.Assembler(1, 4096).accept(junk())
            Chunker.Assembler(4096).accept(junk())
            QrCodec.decode("BP1." + junk(120).toByteString().base64Url(), "fake.game")
            QrCodec.decode(junk(60).decodeToString(), "fake.game")
            // core가 쓰는 디코더는 모두 runCatching으로 감싼다: 예외가 새지 않는지만 본다
            for (adapter in listOf(CtlMessage.ADAPTER, RdvMessage.ADAPTER, GameAction.ADAPTER, HelloBody.ADAPTER)) adapter.runCatching { decode(junk()) }
        }
        // 이어 붙인 조각열도
        val asm = Frame.Assembler(1, 4096)
        repeat(2_000) { asm.accept(junk(30)) }
    }

    @Test
    fun theSessionSurvivesGarbageFromEveryDirection() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val crypto = BouncyCastleCryptoBox()
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        hostT.open(session.sessionTag, ByteArray(0))
        val router = SessionRouter(config.copy(security = config.security.copy(maxConnectPerMin = 10_000)), hostT, HostInbox(clock), sessions, crypto, clock, scope)
        router.start()
        val module = FakeGameModule(GameDescriptor("fake.game", 1, 1, 1, 4, true))
        val host = GameHost(config, module, clock).attachHost(router, sessions, scope)

        val aTransport = net.client()
        val asc = SessionClient(config, aTransport, crypto, clock, scope)
        val agc = SessionGameClient(config, asc, clock, scope)
        val nonce = crypto.randomBytes(16)
        assertIs<JoinOutcome.Joined>(asc.join(sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, "A", "fake.game", 1)))

        // 1) 링크를 연 낯선 기기가 아무 바이트나 보낸다 (HELLO 전)
        repeat(30) {
            val raw = net.client()
            raw.connect(HostRef(session.sessionTag))
            repeat(5) { raw.send(junk(60)) }
        }
        // 2) 합류한 클라이언트가 아무 채널·kind로 아무 본문을 보낸다 (봉인은 정상)
        repeat(200) { asc.send(SessionChannel.entries.random(random), random.nextInt(0, 10), junk(400)) }
        // 3) 호스트가 아무 game kind로 아무 본문을 내려보낸다 → 클라이언트 디코더
        repeat(200) { router.post { listOf(Outgoing(Recipients.All, SessionChannel.Game, random.nextInt(0, 10), junk(400))) } }
        // 4) 다운링크에 봉인되지 않은 쓰레기 프레임 → 클라이언트는 링크 고장으로 보고 다시 붙는다
        repeat(5) { hostT.send(net.linkOf(aTransport), junk(60)) }

        clock.advanceBy(config.game.actionTimeoutMs)
        clock.advanceBy(1_000)
        assertEquals(dev.proximity.core.api.ActionOutcome.Accepted, agc.sendAction(byteArrayOf(1)), "쓰레기 뒤에도 정상 진행")
        assertEquals(dev.proximity.core.api.ActionOutcome.Accepted, host.sendAction(byteArrayOf(1)))
    }
}
