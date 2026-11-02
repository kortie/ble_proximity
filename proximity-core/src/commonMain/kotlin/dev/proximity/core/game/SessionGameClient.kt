package dev.proximity.core.game

import dev.proximity.core.api.ActionOutcome
import dev.proximity.core.api.ClientState
import dev.proximity.core.api.GameClient
import dev.proximity.core.api.GameEvent
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Resynced
import dev.proximity.core.api.Role
import dev.proximity.core.api.Social
import dev.proximity.core.api.SocialOutcome
import dev.proximity.core.api.View
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.router.ClientMessage
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.SessionChannel
import dev.proximity.core.router.SessionPort
import dev.proximity.proto.GameAction
import dev.proximity.proto.GameActionResult
import dev.proximity.proto.GameEnd
import dev.proximity.proto.GameView
import dev.proximity.proto.SocialMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.resume

/**
 * [GameClient] 구현 (S8, 03-game-data-api §4). 원격 기기는 SessionClient 위에, 호스트 기기는 LoopbackPort 위에 만든다 (I-15).
 * - 배치 단위로 적용한다: 한 배치의 events를 순서대로 내보낸 뒤 그 배치의 마지막 view 하나를 내보낸다
 * - 관전자는 액션을 보내지 않는다 (NotAllowed). 크기·속도는 여기서 먼저 검사하고 호스트가 다시 막는다
 * - 재연결 뒤 결과를 못 받은 액션은 같은 actionId로 다시 보낸다 (호스트가 중복을 지운다)
 */
class SessionGameClient(
    private val config: ProximityConfig,
    private val port: SessionPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : GameClient {
    private val stateFlow = MutableStateFlow<ClientState>(map(port.clientState))
    private val rosterFlow = MutableStateFlow<List<Participant>>(emptyList())
    private val viewFlow = MutableSharedFlow<View>(replay = 1, extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val eventFlow = MutableSharedFlow<GameEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val resyncFlow = MutableSharedFlow<Resynced>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val socialFlow = MutableSharedFlow<Social>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val state: Flow<ClientState> = stateFlow
    override val participants: Flow<List<Participant>> = rosterFlow
    override val views: Flow<View> = viewFlow
    override val events: Flow<GameEvent> = eventFlow
    override val resyncs: Flow<Resynced> = resyncFlow
    override val socials: Flow<Social> = socialFlow

    /** 마지막으로 받은 view (시험·UI 편의). */
    var latestView: View? = null
        private set

    private class PendingAction(val payload: ByteArray, val result: CompletableDeferred<ActionOutcome>)

    private var nextActionId = 1L
    private val pending = linkedMapOf<Long, PendingAction>()
    private val socialWaiters = ArrayDeque<CompletableDeferred<SocialOutcome>>()
    private val actionTimes = ArrayDeque<Long>()
    private val socialTimes = ArrayDeque<Long>()
    private var endResult: ByteArray? = null

    init {
        port.onBatch = ::apply
        port.onRoster = { rosterFlow.value = it }
        port.onState = ::onPortState
        port.onDenied = { channel, _ ->
            // 호스트가 역할상 막았다: 기다리는 가장 오래된 액션이 그 대상이다
            if (channel == SessionChannel.Game) pending.keys.firstOrNull()?.let { pending.remove(it)?.result?.complete(ActionOutcome.NotAllowed) }
        }
    }

    override fun me(): Participant = port.me ?: error("아직 합류하지 않았다")

    override suspend fun sendAction(action: ByteArray): ActionOutcome {
        if (port.me?.role != Role.Player) return ActionOutcome.NotAllowed
        if (action.size > config.game.maxActionBytes) return ActionOutcome.TooLarge
        if (stateFlow.value != ClientState.Live) return ActionOutcome.NotLive
        if (!allow(actionTimes, ACTION_WINDOW_MS, config.game.maxActionsPerSec)) return ActionOutcome.RateLimited
        val id = nextActionId++
        val waiter = CompletableDeferred<ActionOutcome>()
        val payload = GameAction.ADAPTER.encode(GameAction(id, action.toByteString()))
        pending[id] = PendingAction(payload, waiter)
        port.send(SessionChannel.Game, GameKind.ACTION, payload) // 실패해도 재연결 뒤 다시 보낸다
        return waitFor(waiter, config.game.actionTimeoutMs) ?: ActionOutcome.Timeout.also { pending.remove(id) }
    }

    override suspend fun sendSocial(message: ByteArray): SocialOutcome {
        if (message.size > config.social.maxMsgBytes) return SocialOutcome.TooLarge
        if (stateFlow.value != ClientState.Live) return SocialOutcome.NotLive
        if (!allow(socialTimes, SOCIAL_WINDOW_MS, config.social.maxMsgPerMin)) return SocialOutcome.RateLimited
        val waiter = CompletableDeferred<SocialOutcome>()
        socialWaiters.addLast(waiter)
        if (!port.send(SessionChannel.Social, 1, message)) {
            socialWaiters.remove(waiter)
            return SocialOutcome.NotLive
        }
        return waitFor(waiter, config.game.actionTimeoutMs) ?: SocialOutcome.NotLive.also { socialWaiters.remove(waiter) }
    }

    // ---- 수신 ----

    private fun apply(batch: List<ClientMessage>) {
        var view: View? = null
        var resyncFrom: Long? = null
        for (m in batch) {
            when (m.channel) {
                SessionChannel.Game -> when (m.kind) {
                    GameKind.ACTION_RESULT -> GameActionResult.ADAPTER.runCatching { decode(m.payload) }.getOrNull()?.let { r ->
                        pending.remove(r.action_id)?.result?.complete(r.toOutcome())
                    }
                    GameKind.EVENT -> eventFlow.tryEmit(GameEvent(m.seq, m.payload))
                    GameKind.VIEW, GameKind.SNAPSHOT -> GameView.ADAPTER.runCatching { decode(m.payload) }.getOrNull()?.let { v ->
                        if (m.resynced && resyncFrom == null) resyncFrom = latestView?.revision ?: 0L
                        view = View(v.revision, v.data_.toByteArray())
                    }
                    GameKind.END -> GameEnd.ADAPTER.runCatching { decode(m.payload) }.getOrNull()?.let { e ->
                        endResult = if (e.has_result) e.result.toByteArray() else null
                        port.endedByHost()
                    }
                }
                SessionChannel.Social -> SocialMessage.ADAPTER.runCatching { decode(m.payload) }.getOrNull()?.let { s ->
                    val from = rosterFlow.value.firstOrNull { it.id.value == s.from } ?: Participant(ParticipantId(s.from), Role.Spectator, "")
                    if (s.from == port.me?.id?.value) socialWaiters.removeFirstOrNull()?.complete(SocialOutcome.Delivered)
                    socialFlow.tryEmit(Social(from, s.data_.toByteArray()))
                }
                SessionChannel.Control -> Unit
            }
        }
        view?.let { v ->
            latestView = v
            viewFlow.tryEmit(v)
            resyncFrom?.let { resyncFlow.tryEmit(Resynced(it, v.revision)) }
        }
    }

    private fun onPortState(s: ClientSessionState) {
        val was = stateFlow.value
        stateFlow.value = map(s)
        if (was == ClientState.Paused && s == ClientSessionState.Live) {
            // 재연결: 결과를 못 받은 액션을 같은 actionId로
            val again = pending.values.map { it.payload }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { again.forEach { port.send(SessionChannel.Game, GameKind.ACTION, it) } }
        }
        if (s == ClientSessionState.Ended) {
            pending.values.forEach { it.result.complete(ActionOutcome.NotLive) }
            pending.clear()
            socialWaiters.forEach { it.complete(SocialOutcome.NotLive) }
            socialWaiters.clear()
        }
    }

    private fun map(s: ClientSessionState): ClientState = when (s) {
        ClientSessionState.Joining -> ClientState.Syncing
        ClientSessionState.Live -> ClientState.Live
        ClientSessionState.Paused -> ClientState.Paused
        ClientSessionState.Ended -> ClientState.Ended(endResult)
    }

    private fun allow(times: ArrayDeque<Long>, windowMs: Long, max: Int): Boolean {
        val now = clock.now()
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }

    private fun GameActionResult.toOutcome(): ActionOutcome = when (outcome) {
        GameActionResult.Outcome.OUTCOME_ACCEPTED -> ActionOutcome.Accepted
        GameActionResult.Outcome.OUTCOME_NOT_ALLOWED -> ActionOutcome.NotAllowed
        GameActionResult.Outcome.OUTCOME_TOO_LARGE -> ActionOutcome.TooLarge
        GameActionResult.Outcome.OUTCOME_RATE_LIMITED -> ActionOutcome.RateLimited
        else -> ActionOutcome.Rejected(code, if (has_detail) detail.toByteArray() else null)
    }

    private suspend fun <T> waitFor(deferred: CompletableDeferred<T>, timeoutMs: Long): T? = suspendCancellableCoroutine { cont ->
        val timer = clock.schedule(timeoutMs) { if (cont.isActive) cont.resume(null) }
        deferred.invokeOnCompletion { if (cont.isActive) cont.resume(runCatching { deferred.getCompleted() }.getOrNull()) }
        cont.invokeOnCancellation { timer.cancel() }
    }

    private companion object {
        const val BUFFER = 256
        const val ACTION_WINDOW_MS = 1000L
        const val SOCIAL_WINDOW_MS = 60_000L
    }
}
