package dev.proximity.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 양방향 GATT 서비스의 클라이언트 쪽 (ADR-008, S6 세션도 사용).
 * 연결 → MTU 요청 → 서비스 탐색 → (읽기 특성) → 알림 구독 순서로 준비한다. 쓰기는 응답을 기다리며 한 번에 하나.
 * 받은 알림은 [inbound] 채널에 쌓이고, 끊기면 채널이 닫힌다 (이미 받은 것은 남는다).
 */
@SuppressLint("MissingPermission") // 호출자가 BLUETOOTH_CONNECT를 확인한다
internal class DuplexGattClient(private val context: Context, private val device: BluetoothDevice, private val spec: DuplexService) {
    val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    var mtu: Int = GattServerHub.DEFAULT_MTU
        private set

    /** [connect] 중 읽은 읽기 특성 값 (세션 서비스의 SESSION_INFO). */
    @Volatile
    var readValue: ByteArray? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Boolean>()
    private val writeLock = Mutex()
    @Volatile
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile
    private var closed = false

    suspend fun connect(timeoutMs: Long): Boolean {
        if (!start()) return false
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (!ok) close()
        return ok
    }

    /** 연결을 시작만 한다. 결과는 [awaitReady]로 조금씩 기다린다. */
    fun start(): Boolean {
        gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        return gatt != null
    }

    /** [sliceMs] 동안 기다린다. 준비됨 = true, 실패 확정 = false, 아직 모름 = null. */
    suspend fun awaitReady(sliceMs: Long): Boolean? = withTimeoutOrNull(sliceMs) { ready.await() }

    suspend fun write(value: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return@withLock false
        val c = writeChar ?: return@withLock false
        if (closed) return@withLock false
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = value
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }.getOrDefault(false)
        if (!started) return@withLock false
        withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
    }

    fun close() {
        if (closed) return
        closed = true
        ready.complete(false)
        pendingWrite?.complete(false)
        inbound.close()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED ->
                    if (runCatching { g.requestMtu(REQUESTED_MTU) }.getOrDefault(false) != true) g.discoverServices()
                newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            runCatching { g.discoverServices() }.onFailure { close() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(spec.service)
            val w = service?.getCharacteristic(spec.write)
            val n = service?.getCharacteristic(spec.notify)
            if (status != BluetoothGatt.GATT_SUCCESS || w == null || n == null) return close()
            writeChar = w
            val r = spec.read?.let { service.getCharacteristic(it) }
            if (r != null) {
                if (runCatching { g.readCharacteristic(r) }.getOrDefault(false) != true) close()
            } else {
                subscribe(g, n)
            }
        }

        @Deprecated("API 33 미만")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onRead(g, characteristic.value, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            onRead(g, value, status)

        private fun onRead(g: BluetoothGatt, value: ByteArray?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return close()
            readValue = value ?: ByteArray(0)
            val n = g.getService(spec.service)?.getCharacteristic(spec.notify) ?: return close()
            subscribe(g, n)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == GattUuids.CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(true) else close()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("API 33 미만")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { inbound.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            inbound.trySend(value.copyOf())
        }
    }

    private fun subscribe(g: BluetoothGatt, n: BluetoothGattCharacteristic) {
        val cccd = n.getDescriptor(GattUuids.CCCD) ?: return close()
        if (runCatching { g.setCharacteristicNotification(n, true) }.getOrDefault(false) != true) return close()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }.getOrDefault(false)
        if (!started) close()
    }

    private companion object {
        const val REQUESTED_MTU = 517
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}
