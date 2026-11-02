package dev.proximity.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import dev.proximity.core.ble.AdvCodec
import dev.proximity.core.interfaces.BleTransport
import dev.proximity.core.interfaces.Clock
import dev.proximity.core.interfaces.ScanResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.bluetooth.le.ScanResult as AndroidScanResult

/**
 * Android 근접 광고·스캔 (S2, ADR-007). 바이트만 옮기고 프로토콜 판단은 core가 한다.
 * - 광고: 레거시·연결 가능 AdvertisingSet 하나. 기기 이름·TX 파워는 싣지 않는다 (I-7).
 *   내용이 바뀌면 setAdvertisingData가 아니라 **세트를 새로 시작한다**: 스캐너 컨트롤러가 같은 주소의 바뀐 데이터를
 *   다시 보고하지 않아(중복 억제) 상대가 bumpSeq 변화를 못 본다 (S11 실기기에서 발견). 새 세트는 새 임의 주소를 쓴다.
 * - 스캔: 접두사 마스크 필터 + [AdvUuid.payloadsFrom]으로 한 번 더 거른다.
 * - 블루투스 꺼짐·권한 없음은 예외 대신 [radioState]로 알린다. 권한은 호출 전에 직접 확인한다.
 * 주의: Android는 30초에 스캔 시작을 5번 넘게 하면 조용히 막는다. 스캔을 짧게 껐다 켜기를 반복하지 않는다.
 */
@SuppressLint("MissingPermission") // 모든 호출 전에 hasPermission으로 확인한다
class AndroidBleTransport(private val context: Context, private val clock: Clock) : BleTransport {
    private val lock = Any()
    private val results = MutableSharedFlow<ScanResult>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val radio = MutableStateFlow<RadioState>(RadioState.Ready)

    override val scanResults: Flow<ScanResult> = results
    val radioState: StateFlow<RadioState> = radio

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)

    // 광고: desired = 지금 보내야 할 내용(null이면 멈춤), running = 시작했거나 시작 중인 세트
    private var desired: ByteArray? = null
    private var running: SetCallback? = null
    private var activeAdvertiser: BluetoothLeAdvertiser? = null

    // 스캔
    private var scanner: BluetoothLeScanner? = null

    override fun startAdvertising(payload: ByteArray) = synchronized(lock) {
        desired = payload.copyOf()
        if (running?.payload?.contentEquals(payload) == true) return@synchronized
        stopRunning()
        beginAdvertising(payload)
    }

    override fun stopAdvertising() = synchronized(lock) {
        desired = null
        stopRunning()
    }

    private fun stopRunning() {
        val r = running ?: return
        running = null
        guarded("advertise") { activeAdvertiser?.stopAdvertisingSet(r) }
    }

    override fun startScan() = synchronized(lock) {
        if (scanner != null) return@synchronized
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return@synchronized
        val adapter = manager?.adapter ?: return@synchronized setRadio(RadioState.Unsupported)
        if (!adapter.isEnabled) return@synchronized setRadio(RadioState.BluetoothOff)
        val le = adapter.bluetoothLeScanner ?: return@synchronized setRadio(RadioState.BluetoothOff)
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AdvUuid.PREFIX_UUID), ParcelUuid(AdvUuid.PREFIX_MASK_UUID))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        guarded("scan") {
            le.startScan(listOf(filter), settings, scanCallback)
            scanner = le
            setRadio(RadioState.Ready)
        }
    }

    override fun stopScan() = synchronized(lock) {
        val le = scanner ?: return@synchronized
        scanner = null
        guarded("scan") { le.stopScan(scanCallback) }
    }

    private fun beginAdvertising(payload: ByteArray) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val adapter = manager?.adapter ?: return setRadio(RadioState.Unsupported)
        if (!adapter.isEnabled) return setRadio(RadioState.BluetoothOff)
        val advertiser = adapter.bluetoothLeAdvertiser ?: return setRadio(RadioState.BluetoothOff)
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true) // Rendezvous GATT 연결을 받는다 (S5)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val callback = SetCallback(payload.copyOf())
        guarded("advertise") {
            advertiser.startAdvertisingSet(parameters, data(payload), null, null, null, callback)
            activeAdvertiser = advertiser
            running = callback
        }
    }

    private fun data(payload: ByteArray): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(AdvUuid.fromBytes(payload)))
        .setIncludeDeviceName(false) // I-7: 기기 이름은 사용자를 식별할 수 있다
        .setIncludeTxPowerLevel(false)
        .build()

    /** 세트마다 콜백 하나. 이미 대체된 세트가 늦게 시작되면 바로 멈춘다. */
    private inner class SetCallback(val payload: ByteArray) : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) = synchronized(lock) {
            if (running !== this) {
                guarded("advertise") { activeAdvertiser?.stopAdvertisingSet(this) }
                return@synchronized
            }
            if (status != ADVERTISE_SUCCESS || advertisingSet == null) {
                running = null
                return@synchronized setRadio(RadioState.Failed("advertise", status))
            }
            setRadio(RadioState.Ready)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: AndroidScanResult) = deliver(result)

        override fun onBatchScanResults(batch: MutableList<AndroidScanResult>) = batch.forEach(::deliver)

        override fun onScanFailed(errorCode: Int) {
            synchronized(lock) { scanner = null }
            setRadio(RadioState.Failed("scan", errorCode))
        }
    }

    private fun deliver(result: AndroidScanResult) {
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: return
        val now = clock.now()
        AdvUuid.payloadsFrom(uuids).forEach { payload ->
            AdvCodec.tempIdOf(payload)?.let { devices[it] = result.device }
            AdvCodec.hostSessionTagOf(payload)?.let { hosts[it] = result.device }
            results.tryEmit(ScanResult(payload, result.rssi, now))
        }
    }

    // tempId → 마지막으로 그 광고를 보낸 기기. Rendezvous 접속 대상을 찾는 데 쓴다 (ADR-008)
    private val devices = java.util.concurrent.ConcurrentHashMap<Int, BluetoothDevice>()

    internal fun deviceFor(tempId: Int): BluetoothDevice? = devices[tempId]

    // sessionTag → 그 세션의 호스트 기기 (세션 클라이언트 접속 대상, S6)
    private val hosts = java.util.concurrent.ConcurrentHashMap<Int, BluetoothDevice>()

    internal fun hostDeviceFor(sessionTag: Int): BluetoothDevice? = hosts[sessionTag]

    private fun hasPermission(permission: String): Boolean {
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) setRadio(RadioState.PermissionMissing(permission))
        return granted
    }

    /** 권한이 런타임에 회수되면 SecurityException이 난다. 앱을 죽이지 않고 상태로 알린다. */
    private inline fun guarded(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            setRadio(RadioState.PermissionMissing(operation))
        } catch (e: IllegalStateException) {
            // 블루투스가 꺼지는 중에 호출하면 IllegalStateException이 날 수 있다
            setRadio(RadioState.BluetoothOff)
        }
    }

    private fun setRadio(state: RadioState) {
        radio.value = state
    }
}
