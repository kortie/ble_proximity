package dev.proximity.core.ble

import dev.proximity.core.interfaces.BleTransport
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** 해석한 광고 하나. [sessionTag]는 상대가 세션에 있을 때만 있다. */
data class PeerSeen(
    val tempId: Int,
    val rssi: Int,
    val timestampMs: Long,
    val flags: AdvFlags,
    val sessionTag: Int?,
)

/** 같은 tempId의 관측을 합친 것. [lastBumpChangeAtMs]는 bumpSeq가 바뀐 것을 마지막으로 본 시각 (S4 F5). */
data class PeerEntry(val latest: PeerSeen, val firstSeenMs: Long, val lastBumpChangeAtMs: Long?)

/**
 * 스캔 결과를 [PeerSeen]으로 바꾸고 관측 표를 유지한다 (S2). 형식·버전이 맞지 않는 광고는 버린다 (I-8).
 * [scope]에서 수집하며 [stop]하면 수집을 취소한다. 표는 한 루프에서만 읽고 쓴다.
 */
class ProximityScanner(
    private val transport: BleTransport,
    private val protocolVersion: Int,
    private val clock: Clock,
    private val peerTtlMs: Long,
    private val scope: CoroutineScope,
) {
    private val flow = MutableSharedFlow<PeerSeen>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val table = LinkedHashMap<Int, PeerEntry>()
    private var job: Job? = null

    /** 유효한 관측마다 하나씩. */
    val sightings: Flow<PeerSeen> = flow

    val isRunning: Boolean get() = job != null

    fun start() {
        if (job != null) return
        // 스캔을 켜기 전에 구독부터 한다 (켜자마자 오는 결과를 놓치지 않게).
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.scanResults.collect(::handle) }
        transport.startScan()
    }

    fun stop() {
        val running = job ?: return
        job = null
        transport.stopScan()
        running.cancel()
    }

    /** 지금 보이는 상대들. ttl이 지난 관측은 뺀다. */
    fun peers(): List<PeerEntry> {
        expire()
        return table.values.toList()
    }

    private fun handle(result: ScanResult) {
        val state = AdvCodec.decode(protocolVersion, result.payload) ?: return
        val seen = PeerSeen(
            tempId = state.tempId,
            rssi = result.rssi,
            timestampMs = result.timestampMs,
            flags = state.flags,
            sessionTag = state.sessionTag.takeIf { state.flags.inSession },
        )
        val prev = table[seen.tempId]
        val bumpChanged = prev != null && prev.latest.flags.bumpSeq != seen.flags.bumpSeq
        table[seen.tempId] = PeerEntry(
            latest = seen,
            firstSeenMs = prev?.firstSeenMs ?: seen.timestampMs,
            lastBumpChangeAtMs = if (bumpChanged) seen.timestampMs else prev?.lastBumpChangeAtMs,
        )
        flow.tryEmit(seen)
        expire()
    }

    private fun expire() {
        val now = clock.now()
        table.values.removeAll { now - it.latest.timestampMs >= peerTtlMs }
    }
}
