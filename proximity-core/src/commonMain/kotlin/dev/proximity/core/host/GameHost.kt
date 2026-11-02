package dev.proximity.core.host

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameClient
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.api.Target
import dev.proximity.core.game.GameKind
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Cancellable
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.GameModule
import dev.proximity.core.router.HostApplication
import dev.proximity.core.router.HostControls
import dev.proximity.core.router.LoopbackPort
import dev.proximity.core.router.Outgoing
import dev.proximity.core.router.Recipients
import dev.proximity.core.router.SessionChannel
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.Admission
import dev.proximity.core.session.GameAdmission
import dev.proximity.core.session.SessionManager
import dev.proximity.core.session.TokenRejection
import dev.proximity.proto.GameAction
import dev.proximity.proto.GameActionResult
import dev.proximity.proto.GameEnd
import dev.proximity.proto.GameView
import kotlinx.coroutines.CoroutineScope
import okio.ByteString.Companion.toByteString

/**
 * 호스트 기기에서 [GameModule]을 실행한다 (S8, 03-game-data-api §3).
 * - 모든 GameModule 호출은 HostInbox 루프에서 하나씩 (I-11). 한 호출에서 나온 출력은 한 배치: ACTION_RESULT → EVENT… → VIEW…
 * - 관전자의 game 업링크는 라우터가 먼저 막는다 (I-12). 여기는 Player 액션만 온다
 * - 수신자는 GameModule이 정한 대상뿐이다 (I-13). view는 참가자마다 viewFor()
 * - (participantId, actionId)로 중복을 지운다 (at-most-once). GameModule 예외는 그 처리만 Rejected(INTERNAL)
 */
class GameHost(
    private val config: ProximityConfig,
    private val module: GameModule,
    private val clock: Clock,
) : HostApplication {
    /** 운영 로그 (본문 없음). */
    var log: (String) -> Unit = {}

    private lateinit var controls: HostControls
    private val descriptor = module.descriptor()
    private var revision = 0L
    private var ended = false
    private val timers = mutableMapOf<String, Cancellable>()
    private val dedup = mutableMapOf<ParticipantId, LinkedHashMap<Long, GameActionResult>>()
    private val actionTimes = mutableMapOf<ParticipantId, ArrayDeque<Long>>()

    /** 지금 GameModule 콜백 안이면 그 배치. 밖이면 null. */
    private var batch: Batch? = null

    private class Batch {
        val events = mutableListOf<Outgoing>()
        var publish = false
        var viewFailed = false
        var endResult: ByteArray? = null
        var ending = false
    }

    /**
     * 라우터·세션에 붙고, 호스트 UI용 loopback [GameClient]를 돌려준다 (I-15). 호스트가 첫 onJoin을 받는다.
     */
    fun attachHost(router: SessionRouter, sessions: SessionManager, scope: CoroutineScope): GameClient {
        controls = router
        router.app = this
        sessions.gameMaxPlayers = descriptor.maxPlayers
        sessions.gameId = descriptor.gameId
        sessions.gameAdmission = GameAdmission(::admit)
        val host = sessions.participants().first()
        val client = SessionGameClient(config, LoopbackPort(router, host), clock, scope)
        router.post { onJoined(host) }
        return client
    }

    /** 호스트 앱이 세션을 끝낸다 (GameHostContext.endSession과 같다). */
    fun end(result: ByteArray?) = context.endSession(result)

    // ---- 합류 검사 (TOKEN_REQ 사전 검사·HELLO, 자리 검사 다음) ----

    private fun admit(role: Role, displayName: String, gameId: String?, gameVersion: Int?): Admission.Rejected? {
        if (ended) return Admission.Rejected(TokenRejection.NoSession)
        if (gameId != null && gameId != descriptor.gameId) return Admission.Rejected(TokenRejection.GameMismatch)
        if (gameVersion != null && gameVersion < descriptor.minCompatibleVersion) return Admission.Rejected(TokenRejection.GameMismatch)
        if (role == Role.Spectator && !descriptor.allowSpectators) return Admission.Rejected(TokenRejection.SpectatorsNotAllowed)
        val decision = runCatching { module.onJoinRequest(role, displayName) }.getOrElse {
            log("onJoinRequest threw ${it::class.simpleName}")
            JoinDecision.Refuse(ActionResult.INTERNAL)
        }
        return (decision as? JoinDecision.Refuse)?.let { Admission.Rejected(TokenRejection.GameRefused, it.code) }
    }

    // ---- HostApplication ----

    override fun onJoined(participant: Participant): List<Outgoing> {
        if (ended) return emptyList()
        val outs = callModule { module.onJoin(context, participant) }.filterNot { it.channel == SessionChannel.Game && it.kind == GameKind.VIEW && it.to == Recipients.One(participant.id) }
        // 새 참가자의 첫 view는 onJoin 뒤 상태의 SNAPSHOT (03 §5)
        return snapshotFor(participant) + outs
    }

    override fun onLeft(participant: Participant, reason: LeaveReason): List<Outgoing> {
        dedup.remove(participant.id)
        actionTimes.remove(participant.id)
        if (ended) return emptyList()
        return callModule { module.onLeave(context, participant, reason) }
    }

    override fun onGameUplink(from: Participant, kind: Int, payload: ByteArray): List<Outgoing> {
        if (ended || kind != GameKind.ACTION) return emptyList()
        val action = GameAction.ADAPTER.runCatching { decode(payload) }.getOrNull() ?: return emptyList()
        val seen = dedup.getOrPut(from.id) { LinkedHashMap() }
        // 재연결 뒤 같은 actionId: GameModule을 다시 부르지 않고 처음 결과만 다시 보낸다
        seen[action.action_id]?.let { return listOf(resultTo(from, it)) }
        var outputs = emptyList<Outgoing>()
        val result = when {
            action.data_.size > config.game.maxActionBytes -> outcome(action.action_id, GameActionResult.Outcome.OUTCOME_TOO_LARGE)
            !rateAllows(from.id) -> outcome(action.action_id, GameActionResult.Outcome.OUTCOME_RATE_LIMITED)
            else -> {
                var r: ActionResult = ActionResult.Rejected(ActionResult.INTERNAL)
                val b = Batch()
                outputs = collect(b) { r = module.onAction(context, from, action.data_.toByteArray()) }
                if (threw || b.viewFailed) r = ActionResult.Rejected(ActionResult.INTERNAL)
                r.toWire(action.action_id)
            }
        }
        seen[action.action_id] = result
        while (seen.size > config.game.dedupWindow) seen.remove(seen.keys.first())
        return listOf(resultTo(from, result)) + outputs
    }

    override fun snapshotFor(participant: Participant): List<Outgoing> {
        val view = viewOf(participant) ?: return emptyList()
        return listOf(Outgoing(Recipients.One(participant.id), SessionChannel.Game, GameKind.SNAPSHOT, GameView.ADAPTER.encode(GameView(revision, view.toByteString()))))
    }

    // ---- GameHostContext ----

    private val context = object : GameHostContext {
        override fun participants(): List<Participant> = controls.participants()

        override fun publishViews() = inBatch { it.publish = true }

        override fun sendEvent(to: Target, event: ByteArray) {
            require(event.size <= config.game.maxEventBytes) { "event ${event.size}B > game.max_event_bytes" }
            inBatch { it.events += Outgoing(recipients(to), SessionChannel.Game, GameKind.EVENT, event.copyOf()) }
        }

        override fun now(): Long = clock.now()

        override fun schedule(delayMs: Long, token: String) {
            if (ended) return
            timers.remove(token)?.cancel()
            timers[token] = clock.schedule(delayMs) {
                controls.post {
                    if (timers.remove(token) == null || ended) emptyList() else callModule { module.onTimer(this, token) }
                }
            }
        }

        override fun cancel(token: String) {
            timers.remove(token)?.cancel()
        }

        override fun kick(participant: Participant, code: Int) {
            if (participant.id == controls.participants().firstOrNull()?.id) {
                log("kick of the host ignored")
                return
            }
            controls.kick(participant.id, code)
        }

        override fun endSession(result: ByteArray?) = inBatch {
            it.ending = true
            it.endResult = result
        }
    }

    // ---- 배치 ----

    private var threw = false

    /** 콜백 하나를 실행하고 그 배치를 출력으로 바꾼다. 예외면 출력을 버린다. */
    private fun callModule(block: () -> Unit): List<Outgoing> = collect(Batch(), block)

    private fun collect(b: Batch, block: () -> Unit): List<Outgoing> {
        val outer = batch
        batch = b
        threw = false
        try {
            block()
        } catch (e: Exception) {
            threw = true
            log("GameModule threw ${e::class.simpleName}")
        } finally {
            batch = outer
        }
        if (threw) return emptyList()
        val outs = b.events.toMutableList()
        if (b.publish) outs += views(b)
        if (b.ending) outs += finish(b.endResult)
        return outs
    }

    /** 콜백 밖에서 부르면 그 호출만의 배치로 HostInbox에 넣는다. */
    private fun inBatch(apply: (Batch) -> Unit) {
        batch?.let { return apply(it) }
        controls.post {
            val b = Batch()
            apply(b)
            val outs = b.events.toMutableList()
            if (b.publish) outs += views(b)
            if (b.ending) outs += finish(b.endResult)
            outs
        }
    }

    private fun views(b: Batch): List<Outgoing> {
        revision++
        return controls.participants().mapNotNull { p ->
            val view = viewOf(p)
            if (view == null) {
                b.viewFailed = true
                null
            } else {
                Outgoing(Recipients.One(p.id), SessionChannel.Game, GameKind.VIEW, GameView.ADAPTER.encode(GameView(revision, view.toByteString())))
            }
        }
    }

    private fun viewOf(p: Participant): ByteArray? {
        val view = runCatching { module.viewFor(p) }.getOrElse {
            log("viewFor threw ${it::class.simpleName}")
            return null
        }
        if (view.size > config.game.maxViewBytes) {
            log("view for participant ${p.id.value} is ${view.size}B > game.max_view_bytes")
            return null
        }
        return view
    }

    /** END를 전원에게 보내고, 이 배치를 다 보낸 뒤 링크를 정리하고 onSessionEnd를 한 번 부른다. */
    private fun finish(result: ByteArray?): List<Outgoing> {
        if (ended) return emptyList()
        ended = true
        timers.values.forEach { it.cancel() }
        timers.clear()
        controls.end()
        controls.post {
            runCatching { module.onSessionEnd() }.onFailure { log("onSessionEnd threw ${it::class.simpleName}") }
            emptyList()
        }
        val end = GameEnd(result = (result ?: ByteArray(0)).toByteString(), has_result = result != null)
        return listOf(Outgoing(Recipients.All, SessionChannel.Game, GameKind.END, GameEnd.ADAPTER.encode(end)))
    }

    private fun recipients(to: Target): Recipients = when (to) {
        is Target.One -> Recipients.One(to.participant)
        Target.All -> Recipients.All
        Target.AllPlayers -> Recipients.Many(controls.participants().filter { it.role == Role.Player }.map { it.id }.toSet())
        Target.AllSpectators -> Recipients.Many(controls.participants().filter { it.role == Role.Spectator }.map { it.id }.toSet())
    }

    private fun rateAllows(id: ParticipantId): Boolean {
        val now = clock.now()
        val times = actionTimes.getOrPut(id) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= RATE_WINDOW_MS) times.removeFirst()
        if (times.size >= config.game.maxActionsPerSec) return false
        times.addLast(now)
        return true
    }

    private fun resultTo(p: Participant, r: GameActionResult) =
        Outgoing(Recipients.One(p.id), SessionChannel.Game, GameKind.ACTION_RESULT, GameActionResult.ADAPTER.encode(r))

    private fun outcome(id: Long, o: GameActionResult.Outcome) = GameActionResult(action_id = id, outcome = o)

    private fun ActionResult.toWire(id: Long): GameActionResult = when (this) {
        ActionResult.Accepted -> outcome(id, GameActionResult.Outcome.OUTCOME_ACCEPTED)
        is ActionResult.Rejected -> GameActionResult(
            action_id = id,
            outcome = GameActionResult.Outcome.OUTCOME_REJECTED,
            code = code,
            detail = (detail ?: ByteArray(0)).toByteString(),
            has_detail = detail != null,
        )
    }

    private companion object {
        const val RATE_WINDOW_MS = 1000L
    }
}
