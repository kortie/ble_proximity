package dev.proximity.core.state

import dev.proximity.core.config.ProximityConfig

/** 전이에 필요한 설정값 (config/default.yaml). */
data class MatchEnv(
    val maxAttempts: Int,
    val candidateMs: Long,
    val attemptTimeoutMs: Long,
    val confirmingMs: Long,
    val joiningMs: Long,
) {
    companion object {
        fun from(config: ProximityConfig) = MatchEnv(
            maxAttempts = config.matching.maxAttempts,
            candidateMs = config.timeouts.candidateMs,
            attemptTimeoutMs = config.matching.attemptTimeoutMs,
            confirmingMs = config.timeouts.confirmingMs,
            joiningMs = config.timeouts.joiningMs,
        )
    }
}

data class Transition(val state: MatchState, val ctx: MatchContext, val effects: List<MatchEffect>)

/**
 * 매칭 전이표 (S1). 행 = 출발 상태 × 이벤트 → 가능한 도착 상태 + 규칙. 행이 없거나 규칙이 null이면 무시한다.
 * 상태를 바꾸는 유일한 길이다 (I-4'). Matched로 가는 행은 Confirming 출발뿐이다 (I-6).
 * 타이머는 규칙이 아니라 [transition]이 단계 변화를 보고 한곳에서 건다.
 */
object MatchTransitions {
    class Row internal constructor(
        val from: StateKind,
        val on: EventKind,
        val to: Set<StateKind>,
        internal val rule: (Input) -> Step?,
    )

    class Input internal constructor(val env: MatchEnv, val state: MatchState, val ctx: MatchContext, val event: MatchEvent)

    /** 규칙의 결과 (타이머 처리 전). */
    class Step internal constructor(val state: MatchState, val ctx: MatchContext, val effects: List<MatchEffect>)

    val rows: List<Row> = buildRows()
    private val index: Map<Pair<StateKind, EventKind>, Row> = rows.associateBy { it.from to it.on }

    fun transition(env: MatchEnv, state: MatchState, ctx: MatchContext, event: MatchEvent): Transition {
        val ignored = Transition(state, ctx, emptyList())
        if (event is MatchEvent.Timeout && event.token != ctx.timerToken) return ignored
        val row = index[state.kind to event.kind] ?: return ignored
        val step = row.rule(Input(env, state, ctx, event)) ?: return ignored
        check(step.state.kind in row.to) { "전이표 위반: ${state.kind} × ${event.kind} → ${step.state.kind} (선언: ${row.to})" }
        return withTimer(env, state, step)
    }

    private fun withTimer(env: MatchEnv, before: MatchState, step: Step): Transition {
        val old = timerSpec(env, before)
        val new = timerSpec(env, step.state)
        if (old?.first == new?.first) return Transition(step.state, step.ctx, step.effects)
        return if (new == null) {
            val disarm = if (step.ctx.timerToken != null) listOf(MatchEffect.DisarmTimer) else emptyList()
            Transition(step.state, step.ctx.copy(timerToken = null), disarm + step.effects)
        } else {
            val token = step.ctx.tokenSeq + 1
            Transition(
                step.state,
                step.ctx.copy(timerToken = token, tokenSeq = token),
                listOf(MatchEffect.ArmTimer(token, new.second)) + step.effects,
            )
        }
    }

    /** 단계마다 타이머 하나: (단계 키, 지연). 키가 같으면 타이머를 다시 걸지 않는다 (수락 플래그 변화 등). */
    private fun timerSpec(env: MatchEnv, state: MatchState): Pair<String, Long>? = when (state) {
        is MatchState.Candidate -> when (val phase = state.phase) {
            CandidatePhase.Collecting -> "collect" to env.candidateMs
            CandidatePhase.AwaitingIncoming -> "awaiting" to env.candidateMs
            is CandidatePhase.Attempting -> "attempt:${phase.peer.tempId}" to env.attemptTimeoutMs
            is CandidatePhase.Manual -> "manual" to env.candidateMs
        }
        is MatchState.Confirming -> "confirm:${state.peer.tempId}" to env.confirmingMs
        is MatchState.Matched -> "matched" to env.joiningMs
        is MatchState.Joining -> "joining" to env.joiningMs
        is MatchState.Introducing -> "introducing" to env.joiningMs
        MatchState.Idle, MatchState.Advertising, MatchState.Joined, is MatchState.Failed -> null
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        fun row(from: StateKind, on: EventKind, vararg to: StateKind, rule: (Input) -> Step?) {
            rows += Row(from, on, to.toSet(), rule)
        }

        // 매칭 시작
        for (from in listOf(StateKind.Idle, StateKind.Joined, StateKind.Failed)) {
            row(from, EventKind.StartAdvertising, StateKind.Advertising) {
                Step(MatchState.Advertising, it.ctx, listOf(MatchEffect.StartRadio))
            }
        }

        // 역할은 범프 전에만 바꾼다
        for (from in listOf(StateKind.Idle, StateKind.Advertising)) {
            row(from, EventKind.RoleSelected, from) {
                val role = (it.event as MatchEvent.RoleSelected).role
                if (role == it.ctx.role) null else Step(it.state, it.ctx.copy(role = role), emptyList())
            }
        }

        row(StateKind.Advertising, EventKind.BumpDetected, StateKind.Candidate) {
            val at = (it.event as MatchEvent.BumpDetected).atMs
            Step(MatchState.Candidate(CandidatePhase.Collecting), it.ctx, listOf(MatchEffect.AdvertiseBump, MatchEffect.CollectCandidates(at)))
        }

        row(StateKind.Candidate, EventKind.CandidateFound, StateKind.Candidate, StateKind.Failed) {
            if ((it.state as MatchState.Candidate).phase != CandidatePhase.Collecting) return@row null
            val found = (it.event as MatchEvent.CandidateFound).candidates.distinct()
            if (found.isEmpty()) {
                // 내가 상대를 못 봤어도 상대는 나를 봤을 수 있다: 요청을 기다린다
                return@row Step(MatchState.Candidate(CandidatePhase.AwaitingIncoming), it.ctx, emptyList())
            }
            val auto = found.take(it.env.maxAttempts)
            startPlan(it.ctx, AttemptPlan(auto, found.drop(it.env.maxAttempts)), failed = null)
        }

        row(StateKind.Candidate, EventKind.CandidatePicked, StateKind.Candidate) {
            val phase = (it.state as MatchState.Candidate).phase as? CandidatePhase.Manual ?: return@row null
            val peer = (it.event as MatchEvent.CandidatePicked).peer
            if (peer !in phase.pool) return@row null
            attempt(it.ctx, peer, AttemptPlan(emptyList(), phase.pool - peer))
        }

        // 응답 쪽: 범프 직후(Candidate)에만 받는다
        row(StateKind.Advertising, EventKind.IncomingConfirm, StateKind.Advertising) {
            refuse(it, RefuseReason.NotBumped)
        }
        row(StateKind.Candidate, EventKind.IncomingConfirm, StateKind.Candidate, StateKind.Confirming) {
            val peer = (it.event as MatchEvent.IncomingConfirm).peer
            when (val phase = (it.state as MatchState.Candidate).phase) {
                CandidatePhase.Collecting, CandidatePhase.AwaitingIncoming, is CandidatePhase.Manual ->
                    acceptIncoming(it.ctx, peer, fallback = null)
                // 서로 고른 경우: 내가 시도 중인 상대가 나에게 요청했다 → 하나의 확인으로 합친다
                is CandidatePhase.Attempting ->
                    if (phase.peer == peer) acceptIncoming(it.ctx, peer, phase.plan) else refuse(it, RefuseReason.Busy)
            }
        }
        for (from in listOf(StateKind.Idle, StateKind.Matched, StateKind.Joining, StateKind.Introducing, StateKind.Joined, StateKind.Failed)) {
            row(from, EventKind.IncomingConfirm, from) { refuse(it, RefuseReason.Busy) }
        }
        row(StateKind.Confirming, EventKind.IncomingConfirm, StateKind.Confirming) {
            val peer = (it.event as MatchEvent.IncomingConfirm).peer
            if (peer == (it.state as MatchState.Confirming).peer) null else refuse(it, RefuseReason.Busy)
        }

        row(StateKind.Candidate, EventKind.RendezvousReady, StateKind.Confirming) {
            val phase = (it.state as MatchState.Candidate).phase as? CandidatePhase.Attempting ?: return@row null
            if ((it.event as MatchEvent.RendezvousReady).peer != phase.peer) return@row null
            Step(MatchState.Confirming(phase.peer, phase.plan, localAccepted = false, peerAccepted = false), it.ctx, emptyList())
        }

        // 확인 화면: 양쪽 수락이 모두 있어야 Matched (I-6). 이 두 행만 Matched를 도착 상태로 가진다.
        row(StateKind.Confirming, EventKind.ConfirmSent, StateKind.Confirming, StateKind.Matched) {
            val s = it.state as MatchState.Confirming
            if (s.localAccepted) return@row null
            accepted(it.ctx, s.copy(localAccepted = true), listOf(MatchEffect.SendAccept(s.peer)))
        }
        row(StateKind.Confirming, EventKind.ConfirmReceived, StateKind.Confirming, StateKind.Matched) {
            val s = it.state as MatchState.Confirming
            if (s.peerAccepted || (it.event as MatchEvent.ConfirmReceived).peer != s.peer) return@row null
            accepted(it.ctx, s.copy(peerAccepted = true), emptyList())
        }

        // 거절 → 다음 후보
        row(StateKind.Candidate, EventKind.ConfirmRefused, StateKind.Candidate, StateKind.Failed) {
            val phase = (it.state as MatchState.Candidate).phase as? CandidatePhase.Attempting ?: return@row null
            if ((it.event as MatchEvent.ConfirmRefused).peer != phase.peer) return@row null
            startPlan(it.ctx, phase.plan, failed = phase.peer)
        }
        row(StateKind.Confirming, EventKind.ConfirmRefused, StateKind.Candidate, StateKind.Failed) {
            val s = it.state as MatchState.Confirming
            if ((it.event as MatchEvent.ConfirmRefused).peer != s.peer) return@row null
            afterRejection(it.ctx, s, emptyList())
        }
        row(StateKind.Confirming, EventKind.ConfirmDeclined, StateKind.Candidate, StateKind.Failed) {
            val s = it.state as MatchState.Confirming
            afterRejection(it.ctx, s, listOf(MatchEffect.SendRefuse(s.peer, RefuseReason.Rejected)))
        }

        row(StateKind.Matched, EventKind.HostDecided, StateKind.Joined, StateKind.Joining, StateKind.Introducing, StateKind.Failed) {
            val peer = (it.state as MatchState.Matched).peer
            when (val d = (it.event as MatchEvent.HostDecided).decision) {
                HostDecision.BecomeHost -> Step(MatchState.Joined, it.ctx, listOf(MatchEffect.CreateSession))
                HostDecision.JoinNew, HostDecision.JoinExisting ->
                    Step(MatchState.Joining(peer, joinInfoReceived = false), it.ctx, listOf(MatchEffect.AwaitJoinInfo(peer)))
                HostDecision.Introduce -> Step(MatchState.Introducing(peer), it.ctx, listOf(MatchEffect.StartIntroduction(peer)))
                is HostDecision.Fail -> fail(it.ctx, it.state, d.reason)
            }
        }

        row(StateKind.Joining, EventKind.JoinInfoReceived, StateKind.Joining) {
            val s = it.state as MatchState.Joining
            if (s.joinInfoReceived) null else Step(s.copy(joinInfoReceived = true), it.ctx, listOf(MatchEffect.ConnectToHost))
        }
        row(StateKind.Joining, EventKind.SessionJoined, StateKind.Joined) {
            if (!(it.state as MatchState.Joining).joinInfoReceived) null else Step(MatchState.Joined, it.ctx, listOf(MatchEffect.NotifyJoined))
        }
        row(StateKind.Introducing, EventKind.IntroductionFinished, StateKind.Advertising) {
            Step(MatchState.Advertising, it.ctx, listOf(MatchEffect.ReleaseRendezvous((it.state as MatchState.Introducing).peer)))
        }

        // 시간 초과 (transition이 옛 토큰을 이미 걸렀다)
        row(StateKind.Candidate, EventKind.Timeout, StateKind.Candidate, StateKind.Failed) {
            when (val phase = (it.state as MatchState.Candidate).phase) {
                is CandidatePhase.Attempting -> startPlan(it.ctx, phase.plan, failed = phase.peer)
                CandidatePhase.Collecting, is CandidatePhase.Manual -> fail(it.ctx, it.state, FailureReason.Timeout)
                CandidatePhase.AwaitingIncoming -> fail(it.ctx, it.state, FailureReason.NoCandidate)
            }
        }
        for (from in listOf(StateKind.Confirming, StateKind.Matched, StateKind.Joining)) {
            row(from, EventKind.Timeout, StateKind.Failed) { fail(it.ctx, it.state, FailureReason.Timeout) }
        }
        row(StateKind.Introducing, EventKind.Timeout, StateKind.Advertising) {
            Step(MatchState.Advertising, it.ctx, listOf(MatchEffect.ReleaseRendezvous((it.state as MatchState.Introducing).peer)))
        }

        // 취소·오류
        for (from in StateKind.entries - StateKind.Idle) {
            row(from, EventKind.Cancel, StateKind.Idle) {
                Step(MatchState.Idle, it.ctx, releaseOf(it.state) + MatchEffect.StopRadio)
            }
        }
        for (from in StateKind.entries - StateKind.Idle - StateKind.Failed) {
            row(from, EventKind.Error, StateKind.Failed) { fail(it.ctx, it.state, FailureReason.Internal) }
        }
        return rows
    }

    private fun attempt(ctx: MatchContext, peer: PeerRef, plan: AttemptPlan) =
        Step(MatchState.Candidate(CandidatePhase.Attempting(peer, plan)), ctx, listOf(MatchEffect.RequestConfirm(peer)))

    /** 계획에서 다음 후보를 꺼낸다. [failed]가 있으면 그 상대를 놓아주고 cooldown에 올린다. */
    private fun startPlan(ctx: MatchContext, plan: AttemptPlan, failed: PeerRef?): Step {
        val cleanup = failed?.let { listOf(MatchEffect.ReleaseRendezvous(it), MatchEffect.MarkCooldown(it)) } ?: emptyList()
        val next = when {
            plan.autoRemaining.isNotEmpty() ->
                attempt(ctx, plan.autoRemaining.first(), plan.copy(autoRemaining = plan.autoRemaining.drop(1)))
            plan.manualPool.isNotEmpty() ->
                Step(MatchState.Candidate(CandidatePhase.Manual(plan.manualPool)), ctx, listOf(MatchEffect.ShowManualSelection(plan.manualPool)))
            else -> Step(MatchState.Failed(FailureReason.NoCandidate), ctx, listOf(MatchEffect.NotifyFailed(FailureReason.NoCandidate)))
        }
        return Step(next.state, next.ctx, cleanup + next.effects)
    }

    private fun afterRejection(ctx: MatchContext, s: MatchState.Confirming, first: List<MatchEffect>): Step {
        val next = s.fallback?.let { startPlan(ctx, it, failed = s.peer) } ?: fail(ctx, s, FailureReason.Rejected)
        return Step(next.state, next.ctx, first + next.effects)
    }

    private fun accepted(ctx: MatchContext, s: MatchState.Confirming, effects: List<MatchEffect>): Step =
        if (s.localAccepted && s.peerAccepted) {
            Step(MatchState.Matched(s.peer), ctx, effects + MatchEffect.DecideHost(s.peer))
        } else {
            Step(s, ctx, effects)
        }

    private fun acceptIncoming(ctx: MatchContext, peer: PeerRef, fallback: AttemptPlan?) =
        Step(MatchState.Confirming(peer, fallback, localAccepted = false, peerAccepted = false), ctx, listOf(MatchEffect.AcceptIncoming(peer)))

    private fun refuse(input: Input, reason: RefuseReason): Step {
        val peer = (input.event as MatchEvent.IncomingConfirm).peer
        return Step(input.state, input.ctx, listOf(MatchEffect.RefuseIncoming(peer, reason)))
    }

    private fun fail(ctx: MatchContext, state: MatchState, reason: FailureReason) =
        Step(MatchState.Failed(reason), ctx, releaseOf(state) + MatchEffect.NotifyFailed(reason))

    /** 상대와 Rendezvous가 열려 있을 수 있는 상태면 놓아준다. */
    private fun releaseOf(state: MatchState): List<MatchEffect> {
        val peer = when (state) {
            is MatchState.Candidate -> (state.phase as? CandidatePhase.Attempting)?.peer
            is MatchState.Confirming -> state.peer
            is MatchState.Matched -> state.peer
            is MatchState.Joining -> state.peer
            is MatchState.Introducing -> state.peer
            else -> null
        }
        return listOfNotNull(peer?.let { MatchEffect.ReleaseRendezvous(it) })
    }
}
