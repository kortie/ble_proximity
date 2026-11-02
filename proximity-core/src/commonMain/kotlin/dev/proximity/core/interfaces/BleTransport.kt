package dev.proximity.core.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * 근접 발견용 광고·스캔. 바이트만 다룬다.
 * 바이트 → PeerSeen 해석은 core Codec(S2)이 한다. 세션 데이터는 [SessionTransport]로만 보낸다.
 */
interface BleTransport {
    val scanResults: Flow<ScanResult>

    /** 광고 내용을 [payload]로 바꾼다. 이미 광고 중이면 교체한다. */
    fun startAdvertising(payload: ByteArray)

    fun stopAdvertising()

    fun startScan()

    fun stopScan()
}

/** 스캔으로 관측한 광고 하나. [timestampMs]는 [Clock.now] 기준. */
class ScanResult(val payload: ByteArray, val rssi: Int, val timestampMs: Long)
