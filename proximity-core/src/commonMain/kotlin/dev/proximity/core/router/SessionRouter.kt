package dev.proximity.core.router

import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.LinkEvent
import dev.proximity.core.interfaces.LinkId
import dev.proximity.core.interfaces.SessionTransport
import dev.proximity.core.session.Admission
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.Slots
import dev.proximity.core.session.code
import dev.proximity.proto.Capacity
import dev.proximity.proto.CtlMessage
import dev.proximity.proto.Denied
import dev.proximity.proto.Hello
import dev.proximity.proto.HelloBody
import dev.proximity.proto.ParticipantEntry
import dev.proximity.proto.Participants
import dev.proximity.proto.Pong
import dev.proximity.proto.Reject
import dev.proximity.proto.SessionInfo
import dev.proximity.proto.SocialMessage
import dev.proximity.core.session.TokenResult
import dev.proximity.proto.Token
import dev.proximity.proto.TokenReject
import dev.proximity.proto.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import dev.proximity.proto.Role as WireRole

/**
 * 호스트 세션 라우터 (S6, 04-session-flows §6~§8). 모든 입력은 [HostInbox]에서 하나씩 처리한다 (I-11).
 * - 링크 ↔ 참가자 바인딩은 WELCOME 때 여기 메모리에만 있다. 권한 판정은 이 바인딩으로만 한다 (프레임 값은 믿지 않는다)
 * - 다운링크: 수신자별 연속 seq, 한 입력의 출력은 배치(BATCH_END), 링크마다 FIFO 송신 큐
 * - 핸드셰이크성 control(WELCOME·REJECT·TOKEN·PONG)은 seq 0 (순서 밖) — resume 때 재전송보다 앞서 가야 한다
 * - 재전송: 참가자마다 최근 resend_window개 보관, ACK 이하는 지운다. 재연결 HELLO(resume)에서 이어 보내거나 창 밖이면 스냅샷
 * - 느린 링크: 큐가 link_queue_max를 넘으면 그 링크만 비우고 스냅샷(RESYNC)으로 따라잡게 한다
 * - 역할 (S7): game 업링크는 바인딩된 참가자가 Player일 때만 상위 계층으로 간다. social은 송신자별 크기·속도 제한
 * - 자리 (S7): 남은 자리가 바뀌면 CAPACITY를 전원에게. 광고 accepting은 HELLO 전 링크와 진행 중 Rendezvous까지 센다
 */
class SessionRouter(
    private val config: ProximityConfig,
    private val transport: SessionTransport.Host,
    private val inbox: HostInbox,
    private val sessions: SessionManager,
    private val crypto: CryptoBox,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : HostControls {
    var app: HostApplication = HostApplication.None

    /** 빈 자리가 생기거나 없어질 때 (광고 accepting, S6·S7). */
    var onCapacityChanged: (Boolean) -> Unit = {}

    /** 세션 코드 합류 요청 (호스트 화면의 승인 대기, 04 §5.2.2). 답은 [answerCodeJoin]. */
    var onCodeJoinRequest: (CodeJoinRequest) -> Unit = {}

    /** 승인 대기가 끝났다 (답함·시간 초과·링크 끊김). 화면에서 지운다. */
    var onCodeJoinClosed: (Int) -> Unit = {}

    /** 운영 로그. participantId·채널만 쓰고 본문·이름은 쓰지 않는다. */
    var log: (String) -> Unit = {}

    /** 나가는 논리 메시지마다(평문) — 결정성·순서 시험용. */
    var tap: ((ParticipantId, MessageMeta, ByteArray) -> Unit)? = null

    var hostState: HostSessionState = HostSessionState.Forming
        private set

    private val version = config.ble.protocolVersion
    private val members = linkedMapOf<ParticipantId, Member>()
    private val links = mutableMapOf<LinkId, Link>()
    private var lastAccepting: Boolean? = null
    private var lastCapacity: Capacity? = null
    private var rendezvousInProgress = 0

    private class Stored(val meta: MessageMeta, val payload: ByteArray)

    private inner class Member(var participant: Participant, val secret: ByteArray) {
        var nextSeq = 1L
        val stored = ArrayDeque<Stored>()
        var link: Link? = null
        var loopback: ((MessageMeta, ByteArray) -> Unit)? = null
        var leaveTimer: Cancellable? = null
        val socialTimes = ArrayDeque<Long>()
    }

    private class Pending(val meta: MessageMeta, val payload: ByteArray)

    private inner class Link(val id: LinkId) {
        var member: Member? = null
        var channels: LinkCrypto.LinkChannels? = null
        val assembler = Frame.Assembler(version, MAX_MESSAGE_BYTES)
        var msgId = 0
        val queue = ArrayDeque<Pending>()
        var pumping = false
        var needsSnapshot = false
        var closing = false
        var helloFailures = 0
        var helloTimer: Cancellable? = null
        var closeWhenDrained = false
        var pendingCode: PendingCode? = null
        var lastHeard = 0L
    }

    private class PendingCode(val id: Int, val role: Role, val displayName: String, val gameId: String, val gameVersion: Int)

    private var nextCodeRequest = 1
    /** WELCOME 전에 끝난(인증 실패) 링크의 시각. 정상 재연결은 세지 않는다 (S10·S11, ADR-006 개정). */
    private val failedLinks = ArrayDeque<Long>()
    private var lastSessionInfo: SessionInfo? = null

    private val listeners = mutableListOf<Job>()

    fun start() {
        listeners += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.inbound.collect { f -> inbox.post { onFrame(f.link, f.bytes) } }
        }
        listeners += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.linkEvents.collect { e ->
                inbox.post {
                    when (e) {
                        is LinkEvent.Up -> onLinkUp(e.link)
                        is LinkEvent.Down -> onLinkDown(e.link)
                    }
                }
            }
        }
        updateCapacity()
    }

    /** 호스트 기기 자신의 클라이언트 (I-15). 같은 seq·배치 경로를 타고 [deliver]로 받는다. 업링크는 [LoopbackEndpoint.send]. */
    fun attachLoopback(host: Participant, deliver: (MessageMeta, ByteArray) -> Unit): LoopbackEndpoint {
        val member = members.getOrPut(host.id) { Member(host, ByteArray(0)) }
        member.loopback = deliver
        return LoopbackEndpoint { channel, kind, payload -> inbox.post { onUplink(member, channel, kind, payload) } }
    }

    fun interface LoopbackEndpoint {
        fun send(channel: SessionChannel, kind: Int, payload: ByteArray)
    }

    /** 현재 참가자 (호스트 포함). */
    override fun participants(): List<Participant> = members.values.map { it.participant }

    /** 재전송 버퍼 크기 (ACK 정리 시험용). */
    fun storedCount(id: ParticipantId): Int = members[id]?.stored?.size ?: 0

    /** 상위 계층이 입력 밖에서(예: 타이머) 보내야 할 때. HostInbox 순서로 처리된다. */
    override fun post(outputs: () -> List<Outgoing>) = inbox.post { deliverOutputs(outputs(), resync = false) }

    /** 지금 처리 중인 입력의 배치를 다 보낸 뒤 내보낸다. 호스트 자신은 내보낼 수 없다. */
    override fun kick(id: ParticipantId, code: Int) = inbox.post {
        val member = members[id] ?: return@post
        if (member.loopback != null) return@post
        member.link?.let { rejectAndClose(it, TokenResult.KICKED, code) }
        member.link = null
        removeMember(member, LeaveReason.Kicked)
    }

    /** 연결 전에 읽는 SESSION_INFO (04 §6). 세션 코드 합류는 여기의 hostSessionPub을 믿는다 (TOFU). */
    fun sessionInfo(): ByteArray = SessionInfo.ADAPTER.encode(currentSessionInfo())

    private fun currentSessionInfo(): SessionInfo {
        val session = sessions.session
        val slots = if (hostState == HostSessionState.Ended) Slots(0, 0) else sessions.slots()
        return SessionInfo(
            version = version,
            game_id = sessions.gameId,
            accepting = lastAccepting ?: slots.accepting,
            free_player_slots = slots.players,
            free_spectator_slots = slots.spectators,
            host_session_public_key = (session?.hostKeys?.publicKey ?: ByteArray(0)).toByteString(),
            session_tag = session?.sessionTag ?: 0,
        )
    }

    /** 코드 합류 승인·거절. 승인해도 자리·게임을 다시 검사한다. */
    fun answerCodeJoin(id: Int, accept: Boolean) = inbox.post {
        val link = links.values.firstOrNull { it.pendingCode?.id == id } ?: return@post
        val p = link.pendingCode!!
        link.pendingCode = null
        link.helloTimer?.cancel()
        onCodeJoinClosed(id)
        if (!accept) return@post rejectAndClose(link, TokenResult.CODE_DENIED)
        when (val a = sessions.admitDirect(p.role, p.displayName, p.gameId, p.gameVersion)) {
            is Admission.Rejected -> rejectAndClose(link, a.reason.code(), a.gameCode)
            is Admission.Admitted -> admitNew(link, a.participant, a.resumeSecret)
        }
    }

    /** 호스트 RendezvousEngine이 열고 있는 Rendezvous 링크 수 (물리 링크 상한에 넣어 센다). */
    fun setRendezvousInProgress(count: Int) = inbox.post {
        rendezvousInProgress = count
        updateCapacity()
    }

    /** 새 Rendezvous를 열어도 물리 링크가 max_links를 넘지 않는가 (04 §5.3). */
    fun rendezvousAllowed(): Boolean =
        hostState != HostSessionState.Ended && physicalLinks() < config.session.maxLinks && sessions.slots().accepting

    /** 세션을 끝낸다. 링크마다 대기 중인 프레임(END 등)을 다 보낸 뒤 끊는다. */
    override fun end() = inbox.post {
        hostState = SessionTransitions.host(hostState, HostSessionEvent.End)
        links.values.toList().forEach { link ->
            link.closing = true
            link.helloTimer?.cancel()
            if (link.pumping) link.closeWhenDrained = true else transport.close(link.id)
        }
        updateCapacity()
        // 끝난 세션의 라우터는 더 듣지 않는다: 같은 전송으로 다음 세션을 열면 새 라우터만 링크를 받아야 한다 (S11 실기기에서 발견)
        listeners.forEach { it.cancel() }
        listeners.clear()
    }

    // ---- 링크 ----

    private fun onLinkUp(id: LinkId) {
        // 연결 속도 제한: 최근 60초 인증 실패 링크가 max_connect_per_min개면 새 링크를 받지 않는다
        val now = clock.now()
        while (failedLinks.isNotEmpty() && now - failedLinks.first() >= RATE_WINDOW_MS) failedLinks.removeFirst()
        if (failedLinks.size >= config.security.maxConnectPerMin) {
            log("connection rate limit: link closed")
            transport.close(id)
            return
        }
        val link = Link(id)
        link.lastHeard = now
        links[id] = link
        watchLiveness(link)
        link.helloTimer = clock.schedule(config.transport.helloTimeoutMs) {
            inbox.post { if (link.member == null) closeLink(link) }
        }
        updateCapacity()
    }

    private fun onLinkDown(id: LinkId) {
        val link = links.remove(id) ?: return
        link.helloTimer?.cancel()
        link.pendingCode?.let { onCodeJoinClosed(it.id) }
        if (link.member == null) failedLinks.addLast(clock.now())
        val member = link.member ?: return updateCapacity()
        if (member.link === link) {
            member.link = null
            // client_reconnect_ms 안에 resume하면 같은 participantId로 이어 받는다
            member.leaveTimer = clock.schedule(config.session.clientReconnectMs) {
                inbox.post { if (member.link == null && members[member.participant.id] === member) removeMember(member, LeaveReason.Disconnected) }
            }
        }
        updateCapacity()
    }

    private fun closeLink(link: Link) {
        link.closing = true
        link.helloTimer?.cancel()
        transport.close(link.id)
    }

    // ---- 업링크 ----

    /** 바인딩된 링크가 link_timeout_ms 넘게 조용하면 끊는다: 클라이언트 앱이 죽어도 BLE 연결이 남을 수 있다 (04 §9). */
    private fun watchLiveness(link: Link) {
        clock.schedule(config.transport.keepaliveMs) {
            inbox.post {
                if (links[link.id] !== link || link.closing) return@post
                if (link.member != null && clock.now() - link.lastHeard >= config.transport.linkTimeoutMs) {
                    log("link timeout: closed")
                    closeLink(link)
                    return@post
                }
                watchLiveness(link)
            }
        }
    }

    private fun onFrame(id: LinkId, bytes: ByteArray) {
        val link = links[id] ?: return
        link.lastHeard = clock.now()
        val message = when (val r = link.assembler.accept(bytes)) {
            Frame.Result.Partial -> return
            is Frame.Result.Broken -> return closeLink(link)
            is Frame.Result.Complete -> r.message
        }
        val channels = link.channels
        if (channels == null || link.member == null) {
            // HELLO 승인 전에는 HELLO만 받는다
            if (message.meta.channel == SessionChannel.Control && message.meta.kind == ControlKind.HELLO) onHello(link, message.body)
            return
        }
        val plain = channels.receive.open(message.body, Frame.aad(version, message.meta, message.msgId)) ?: return closeLink(link)
        onUplink(link.member!!, message.meta.channel, message.meta.kind, plain)
    }

    private fun onUplink(member: Member, channel: SessionChannel, kind: Int, payload: ByteArray) {
        when (channel) {
            SessionChannel.Control -> CtlMessage.ADAPTER.runCatching { decode(payload) }.getOrNull()?.let { onControl(member, it) }
            SessionChannel.Social -> {
                if (payload.size > config.social.maxMsgBytes || !socialAllowed(member)) return
                // 송신자 포함 전원 (송신자는 echo로 자기 메시지의 전역 위치를 안다). 송신자는 링크 바인딩으로 채운다
                val wrapped = SocialMessage.ADAPTER.encode(SocialMessage(member.participant.id.value, payload.toByteString()))
                deliverOutputs(listOf(Outgoing(Recipients.All, SessionChannel.Social, kind, wrapped)), resync = false)
            }
            SessionChannel.Game -> {
                // 권한은 링크에 묶인 역할로만 판정한다 (I-12). 프레임에는 송신자·역할 칸이 없다
                if (member.participant.role != Role.Player) return deny(member, channel, kind)
                deliverOutputs(app.onGameUplink(member.participant, kind, payload), resync = false)
            }
        }
    }

    private fun deny(member: Member, channel: SessionChannel, kind: Int) {
        log("denied ${channel.name.lowercase()} uplink from participant ${member.participant.id.value} (${member.participant.role})")
        sendUnsequenced(member, CtlMessage(denied = Denied(channel.code, kind)))
    }

    /** 송신자별 최근 60초 창에 max_msg_per_min개까지. */
    private fun socialAllowed(member: Member): Boolean {
        val now = clock.now()
        while (member.socialTimes.isNotEmpty() && now - member.socialTimes.first() >= RATE_WINDOW_MS) member.socialTimes.removeFirst()
        if (member.socialTimes.size >= config.social.maxMsgPerMin) return false
        member.socialTimes.addLast(now)
        return true
    }

    private fun onControl(member: Member, ctl: CtlMessage) {
        ctl.ack?.let { ack -> while (member.stored.isNotEmpty() && member.stored.first().meta.seq <= ack.last_seq) member.stored.removeFirst() }
        ctl.token_req?.let { req ->
            val reply = when (val r = sessions.requestToken(req.join_nonce.toByteArray(), req.role.toRole(), req.display_name, req.game_version)) {
                is TokenResult.Refused -> CtlMessage(token_reject = TokenReject(r.reason, r.gameCode))
                is TokenResult.Granted -> r.ticket.let { t ->
                    CtlMessage(token = Token(t.token.toByteString(), t.sessionTag, t.hostSessionPublicKey.toByteString(), t.expiresAtMs))
                }
            }
            sendUnsequenced(member, reply)
        }
        ctl.leave?.let { removeMember(member, LeaveReason.Left) }
        ctl.ping?.let { sendUnsequenced(member, CtlMessage(pong = Pong(it.sent_at_ms))) }
    }

    private fun onHello(link: Link, body: ByteArray) {
        if (link.pendingCode != null) return // 승인 대기 중에는 다시 받지 않는다
        val session = sessions.session ?: return closeLink(link)
        val hello = Hello.ADAPTER.runCatching { decode(body) }.getOrNull() ?: return helloFailed(link)
        val clientPub = hello.client_public_key.toByteArray()
        val channels = LinkCrypto.derive(
            crypto, session.hostKeys.privateKey, clientPub, clientPub, session.hostKeys.publicKey, session.sessionTag, iAmHost = true,
            sasDigits = config.security.sasDigits,
        ) ?: return helloFailed(link)
        val inner = channels.receive.open(hello.sealed_body.toByteArray(), LinkCrypto.HELLO_AAD) ?: return helloFailed(link)
        val helloBody = HelloBody.ADAPTER.runCatching { decode(inner) }.getOrNull() ?: return helloFailed(link)
        link.channels = channels
        helloBody.new_join?.let { join ->
            when (val a = sessions.redeem(join.token.toByteArray(), join.join_nonce.toByteArray(), join.display_name, join.game_id, join.game_version, join.role.toRole(), join.join_secret.toByteArray())) {
                is Admission.Rejected -> rejectAndClose(link, a.reason.code(), a.gameCode)
                is Admission.Admitted -> admitNew(link, a.participant, a.resumeSecret)
            }
            return
        }
        helloBody.code_join?.let { cj ->
            if (!sessions.checkCode(cj.code, config.security.maxAuthFailures)) return rejectAndClose(link, TokenResult.BAD_CODE)
            val role = cj.role.toRole()
            sessions.precheckDirect(role, cj.display_name, cj.game_id, cj.game_version)?.let { return rejectAndClose(link, it.reason.code(), it.gameCode) }
            val pending = PendingCode(nextCodeRequest++, role, cj.display_name, cj.game_id, cj.game_version)
            link.pendingCode = pending
            // 승인 대기는 hello_timeout 대신 confirming_ms
            link.helloTimer?.cancel()
            link.helloTimer = clock.schedule(config.timeouts.confirmingMs) {
                inbox.post {
                    if (link.pendingCode === pending) {
                        link.pendingCode = null
                        onCodeJoinClosed(pending.id)
                        rejectAndClose(link, TokenResult.CODE_DENIED)
                    }
                }
            }
            onCodeJoinRequest(CodeJoinRequest(pending.id, cj.display_name, role, channels.sas))
            return
        }
        helloBody.resume?.let { r ->
            val member = members[ParticipantId(r.participant_id)]
            if (member == null || !member.secret.contentEquals(r.resume_secret.toByteArray())) return rejectAndClose(link, REJECT_BAD_RESUME)
            resume(link, member, r.last_seq)
        }
    }

    private fun helloFailed(link: Link) {
        link.helloFailures++
        if (link.helloFailures >= config.security.maxAuthFailures) closeLink(link)
    }

    private fun rejectAndClose(link: Link, reason: Int, gameCode: Int = 0) {
        enqueue(link, Pending(MessageMeta(SessionChannel.Control, ControlKind.CTL, 0), CtlMessage.ADAPTER.encode(CtlMessage(reject = Reject(reason, gameCode)))))
        link.closing = true
        pump(link) { transport.close(link.id) }
    }

    private fun admitNew(link: Link, participant: Participant, secret: ByteArray) {
        val existing = members[participant.id]
        val member = existing ?: Member(participant, secret).also { members[participant.id] = it }
        bind(link, member)
        sendUnsequenced(member, welcome(member))
        if (existing == null) {
            hostState = SessionTransitions.host(hostState, HostSessionEvent.FirstWelcome)
            deliverOutputs(listOf(roster()) + app.onJoined(participant), resync = false)
        } else {
            // 같은 token·joinNonce로 HELLO를 다시 보냈다 (S5): 새 참가자 없이 처음부터 다시 보낸다
            resendFrom(member, 0)
        }
        updateCapacity()
    }

    private fun resume(link: Link, member: Member, lastSeq: Long) {
        member.link?.takeIf { it !== link }?.let { closeLink(it) }
        bind(link, member)
        member.leaveTimer?.cancel()
        sendUnsequenced(member, welcome(member))
        resendFrom(member, lastSeq)
        updateCapacity()
    }

    /** lastSeq 다음부터 보낸다. 창 밖이면 스냅샷으로 따라잡게 한다. */
    private fun resendFrom(member: Member, lastSeq: Long) {
        val oldest = member.stored.firstOrNull()?.meta?.seq ?: member.nextSeq
        if (lastSeq + 1 >= oldest) {
            member.stored.filter { it.meta.seq > lastSeq }.forEach { s -> send(member, s.meta, s.payload) }
        } else {
            snapshot(member)
        }
    }

    private fun bind(link: Link, member: Member) {
        link.helloTimer?.cancel()
        link.member = member
        member.link = link
    }

    private fun removeMember(member: Member, reason: LeaveReason) {
        members.remove(member.participant.id) ?: return
        member.leaveTimer?.cancel()
        member.link?.let { closeLink(it) }
        sessions.remove(member.participant.id)
        deliverOutputs(listOf(roster()) + app.onLeft(member.participant, reason), resync = false)
        updateCapacity()
    }

    // ---- 다운링크 ----

    private fun deliverOutputs(outputs: List<Outgoing>, resync: Boolean) {
        // 수신자별로 모은 뒤 각자의 마지막 메시지에 BATCH_END
        val perMember = linkedMapOf<Member, MutableList<Outgoing>>()
        for (out in outputs) for (m in recipients(out.to)) perMember.getOrPut(m) { mutableListOf() } += out
        perMember.forEach { (member, list) ->
            list.forEachIndexed { i, out ->
                val meta = MessageMeta(out.channel, out.kind, member.nextSeq++, resync = resync && i == 0, batchEnd = i == list.lastIndex)
                member.stored.addLast(Stored(meta, out.payload))
                while (member.stored.size > config.transport.resendWindow) member.stored.removeFirst()
                send(member, meta, out.payload)
            }
        }
    }

    private fun recipients(to: Recipients): List<Member> = when (to) {
        Recipients.All -> members.values.toList()
        is Recipients.One -> listOfNotNull(members[to.id])
        is Recipients.Many -> members.values.filter { it.participant.id in to.ids }
    }

    private fun snapshot(member: Member) {
        val outs = app.snapshotFor(member.participant)
        val list = outs.ifEmpty { listOf(roster()) }
        val perOne = list.map { Outgoing(Recipients.One(member.participant.id), it.channel, it.kind, it.payload) }
        deliverOutputs(perOne, resync = true)
    }

    private fun send(member: Member, meta: MessageMeta, payload: ByteArray) {
        tap?.invoke(member.participant.id, meta, payload)
        member.loopback?.let { return it(meta, payload) }
        val link = member.link ?: return // 끊긴 동안은 보관만 (resume 때 보낸다)
        if (link.needsSnapshot) return
        if (link.queue.size >= config.transport.linkQueueMax) {
            // 느린 링크: 그 링크만 비우고, 큐가 빈 뒤 스냅샷으로 따라잡게 한다
            link.queue.clear()
            link.needsSnapshot = true
            return
        }
        enqueue(link, Pending(meta, payload))
    }

    private fun sendUnsequenced(member: Member, ctl: CtlMessage) {
        val bytes = CtlMessage.ADAPTER.encode(ctl)
        val meta = MessageMeta(SessionChannel.Control, ControlKind.CTL, 0)
        member.loopback?.let { return it(meta, bytes) }
        member.link?.let { enqueue(it, Pending(meta, bytes)) }
    }

    private fun enqueue(link: Link, pending: Pending) {
        link.queue.addLast(pending)
        pump(link)
    }

    /** 링크마다 하나의 송신 코루틴이 큐를 FIFO로 비운다. 봉인도 보내는 순서대로 한다 (카운터 순서). */
    private fun pump(link: Link, afterDrain: (() -> Unit)? = null) {
        if (link.pumping) return
        link.pumping = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val channels = link.channels
            while (link.queue.isNotEmpty()) {
                if (channels?.send?.exhausted == true) {
                    // nonce 카운터 상한: 끊으면 클라이언트가 새 임시 키로 다시 붙는다 (새 링크 키)
                    transport.close(link.id)
                    break
                }
                val p = link.queue.removeFirst()
                val msgId = link.msgId++ and 0xffff
                val sealed = channels?.send?.seal(p.payload, Frame.aad(version, p.meta, msgId)) ?: break
                val frames = Frame.split(version, p.meta, msgId, sealed, transport.mtu(link.id))
                if (!frames.all { transport.send(link.id, it) }) break
            }
            link.pumping = false
            afterDrain?.invoke()
            if (link.closeWhenDrained) transport.close(link.id)
            if (link.needsSnapshot && links[link.id] === link) {
                inbox.post {
                    link.needsSnapshot = false
                    link.member?.let(::snapshot)
                }
            }
        }
    }

    // ---- 기타 ----

    private fun welcome(member: Member) = CtlMessage(
        welcome = Welcome(member.participant.id.value, member.secret.toByteString(), member.participant.role.toWire(), member.participant.displayName),
    )

    private fun roster() = Outgoing(
        Recipients.All,
        SessionChannel.Control,
        ControlKind.CTL,
        CtlMessage.ADAPTER.encode(
            CtlMessage(participants = Participants(members.values.map { ParticipantEntry(it.participant.id.value, it.participant.role.toWire(), it.participant.displayName) })),
        ),
    )

    private fun physicalLinks() = links.values.count { !it.closing } + rendezvousInProgress

    private fun updateCapacity() {
        val slots = if (hostState == HostSessionState.Ended) Slots(0, 0) else sessions.slots()
        val accepting = slots.accepting && physicalLinks() < config.session.maxLinks
        if (accepting != lastAccepting) {
            lastAccepting = accepting
            onCapacityChanged(accepting)
        }
        val info = currentSessionInfo()
        if (info != lastSessionInfo) {
            lastSessionInfo = info
            transport.updateSessionInfo(SessionInfo.ADAPTER.encode(info))
        }
        val capacity = Capacity(accepting, slots.players, slots.spectators)
        if (capacity != lastCapacity && hostState != HostSessionState.Ended) {
            lastCapacity = capacity
            deliverOutputs(listOf(Outgoing(Recipients.All, SessionChannel.Control, ControlKind.CTL, CtlMessage.ADAPTER.encode(CtlMessage(capacity = capacity)))), resync = false)
        }
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024
        const val REJECT_BAD_RESUME = 100
        const val RATE_WINDOW_MS = 60_000L

        fun Role.toWire() = if (this == Role.Player) WireRole.ROLE_PLAYER else WireRole.ROLE_SPECTATOR

        fun WireRole.toRole() = if (this == WireRole.ROLE_SPECTATOR) Role.Spectator else Role.Player
    }
}
