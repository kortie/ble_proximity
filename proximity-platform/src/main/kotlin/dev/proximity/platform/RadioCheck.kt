package dev.proximity.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 앱이 블루투스 API를 직접 부르지 않고(I-5) 무선 준비 상태를 알게 한다 (S9 안내 화면).
 * 권한 요청 자체(다이얼로그)는 앱의 Activity가 [permissions]로 한다.
 */
object RadioCheck {
    /** BLE 광고·스캔·연결에 필요한 런타임 권한 (minSdk 31). */
    val permissions = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun current(context: Context): RadioState {
        permissions.firstOrNull { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }?.let {
            return RadioState.PermissionMissing(it)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return RadioState.Unsupported
        if (!adapter.isEnabled) return RadioState.BluetoothOff
        if (adapter.bluetoothLeAdvertiser == null || !adapter.isMultipleAdvertisementSupported) return RadioState.Unsupported
        return RadioState.Ready
    }

    /** 블루투스를 켜거나 끌 때마다 [current]를 다시 낸다. */
    fun observe(context: Context): Flow<RadioState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(current(context))
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        trySend(current(context))
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /** 시스템의 "블루투스를 켤까요?" 화면. */
    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
}
