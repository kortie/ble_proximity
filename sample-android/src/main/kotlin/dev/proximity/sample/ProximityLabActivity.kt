package dev.proximity.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.proximity.core.ble.AdvFlags
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.PeerEntry
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.interfaces.AccelSample
import dev.proximity.core.sensing.AccelTrace
import dev.proximity.core.sensing.BumpDetector
import dev.proximity.crypto.BouncyCastleCryptoBox
import dev.proximity.platform.AndroidBleTransport
import dev.proximity.platform.AndroidBumpSensor
import dev.proximity.platform.AndroidClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 디버그 화면 "Proximity Lab" (S2). 실기기 검증 HW-S2를 수행하는 도구다.
 * 광고·스캔을 켜고, 범프(감지 또는 버튼)로 bumpSeq를 올리고, 상대 광고를 본다 (HW-S2·S3).
 * "센서 기록"은 가속도 트레이스를 앱 외부 파일 디렉터리(traces/)에 CSV로 남긴다 — `adb pull`로 가져가 fixtures에 넣는다.
 * 임시 ID는 끝 1바이트만 보인다 (기기 2대 구분용). 트레이스 외에는 화면 밖으로 기록하지 않는다.
 */
class ProximityLabActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val clock = AndroidClock()
    private lateinit var transport: AndroidBleTransport
    private lateinit var advertiser: ProximityAdvertiser
    private lateinit var scanner: ProximityScanner
    private lateinit var sensor: AndroidBumpSensor
    private lateinit var detector: BumpDetector
    private var bumpCount = 0
    private var lastBumpAt: Long? = null
    private var recording: MutableList<AccelSample>? = null
    private var lastTraceFile: String? = null
    private lateinit var status: TextView
    private lateinit var peers: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTitle()
        val config = ProximityConfig.fromYaml(assets.open("default.yaml").bufferedReader().readText())
        transport = AndroidBleTransport(this, clock)
        val rotator = IdRotator(config.ble.idRotateSec * 1000, clock, BouncyCastleCryptoBox())
        advertiser = ProximityAdvertiser(transport, config.ble.protocolVersion, rotator)
        scanner = ProximityScanner(transport, config.ble.protocolVersion, clock, config.ble.peerTtlMs, scope)
        sensor = AndroidBumpSensor(this, config.bump.samplePeriodUs)
        detector = BumpDetector(config.bump)
        scope.launch { sensor.samples.collect(::onSample) }
        // HW-S2·S4 측정용 로그: 범프 보냄/봄 시각과 seq만 남긴다 (임시 ID·위치는 남기지 않음)
        scope.launch {
            val lastSeq = mutableMapOf<Int, Int>()
            scanner.sightings.collect { s ->
                val prev = lastSeq.put(s.tempId, s.flags.bumpSeq)
                if (prev != null && prev != s.flags.bumpSeq) android.util.Log.i(LOG_TAG, "bump seen seq=${s.flags.bumpSeq} rssi=${s.rssi}")
            }
        }

        status = TextView(this)
        peers = TextView(this).apply { textSize = 16f }
        val buttons = LinearLayout(this).apply {
            addView(button("시작") { start() })
            addView(button("정지") { stop() })
            addView(button("범프") { labBump() })
            addView(button("센서 기록") { toggleRecording() })
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@ProximityLabActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(32, 32, 32, 32)
                        addView(status)
                        addView(buttons)
                        addView(peers)
                    },
                )
                padForSystemBars()
            },
        )
        scope.launch {
            while (isActive) {
                render()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (missingPermissions().isEmpty()) start()
    }

    private fun start() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        running = true
        advertiser.start()
        scanner.start()
        sensor.start()
    }

    private fun stop() {
        running = false
        sensor.stop()
        scanner.stop()
        advertiser.stop()
        recording?.let { saveTrace(it) }
        recording = null
    }

    private fun labBump() {
        advertiser.bump()
        android.util.Log.i(LOG_TAG, "bump sent seq=${advertiser.state.flags.bumpSeq}")
    }

    private fun onSample(sample: AccelSample) {
        recording?.let { if (it.size < MAX_TRACE_SAMPLES) it += sample }
        detector.feed(sample)?.let { bump ->
            bumpCount++
            lastBumpAt = bump.atMs
            labBump()
        }
    }

    private fun toggleRecording() {
        val current = recording
        if (current == null) {
            recording = mutableListOf()
        } else {
            recording = null
            saveTrace(current)
        }
    }

    private fun saveTrace(samples: List<AccelSample>) {
        if (samples.isEmpty()) return
        val dir = File(getExternalFilesDir(null), "traces").apply { mkdirs() }
        val file = File(dir, "trace_${samples.first().timestampMs}.csv")
        file.writeText(AccelTrace.format(samples, comment = "Proximity Lab ${android.os.Build.MODEL}"))
        lastTraceFile = file.name
    }

    private fun missingPermissions(): List<String> =
        PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun render() {
        val me = advertiser.state
        status.text = buildString {
            appendLine("무선: ${transport.radioState.value}")
            appendLine("동작: ${if (running) "광고·스캔 중" else "정지"}")
            appendLine("나: ${mask(me.tempId)}  ${flagsText(me.flags)}")
            appendLine("센서: ${if (sensor.isAvailable) "있음" else "없음"}  감지 ${bumpCount}회" + (lastBumpAt?.let { " (${clock.now() - it}ms 전)" } ?: ""))
            appendLine("기록: " + (recording?.let { "중 (${it.size}샘플)" } ?: (lastTraceFile ?: "없음")))
        }
        val now = clock.now()
        peers.text = scanner.peers().sortedByDescending { it.latest.rssi }.joinToString("\n") { line(it, now) }
            .ifEmpty { "(보이는 기기 없음)" }
    }

    private fun line(entry: PeerEntry, now: Long): String {
        val p = entry.latest
        val bump = entry.lastBumpChangeAtMs?.let { "범프 ${now - it}ms 전" } ?: "범프 기록 없음"
        val tag = p.sessionTag?.let { " 세션 ${mask(it)}" } ?: ""
        return "${mask(p.tempId)}  ${p.rssi}dBm  ${flagsText(p.flags)}$tag  $bump"
    }

    private fun flagsText(f: AdvFlags): String = buildString {
        append("seq=${f.bumpSeq}")
        if (f.inSession) append(" 세션")
        if (f.isHost) append(" 호스트")
        if (f.accepting) append(" 자리있음")
        if (f.busy) append(" 확인중")
    }

    private fun mask(id: Int): String = "#%02x".format(id and 0xff)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private companion object {
        const val REFRESH_MS = 500L
        const val LOG_TAG = "ProxLab"
        const val MAX_TRACE_SAMPLES = 60_000 // 100Hz × 10분
        const val PERMISSION_REQUEST = 1
        val PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
