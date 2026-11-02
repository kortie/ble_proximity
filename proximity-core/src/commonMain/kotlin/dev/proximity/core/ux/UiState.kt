package dev.proximity.core.ux

import dev.proximity.core.api.Participant
import dev.proximity.core.api.Role
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.CodeJoinRequest
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.PeerRef

/** 플랫폼 무선 상태를 화면용으로 줄인 것. 앱이 [dev.proximity.core.node.ProximityNode.setRadio]로 넘긴다. */
enum class RadioStatus { Ready, BluetoothOff, PermissionNeeded, PermissionDeniedForever, BackgroundRestricted, Unsupported }

enum class Proximity { Near, Medium, Far }

/** 사용자가 누를 수 있는 것. 화면은 여기 있는 것만 보여 준다. */
enum class UiAction {
    SetName, ChooseRole, CreateRoom, EnterCode, ScanQr, PickCandidate, Accept, Decline, Cancel, Retry,
    SendAction, SendSocial, Leave, EndSession, ShowCode, ShowQr, ApproveCodeJoin,
    TurnOnBluetooth, RequestPermission, OpenSettings,
}

enum class NameError { Blank, TooLong }

data class CandidateRow(val peer: PeerRef, val proximity: Proximity, val rssi: Int)

data class PausedInfo(val remainingMs: Long)

/** 호스트 화면에만 있는 것: 세션 코드, QR, 코드 합류 승인 대기, 인원 축소 안내. */
data class HostPanel(val sessionCode: String, val qr: String, val pendingCodeJoins: List<CodeJoinRequest>, val maxPlayersNote: String?)

sealed interface Screen {
    data class NeedsName(val maxBytes: Int) : Screen

    data class RadioProblem(val status: RadioStatus, val message: String) : Screen

    data class Home(val role: Role, val canCreateRoom: Boolean) : Screen

    data object Searching : Screen

    data class ManualPick(val candidates: List<CandidateRow>) : Screen

    /** D6: 상대 닉네임 + 확인 코드, 양쪽 수락. [remainingMs]는 confirming_ms 남은 시간. */
    data class Confirm(val peerName: String, val sas: String, val remainingMs: Long, val waitingForPeer: Boolean) : Screen

    /** 세션 코드 합류: 호스트 화면의 확인 코드와 같은지 보라고 안내한다. */
    data class CodeWaiting(val sas: String?) : Screen

    data object Joining : Screen

    data class InSession(
        val role: Role,
        val isHost: Boolean,
        val participants: List<Participant>,
        val paused: PausedInfo?,
        val host: HostPanel?,
    ) : Screen

    data class Failed(val reason: FailureReason, val message: String, val next: Set<UiAction>) : Screen
}

data class UiState(val screen: Screen, val actions: Set<UiAction>, val notice: String?)

// ---- 입력 ----

data class ConfirmInfo(val peerName: String, val sas: String, val startedAtMs: Long)

data class SessionView(
    val isHost: Boolean,
    val myRole: Role,
    val participants: List<Participant>,
    val clientState: ClientSessionState,
    val pausedSinceMs: Long? = null,
    val hostPanel: HostPanel? = null,
)

data class CodeJoinView(val sas: String?)

/** 화면을 그리는 데 필요한 사실 전부. [UiRender.render]는 이것만 본다. */
data class UiInput(
    val displayName: String?,
    val maxNameBytes: Int,
    val radio: RadioStatus,
    val role: Role,
    val hostCapable: Boolean,
    val match: MatchState?,
    val nowMs: Long,
    val nameError: NameError? = null,
    val confirm: ConfirmInfo? = null,
    val confirmingMs: Long = 0,
    val rssi: Map<PeerRef, Int> = emptyMap(),
    val rssiStrongDbm: Int = -50,
    val rssiMinDbm: Int = -80,
    val session: SessionView? = null,
    val hostGraceMs: Long = 0,
    val codeJoin: CodeJoinView? = null,
    val joinRefusal: Int? = null,
)
