package dev.proximity.platform

/**
 * 블루투스 무선 상태. 어댑터는 예외를 던지지 않고 이 값으로 알린다 (안내 UI는 S9).
 * core 인터페이스에는 아직 없다 — 앱이 [AndroidBleTransport.radioState]를 직접 본다.
 */
sealed interface RadioState {
    data object Ready : RadioState

    data object BluetoothOff : RadioState

    data class PermissionMissing(val permission: String) : RadioState

    data object Unsupported : RadioState

    /** 플랫폼이 돌려준 오류 코드 (AdvertisingSetCallback·ScanCallback). */
    data class Failed(val operation: String, val code: Int) : RadioState
}
