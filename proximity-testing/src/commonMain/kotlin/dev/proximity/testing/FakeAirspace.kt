package dev.proximity.testing

import dev.proximity.core.interfaces.BleTransport
import dev.proximity.core.interfaces.ScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 메모리 속 가상 공간. 기기마다 [FakeBleTransport]를 하나씩 주고, 광고를 스캔 중인 다른 기기에 전달한다.
 * RSSI는 기기 쌍마다 [setRssi]로 정한다 (대칭). 실제 광고 주기는 [rebroadcast]로 흉내 낸다.
 */
class FakeAirspace(private val clock: FakeClock, private val defaultRssi: Int = -60) {
    private val devices = linkedMapOf<String, FakeBleTransport>()
    private val rssi = mutableMapOf<Set<String>, Int>()

    fun device(name: String): FakeBleTransport {
        require(name !in devices) { "같은 이름의 기기가 있다: $name" }
        return FakeBleTransport(name, this).also { devices[name] = it }
    }

    fun setRssi(a: String, b: String, dbm: Int) {
        rssi[setOf(a, b)] = dbm
    }

    /** 현재 광고 중인 모든 기기의 광고를 스캔 중인 기기들에게 한 번 더 전달한다. */
    fun rebroadcast() {
        devices.values.forEach { sender -> sender.advertising?.let { deliverFrom(sender, it) } }
    }

    internal fun deliverFrom(sender: FakeBleTransport, payload: ByteArray) {
        devices.values.filter { it !== sender && it.isScanning }.forEach { receiver -> receiver.receive(sender, payload) }
    }

    internal fun deliverCurrentTo(receiver: FakeBleTransport) {
        devices.values.filter { it !== receiver }.forEach { sender -> sender.advertising?.let { receiver.receive(sender, it) } }
    }

    internal fun resultFor(sender: FakeBleTransport, receiver: FakeBleTransport, payload: ByteArray) =
        ScanResult(payload.copyOf(), rssi[setOf(sender.name, receiver.name)] ?: defaultRssi, clock.now())
}

class FakeBleTransport internal constructor(val name: String, private val air: FakeAirspace) : BleTransport {
    private val results = MutableSharedFlow<ScanResult>(extraBufferCapacity = BUFFER)
    override val scanResults: Flow<ScanResult> = results

    var advertising: ByteArray? = null
        private set

    /** 광고를 바꾼 횟수 (같은 내용을 다시 보냈는지 확인용). */
    var advertiseCalls = 0
        private set

    /** scanResults를 구독 중인 수집자 수 (스캔 중단 후 누수 확인용). */
    val subscriberCount: Int get() = results.subscriptionCount.value
    var isScanning = false
        private set

    override fun startAdvertising(payload: ByteArray) {
        advertiseCalls++
        advertising = payload.copyOf()
        air.deliverFrom(this, payload)
    }

    override fun stopAdvertising() {
        advertising = null
    }

    override fun startScan() {
        if (isScanning) return
        isScanning = true
        air.deliverCurrentTo(this)
    }

    override fun stopScan() {
        isScanning = false
    }

    internal fun receive(sender: FakeBleTransport, payload: ByteArray) {
        check(results.tryEmit(air.resultFor(sender, this, payload))) { "스캔 결과 버퍼가 찼다" }
    }

    private companion object {
        const val BUFFER = 1024
    }
}
