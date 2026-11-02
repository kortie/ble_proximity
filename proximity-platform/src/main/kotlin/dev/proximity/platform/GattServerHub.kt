package dev.proximity.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 양방향 서비스 한 개에 대한 서버 쪽 사건. 콜백 스레드(바인더)에서 불린다. */
internal interface DuplexServerListener {
    /** 클라이언트가 알림을 구독했다 = 링크 준비 완료. */
    fun onSubscribed(device: BluetoothDevice)

    fun onWrite(device: BluetoothDevice, value: ByteArray)

    fun onDisconnected(device: BluetoothDevice)

    /** 읽기 특성 요청에 돌려줄 값 (없으면 빈 값). */
    fun onRead(device: BluetoothDevice): ByteArray = ByteArray(0)
}

/**
 * 앱 하나에 GATT 서버 하나. Rendezvous(S5)·세션(S6) 양방향 서비스를 여기에 올린다.
 * 알림은 기기마다 한 번에 하나만 보내고 onNotificationSent를 기다린다 (Android 흐름 제어).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT는 open 전에 확인한다
internal class GattServerHub(private val context: Context) {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private val listeners = ConcurrentHashMap<java.util.UUID, Pair<DuplexService, DuplexServerListener>>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val notifyLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingSent = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun addService(spec: DuplexService, listener: DuplexServerListener): Boolean {
        if (!hasPermission()) return false
        val gatt = server ?: runCatching { manager?.openGattServer(context, callback) }.getOrNull()?.also { server = it } ?: return false
        if (listeners.containsKey(spec.service)) return true
        listeners[spec.service] = spec to listener
        val service = BluetoothGattService(spec.service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(spec.write, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(spec.notify, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
                addDescriptor(BluetoothGattDescriptor(GattUuids.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            },
        )
        spec.read?.let {
            service.addCharacteristic(BluetoothGattCharacteristic(it, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        }
        return runCatching { gatt.addService(service) }.getOrDefault(false)
    }

    @Synchronized
    fun removeService(spec: DuplexService) {
        listeners.remove(spec.service) ?: return
        runCatching { server?.getService(spec.service)?.let { server?.removeService(it) } }
        if (listeners.isEmpty()) {
            runCatching { server?.close() }
            server = null
        }
    }

    fun mtu(device: BluetoothDevice): Int = mtus[device.address] ?: DEFAULT_MTU

    fun disconnect(device: BluetoothDevice) {
        runCatching { server?.cancelConnection(device) }
    }

    /** 알림 하나를 보내고 onNotificationSent까지 기다린다. 기기마다 직렬. */
    suspend fun notify(device: BluetoothDevice, spec: DuplexService, value: ByteArray): Boolean {
        val lock = notifyLocks.getOrPut(device.address) { Mutex() }
        return lock.withLock {
            val gatt = server ?: return@withLock false
            val characteristic = gatt.getService(spec.service)?.getCharacteristic(spec.notify) ?: return@withLock false
            val sent = CompletableDeferred<Boolean>()
            pendingSent[device.address] = sent
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    @Suppress("DEPRECATION")
                    gatt.notifyCharacteristicChanged(device, characteristic, false)
                }
            }.getOrDefault(false)
            if (!started) {
                pendingSent.remove(device.address)
                return@withLock false
            }
            withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { sent.await() } ?: false
        }
    }

    private fun listenerFor(serviceUuid: java.util.UUID) = listeners[serviceUuid]

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device.address)
                pendingSent.remove(device.address)?.complete(false)
                listeners.values.forEach { it.second.onDisconnected(device) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val entry = listenerFor(characteristic.service.uuid)
            val ok = entry != null && characteristic.uuid == entry.first.write && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                runCatching { server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null) }
            }
            if (ok) entry!!.second.onWrite(device, value!!)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val entry = listenerFor(characteristic.service.uuid)
            val value = if (entry != null && characteristic.uuid == entry.first.read) entry.second.onRead(device) else null
            val chunk = value?.let { if (offset < it.size) it.copyOfRange(offset, it.size) else ByteArray(0) }
            runCatching {
                server?.sendResponse(device, requestId, if (chunk != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, offset, chunk)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val entry = listenerFor(descriptor.characteristic.service.uuid)
            val ok = entry != null && descriptor.uuid == GattUuids.CCCD && descriptor.characteristic.uuid == entry.first.notify
            if (responseNeeded) {
                runCatching { server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null) }
            }
            if (ok && value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) entry!!.second.onSubscribed(device)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingSent.remove(device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    companion object {
        const val DEFAULT_MTU = 23
        private const val NOTIFY_TIMEOUT_MS = 2_000L
    }
}
