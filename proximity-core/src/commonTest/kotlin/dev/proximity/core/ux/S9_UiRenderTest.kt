package dev.proximity.core.ux

import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.router.ClientSessionState
import dev.proximity.core.router.CodeJoinRequest
import dev.proximity.core.session.TokenResult
import dev.proximity.core.state.CandidatePhase
import dev.proximity.core.state.FailureReason
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.PeerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 화면 상태 (S9). 화면은 UiState를 그리기만 하므로 여기서 검증한다. */
class S9_UiRenderTest {
    private val base = UiInput(
        displayName = "민수",
        maxNameBytes = 24,
        radio = RadioStatus.Ready,
        role = Role.Player,
        hostCapable = true,
        match = MatchState.Advertising,
        nowMs = 1_000,
    )

    @Test
    fun firstRunAsksForANickname() {
        val s = UiRender.render(base.copy(displayName = null))
        assertIs<Screen.NeedsName>(s.screen)
        assertEquals(setOf(UiAction.SetName), s.actions)
        assertEquals(UxCopy.nameTooLong(24), UiRender.render(base.copy(displayName = null, nameError = NameError.TooLong)).notice)
    }

    @Test
    fun radioProblemsShowGuidanceInsteadOfCrashing() {
        val off = UiRender.render(base.copy(radio = RadioStatus.BluetoothOff))
        assertIs<Screen.RadioProblem>(off.screen)
        assertEquals(setOf(UiAction.TurnOnBluetooth), off.actions)
        assertEquals(setOf(UiAction.RequestPermission), UiRender.render(base.copy(radio = RadioStatus.PermissionNeeded)).actions, "거부해도 다시 요청할 수 있다")
        assertEquals(setOf(UiAction.OpenSettings), UiRender.render(base.copy(radio = RadioStatus.PermissionDeniedForever)).actions)
        assertTrue(UxCopy.permissionRationale.isNotBlank(), "권한 요청 사유 문구")
        for (r in RadioStatus.entries.filter { it != RadioStatus.Ready }) assertTrue(UxCopy.radio(r).isNotBlank(), r.name)
    }

    @Test
    fun homeOffersRoomOnlyToHostCapablePlayers() {
        val home = UiRender.render(base)
        assertEquals(Screen.Home(Role.Player, canCreateRoom = true), home.screen)
        assertTrue(UiAction.CreateRoom in home.actions && UiAction.EnterCode in home.actions && UiAction.ScanQr in home.actions && UiAction.ChooseRole in home.actions)
        assertFalse(UiAction.CreateRoom in UiRender.render(base.copy(role = Role.Spectator)).actions)
        assertFalse(UiAction.CreateRoom in UiRender.render(base.copy(hostCapable = false)).actions)
    }

    @Test
    fun manualPickListsCandidatesByStrengthWithDistanceLabels() {
        val a = PeerRef(1)
        val b = PeerRef(2)
        val c = PeerRef(3)
        val input = base.copy(
            match = MatchState.Candidate(CandidatePhase.Manual(listOf(c, a, b))),
            rssi = mapOf(a to -70, b to -40, c to -85),
            rssiStrongDbm = -50,
            rssiMinDbm = -80,
        )
        val pick = assertIs<Screen.ManualPick>(UiRender.render(input).screen)
        assertEquals(listOf(b, a, c), pick.candidates.map { it.peer }, "신호 센 순")
        assertEquals(listOf(Proximity.Near, Proximity.Medium, Proximity.Far), pick.candidates.map { it.proximity })
        assertTrue(UiAction.PickCandidate in UiRender.render(input).actions)
    }

    @Test
    fun confirmScreenShowsNameCodeAndCountdown() {
        val peer = PeerRef(5)
        val input = base.copy(
            match = MatchState.Confirming(peer, null, localAccepted = false, peerAccepted = false),
            confirm = ConfirmInfo("지영", "42", startedAtMs = 0),
            confirmingMs = 20_000,
            nowMs = 5_000,
        )
        val c = assertIs<Screen.Confirm>(UiRender.render(input).screen)
        assertEquals("지영", c.peerName)
        assertEquals("42", c.sas)
        assertEquals(15_000, c.remainingMs)
        assertFalse(c.waitingForPeer)
        assertEquals(setOf(UiAction.Accept, UiAction.Decline), UiRender.render(input).actions)
        val waiting = UiRender.render(input.copy(match = MatchState.Confirming(peer, null, localAccepted = true, peerAccepted = false)))
        assertTrue(assertIs<Screen.Confirm>(waiting.screen).waitingForPeer, "상대 대기 표시")
        assertEquals(setOf(UiAction.Decline), waiting.actions)
    }

    @Test
    fun failuresTellWhatToDoNext() {
        for (reason in FailureReason.entries) {
            val s = UiRender.render(base.copy(match = MatchState.Failed(reason)))
            val f = assertIs<Screen.Failed>(s.screen)
            assertTrue(f.message.isNotBlank(), reason.name)
            assertTrue(f.next.isNotEmpty(), "${reason.name}: 다음 행동")
        }
        assertTrue(UiAction.EnterCode in (UiRender.render(base.copy(match = MatchState.Failed(FailureReason.NoCandidate))).screen as Screen.Failed).next)
    }

    @Test
    fun capacityRefusalsHaveTheirOwnMessages() {
        val codes = listOf(TokenResult.SESSION_FULL, TokenResult.PLAYER_SLOTS_FULL, TokenResult.SPECTATOR_SLOTS_FULL, TokenResult.SPECTATORS_NOT_ALLOWED, TokenResult.GAME_MISMATCH, TokenResult.BAD_CODE, TokenResult.CODE_DENIED)
        val messages = codes.map { UxCopy.refusal(it) }
        assertEquals(codes.size, messages.toSet().size, "사유마다 다른 문구")
        assertTrue(UxCopy.refusal(TokenResult.PLAYER_SLOTS_FULL).contains("관전"), "플레이어 자리가 없으면 관전을 권한다")
        val s = UiRender.render(base.copy(joinRefusal = TokenResult.PLAYER_SLOTS_FULL))
        assertEquals(UxCopy.refusal(TokenResult.PLAYER_SLOTS_FULL), s.notice)
    }

    private val players = listOf(Participant(ParticipantId(1), Role.Player, "H"), Participant(ParticipantId(2), Role.Spectator, "S"))

    @Test
    fun spectatorSessionScreenHasNoGameInput() {
        val s = UiRender.render(base.copy(role = Role.Spectator, session = SessionView(isHost = false, myRole = Role.Spectator, participants = players, clientState = ClientSessionState.Live)))
        val screen = assertIs<Screen.InSession>(s.screen)
        assertEquals(players, screen.participants, "참가자·역할 목록")
        assertFalse(UiAction.SendAction in s.actions, "관전자에게 game 입력 없음")
        assertTrue(UiAction.SendSocial in s.actions && UiAction.Leave in s.actions)
        val player = UiRender.render(base.copy(session = SessionView(false, Role.Player, players, ClientSessionState.Live)))
        assertTrue(UiAction.SendAction in player.actions)
    }

    @Test
    fun pausedSessionShowsReconnectCountdown() {
        val s = UiRender.render(base.copy(
            nowMs = 4_000,
            hostGraceMs = 15_000,
            session = SessionView(false, Role.Player, players, ClientSessionState.Paused, pausedSinceMs = 1_000),
        ))
        assertEquals(12_000, assertIs<Screen.InSession>(s.screen).paused!!.remainingMs)
        assertFalse(UiAction.SendAction in s.actions, "Paused 중에는 보내지 않는다")
    }

    @Test
    fun hostPanelShowsCodeQrAndCodeJoinApprovals() {
        val req = CodeJoinRequest(3, "코드손님", Role.Player, "17")
        val s = UiRender.render(base.copy(session = SessionView(true, Role.Player, players, ClientSessionState.Live, hostPanel = HostPanel("123456", "BP1.x", listOf(req), maxPlayersNote = null))))
        val panel = assertIs<Screen.InSession>(s.screen).host!!
        assertEquals("123456", panel.sessionCode)
        assertEquals(listOf(req), panel.pendingCodeJoins)
        assertTrue(UiAction.ApproveCodeJoin in s.actions && UiAction.EndSession in s.actions)
    }

    @Test
    fun codeJoinWaitingShowsTheLinkCode() {
        val s = UiRender.render(base.copy(codeJoin = CodeJoinView(sas = "17")))
        assertEquals(Screen.CodeWaiting("17"), s.screen)
        assertEquals(setOf(UiAction.Cancel), s.actions)
    }
}
