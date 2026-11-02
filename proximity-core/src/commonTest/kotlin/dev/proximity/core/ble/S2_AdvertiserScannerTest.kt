package dev.proximity.core.ble

import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class S2_AdvertiserScannerTest {
    private val version = 1
    private val period = 300_000L
    private val ttl = 5_000L
    private val clock = FakeClock()
    private val air = FakeAirspace(clock)

    private fun advertiser(name: String, seed: Int): Pair<ProximityAdvertiser, IdRotator> {
        val rotator = IdRotator(period, clock, FakeCryptoBox(seed))
        return ProximityAdvertiser(air.device(name), version, rotator) to rotator
    }

    private fun TestScope.unconfined(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun advertiserSendsOnlyWhenContentChanges() {
        val transport = air.device("A")
        val adv = ProximityAdvertiser(transport, version, IdRotator(period, clock, FakeCryptoBox(1)))
        adv.start()
        assertEquals(1, transport.advertiseCalls)
        adv.setBusy(false)
        adv.setAccepting(false)
        assertEquals(1, transport.advertiseCalls, "같은 내용이면 다시 보내지 않는다")

        adv.bump()
        adv.setBusy(true)
        adv.joinSession(sessionTag = 42, isHost = true, accepting = true)
        assertEquals(4, transport.advertiseCalls)
        assertEquals(AdvFlags(inSession = true, isHost = true, accepting = true, busy = true, bumpSeq = 1), adv.state.flags)
        assertEquals(adv.state, AdvCodec.decode(version, transport.advertising!!))

        adv.leaveSession()
        assertEquals(AdvFlags(busy = true, bumpSeq = 1), adv.state.flags)
        assertEquals(0, adv.state.sessionTag)
    }

    @Test
    fun updatesWhileStoppedAreKeptButNotSent() {
        val transport = air.device("A")
        val adv = ProximityAdvertiser(transport, version, IdRotator(period, clock, FakeCryptoBox(1)))
        adv.bump()
        assertEquals(0, transport.advertiseCalls)
        adv.start()
        assertEquals(1, AdvCodec.decode(version, transport.advertising!!)!!.flags.bumpSeq)
        adv.stop()
        assertNull(transport.advertising)
    }

    @Test
    fun idRotationReadvertises() {
        val transport = air.device("A")
        val rotator = IdRotator(period, clock, FakeCryptoBox(1))
        val adv = ProximityAdvertiser(transport, version, rotator)
        adv.start()
        val before = adv.state.tempId
        clock.advanceBy(period)
        assertTrue(adv.state.tempId != before)
        assertEquals(adv.state.tempId, AdvCodec.decode(version, transport.advertising!!)!!.tempId)
    }

    @Test
    fun scannerTurnsAdvertisementsIntoPeerSeen() = runTest {
        val (advA, _) = advertiser("A", 1)
        val scanB = ProximityScanner(air.device("B"), version, clock, ttl, unconfined())
        air.setRssi("A", "B", -40)
        val seen = mutableListOf<PeerSeen>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanB.sightings.toList(seen) }

        scanB.start()
        clock.advanceBy(10)
        advA.start()
        val p = seen.single()
        assertEquals(advA.state.tempId, p.tempId)
        assertEquals(-40, p.rssi)
        assertEquals(10, p.timestampMs)
        assertNull(p.sessionTag)

        advA.joinSession(sessionTag = 7, isHost = false, accepting = true)
        assertEquals(7, seen.last().sessionTag)
    }

    @Test
    fun scannerDropsInvalidOrOtherVersionAdvertisements() = runTest {
        val other = air.device("X")
        val scanB = ProximityScanner(air.device("B"), version, clock, ttl, unconfined())
        val seen = mutableListOf<PeerSeen>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanB.sightings.toList(seen) }
        scanB.start()

        other.startAdvertising(AdvCodec.encode(version + 1, AdvertState(1, AdvFlags(), 0)))
        other.startAdvertising(byteArrayOf(1, 2, 3))
        assertTrue(seen.isEmpty(), "I-8: 버전이 다른 광고·다른 형식은 이벤트를 만들지 않는다")
        assertTrue(scanB.peers().isEmpty())
    }

    @Test
    fun peerTableMergesRepeatsAndRecordsBumpChanges() = runTest {
        val (advA, _) = advertiser("A", 1)
        val scanB = ProximityScanner(air.device("B"), version, clock, ttl, unconfined())
        scanB.start()
        advA.start()
        clock.advanceBy(100)
        air.rebroadcast()
        air.rebroadcast()
        assertEquals(1, scanB.peers().size, "같은 tempId는 하나로 합친다")
        assertNull(scanB.peers().single().lastBumpChangeAtMs)

        clock.advanceBy(400)
        advA.bump()
        val entry = scanB.peers().single()
        assertEquals(500, entry.lastBumpChangeAtMs, "bumpSeq가 바뀐 시각")
        assertEquals(1, entry.latest.flags.bumpSeq)
        assertEquals(0, entry.firstSeenMs)
    }

    @Test
    fun peersExpireAfterTtl() = runTest {
        val (advA, _) = advertiser("A", 1)
        val scanB = ProximityScanner(air.device("B"), version, clock, ttl, unconfined())
        scanB.start()
        advA.start()
        clock.advanceBy(ttl - 1)
        assertEquals(1, scanB.peers().size)
        clock.advanceBy(1)
        assertTrue(scanB.peers().isEmpty(), "ttl 동안 안 보이면 뺀다")
    }

    @Test
    fun startStopCyclesLeaveNoCollectors() = runTest {
        val transportB = air.device("B")
        val scanB = ProximityScanner(transportB, version, clock, ttl, unconfined())
        repeat(100) {
            scanB.start()
            scanB.start()
            assertEquals(1, transportB.subscriberCount, "중복 시작은 무시")
            assertTrue(transportB.isScanning)
            scanB.stop()
            assertEquals(0, transportB.subscriberCount, "중단하면 수집자가 남지 않는다")
            assertTrue(!transportB.isScanning)
        }
        val (advA, _) = advertiser("A", 1)
        advA.start()
        assertTrue(scanB.peers().isEmpty(), "중단 중에는 관측하지 않는다")
        scanB.start()
        assertEquals(1, scanB.peers().size, "재개하면 다시 관측한다")
    }
}
