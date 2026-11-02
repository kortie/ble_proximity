package dev.proximity.core.ux

import dev.proximity.core.api.Role
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.state.CandidatePhase
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.MatchState

/** [UiInput] → [UiState]. 순수 함수 (S9). 앞의 조건이 이긴다. */
object UiRender {
    fun render(input: UiInput): UiState {
        val notice = input.joinRefusal?.let(UxCopy::refusal) ?: input.nameError?.let { UxCopy.nameError(it, input.maxNameBytes) }
        val screen = screenOf(input)
        return UiState(screen, actionsOf(screen, input), notice)
    }

    private fun screenOf(input: UiInput): Screen {
        if (input.displayName == null) return Screen.NeedsName(input.maxNameBytes)
        if (input.radio != RadioStatus.Ready) return Screen.RadioProblem(input.radio, UxCopy.radio(input.radio))
        input.codeJoin?.let { return Screen.CodeWaiting(it.sas) }
        when (val m = input.match) {
            is MatchState.Confirming -> {
                val c = input.confirm
                val remaining = if (c == null) input.confirmingMs else (c.startedAtMs + input.confirmingMs - input.nowMs).coerceAtLeast(0)
                return Screen.Confirm(c?.peerName ?: "", c?.sas ?: "", remaining, waitingForPeer = m.localAccepted)
            }
            is MatchState.Failed -> return Screen.Failed(m.reason, UxCopy.failure(m.reason), nextFor(m.reason))
            // 세션 중이어도(호스트·멤버가 새 기기와 범프) 범프 직후 화면이 먼저다
            is MatchState.Candidate -> return (m.phase as? CandidatePhase.Manual)?.let { manual ->
                Screen.ManualPick(
                    manual.pool.map { peer ->
                        val rssi = input.rssi[peer] ?: Int.MIN_VALUE
                        CandidateRow(peer, proximityOf(rssi, input), rssi)
                    }.sortedByDescending { it.rssi },
                )
            } ?: Screen.Searching
            else -> Unit
        }
        input.session?.let { s ->
            val paused = s.pausedSinceMs?.takeIf { s.clientState == ClientSessionState.Paused }?.let {
                PausedInfo((it + input.hostGraceMs - input.nowMs).coerceAtLeast(0))
            }
            return Screen.InSession(s.myRole, s.isHost, s.participants, paused, s.hostPanel.takeIf { s.isHost })
        }
        return when (input.match) {
            is MatchState.Matched, is MatchState.Joining, is MatchState.Introducing, MatchState.Joined -> Screen.Joining
            else -> Screen.Home(input.role, canCreateRoom = input.role == Role.Player && input.hostCapable)
        }
    }

    /** 거리 표시 (광고에 이름이 없으므로): rssi_min~rssi_strong의 가운데 이상이면 가까움, rssi_min 이상이면 보통. */
    private fun proximityOf(rssi: Int, input: UiInput) = when {
        rssi >= (input.rssiStrongDbm + input.rssiMinDbm) / 2 -> Proximity.Near
        rssi >= input.rssiMinDbm -> Proximity.Medium
        else -> Proximity.Far
    }

    private fun nextFor(reason: FailureReason): Set<UiAction> = when (reason) {
        FailureReason.NoCandidate, FailureReason.Timeout, FailureReason.Rejected -> setOf(UiAction.Retry, UiAction.EnterCode, UiAction.ScanQr)
        FailureReason.SessionFull, FailureReason.NoSessionToWatch, FailureReason.NoHostCapable -> setOf(UiAction.Retry, UiAction.ChooseRole)
        FailureReason.GameMismatch, FailureReason.SessionConflict, FailureReason.HostBidTie,
        FailureReason.Cancelled, FailureReason.Internal -> setOf(UiAction.Retry)
    }

    private fun actionsOf(screen: Screen, input: UiInput): Set<UiAction> = when (screen) {
        is Screen.NeedsName -> setOf(UiAction.SetName)
        is Screen.RadioProblem -> when (screen.status) {
            RadioStatus.BluetoothOff -> setOf(UiAction.TurnOnBluetooth)
            RadioStatus.PermissionNeeded -> setOf(UiAction.RequestPermission)
            RadioStatus.PermissionDeniedForever, RadioStatus.BackgroundRestricted -> setOf(UiAction.OpenSettings)
            RadioStatus.Unsupported, RadioStatus.Ready -> emptySet()
        }
        is Screen.Home -> buildSet {
            add(UiAction.ChooseRole)
            add(UiAction.EnterCode)
            add(UiAction.ScanQr)
            add(UiAction.SetName)
            if (screen.canCreateRoom) add(UiAction.CreateRoom)
        }
        Screen.Searching, Screen.Joining, is Screen.CodeWaiting -> setOf(UiAction.Cancel)
        is Screen.ManualPick -> setOf(UiAction.PickCandidate, UiAction.Cancel)
        is Screen.Confirm -> if (screen.waitingForPeer) setOf(UiAction.Decline) else setOf(UiAction.Accept, UiAction.Decline)
        is Screen.Failed -> screen.next
        is Screen.InSession -> buildSet {
            val live = screen.paused == null && input.session?.clientState == ClientSessionState.Live
            if (live) add(UiAction.SendSocial)
            // 관전자에게는 game 입력이 없다 (I-12의 화면 쪽)
            if (live && screen.role == Role.Player) add(UiAction.SendAction)
            add(UiAction.Leave)
            if (screen.isHost) {
                add(UiAction.EndSession)
                add(UiAction.ShowCode)
                add(UiAction.ShowQr)
                if (screen.host?.pendingCodeJoins?.isNotEmpty() == true) add(UiAction.ApproveCodeJoin)
            }
        }
    }
}
