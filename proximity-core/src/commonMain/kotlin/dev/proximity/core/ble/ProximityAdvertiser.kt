package dev.proximity.core.ble

import dev.proximity.core.interfaces.BleTransport

/**
 * 근접 광고 내용을 관리한다 (S2). 상태가 바뀌면 바로 다시 광고하고, 같은 내용이면 보내지 않는다.
 * 멈춘 동안의 변경은 기억했다가 [start] 때 반영한다. 한 루프에서만 부른다.
 */
class ProximityAdvertiser(
    private val transport: BleTransport,
    private val protocolVersion: Int,
    private val rotator: IdRotator,
) {
    private var flags = AdvFlags()
    private var sessionTag = 0
    private var running = false
    private var lastSent: ByteArray? = null

    val state: AdvertState get() = AdvertState(rotator.current, flags, sessionTag)

    fun start() {
        if (running) return
        running = true
        rotator.onRotate = { publish() }
        rotator.start()
        publish()
    }

    fun stop() {
        if (!running) return
        running = false
        rotator.stop()
        transport.stopAdvertising()
        lastSent = null
    }

    fun bump() = update { flags = flags.nextBump() }

    fun setBusy(busy: Boolean) = update { flags = flags.copy(busy = busy) }

    fun setAccepting(accepting: Boolean) = update { flags = flags.copy(accepting = accepting) }

    fun joinSession(sessionTag: Int, isHost: Boolean, accepting: Boolean) = update {
        this.sessionTag = sessionTag
        flags = flags.copy(inSession = true, isHost = isHost, accepting = accepting)
    }

    fun leaveSession() = update {
        sessionTag = 0
        flags = flags.copy(inSession = false, isHost = false, accepting = false)
    }

    private inline fun update(change: () -> Unit) {
        change()
        publish()
    }

    private fun publish() {
        if (!running) return
        val bytes = AdvCodec.encode(protocolVersion, state)
        if (lastSent?.contentEquals(bytes) == true) return
        transport.startAdvertising(bytes)
        lastSent = bytes
    }
}
