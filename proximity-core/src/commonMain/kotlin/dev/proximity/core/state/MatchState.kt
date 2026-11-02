package dev.proximity.core.state

import dev.proximity.core.api.Role

/** 범프 상대를 가리키는 값 (광고의 임시 ID, I-7). 로그·toString에 원값을 남기지 않는다. */
data class PeerRef(val tempId: Int) {
    override fun toString(): String = "PeerRef(***)"
}

enum class StateKind { Idle, Advertising, Candidate, Confirming, Matched, Joining, Introducing, Joined, Failed }

/** 매칭 상태 (S1, 04-session-flows §3~§5). 바꾸는 길은 [MatchTransitions.transition] 하나뿐이다 (I-4'). */
sealed interface MatchState {
    val kind: StateKind

    data object Idle : MatchState {
        override val kind = StateKind.Idle
    }

    /** 광고·스캔 중, 범프를 기다린다. 세션 멤버도 범프를 받으려고 여기 머문다. */
    data object Advertising : MatchState {
        override val kind = StateKind.Advertising
    }

    data class Candidate(val phase: CandidatePhase) : MatchState {
        override val kind get() = StateKind.Candidate
    }

    /**
     * 확인 화면. 양쪽 수락이 모두 있어야 Matched (I-6).
     * [fallback]은 이 확인이 거절되면 이어서 시도할 후보 계획이다. 남이 걸어온 확인이면 null.
     */
    data class Confirming(
        val peer: PeerRef,
        val fallback: AttemptPlan?,
        val localAccepted: Boolean,
        val peerAccepted: Boolean,
    ) : MatchState {
        override val kind get() = StateKind.Confirming
    }

    data class Matched(val peer: PeerRef) : MatchState {
        override val kind get() = StateKind.Matched
    }

    /** 신규 기기: JOIN_INFO를 기다리거나(false) 받아서 호스트에 연결 중(true). */
    data class Joining(val peer: PeerRef, val joinInfoReceived: Boolean) : MatchState {
        override val kind get() = StateKind.Joining
    }

    /** 세션 멤버: 신규 기기에 token을 중개 중. 끝나면 Advertising (세션은 유지). */
    data class Introducing(val peer: PeerRef) : MatchState {
        override val kind get() = StateKind.Introducing
    }

    data object Joined : MatchState {
        override val kind = StateKind.Joined
    }

    data class Failed(val reason: FailureReason) : MatchState {
        override val kind get() = StateKind.Failed
    }
}

sealed interface CandidatePhase {
    /** 범프 직후, PeerMatcher가 후보를 고르는 중 (S4). */
    data object Collecting : CandidatePhase

    /** 후보가 없다. 상대가 나를 봤을 수 있으니 candidate_ms까지 요청을 기다린다 (S4 개정). */
    data object AwaitingIncoming : CandidatePhase

    data class Attempting(val peer: PeerRef, val plan: AttemptPlan) : CandidatePhase

    /** 자동 시도를 다 쓰고 사용자가 고르기를 기다린다. */
    data class Manual(val pool: List<PeerRef>) : CandidatePhase
}

/** 지금 시도가 실패하면 이어갈 후보: 자동으로 시도할 남은 후보, 그다음 수동 선택 목록. */
data class AttemptPlan(val autoRemaining: List<PeerRef>, val manualPool: List<PeerRef>)

enum class FailureReason {
    NoCandidate, GameMismatch, SessionConflict, NoSessionToWatch, NoHostCapable, HostBidTie, SessionFull, Rejected, Timeout, Cancelled, Internal,
}

enum class RefuseReason { Busy, NotBumped, Rejected }

/**
 * 상태와 함께 다니는 값. [timerToken]은 지금 걸려 있는 타이머의 토큰(없으면 null),
 * [tokenSeq]는 마지막으로 발급한 토큰이다. 옛 토큰의 Timeout은 무시된다.
 */
data class MatchContext(val role: Role, val timerToken: Long?, val tokenSeq: Long) {
    companion object {
        fun initial(role: Role) = MatchContext(role = role, timerToken = null, tokenSeq = 0)
    }
}
