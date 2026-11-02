package dev.proximity.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.BatteryManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import dev.proximity.core.api.GameClient
import dev.proximity.core.api.Role
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.node.Metric
import dev.proximity.core.node.NodePlatform
import dev.proximity.core.node.ProximityNode
import dev.proximity.core.ux.Proximity
import dev.proximity.core.ux.RadioStatus
import dev.proximity.core.ux.Screen
import dev.proximity.core.ux.UiAction
import dev.proximity.core.ux.UiState
import dev.proximity.core.ux.UxCopy
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.platform.AndroidBumpSensor
import dev.proximity.platform.AndroidClock
import dev.proximity.platform.AndroidRadio
import dev.proximity.platform.RadioCheck
import dev.proximity.platform.RadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proximity Lab — 세션 화면 (S9). 라이브러리 파사드 [ProximityNode]의 사용 예다.
 * 이 화면은 [ProximityNode.ui]를 그리기만 하고, 게임 데이터는 [GameClient]로만 주고받는다 (I-5·I-15).
 * 실제 동작 확인은 S11 실기기 항목 (HW-S9).
 */
class SessionLabActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = AndroidClock()
    private lateinit var node: ProximityNode
    private var hooks: LabTestHooks? = null
    private lateinit var root: LinearLayout
    private var asked = false
    private var gameJobs = listOf<Job>()
    private var viewText = ""
    private val events = ArrayDeque<String>()
    private val socials = ArrayDeque<String>()
    private var lastOutcome = ""
    private var qrShown: String? = null
    private var qrBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTitle()
        val config = ProximityConfig.fromYaml(assets.open("default.yaml").bufferedReader().readText())
        val radio = AndroidRadio(this, clock, scope, config.matching.attemptTimeoutMs)
        val platform = NodePlatform(radio.ble, radio.rendezvous, radio.sessionHost, radio::newSessionClient, AndroidBumpSensor(this, config.bump.samplePeriodUs))
        node = ProximityNode(config, platform, BouncyCastleCryptoBox(), clock, scope, DemoGame())
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }
        setContentView(ScrollView(this).apply {
            addView(root)
            padForSystemBars()
        })

        prefs().getString(KEY_NAME, null)?.let(node::setDisplayName)
        scope.launch { RadioCheck.observe(this@SessionLabActivity).collect { node.setRadio(statusOf(it)) } }
        scope.launch { node.ui.collect(::render) }
        hooks = LabTestHooks(this, node, scope) { clock.now() }.also { it.register() }
        scope.launch { node.gameClient.collect(::follow) }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                node.refresh()
            }
        }
        // 배터리 표본 (S11 배터리 기준): 1분마다
        scope.launch {
            val battery = getSystemService(BatteryManager::class.java)
            while (isActive) {
                battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { node.perf.recordBattery(clock.now(), it) }
                delay(BATTERY_SAMPLE_MS)
            }
        }
    }

    /** 측정값을 앱 외부 파일 디렉터리(perf/)에 CSV로 — `adb pull` 후 scripts/perf-report.py. */
    private fun exportPerf() {
        val dir = java.io.File(getExternalFilesDir(null), "perf").apply { mkdirs() }
        val file = java.io.File(dir, "perf_${System.currentTimeMillis()}.csv")
        file.writeText(node.perf.csv())
        lastOutcome = "측정 내보냄: perf/${file.name}"
        redraw()
    }

    /** 설정 화면에서 권한을 켜고 돌아온 경우: 블루투스 방송이 없으니 여기서 다시 확인한다 (S11 실기기에서 발견). */
    override fun onResume() {
        super.onResume()
        if (::node.isInitialized) node.setRadio(statusOf(RadioCheck.current(this)))
    }

    override fun onDestroy() {
        // 세션 정리: 호스트는 END, 멤버는 LEAVE, 그 뒤 무선 정지 (S10 고아 세션 0)
        hooks?.unregister()
        node.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asked = true
        node.setRadio(statusOf(RadioCheck.current(this)))
    }

    @Deprecated("Activity 결과 API 대신 간단히 쓴다 (Lab)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QR_PHOTO && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val photo = data?.extras?.get("data") as? Bitmap
            val text = photo?.let(::decodeQr)
            if (text == null || !node.joinWithQr(text)) lastOutcome = "QR을 읽지 못했습니다. 더 가까이 찍거나 문자열을 붙여 넣으세요."
            redraw()
        }
        if (requestCode == REQUEST_ENABLE_BT) node.setRadio(statusOf(RadioCheck.current(this)))
    }

    /** 권한을 한 번 거절한 뒤 다시 물을 수 없으면 "설정 열기"로 안내한다. */
    private fun statusOf(state: RadioState): RadioStatus = when (state) {
        RadioState.Ready -> RadioStatus.Ready
        RadioState.BluetoothOff -> RadioStatus.BluetoothOff
        RadioState.Unsupported -> RadioStatus.Unsupported
        is RadioState.PermissionMissing ->
            if (asked && !shouldShowRequestPermissionRationale(state.permission)) RadioStatus.PermissionDeniedForever else RadioStatus.PermissionNeeded
        is RadioState.Failed -> RadioStatus.Ready
    }

    private fun follow(client: GameClient?) {
        gameJobs.forEach { it.cancel() }
        viewText = ""
        events.clear()
        socials.clear()
        if (client == null) return
        gameJobs = listOf(
            scope.launch { client.views.collect { viewText = it.data.decodeToString(); redraw() } },
            scope.launch { client.events.collect { keep(events, it.data.decodeToString()); redraw() } },
            scope.launch { client.socials.collect { keep(socials, "${it.from.displayName}: ${it.data.decodeToString()}"); redraw() } },
            scope.launch { client.resyncs.collect { keep(events, "(다시 맞춤: 일부 이벤트를 건너뜀)"); redraw() } },
        )
    }

    private fun keep(list: ArrayDeque<String>, line: String) {
        list.addLast(line)
        while (list.size > KEEP) list.removeFirst()
    }

    // ---- 그리기 ----

    /**
     * 게임 데이터·측정값·결과 문구는 UiState 밖에 있어서, 바뀌어도 node.ui가 새 값을 내지 않는다.
     * 그런 때는 지금 상태로 직접 다시 그린다 (S11 실기기에서 발견: 액션 40번 뒤에도 "카운터 0").
     */
    private fun redraw() = render(node.ui.value)

    private var lastScreenName = ""

    private fun render(state: UiState) {
        // 실기기 시험용: 화면 종류가 바뀔 때만 로그 (내용·이름·코드는 남기지 않음)
        val name = state.screen::class.simpleName ?: ""
        if (name != lastScreenName) {
            lastScreenName = name
            android.util.Log.i("ProxSession", "screen=$name")
        }
        root.removeAllViews()
        state.notice?.let { root.addView(text(it, Color.rgb(0xB0, 0x30, 0x30))) }
        if (lastOutcome.isNotEmpty()) root.addView(text(lastOutcome, Color.DKGRAY))
        when (val s = state.screen) {
            is Screen.NeedsName -> nameScreen(s.maxBytes)
            is Screen.RadioProblem -> radioScreen(s, state.actions)
            is Screen.Home -> homeScreen(s, state.actions)
            Screen.Searching -> {
                root.addView(big("상대를 찾는 중…"))
                root.addView(button("취소") { node.cancel() })
            }
            is Screen.ManualPick -> {
                root.addView(text("자동으로 정하지 못했습니다. 부딪힌 기기를 고르세요 (가까운 순)."))
                s.candidates.forEachIndexed { i, c ->
                    val label = when (c.proximity) {
                        Proximity.Near -> "가까움"
                        Proximity.Medium -> "보통"
                        Proximity.Far -> "멂"
                    }
                    root.addView(button("기기 ${i + 1} · $label (${c.rssi}dBm)") { node.pick(c.peer) })
                }
                root.addView(button("취소") { node.cancel() })
            }
            is Screen.Confirm -> {
                root.addView(text("상대"))
                root.addView(big(s.peerName))
                root.addView(text("확인 코드 — 두 화면의 코드가 같으면 수락하세요"))
                root.addView(huge(s.sas))
                root.addView(text("남은 시간 ${s.remainingMs / 1000}초"))
                if (s.waitingForPeer) root.addView(text("상대의 수락을 기다리는 중…"))
                if (UiAction.Accept in state.actions) root.addView(button("수락") { node.accept() })
                root.addView(button("거절") { node.decline() })
            }
            is Screen.CodeWaiting -> {
                root.addView(text("호스트 화면에 아래 코드가 보이면 기다리세요. 다르면 취소하세요."))
                root.addView(huge(s.sas ?: "…"))
                root.addView(button("취소") { node.cancel() })
            }
            Screen.Joining -> root.addView(big("세션에 들어가는 중…"))
            is Screen.InSession -> sessionScreen(s, state.actions)
            is Screen.Failed -> {
                root.addView(big("실패"))
                root.addView(text(s.message))
                if (UiAction.Retry in s.next) root.addView(button("다시 부딪히기") { node.retry() })
                if (UiAction.EnterCode in s.next || UiAction.ScanQr in s.next) root.addView(button("코드·QR로 들어가기") { node.retry() })
                if (UiAction.ChooseRole in s.next) root.addView(button("관전자로 바꾸기") { node.selectRole(Role.Spectator); node.retry() })
            }
        }
    }

    private fun nameScreen(maxBytes: Int) {
        root.addView(big("닉네임"))
        root.addView(text("상대 기기의 확인 화면과 참가자 목록에 보입니다. 최대 ${maxBytes}바이트."))
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(input)
        root.addView(button("저장") {
            val name = input.text.toString()
            if (node.setDisplayName(name)) prefs().edit().putString(KEY_NAME, name.trim()).apply()
        })
    }

    private fun radioScreen(s: Screen.RadioProblem, actions: Set<UiAction>) {
        root.addView(text(s.message))
        if (UiAction.RequestPermission in actions) {
            root.addView(text(UxCopy.permissionRationale))
            root.addView(button("권한 요청") { requestPermissions(RadioCheck.permissions.toTypedArray(), REQUEST_PERMISSIONS) })
        }
        if (UiAction.OpenSettings in actions) {
            root.addView(button("설정 열기") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
        }
        @Suppress("DEPRECATION")
        if (UiAction.TurnOnBluetooth in actions) root.addView(button("블루투스 켜기") { startActivityForResult(RadioCheck.enableBluetoothIntent(), REQUEST_ENABLE_BT) })
    }

    private fun homeScreen(s: Screen.Home, actions: Set<UiAction>) {
        root.addView(big("범프로 합류"))
        root.addView(text("휴대폰끼리 가볍게 부딪히세요. 역할: ${if (s.role == Role.Player) "플레이어" else "관전자"}"))
        root.addView(button(if (s.role == Role.Player) "관전자로 바꾸기" else "플레이어로 바꾸기") {
            node.selectRole(if (s.role == Role.Player) Role.Spectator else Role.Player)
        })
        if (UiAction.CreateRoom in actions) root.addView(button("방 만들기") { node.createRoom() })
        root.addView(button("범프 (센서 대신)") { node.onBump(dev.proximity.core.sensing.Bump(clock.now(), BUTTON_PEAK_G)) })
        root.addView(text("세션 코드 (숫자 6자리)"))
        val code = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(code)
        root.addView(button("코드로 들어가기") { node.joinWithCode(code.text.toString()) })
        @Suppress("DEPRECATION")
        root.addView(button("QR 찍기") { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQUEST_QR_PHOTO) })
        val paste = EditText(this).apply { hint = "또는 QR 문자열(BP1.…) 붙여 넣기" }
        root.addView(paste)
        root.addView(button("QR 문자열로 들어가기") { if (!node.joinWithQr(paste.text.toString())) lastOutcome = "QR 문자열이 올바르지 않습니다." })
    }

    private fun sessionScreen(s: Screen.InSession, actions: Set<UiAction>) {
        root.addView(big(if (s.isHost) "세션 (호스트)" else if (s.role == Role.Spectator) "세션 (관전)" else "세션"))
        s.paused?.let { root.addView(text("호스트와 연결이 끊겼습니다. ${it.remainingMs / 1000}초 안에 다시 연결합니다…", Color.rgb(0xB0, 0x60, 0x00))) }
        // 세션 중에도 새 기기와 부딪혀 받는다 (센서 대신 버튼, 실기기 시험용)
        root.addView(button("범프 (센서 대신)") { node.onBump(dev.proximity.core.sensing.Bump(clock.now(), BUTTON_PEAK_G)) })
        root.addView(text("참가자: " + s.participants.joinToString { "${it.displayName}(${if (it.role == Role.Player) "플레이어" else "관전"})" }))
        s.host?.let { panel ->
            panel.maxPlayersNote?.let { root.addView(text(it)) }
            root.addView(text("세션 코드"))
            root.addView(huge(panel.sessionCode))
            if (panel.qr.isNotEmpty()) root.addView(qrView(panel.qr))
            root.addView(button("새 QR") { node.refreshQr() })
            panel.pendingCodeJoins.forEach { req ->
                root.addView(text("${req.displayName}(${if (req.role == Role.Player) "플레이어" else "관전"})가 코드로 합류 요청 — 상대 화면 코드: ${req.sas}"))
                root.addView(button("수락 (코드 ${req.sas})") { node.answerCodeJoin(req.id, true) })
                root.addView(button("거절") { node.answerCodeJoin(req.id, false) })
            }
        }
        root.addView(big(viewText.ifEmpty { "(view 없음)" }))
        events.forEach { root.addView(text("· $it")) }
        val client = node.gameClient.value
        if (UiAction.SendAction in actions && client != null) {
            root.addView(button("+1") { scope.launch { lastOutcome = "결과: ${node.timedAction(byteArrayOf(1))}"; redraw() } })
        }
        if (!s.isHost) root.addView(button("핑 (왕복 지연)") { scope.launch { lastOutcome = "왕복 ${node.ping() ?: "응답 없음"}ms"; redraw() } })
        socials.forEach { root.addView(text(it)) }
        if (UiAction.SendSocial in actions && client != null) {
            val msg = EditText(this).apply { hint = "메시지" }
            root.addView(msg)
            root.addView(button("보내기") { scope.launch { lastOutcome = "메시지: ${client.sendSocial(msg.text.toString().encodeToByteArray())}"; redraw() } })
        }
        if (UiAction.EndSession in actions) root.addView(button("세션 끝내기") { node.endSession() }) else root.addView(button("나가기") { node.leave() })
        perfPanel()
    }

    /** S11 측정: 항목별 표본 수·p95. 판정은 perf-report.py가 기준치와 비교해 한다. */
    private fun perfPanel() {
        val lines = Metric.entries.joinToString("\n") { m -> "${m.key}: n=${node.perf.count(m)} p95=${node.perf.p95(m) ?: "-"}" }
        root.addView(text("측정\n$lines", Color.DKGRAY))
        root.addView(button("측정 내보내기 (CSV)") { exportPerf() })
    }

    // ---- QR ----

    private fun qrView(text: String): View {
        if (text != qrShown) {
            qrShown = text
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_PX, QR_PX)
            qrBitmap = Bitmap.createBitmap(QR_PX, QR_PX, Bitmap.Config.RGB_565).apply {
                for (x in 0 until QR_PX) for (y in 0 until QR_PX) setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return ImageView(this).apply { setImageBitmap(qrBitmap) }
    }

    private fun decodeQr(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
    }

    // ---- 보조 ----

    private fun prefs() = getSharedPreferences("lab", MODE_PRIVATE)

    private fun text(s: String, color: Int = Color.BLACK) = TextView(this).apply {
        text = s
        setTextColor(color)
    }

    private fun big(s: String) = text(s).apply { textSize = BIG_SP }

    private fun huge(s: String) = text(s).apply { textSize = HUGE_SP }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private companion object {
        const val PAD = 32
        const val TICK_MS = 1000L
        const val BATTERY_SAMPLE_MS = 60_000L
        const val KEEP = 8
        const val QR_PX = 512
        const val BIG_SP = 22f
        const val HUGE_SP = 48f
        const val BUTTON_PEAK_G = 3.0
        const val KEY_NAME = "displayName"
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_QR_PHOTO = 2
        const val REQUEST_ENABLE_BT = 3
    }
}
