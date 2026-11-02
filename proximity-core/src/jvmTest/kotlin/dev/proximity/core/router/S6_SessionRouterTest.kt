package dev.proximity.core.router

import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.LinkId
import dev.proximity.core.session.Admission
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.SocialMessage
import dev.proximity.proto.Hello
import dev.proximity.proto.HelloBody
import dev.proximity.testing.FakeClientTransport
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 호스트 1 + 클라이언트 N + 호스트 loopback (S6, FakeSessionNetwork). */
@OptIn(ExperimentalCoroutinesApi::class)
class S6_SessionRouterTest {
    // S7의 social 속도 제한(분당 20)은 S7_RolesTest가 본다. 여기서는 social을 트래픽 발생용으로 쓰므로 풀어 둔다
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText()).let {
        it.copy(social = it.social.copy(maxMsgPerMin = 1_000_000))
    }
    private val crypto = RecordingCrypto(BouncyCastleCryptoBox())

    /** seal에 쓰인 (key, nonce)를 기록한다 — nonce 재사용 검사용. */
    private class RecordingCrypto(private val inner: CryptoBox) : CryptoBox by inner {
        val sealed = mutableListOf<Pair<List<Byte>, List<Byte>>>()

        override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            sealed += key.toList() to nonce.toList()
            return inner.seal(key, nonce, aad, plaintext)
        }
    }

    /** 시험용 상위 계층: game 업링크를 기록하고, 요청에 따라 수신자 지정 출력을 낸다. */
    private class TestApp : HostApplication {
        val gameFrom = mutableListOf<Participant>()
        var outputsFor: (Participant, ByteArray) -> List<Outgoing> = { _, _ -> emptyList() }

        override fun onGameUplink(from: Participant, kind: Int, payload: ByteArray): List<Outgoing> {
            gameFrom += from
            return outputsFor(from, payload)
        }

        override fun snapshotFor(participant: Participant) =
            listOf(Outgoing(Recipients.One(participant.id), SessionChannel.Game, 9, "snap".encodeToByteArray()))
    }

    private inner class Env(val scope: CoroutineScope, val config: ProximityConfig = baseConfig, mtu: Int = 185) {
        val clock = FakeClock()
        val net = FakeSessionNetwork(mtu)
        // S7: 관전자 자리는 게임 인원에 따라 정해진다 (8인이면 0). 관전자를 쓰는 시험은 4인 게임으로
        val sessions = SessionManager(config.session, clock, crypto).also { it.gameMaxPlayers = 4 }
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val app = TestApp()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope).also { it.app = app }
        val tapLog = mutableListOf<String>()
        val capacity = mutableListOf<Boolean>()
        val hostBatches = mutableListOf<List<ClientMessage>>()
        lateinit var loopback: SessionRouter.LoopbackEndpoint

        suspend fun start() {
            hostT.open(session.sessionTag, ByteArray(0))
            router.tap = { id, meta, p -> tapLog += "${id.value}:${meta.seq}:${meta.channel}:${meta.kind}:${meta.batchEnd}:${meta.resync}:${p.toList()}" }
            router.onCapacityChanged = { capacity += it }
            router.start()
            val receiver = ClientReceiver(onBatch = { hostBatches += it }, onGap = { error("loopback 공백") })
            loopback = router.attachLoopback(sessions.participants().first()) { meta, p -> if (meta.seq != 0L) receiver.accept(meta, p) }
        }

        inner class Member(val name: String, val transport: FakeClientTransport, val client: SessionClient) {
            val batches = mutableListOf<List<ClientMessage>>()
            var roster: List<Participant> = emptyList()
            lateinit var me: Participant
            val link: LinkId get() = net.linkOf(transport)

            fun socials() = batches.flatten().filter { it.channel == SessionChannel.Social }.map { SocialMessage.ADAPTER.decode(it.payload).data_.utf8() }
        }

        suspend fun join(name: String, role: Role = Role.Player): Member {
            val nonce = crypto.randomBytes(16)
            val ticket = sessions.issueToken(nonce, role)!!
            val transport = net.client()
            val client = SessionClient(config, transport, crypto, clock, scope)
            val member = Member(name, transport, client)
            client.onBatch = { member.batches += it }
            client.onRoster = { member.roster = it }
            val outcome = assertIs<JoinOutcome.Joined>(client.join(ticket, JoinRequest(nonce, role, name, "game", 1)))
            member.me = outcome.participant
            return member
        }

        fun hostSocials() = hostBatches.flatten().filter { it.channel == SessionChannel.Social }.map { SocialMessage.ADAPTER.decode(it.payload).data_.utf8() }
    }

    private fun TestScope.env(config: ProximityConfig = baseConfig, mtu: Int = 185) =
        Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config, mtu)

    @Test
    fun concurrentUplinksArriveInTheSameOrderEverywhereIncludingLoopback() = runTest {
        val e = env().apply { start() }
        val (a, b, c) = listOf("A", "B", "C").map { e.join(it) }
        repeat(3) { i -> for (m in listOf(a, b, c)) m.client.send(SessionChannel.Social, 1, "${m.name}$i".encodeToByteArray()) }
        e.loopback.send(SessionChannel.Social, 1, "H0".encodeToByteArray())
        val expected = listOf("A0", "B0", "C0", "A1", "B1", "C1", "A2", "B2", "C2", "H0")
        for (m in listOf(a, b, c)) assertEquals(expected, m.socials(), "${m.name}: 송신자 echo 포함, 같은 순서")
        assertEquals(expected, e.hostSocials(), "호스트 loopback도 같은 순서 (I-15)")
        assertEquals(listOf("H", "A", "B", "C"), a.roster.map { it.displayName })
    }

    @Test
    fun recipientSpecificMessagesAndBatches() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        e.app.outputsFor = { _, _ ->
            listOf(
                Outgoing(Recipients.One(a.me.id), SessionChannel.Game, 1, "for-a".encodeToByteArray()),
                Outgoing(Recipients.All, SessionChannel.Game, 2, "all".encodeToByteArray()),
            )
        }
        b.client.send(SessionChannel.Game, 7, "act".encodeToByteArray())
        assertEquals(listOf(b.me), e.app.gameFrom, "송신자는 링크 바인딩으로 판정")
        val aGame = a.batches.last()
        assertEquals(listOf("for-a", "all"), aGame.map { it.payload.decodeToString() }, "A는 두 메시지를 한 배치로")
        assertEquals(listOf("all"), b.batches.last().map { it.payload.decodeToString() }, "B는 공통 메시지만, seq 공백 없음")
        assertEquals(aGame[0].seq + 1, aGame[1].seq)
    }

    @Test
    fun lostDownlinkFrameTriggersReconnectAndInOrderRecovery() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        var dropped = false
        val bLink = b.link
        e.net.dropRule = { r -> (r.toClient && r.link == bLink && !dropped && r.bytes.size > 20).also { if (it) dropped = true } }
        listOf("m1", "m2", "m3").forEach { a.client.send(SessionChannel.Social, 1, it.encodeToByteArray()) }
        assertTrue(dropped)
        assertEquals(listOf("m1", "m2", "m3"), b.socials(), "링크 고장 → 재연결(resume) → 빠진 것부터 순서대로")
        assertEquals(ClientSessionState.Live, b.client.clientState)
        assertEquals(b.me, b.client.me, "같은 participantId")
    }

    @Test
    fun outOfWindowResumeGetsSnapshot() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        e.net.acceptingConnections = false
        e.net.disconnect(b.link)
        assertEquals(ClientSessionState.Paused, b.client.clientState)
        repeat(e.config.transport.resendWindow + 6) { a.client.send(SessionChannel.Social, 1, "x$it".encodeToByteArray()) }
        e.net.acceptingConnections = true
        e.clock.advanceBy(e.config.session.joinRetryBackoffMs)
        assertEquals(ClientSessionState.Live, b.client.clientState)
        val first = b.batches.last().first()
        assertTrue(first.resynced, "재전송 창 밖 → 스냅샷(RESYNC)")
        assertEquals("snap", first.payload.decodeToString())
    }

    @Test
    fun slowLinkDoesNotBlockOthersAndCatchesUpWithSnapshot() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val c = e.join("C")
        e.net.block(b.link)
        val n = e.config.transport.linkQueueMax + 12
        repeat(n) { a.client.send(SessionChannel.Social, 1, "s$it".encodeToByteArray()) }
        assertEquals(n, c.socials().size, "C는 B와 무관하게 다 받는다")
        e.net.unblock(b.link)
        assertTrue(b.batches.flatten().any { it.resynced && it.payload.decodeToString() == "snap" }, "B는 스냅샷으로 따라잡는다")
    }

    @Test
    fun framesBeforeHelloAreDroppedAndHelloTimesOut() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val raw = e.net.client()
        assertNotNull(raw.connect(HostRef(e.session.sessionTag)))
        Frame.split(1, MessageMeta(SessionChannel.Social, 1, 0), 0, "spoof".encodeToByteArray(), 185).forEach { raw.send(it) }
        assertFalse("spoof" in a.socials(), "HELLO 전 프레임은 버린다")
        e.clock.advanceBy(e.config.transport.helloTimeoutMs)
        assertTrue(runCatching { e.net.linkOf(raw) }.isFailure, "HELLO가 없으면 링크를 끊는다")
    }

    @Test
    fun wrongResumeSecretIsRejected() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val raw = e.net.client()
        val replies = mutableListOf<CtlMessage>()
        val eph = crypto.x25519KeyPair()
        val hostPub = e.session.hostKeys.publicKey
        val ch = LinkCrypto.derive(crypto, eph.privateKey, hostPub, eph.publicKey, hostPub, e.session.sessionTag, iAmHost = false)!!
        val asm = Frame.Assembler(1, 4096)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            raw.inbound.collect { f ->
                (asm.accept(f) as? Frame.Result.Complete)?.message?.let { m ->
                    ch.receive.open(m.body, Frame.aad(1, m.meta, m.msgId))?.let { replies += CtlMessage.ADAPTER.decode(it) }
                }
            }
        }
        raw.connect(HostRef(e.session.sessionTag))
        val body = HelloBody(resume = HelloBody.Resume(a.me.id.value, ByteArray(16).toByteString(), 0))
        val hello = Hello(eph.publicKey.toByteString(), ch.send.seal(HelloBody.ADAPTER.encode(body), LinkCrypto.HELLO_AAD).toByteString())
        Frame.split(1, MessageMeta(SessionChannel.Control, ControlKind.HELLO, 0), 0, Hello.ADAPTER.encode(hello), 185).forEach { raw.send(it) }
        assertNotNull(replies.single().reject, "남의 participantId로 resume 시도 → REJECT")
        assertTrue(runCatching { e.net.linkOf(raw) }.isFailure)
        assertEquals(ClientSessionState.Live, a.client.clientState, "진짜 A는 영향 없음")
    }

    @Test
    fun ackTrimsResendBuffer() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        repeat(40) { b.client.send(SessionChannel.Social, 1, "b$it".encodeToByteArray()) }
        assertTrue(e.router.storedCount(a.me.id) < e.config.transport.ackEveryN, "ACK 이하는 지운다: ${e.router.storedCount(a.me.id)}")
        e.clock.advanceBy(e.config.transport.ackIntervalMs)
        assertEquals(0, e.router.storedCount(a.me.id), "주기 ACK로 나머지도")
    }

    @Test
    fun reconnectKeepsParticipantAndLongAbsenceRemovesIt() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        e.net.disconnect(b.link)
        assertEquals(ClientSessionState.Live, b.client.clientState, "바로 다시 붙었다")
        assertEquals(3, e.router.participants().size)

        e.net.acceptingConnections = false
        e.net.disconnect(b.link)
        e.clock.advanceBy(e.config.session.clientReconnectMs)
        assertEquals(listOf("H", "A"), e.router.participants().map { it.displayName }, "유예가 지나면 이탈")
        assertEquals(listOf("H", "A"), a.roster.map { it.displayName })
        e.clock.advanceBy(e.config.session.hostGraceMs)
        assertEquals(ClientSessionState.Ended, b.client.clientState)
    }

    @Test
    fun hostLossPausesThenEndsUnlessItComesBack() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        e.net.acceptingConnections = false
        e.net.disconnect(a.link)
        e.net.disconnect(b.link)
        assertEquals(ClientSessionState.Paused, a.client.clientState)
        e.clock.advanceBy(e.config.session.joinRetryBackoffMs * 2)
        e.net.acceptingConnections = true
        e.clock.advanceBy(e.config.session.joinRetryBackoffMs)
        assertEquals(ClientSessionState.Live, a.client.clientState, "유예 안에 호스트가 돌아오면 Live")
        e.net.acceptingConnections = false
        e.net.disconnect(a.link)
        e.clock.advanceBy(e.config.session.hostGraceMs + e.config.session.joinRetryBackoffMs)
        assertEquals(ClientSessionState.Ended, a.client.clientState)
    }

    @Test
    fun memberRequestsTokenOverTheLink() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val nonce = crypto.randomBytes(16)
        val result = assertIs<TokenResult.Granted>(a.client.requestToken(nonce, Role.Spectator, "X", 1))
        assertEquals(e.session.sessionTag, result.ticket.sessionTag)
        assertIs<Admission.Admitted>(e.sessions.redeem(result.ticket.token, nonce, "X"))
    }

    @Test
    fun sameInputsGiveSameOutputs() = runTest {
        fun scenario(e: Env) = e
        val logs = (0 until 2).map {
            val e = env().apply { start() }
            val a = e.join("A")
            val b = e.join("B")
            e.app.outputsFor = { from, p -> listOf(Outgoing(Recipients.One(from.id), SessionChannel.Game, 1, p)) }
            a.client.send(SessionChannel.Social, 1, "a".encodeToByteArray())
            b.client.send(SessionChannel.Game, 2, "g".encodeToByteArray())
            a.client.send(SessionChannel.Social, 1, "a2".encodeToByteArray())
            scenario(e).tapLog.toList()
        }
        assertEquals(logs[0], logs[1], "HostInbox: 같은 입력 순서 → 같은 출력(수신자·seq·내용)")
        assertTrue(logs[0].isNotEmpty())
    }

    @Test
    fun advertisingAcceptingFollowsLinkSlots() = runTest {
        val config = baseConfig.copy(session = baseConfig.session.copy(maxLinks = 2))
        val e = env(config).apply { start() }
        e.join("A")
        val b = e.join("B")
        assertEquals(listOf(true, false), e.capacity.distinct().let { e.capacity }, "빈 링크가 없으면 accepting=false")
        e.net.acceptingConnections = false
        e.net.disconnect(b.link)
        assertEquals(false, e.capacity.last(), "재연결 유예 중인 참가자는 자리를 지킨다 (S7, 04 §5.3)")
        e.clock.advanceBy(e.config.session.clientReconnectMs)
        assertEquals(true, e.capacity.last())
    }

    @Test
    fun anEndedRouterStopsListeningToTheTransport() = runTest {
        // S11 실기기에서 발견: 같은 전송으로 다음 세션을 열면 끝난 라우터도 새 링크를 받아 끊어 버렸다
        val e = env().apply { start() }
        e.join("A")
        e.router.end()
        e.tapLog.clear()
        e.sessions.end()
        val next = e.sessions.create(Role.Player, "H")
        val router2 = SessionRouter(e.config, e.hostT, HostInbox(e.clock), e.sessions, crypto, e.clock, e.scope)
        e.hostT.open(next.sessionTag, ByteArray(0))
        router2.start()
        val nonce = crypto.randomBytes(16)
        val c = SessionClient(e.config, e.net.client(), crypto, e.clock, e.scope)
        assertIs<JoinOutcome.Joined>(c.join(e.sessions.issueToken(nonce, Role.Player)!!, JoinRequest(nonce, Role.Player, "B", "game", 1)))
        assertTrue(e.tapLog.isEmpty(), "끝난 라우터는 아무것도 보내지 않는다: ${e.tapLog}")
        assertEquals(ClientSessionState.Live, c.clientState)
    }

    @Test
    fun pingMeasuresRoundTrip() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        assertEquals(0L, a.client.ping(), "FakeClock이라 0ms")
    }

    @Test
    fun linkBytesCarryNoPlaintext() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val seen = mutableListOf<Byte>()
        e.net.dropRule = { r -> seen += r.bytes.drop(Frame.HEADER_BYTES); false }
        a.client.send(SessionChannel.Social, 1, "비밀-메시지".encodeToByteArray())
        assertEquals(listOf("비밀-메시지"), b.socials())
        val secret = "비밀-메시지".encodeToByteArray().toList()
        assertFalse(seen.windowed(secret.size).any { it == secret })
    }

    @Test
    fun mtu23StillCarriesLargeMessages() = runTest {
        val e = env(mtu = 23).apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val big = ByteArray(1024) { (it % 251).toByte() }
        // social은 140바이트 상한이라 game 채널로 (상위 계층이 전원에게 그대로 보낸다)
        e.app.outputsFor = { _, p -> listOf(Outgoing(Recipients.All, SessionChannel.Game, 1, p)) }
        a.client.send(SessionChannel.Game, 1, big)
        assertContentEquals(big, b.batches.flatten().last { it.channel == SessionChannel.Game }.payload)
        a.client.send(SessionChannel.Social, 1, ByteArray(141))
        assertTrue(b.batches.flatten().none { it.channel == SessionChannel.Social }, "social 크기 상한 초과는 버린다")
    }

    @Test
    fun tamperedDownlinkFrameIsALinkFaultAndRecovers() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val bLink = b.link
        var flipped = false
        e.net.dropRule = { r ->
            if (r.toClient && r.link == bLink && !flipped && r.bytes.size > Frame.HEADER_BYTES + 4) {
                r.bytes[r.bytes.size - 1] = (r.bytes[r.bytes.size - 1].toInt() xor 1).toByte()
                flipped = true
            }
            false
        }
        a.client.send(SessionChannel.Social, 1, "t1".encodeToByteArray())
        assertTrue(flipped)
        assertTrue(b.link != bLink, "1비트 변조 → 링크 고장 → 새 링크로 재연결")
        assertEquals(listOf("t1"), b.socials(), "변조된 내용은 적용하지 않고 resume으로 원본을 받는다")
    }

    @Test
    fun tamperedUplinkFrameClosesTheLinkWithoutApplyingIt() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val aLink = a.link
        e.net.dropRule = { r ->
            if (!r.toClient && r.link == aLink) r.bytes[r.bytes.size - 1] = (r.bytes[r.bytes.size - 1].toInt() xor 1).toByte()
            false
        }
        a.client.send(SessionChannel.Social, 1, "u1".encodeToByteArray())
        assertTrue(b.socials().isEmpty(), "열리지 않은 업링크는 아무에게도 가지 않는다")
        assertTrue(a.link != aLink, "호스트가 링크를 끊고 A는 다시 붙는다")
        assertEquals(listOf("H", "A", "B"), e.router.participants().map { it.displayName }, "참가자는 유지")
    }

    @Test
    fun frameReplayedOnAnotherLinkIsRejected() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val captured = mutableListOf<ByteArray>()
        val aLink = a.link
        e.net.dropRule = { r -> if (!r.toClient && r.link == aLink) captured += r.bytes.copyOf(); false }
        a.client.send(SessionChannel.Social, 1, "rp".encodeToByteArray())
        e.net.dropRule = { false }
        val bLink = b.link
        captured.forEach { b.transport.send(it) }
        assertEquals(listOf("rp"), a.socials(), "재생은 적용되지 않는다")
        assertEquals(listOf("rp"), b.socials())
        assertTrue(b.link != bLink, "다른 링크 키로 열리지 않으니 B 링크 고장 처리")
    }

    @Test
    fun reconnectDerivesANewLinkKey() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        val captured = mutableListOf<ByteArray>()
        val bLink = b.link
        e.net.dropRule = { r -> if (!r.toClient && r.link == bLink) captured += r.bytes.copyOf(); false }
        b.client.send(SessionChannel.Social, 1, "old".encodeToByteArray())
        e.net.dropRule = { false }
        e.net.disconnect(bLink)
        assertEquals(ClientSessionState.Live, b.client.clientState)
        val newLink = b.link
        captured.forEach { b.transport.send(it) }
        assertEquals(listOf("old"), a.socials(), "이전 링크 키로 만든 프레임은 새 링크에서 열리지 않는다")
        assertTrue(b.link != newLink)
    }

    @Test
    fun noNonceIsReusedUnderTheSameKey() = runTest {
        crypto.sealed.clear()
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        repeat(10) { a.client.send(SessionChannel.Social, 1, "n$it".encodeToByteArray()) }
        e.net.disconnect(b.link) // 재연결 = 새 키, 새 카운터
        repeat(5) { b.client.send(SessionChannel.Social, 1, "m$it".encodeToByteArray()) }
        assertTrue(crypto.sealed.size > 30)
        assertEquals(crypto.sealed.size, crypto.sealed.toSet().size, "같은 키에서 nonce 재사용 0")
    }

    @Test
    fun linkBindingDecidesTheSenderNotFrameContents() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        // 바인딩된 링크에서 A의 participantId로 resume HELLO를 다시 보내도 바인딩은 바뀌지 않는다
        val forged = CtlMessage(hello = Hello(ByteArray(32).toByteString(), HelloBody.ADAPTER.encode(
            HelloBody(resume = HelloBody.Resume(a.me.id.value, ByteArray(16).toByteString(), 0))).toByteString()))
        b.client.send(SessionChannel.Control, ControlKind.CTL, CtlMessage.ADAPTER.encode(forged))
        b.client.send(SessionChannel.Game, 1, "g".encodeToByteArray())
        assertEquals(listOf(b.me), e.app.gameFrom, "권한 판정은 링크 바인딩")
        assertEquals(ClientSessionState.Live, a.client.clientState)
    }

    @Test
    fun resendBufferNeverExceedsWindowWithoutAcks() = runTest {
        val config = baseConfig.copy(transport = baseConfig.transport.copy(ackEveryN = 1_000_000, ackIntervalMs = 10_000_000))
        val e = env(config).apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        repeat(config.transport.resendWindow + 25) { b.client.send(SessionChannel.Social, 1, "w$it".encodeToByteArray()) }
        assertEquals(config.transport.resendWindow, e.router.storedCount(a.me.id), "ACK가 없어도 resend_window까지만")
    }
}
