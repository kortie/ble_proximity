package dev.proximity.platform

import android.bluetooth.BluetoothDevice
import android.os.SystemClock

/**
 * 상대 광고 주소를 따라가며 연결한다 (S11 실기기에서 발견).
 * 광고 내용이 바뀔 때마다 상대가 광고 세트를 새로 시작해 주소가 바뀐다(AndroidBleTransport). 연결하는 사이 주소가 바뀌면
 * 옛 주소로는 영영 연결되지 않으므로(그 주소로 광고하지 않음), 스캔에서 새 주소가 보이면 바로 새 주소로 다시 시도한다.
 * 이것이 없으면 합류의 첫 세션 연결이 거의 매번 시간 초과(3초)되고 재시도 뒤에야 붙었다.
 */
internal suspend fun connectFollowingAddress(
    totalMs: Long,
    current: () -> BluetoothDevice?,
    make: (BluetoothDevice) -> DuplexGattClient,
): DuplexGattClient? {
    val deadline = SystemClock.elapsedRealtime() + totalMs
    while (SystemClock.elapsedRealtime() < deadline) {
        val device = current() ?: return null
        val client = make(device)
        if (!client.start()) return null
        while (true) {
            when (client.awaitReady(POLL_MS)) {
                true -> return client
                false -> {
                    client.close()
                    break // 실패 확정: 지금 주소로 처음부터
                }
                null -> {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        client.close()
                        return null
                    }
                    val now = current()
                    if (now != null && now.address != device.address) {
                        client.close()
                        break // 상대가 새 주소로 광고한다
                    }
                }
            }
        }
    }
    return null
}

private const val POLL_MS = 150L
