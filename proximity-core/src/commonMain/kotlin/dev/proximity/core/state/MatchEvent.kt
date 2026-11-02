package dev.proximity.core.state

import dev.proximity.core.api.Role

enum class EventKind {
    StartAdvertising, RoleSelected, BumpDetected, CandidateFound, CandidatePicked, IncomingConfirm, RendezvousReady,
    ConfirmSent, ConfirmReceived, ConfirmRefused, ConfirmDeclined, HostDecided, JoinInfoReceived, SessionJoined,
    IntroductionFinished, Timeout, Cancel, Error,
}

/** 매칭 이벤트. 의미와 발생처는 S1 문서의 이벤트 표. */
sealed interface MatchEvent {
    val kind: EventKind

    data object StartAdvertising : MatchEvent {
        override val kind = EventKind.StartAdvertising
    }

    data class RoleSelected(val role: Role) : MatchEvent {
        override val kind get() = EventKind.RoleSelected
    }

    data class BumpDetected(val atMs: Long) : MatchEvent {
        override val kind get() = EventKind.BumpDetected
    }

    /** 가까운 순으로 정렬된 후보 (S4). 비어 있을 수 있다. */
    data class CandidateFound(val candidates: List<PeerRef>) : MatchEvent {
        override val kind get() = EventKind.CandidateFound
    }

    data class CandidatePicked(val peer: PeerRef) : MatchEvent {
        override val kind get() = EventKind.CandidatePicked
    }

    data class IncomingConfirm(val peer: PeerRef) : MatchEvent {
        override val kind get() = EventKind.IncomingConfirm
    }

    data class RendezvousReady(val peer: PeerRef) : MatchEvent {
        override val kind get() = EventKind.RendezvousReady
    }

    /** 내 사용자가 확인 화면에서 수락했다. */
    data object ConfirmSent : MatchEvent {
        override val kind = EventKind.ConfirmSent
    }

    /** 상대의 ACCEPT를 받았다. */
    data class ConfirmReceived(val peer: PeerRef) : MatchEvent {
        override val kind get() = EventKind.ConfirmReceived
    }

    data class ConfirmRefused(val peer: PeerRef, val reason: RefuseReason) : MatchEvent {
        override val kind get() = EventKind.ConfirmRefused
    }

    /** 내 사용자가 확인 화면에서 거절했다. */
    data object ConfirmDeclined : MatchEvent {
        override val kind = EventKind.ConfirmDeclined
    }

    data class HostDecided(val decision: HostDecision) : MatchEvent {
        override val kind get() = EventKind.HostDecided
    }

    data object JoinInfoReceived : MatchEvent {
        override val kind = EventKind.JoinInfoReceived
    }

    data object SessionJoined : MatchEvent {
        override val kind = EventKind.SessionJoined
    }

    data object IntroductionFinished : MatchEvent {
        override val kind = EventKind.IntroductionFinished
    }

    data class Timeout(val token: Long) : MatchEvent {
        override val kind get() = EventKind.Timeout
    }

    data object Cancel : MatchEvent {
        override val kind = EventKind.Cancel
    }

    data object Error : MatchEvent {
        override val kind = EventKind.Error
    }
}

/** decideHost 결과 (04-session-flows §3.3). */
sealed interface HostDecision {
    data object BecomeHost : HostDecision

    data object JoinNew : HostDecision

    data object JoinExisting : HostDecision

    /** 나는 세션 멤버이고 상대를 세션에 소개한다. */
    data object Introduce : HostDecision

    data class Fail(val reason: FailureReason) : HostDecision
}
