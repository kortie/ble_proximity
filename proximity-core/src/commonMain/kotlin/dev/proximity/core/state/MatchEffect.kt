package dev.proximity.core.state

/**
 * 전이의 결과로 바깥(광고·Rendezvous·세션·UI)이 해야 할 일. 상태 기계는 직접 실행하지 않는다.
 * [ArmTimer]·[DisarmTimer]는 [MatchStateMachine]이 Clock으로 처리하고, 나머지는 호출자에게 돌려준다.
 */
sealed interface MatchEffect {
    /** 로그용 이름. 상대·값을 담지 않는다. */
    val logName: String

    data class ArmTimer(val token: Long, val delayMs: Long) : MatchEffect {
        override val logName get() = "ArmTimer"
    }

    data object DisarmTimer : MatchEffect {
        override val logName = "DisarmTimer"
    }

    data object StartRadio : MatchEffect {
        override val logName = "StartRadio"
    }

    data object StopRadio : MatchEffect {
        override val logName = "StopRadio"
    }

    /** 광고의 bumpSeq를 올린다 (04-session-flows §2). */
    data object AdvertiseBump : MatchEffect {
        override val logName = "AdvertiseBump"
    }

    data class CollectCandidates(val bumpAtMs: Long) : MatchEffect {
        override val logName get() = "CollectCandidates"
    }

    data class RequestConfirm(val peer: PeerRef) : MatchEffect {
        override val logName get() = "RequestConfirm"
    }

    data class AcceptIncoming(val peer: PeerRef) : MatchEffect {
        override val logName get() = "AcceptIncoming"
    }

    data class RefuseIncoming(val peer: PeerRef, val reason: RefuseReason) : MatchEffect {
        override val logName get() = "RefuseIncoming"
    }

    data class SendAccept(val peer: PeerRef) : MatchEffect {
        override val logName get() = "SendAccept"
    }

    data class SendRefuse(val peer: PeerRef, val reason: RefuseReason) : MatchEffect {
        override val logName get() = "SendRefuse"
    }

    data class ReleaseRendezvous(val peer: PeerRef) : MatchEffect {
        override val logName get() = "ReleaseRendezvous"
    }

    /** 실패·거절한 상대를 retry_cooldown_ms 동안 후보에서 뺀다 (S4 F7). */
    data class MarkCooldown(val peer: PeerRef) : MatchEffect {
        override val logName get() = "MarkCooldown"
    }

    data class ShowManualSelection(val candidates: List<PeerRef>) : MatchEffect {
        override val logName get() = "ShowManualSelection"
    }

    data class DecideHost(val peer: PeerRef) : MatchEffect {
        override val logName get() = "DecideHost"
    }

    data object CreateSession : MatchEffect {
        override val logName = "CreateSession"
    }

    data class AwaitJoinInfo(val peer: PeerRef) : MatchEffect {
        override val logName get() = "AwaitJoinInfo"
    }

    data class StartIntroduction(val peer: PeerRef) : MatchEffect {
        override val logName get() = "StartIntroduction"
    }

    data object ConnectToHost : MatchEffect {
        override val logName = "ConnectToHost"
    }

    data object NotifyJoined : MatchEffect {
        override val logName = "NotifyJoined"
    }

    data class NotifyFailed(val reason: FailureReason) : MatchEffect {
        override val logName get() = "NotifyFailed"
    }
}
