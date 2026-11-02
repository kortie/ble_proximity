package dev.proximity.core.matching

import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.sensing.Bump
import dev.proximity.core.state.MatchEffect
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.core.state.PeerRef
import dev.proximity.core.state.RefuseReason

/**
 * 매칭 조정기 (S4): 범프·스캔·후보 선택을 상태 기계에 잇고, 근접 계층 효과를 직접 실행한다.
 * - StartRadio/StopRadio → 광고·스캔, AdvertiseBump → bumpSeq, CollectCandidates → bump_flag_wait_ms 뒤 후보 선택
 * - 확인 화면부터(Confirming~) 광고 busy, 범프 후 매칭이 끝날 때까지 ID 회전 pin
 * - 나머지 효과(Rendezvous·세션·UI)는 [external]로 넘긴다 (S5)
 * 한 루프에서만 부른다.
 */
class MatchCoordinator(
    private val machine: MatchStateMachine,
    private val advertiser: ProximityAdvertiser,
    private val rotator: IdRotator,
    private val scanner: ProximityScanner,
    private val matcher: PeerMatcher,
    private val cooldown: Cooldown,
    private val clock: Clock,
    private val config: ProximityConfig.Matching,
    private val external: (MatchEffect) -> Unit,
) {
    private var lastBumpAt: Long? = null

    /** 진단용: 후보 선택 결과 (실기기 시험에서 제외 사유를 본다). 임시 ID는 넘기지 않는다. */
    var onSelection: (candidates: Int, excluded: List<Exclusion>) -> Unit = { _, _ -> }

    /** 진단용: 걸려 온 확인 요청을 받을지 판단한 결과. */
    var onIncomingDecision: (IncomingDecision) -> Unit = {}

    init {
        machine.listener = ::handle
    }

    fun dispatch(event: MatchEvent) {
        machine.dispatch(event)
    }

    fun onBump(bump: Bump) {
        lastBumpAt = bump.atMs
        machine.dispatch(MatchEvent.BumpDetected(bump.atMs))
    }

    /** Rendezvous에서 [peer]가 CONFIRM_REQ를 보냈다. 필터를 통과하면 상태 기계가 범프 여부·확인 중 여부를 본다. */
    fun onIncomingConfirm(peer: PeerRef) {
        val bumpAt = lastBumpAt
        if (bumpAt != null) {
            val requester = scanner.peers().firstOrNull { it.latest.tempId == peer.tempId }
            val decision = matcher.acceptIncoming(MatchSelf(mySessionTag(), bumpAt), requester, cooldown)
            onIncomingDecision(decision)
            if (decision is IncomingDecision.Refuse && machine.state is MatchState.Candidate) {
                external(MatchEffect.RefuseIncoming(peer, RefuseReason.Rejected))
                return
            }
        }
        machine.dispatch(MatchEvent.IncomingConfirm(peer))
    }

    private fun handle(effects: List<MatchEffect>) {
        effects.forEach { effect ->
            when (effect) {
                MatchEffect.StartRadio -> {
                    advertiser.start()
                    scanner.start()
                }
                MatchEffect.StopRadio -> {
                    scanner.stop()
                    advertiser.stop()
                }
                MatchEffect.AdvertiseBump -> advertiser.bump()
                is MatchEffect.CollectCandidates -> clock.schedule(config.bumpFlagWaitMs) { collect(effect.bumpAtMs) }
                is MatchEffect.MarkCooldown -> cooldown.mark(effect.peer)
                else -> external(effect)
            }
        }
        syncAdvertising()
    }

    private fun collect(bumpAtMs: Long) {
        val selection = matcher.select(MatchSelf(mySessionTag(), bumpAtMs), scanner.peers(), cooldown)
        onSelection(selection.candidates.size, selection.excluded.values.toList())
        // 이미 다른 단계로 넘어갔으면 상태 기계가 이 이벤트를 무시한다
        machine.dispatch(MatchEvent.CandidateFound(selection.peers))
    }

    private fun mySessionTag(): Int? = advertiser.state.takeIf { it.flags.inSession }?.sessionTag

    private fun syncAdvertising() {
        val state = machine.state
        val confirming = state is MatchState.Confirming || state is MatchState.Matched ||
            state is MatchState.Joining || state is MatchState.Introducing
        advertiser.setBusy(confirming)
        // 범프 직후부터 매칭이 끝날 때까지 tempId를 바꾸지 않는다 (상대가 같은 tempId의 bumpSeq 변화를 봐야 한다)
        if (confirming || state is MatchState.Candidate) rotator.pin() else rotator.unpin()
    }
}
