package dev.proximity.core.matching

import dev.proximity.core.ble.PeerEntry
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.state.PeerRef
import kotlin.math.abs

/** 후보 선택에 필요한 내 정보: 내 세션(없으면 null)과 내 범프 시각. */
data class MatchSelf(val sessionTag: Int?, val bumpAtMs: Long)

/** 후보 제외 사유 (04-session-flows §4.1 F1~F7). [Unknown]은 광고를 본 적 없는 요청자. */
enum class Exclusion { SameSession, MemberSeesMember, FullSession, Busy, NotBumped, Weak, Cooldown, Unknown }

data class Candidate(val peer: PeerRef, val score: Double)

data class Selection(val candidates: List<Candidate>, val excluded: Map<PeerRef, Exclusion>) {
    /** 가까운 순 후보 (MatchEvent.CandidateFound에 넣는다). */
    val peers: List<PeerRef> get() = candidates.map { it.peer }
}

sealed interface IncomingDecision {
    data object Accept : IncomingDecision

    data class Refuse(val reason: Exclusion) : IncomingDecision
}

/** 실패·거절한 상대를 잠시 후보에서 뺀다 (F7). */
class Cooldown(private val clock: Clock, private val durationMs: Long) {
    private val until = mutableMapOf<PeerRef, Long>()

    fun mark(peer: PeerRef) {
        until[peer] = clock.now() + durationMs
    }

    fun isActive(peer: PeerRef): Boolean {
        val end = until[peer] ?: return false
        if (clock.now() < end) return true
        until.remove(peer)
        return false
    }
}

/**
 * 근거리 후보 선택 (S4, 04-session-flows §4.1): 제외 필터 F1~F8 → 점수(RSSI·범프 시각 근접도) 순.
 * 시도 순서·횟수(상위 max_attempts → 수동 선택)는 S1 상태 기계가 이 목록으로 정한다.
 */
class PeerMatcher(private val config: ProximityConfig.Matching) {
    private val span = (config.windowMs + config.bumpFlagWaitMs).toDouble()

    fun select(self: MatchSelf, peers: List<PeerEntry>, cooldown: Cooldown): Selection {
        val excluded = linkedMapOf<PeerRef, Exclusion>()
        val candidates = mutableListOf<Candidate>()
        // F8: 같은 tempId 관측은 하나로 (먼저 온 것)
        for (entry in peers.distinctBy { it.latest.tempId }) {
            val peer = PeerRef(entry.latest.tempId)
            val reason = exclusion(self, entry, cooldown, incoming = false)
            if (reason != null) excluded[peer] = reason else candidates += Candidate(peer, score(self, entry))
        }
        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.peer.tempId })
        return Selection(candidates, excluded)
    }

    /** 나에게 확인을 걸어온 요청자를 받을지. 범프 여부·확인 중 여부는 상태 기계가 따로 본다. */
    fun acceptIncoming(self: MatchSelf, requester: PeerEntry?, cooldown: Cooldown): IncomingDecision {
        if (requester == null) return IncomingDecision.Refuse(Exclusion.Unknown)
        return exclusion(self, requester, cooldown, incoming = true)?.let { IncomingDecision.Refuse(it) } ?: IncomingDecision.Accept
    }

    private fun exclusion(self: MatchSelf, entry: PeerEntry, cooldown: Cooldown, incoming: Boolean): Exclusion? {
        val p = entry.latest
        val mySession = self.sessionTag
        val change = entry.lastBumpChangeAtMs
        return when {
            mySession != null && p.sessionTag == mySession -> Exclusion.SameSession
            mySession != null && p.flags.inSession -> Exclusion.MemberSeesMember
            mySession == null && p.flags.inSession && !p.flags.accepting -> Exclusion.FullSession
            // 요청자는 확인을 시작하며 busy를 켜므로 받는 쪽에서는 보지 않는다
            !incoming && p.flags.busy -> Exclusion.Busy
            change == null || change < self.bumpAtMs - config.windowMs || change > self.bumpAtMs + config.bumpFlagWaitMs -> Exclusion.NotBumped
            p.rssi < config.rssiMinDbm -> Exclusion.Weak
            cooldown.isActive(PeerRef(p.tempId)) -> Exclusion.Cooldown
            else -> null
        }
    }

    private fun score(self: MatchSelf, entry: PeerEntry): Double {
        val rssi = ((entry.latest.rssi - config.rssiMinDbm).toDouble() / (config.rssiStrongDbm - config.rssiMinDbm)).coerceIn(0.0, 1.0)
        val change = entry.lastBumpChangeAtMs ?: return 0.0
        val closeness = (1 - abs(change - self.bumpAtMs) / span).coerceIn(0.0, 1.0)
        return config.weightRssi * rssi + config.weightTime * closeness
    }
}
