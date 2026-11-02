package dev.proximity.core.router

import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.repoFile
import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenResult
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.SocialMessage
import dev.proximity.proto.Hello
import dev.proximity.proto.HelloBody
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.proximity.proto.Role as WireRole

/** 역할·채널 권한·자리·social 제한 (S7, I-12·I-14). */
@OptIn(ExperimentalCoroutinesApi::class)
class S7_RolesTest {
    private val baseConfig = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText())
    private val crypto = BouncyCastleCryptoBox()

    private class RecordingApp : HostApplication {
        val gameFrom = mutableListOf<Participant>()

        override fun onGameUplink(from: Participant, kind: Int, payload: ByteArray): List<Outgoing> {
            gameFrom += from
            return listOf(Outgoing(Recipients.All, SessionChannel.Game, kind, payload))
        }
    }

    private inner class Env(val scope: CoroutineScope, val config: ProximityConfig, gameMaxPlayers: Int) {
        val clock = FakeClock()
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto).also { it.gameMaxPlayers = gameMaxPlayers }
        val session = sessions.create(Role.Player, "H")
        val hostT = net.host()
        val app = RecordingApp()
        val router = SessionRouter(config, hostT, HostInbox(clock), sessions, crypto, clock, scope).also { it.app = app }
        val hostAccepting = mutableListOf<Boolean>()
        val logs = mutableListOf<String>()
        val hostBatches = mutableListOf<List<ClientMessage>>()
        lateinit var loopback: SessionRouter.LoopbackEndpoint

        suspend fun start() {
            hostT.open(session.sessionTag, ByteArray(0))
            router.onCapacityChanged = { hostAccepting += it }
            router.log = { logs += it }
            router.start()
            val receiver = ClientReceiver(onBatch = { hostBatches += it }, onGap = { error("loopback 공백") })
            loopback = router.attachLoopback(sessions.participants().first()) { meta, p -> if (meta.seq != 0L) receiver.accept(meta, p) }
        }

        inner class Member(val name: String, val client: SessionClient, val transport: dev.proximity.testing.FakeClientTransport) {
            val batches = mutableListOf<List<ClientMessage>>()
            var roster: List<Participant> = emptyList()
            val capacity = mutableListOf<CapacityInfo>()
            val denied = mutableListOf<SessionChannel>()
            lateinit var me: Participant

            fun socials() = batches.flatten().filter { it.channel == SessionChannel.Social }.map { SocialMessage.ADAPTER.decode(it.payload).data_.utf8() }

            fun games() = batches.flatten().filter { it.channel == SessionChannel.Game }.map { it.payload.decodeToString() }
        }

        /** ticket을 직접 발급해(자리 검사 없이) HELLO 단계의 검사를 본다. */
        suspend fun hello(name: String, role: Role): Pair<Member, JoinOutcome> {
            val nonce = crypto.randomBytes(16)
            val ticket = sessions.issueToken(nonce, role)!!
            val transport = net.client()
            val client = SessionClient(config, transport, crypto, clock, scope)
            val m = Member(name, client, transport)
            client.onBatch = { m.batches += it }
            client.onRoster = { m.roster = it }
            client.onCapacity = { m.capacity += it }
            client.onDenied = { ch, _ -> m.denied += ch }
            val outcome = client.join(ticket, JoinRequest(nonce, role, name, "game", 1))
            if (outcome is JoinOutcome.Joined) m.me = outcome.participant
            return m to outcome
        }

        suspend fun join(name: String, role: Role = Role.Player): Member {
            val (m, outcome) = hello(name, role)
            assertIs<JoinOutcome.Joined>(outcome, "$name 합류")
            return m
        }

        /** SessionClient의 관전자 차단을 거치지 않고, 바인딩된 링크 키로 game 프레임을 직접 보낸다 (위조 시도). */
        suspend fun rawSpectatorGame(name: String, forgedRole: WireRole, forgedId: Int): Member {
            val nonce = crypto.randomBytes(16)
            val ticket = sessions.issueToken(nonce, Role.Spectator)!!
            val raw = net.client()
            val eph = crypto.x25519KeyPair()
            val hostPub = session.hostKeys.publicKey
            val ch = LinkCrypto.derive(crypto, eph.privateKey, hostPub, eph.publicKey, hostPub, session.sessionTag, iAmHost = false)!!
            val m = Member(name, SessionClient(config, net.client(), crypto, clock, scope), raw)
            val asm = Frame.Assembler(1, 16 * 1024)
            scope.launch {
                raw.inbound.collect { f ->
                    (asm.accept(f) as? Frame.Result.Complete)?.message?.let { msg ->
                        val plain = ch.receive.open(msg.body, Frame.aad(1, msg.meta, msg.msgId)) ?: return@let
                        val ctl = if (msg.meta.channel == SessionChannel.Control) CtlMessage.ADAPTER.decode(plain) else null
                        ctl?.denied?.let { m.denied += SessionChannel.Game }
                        if (msg.meta.channel == SessionChannel.Game) m.batches += listOf(ClientMessage(msg.meta.channel, msg.meta.kind, msg.meta.seq, plain, false))
                    }
                }
            }
            raw.connect(HostRef(session.sessionTag))
            val body = HelloBody(new_join = HelloBody.NewJoin(ticket.token.toByteString(), nonce.toByteString(), forgedRole, name, "game", 1))
            val hello = Hello(eph.publicKey.toByteString(), ch.send.seal(HelloBody.ADAPTER.encode(body), LinkCrypto.HELLO_AAD).toByteString())
            Frame.split(1, MessageMeta(SessionChannel.Control, ControlKind.HELLO, 0), 0, Hello.ADAPTER.encode(hello), 185).forEach { raw.send(it) }
            // 헤더·본문에 다른 participantId·역할을 적어도 (프레임에는 송신자 칸이 없다) 호스트는 링크 바인딩만 본다
            val forged = "forged:$forgedId".encodeToByteArray()
            val meta = MessageMeta(SessionChannel.Game, 1, 0)
            Frame.split(1, meta, 1, ch.send.seal(forged, Frame.aad(1, meta, 1)), 185).forEach { raw.send(it) }
            return m
        }
    }

    private fun TestScope.env(config: ProximityConfig = baseConfig, gameMaxPlayers: Int = 4) =
        Env(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), config, gameMaxPlayers)

    @Test
    fun spectatorGameFramesAreDroppedAndDenied() = runTest {
        val e = env().apply { start() }
        val p = e.join("P")
        val s = e.join("S", Role.Spectator)
        assertFalse(s.client.send(SessionChannel.Game, 1, "act".encodeToByteArray()), "관전자 기기는 game을 보내지 않는다")
        val spy = e.rawSpectatorGame("X", forgedRole = WireRole.ROLE_PLAYER, forgedId = p.me.id.value)
        assertTrue(e.app.gameFrom.isEmpty(), "상위 계층 호출 0 (I-12)")
        assertTrue(p.games().isEmpty() && s.games().isEmpty(), "다운링크 전파 0")
        assertEquals(listOf(SessionChannel.Game), spy.denied, "송신자에게 DENIED")
        assertTrue(e.logs.single().startsWith("denied"))
        assertFalse(e.logs.single().contains("forged"), "로그에 본문 없음")
        assertEquals(Role.Spectator, e.router.participants().last().role, "HELLO의 역할 칸이 아니라 token에 묶인 역할")
        p.client.send(SessionChannel.Game, 1, "ok".encodeToByteArray())
        assertEquals(listOf(p.me), e.app.gameFrom)
    }

    @Test
    fun spectatorSocialReachesEveryoneInHostOrder() = runTest {
        val e = env().apply { start() }
        val p = e.join("P")
        val s1 = e.join("S1", Role.Spectator)
        val s2 = e.join("S2", Role.Spectator)
        s1.client.send(SessionChannel.Social, 1, "a".encodeToByteArray())
        p.client.send(SessionChannel.Social, 1, "b".encodeToByteArray())
        s2.client.send(SessionChannel.Social, 1, "c".encodeToByteArray())
        for (m in listOf(p, s1, s2)) assertEquals(listOf("a", "b", "c"), m.socials(), m.name)
        val expected = listOf("H" to Role.Player, "P" to Role.Player, "S1" to Role.Spectator, "S2" to Role.Spectator)
        for (m in listOf(p, s1, s2)) assertEquals(expected, m.roster.map { it.displayName to it.role }, "${m.name}의 참가자·역할 목록")
        assertEquals(expected, e.router.participants().map { it.displayName to it.role })
    }

    @Test
    fun socialSizeAndRateLimits() = runTest {
        val e = env().apply { start() }
        val a = e.join("A")
        val b = e.join("B")
        a.client.send(SessionChannel.Social, 1, ByteArray(e.config.social.maxMsgBytes + 1))
        val limit = e.config.social.maxMsgPerMin
        repeat(limit + 5) { a.client.send(SessionChannel.Social, 1, "m$it".encodeToByteArray()) }
        assertEquals(limit, b.socials().size, "크기 초과 1건 + 속도 초과 5건 드롭")
        b.client.send(SessionChannel.Social, 1, "other".encodeToByteArray())
        assertEquals("other", b.socials().last(), "송신자별 제한")
        e.clock.advanceBy(60_000)
        a.client.send(SessionChannel.Social, 1, "again".encodeToByteArray())
        assertEquals("again", b.socials().last(), "1분 창이 지나면 정상")
    }

    @Test
    fun capacityRejectionReasons() = runTest {
        val config = baseConfig.copy(session = baseConfig.session.copy(maxSpectators = 1))
        val e = env(config, gameMaxPlayers = 2).apply { start() }
        e.join("P")
        val (_, p2) = e.hello("P2", Role.Player)
        assertEquals(JoinOutcome.Refused(TokenResult.PLAYER_SLOTS_FULL), p2, "HELLO 때 다시 검사")
        val s = e.join("S", Role.Spectator)
        val nonce = crypto.randomBytes(16)
        assertEquals(TokenResult.Refused(TokenResult.SPECTATOR_SLOTS_FULL), s.client.requestToken(nonce, Role.Spectator, "S2", 1))
        assertEquals(TokenResult.Refused(TokenResult.PLAYER_SLOTS_FULL), s.client.requestToken(nonce, Role.Player, "P3", 1))

        val tight = baseConfig.copy(session = baseConfig.session.copy(maxLinks = 1))
        val t = env(tight).apply { start() }
        val only = t.join("A")
        assertEquals(TokenResult.Refused(TokenResult.SESSION_FULL), only.client.requestToken(nonce, Role.Spectator, "X", 1))
    }

    @Test
    fun capacityIsBroadcastAndFollowsJoinsAndLeaves() = runTest {
        val config = baseConfig.copy(session = baseConfig.session.copy(maxSpectators = 1))
        val e = env(config, gameMaxPlayers = 3).apply { start() }
        val a = e.join("A")
        assertEquals(CapacityInfo(true, 1, 1), a.capacity.last())
        val b = e.join("B")
        assertEquals(CapacityInfo(true, 0, 1), a.capacity.last())
        val s = e.join("S", Role.Spectator)
        for (m in listOf(a, b, s)) assertEquals(CapacityInfo(false, 0, 0), m.capacity.last(), "${m.name}도 같은 CAPACITY")
        assertEquals(false, e.hostAccepting.last())
        s.client.leave()
        assertEquals(CapacityInfo(true, 0, 1), a.capacity.last())
        assertEquals(true, e.hostAccepting.last())
    }

    @Test
    fun eightPlayerGameUsesAllSevenLinks() = runTest {
        val e = env(gameMaxPlayers = 8).apply { start() }
        val players = (1..7).map { e.join("P$it") }
        assertEquals(8, e.router.participants().count { it.role == Role.Player })
        assertEquals(CapacityInfo(false, 0, 0), players.last().capacity.last(), "관전자 0")
        val (_, x) = e.hello("X", Role.Spectator)
        assertEquals(JoinOutcome.Refused(TokenResult.SESSION_FULL), x)
    }

    @Test
    fun hostLinksNeverExceedMaxLinksIncludingRendezvous() = runTest {
        val config = baseConfig.copy(session = baseConfig.session.copy(maxLinks = 2))
        val e = env(config).apply { start() }
        e.join("A")
        assertTrue(e.router.rendezvousAllowed())
        e.router.setRendezvousInProgress(1)
        assertFalse(e.router.rendezvousAllowed(), "클라이언트 1 + Rendezvous 1 = max_links")
        assertEquals(false, e.hostAccepting.last(), "Rendezvous 중엔 광고 accepting=false")
        e.router.setRendezvousInProgress(0)
        assertEquals(true, e.hostAccepting.last())
    }
}
