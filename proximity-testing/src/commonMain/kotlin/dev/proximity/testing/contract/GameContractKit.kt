package dev.proximity.testing.contract

import dev.proximity.core.api.ActionResult
import dev.proximity.core.api.GameDescriptor
import dev.proximity.core.api.GameHostContext
import dev.proximity.core.api.JoinDecision
import dev.proximity.core.api.LeaveReason
import dev.proximity.core.api.Participant
import dev.proximity.core.api.ParticipantId
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.game.SessionGameClient
import dev.proximity.core.host.GameHost
import dev.proximity.core.interfaces.CryptoBox
import dev.proximity.core.interfaces.GameModule
import dev.proximity.core.router.HostInbox
import dev.proximity.core.router.SessionClient
import dev.proximity.core.router.SessionRouter
import dev.proximity.core.session.JoinOutcome
import dev.proximity.core.session.JoinRequest
import dev.proximity.core.session.SessionManager
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeSessionNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * GameModule 계약 테스트 키트 (S8, tests/contract/). 게임 저장소에서 자기 GameModule로 돌린다.
 *
 * 시나리오: 호스트 + 플레이어 2 (+ 관전자 1, allowSpectators면) 합류 → 플레이어가 돌아가며 [steps]번 액션 → 한 명 이탈 → 호스트 앱이 종료.
 * 검사 (위반 문구 목록을 돌려준다, 비면 통과):
 * - 호출 순서: 참가자의 onJoin 전에 그 참가자의 onAction 없음, onSessionEnd 정확히 1회, 그 뒤 호출 0
 * - 관전자의 onAction 0 (I-12), GameModule 예외 0, view·event 크기 한도 안
 * - viewFor 결정성: 상태가 그대로면 두 번 불러도 같은 bytes
 * - view 계약: 끝나기 직전 각 기기의 마지막 view = 호스트의 viewFor(그 참가자)
 *
 * [scope]는 Unconfined 디스패처를 권장한다 (runTest의 backgroundScope + UnconfinedTestDispatcher). 실제 암호가 필요하다.
 */
class GameContractKit(
    private val module: GameModule,
    private val config: ProximityConfig,
    private val crypto: CryptoBox,
    private val scope: CoroutineScope,
    private val clock: FakeClock = FakeClock(),
) {
    /** [actions]: (보내는 플레이어, 몇 번째) → 액션 bytes. 게임 규칙상 거절되는 액션이어도 된다. */
    suspend fun run(steps: Int = 20, actions: (Participant, Int) -> ByteArray): List<String> {
        val violations = mutableListOf<String>()
        val recorder = Recorder(module, violations)
        val net = FakeSessionNetwork()
        val sessions = SessionManager(config.session, clock, crypto)
        val session = sessions.create(Role.Player, "host")
        val hostTransport = net.host()
        hostTransport.open(session.sessionTag, ByteArray(0))
        val router = SessionRouter(config, hostTransport, HostInbox(clock), sessions, crypto, clock, scope)
        router.start()
        val gameHost = GameHost(config, recorder, clock)
        gameHost.log = { line -> if ("threw" in line || "max_" in line) violations += "core log: $line" }
        val host = gameHost.attachHost(router, sessions, scope) as SessionGameClient

        val descriptor = recorder.descriptor()
        val clients = mutableListOf(host)
        val roles = mutableListOf(Role.Player, Role.Player)
        if (descriptor.allowSpectators) roles += Role.Spectator
        roles.forEachIndexed { i, role ->
            val nonce = crypto.randomBytes(NONCE_BYTES)
            val ticket = sessions.issueToken(nonce, role) ?: return violations.also { it += "token 발급 실패" }
            val sc = SessionClient(config, net.client(), crypto, clock, scope)
            val gc = SessionGameClient(config, sc, clock, scope)
            when (val o = sc.join(ticket, JoinRequest(nonce, role, "p$i", descriptor.gameId, descriptor.gameVersion))) {
                is JoinOutcome.Joined -> clients += gc
                else -> violations += "합류 실패 ($role): $o"
            }
        }

        val players = clients.filter { it.me().role == Role.Player }
        for (step in 0 until steps) {
            val p = players[step % players.size]
            scope.launch { p.sendAction(actions(p.me(), step)) }
            clock.advanceBy(STEP_MS)
            for (c in clients) {
                val a = recorder.inner.viewFor(c.me())
                val b = recorder.inner.viewFor(c.me())
                if (!a.contentEquals(b)) violations += "viewFor가 결정적이지 않다 (${c.me().id.value})"
            }
        }
        for (c in clients) {
            val latest = c.latestView ?: continue
            if (!latest.data.contentEquals(recorder.inner.viewFor(c.me()))) {
                violations += "참가자 ${c.me().id.value}의 마지막 view가 호스트 상태와 다르다 (view 계약)"
            }
        }
        router.kick(clients.last().me().id, 0) // 이탈 하나 (onLeave)
        gameHost.end(null)
        clock.advanceBy(STEP_MS)
        if (recorder.sessionEnds != 1) violations += "onSessionEnd ${recorder.sessionEnds}회 (정확히 1회여야 한다)"
        return violations
    }

    /** 호출 순서를 기록하는 감싸개. */
    private class Recorder(val inner: GameModule, val violations: MutableList<String>) : GameModule {
        private val joined = mutableSetOf<ParticipantId>()
        var sessionEnds = 0

        private fun afterEnd(name: String) {
            if (sessionEnds > 0) violations += "onSessionEnd 뒤 $name 호출"
        }

        override fun descriptor(): GameDescriptor = inner.descriptor()

        override fun onJoinRequest(role: Role, displayName: String): JoinDecision = inner.onJoinRequest(role, displayName)

        override fun onJoin(ctx: GameHostContext, participant: Participant) {
            afterEnd("onJoin")
            joined += participant.id
            inner.onJoin(ctx, participant)
        }

        override fun onAction(ctx: GameHostContext, sender: Participant, action: ByteArray): ActionResult {
            afterEnd("onAction")
            if (sender.id !in joined) violations += "onJoin 전에 onAction (${sender.id.value})"
            if (sender.role != Role.Player) violations += "관전자의 onAction (I-12)"
            return inner.onAction(ctx, sender, action)
        }

        override fun viewFor(participant: Participant): ByteArray = inner.viewFor(participant)

        override fun onTimer(ctx: GameHostContext, token: String) {
            afterEnd("onTimer")
            inner.onTimer(ctx, token)
        }

        override fun onLeave(ctx: GameHostContext, participant: Participant, reason: LeaveReason) {
            afterEnd("onLeave")
            joined -= participant.id
            inner.onLeave(ctx, participant, reason)
        }

        override fun onSessionEnd() {
            sessionEnds++
            inner.onSessionEnd()
        }
    }

    private companion object {
        const val NONCE_BYTES = 16
        const val STEP_MS = 250L
    }
}
