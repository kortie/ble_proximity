package dev.proximity.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.proximity.core.api.Role
import dev.proximity.core.node.ProximityNode
import dev.proximity.core.sensing.Bump
import dev.proximity.core.ux.Screen
import dev.proximity.core.ux.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 실기기 자동 시험용 훅 (S11). **디버그 빌드에서만** 켠다.
 * 화면 버튼과 같은 동작을 `adb shell am broadcast -a dev.proximity.sample.LAB --es cmd <명령> [--es arg <값>]`으로 실행한다.
 * 화면 덤프(uiautomator)는 매초 다시 그리는 확인 화면에서 너무 느려 20초 안에 수락하지 못했다.
 * 상태는 로그 태그 `ProxTest`로 낸다. 확인 코드·세션 코드는 시험 비교용으로만 디버그 빌드에서 낸다.
 */
class LabTestHooks(
    private val context: Context,
    private val node: ProximityNode,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val arg = intent.getStringExtra("arg") ?: ""
            when (intent.getStringExtra("cmd")) {
                "bump" -> node.onBump(Bump(now(), 3.0))
                "room" -> node.createRoom()
                "accept" -> node.accept()
                "decline" -> node.decline()
                "retry" -> node.retry()
                "cancel" -> node.cancel()
                "leave" -> node.leave()
                "end" -> node.endSession()
                "role" -> node.selectRole(if (arg == "spectator") Role.Spectator else Role.Player)
                "code" -> node.joinWithCode(arg)
                "qr" -> Log.i(TAG, "qr accepted=${node.joinWithQr(arg.ifEmpty { File(context.getExternalFilesDir(null), "qr.txt").readText() })}")
                "saveqr" -> (node.ui.value.screen as? Screen.InSession)?.host?.qr?.let { File(context.getExternalFilesDir(null), "qr.txt").writeText(it) }
                "approve" -> pending()?.let { node.answerCodeJoin(it, true) }
                "deny" -> pending()?.let { node.answerCodeJoin(it, false) }
                "action" -> scope.launch { Log.i(TAG, "action ${node.timedAction(byteArrayOf(1))}") }
                "social" -> scope.launch { Log.i(TAG, "social ${node.gameClient.value?.sendSocial(arg.encodeToByteArray())}") }
                "ping" -> scope.launch { Log.i(TAG, "ping ${node.ping()}") }
                "export" -> File(File(context.getExternalFilesDir(null), "perf").apply { mkdirs() }, "perf_${System.currentTimeMillis()}.csv").writeText(node.perf.csv())
                "state" -> log(node.ui.value)
                // 다른 사용자 프로필에서 도는 기기(adb로 앱 폴더를 못 읽음)용: 측정 CSV를 로그로 (시간 값뿐)
                "perfdump" -> node.perf.csv().lines().filter { it.isNotBlank() }.forEach { Log.i(PERF_TAG, it) }
            }
        }
    }

    private fun pending() = (node.ui.value.screen as? Screen.InSession)?.host?.pendingCodeJoins?.firstOrNull()?.id

    fun register() {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        node.debugLog = { Log.i(TAG, it) }
        scope.launch { node.ui.collect(::log) }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun log(state: UiState) {
        val detail = when (val s = state.screen) {
            is Screen.Confirm -> "peer=${s.peerName} sas=${s.sas} waiting=${s.waitingForPeer}"
            is Screen.CodeWaiting -> "sas=${s.sas}"
            is Screen.InSession -> "role=${s.role} host=${s.isHost} n=${s.participants.size} names=${s.participants.joinToString("|") { "${it.displayName}:${it.role}" }}" +
                " paused=${s.paused?.remainingMs}" + (s.host?.let { " code=${it.sessionCode} pending=${it.pendingCodeJoins.joinToString("|") { p -> "${p.displayName}:${p.sas}" }}" } ?: "")
            is Screen.Failed -> "reason=${s.reason}"
            else -> ""
        }
        Log.i(TAG, "screen=${state.screen::class.simpleName} $detail actions=${state.actions.sorted().joinToString(",")} notice=${state.notice ?: ""}")
    }

    companion object {
        const val ACTION = "dev.proximity.sample.LAB"
        const val TAG = "ProxTest"
        const val PERF_TAG = "ProxPerf"
    }
}
