package dev.proximity.testing

import dev.proximity.core.interfaces.HostRef
import dev.proximity.core.interfaces.LinkEvent
import dev.proximity.core.interfaces.ScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S0_FakeClockTest {
    @Test
    fun runsDueActionsInTimeOrder() {
        val clock = FakeClock()
        val log = mutableListOf<String>()
        clock.schedule(300) { log += "c@${clock.now()}" }
        clock.schedule(100) { log += "a@${clock.now()}" }
        val cancelled = clock.schedule(200) { log += "b" }
        cancelled.cancel()

        clock.advanceBy(150)
        assertEquals(listOf("a@100"), log)
        clock.advanceBy(150)
        assertEquals(listOf("a@100", "c@300"), log)
        assertEquals(300, clock.now())
    }

    @Test
    fun actionsScheduledDuringAdvanceAlsoRun() {
        val clock = FakeClock()
        val log = mutableListOf<Long>()
        clock.schedule(100) { clock.schedule(50) { log += clock.now() } }
        clock.advanceBy(200)
        assertEquals(listOf(150L), log)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class S0_FakeBleTransportTest {
    @Test
    fun twoVirtualDevicesSeeEachOthersAdvertisements() = runTest {
        val clock = FakeClock()
        val air = FakeAirspace(clock)
        val a = air.device("A")
        val b = air.device("B")
        air.setRssi("A", "B", -42)

        val seenByA = mutableListOf<ScanResult>()
        val seenByB = mutableListOf<ScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { a.scanResults.toList(seenByA) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { b.scanResults.toList(seenByB) }

        a.startScan()
        b.startScan()
        clock.advanceBy(10)
        a.startAdvertising(byteArrayOf(1, 2, 3))
        b.startAdvertising(byteArrayOf(9))

        assertEquals(1, seenByA.size)
        assertContentEquals(byteArrayOf(9), seenByA.single().payload)
        assertEquals(-42, seenByA.single().rssi)
        assertEquals(10, seenByA.single().timestampMs)
        assertContentEquals(byteArrayOf(1, 2, 3), seenByB.single().payload)
    }

    @Test
    fun noResultsWhenNotScanningAndRebroadcastRepeatsCurrentAds() = runTest {
        val clock = FakeClock()
        val air = FakeAirspace(clock)
        val a = air.device("A")
        val b = air.device("B")
        val seenByB = mutableListOf<ScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { b.scanResults.toList(seenByB) }

        a.startAdvertising(byteArrayOf(1))
        assertTrue(seenByB.isEmpty(), "B가 스캔하지 않으면 받지 않는다")

        b.startScan()
        assertEquals(1, seenByB.size, "스캔을 시작하면 현재 광고를 받는다")
        air.rebroadcast()
        assertEquals(2, seenByB.size)

        a.stopAdvertising()
        air.rebroadcast()
        assertEquals(2, seenByB.size, "광고를 멈추면 더 받지 않는다")
        assertFalse(a.isScanning)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class S0_FakeSessionTransportTest {
    @Test
    fun hostAndThreeClientsExchangeFrames() = runTest {
        val net = FakeSessionNetwork()
        val host = net.host()
        val hostInbound = mutableListOf<Pair<Int, List<Byte>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.inbound.collect { hostInbound += it.link.value to it.bytes.toList() }
        }
        host.open(sessionTag = 0x7f3a, sessionInfo = byteArrayOf(42))

        val clients = List(3) { net.client() }
        val received = List(3) { mutableListOf<List<Byte>>() }
        clients.forEachIndexed { i, c ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.inbound.collect { received[i] += it.toList() } }
        }
        val links = clients.map { c ->
            assertContentEquals(byteArrayOf(42), c.connect(HostRef(0x7f3a)), "연결하면 SESSION_INFO를 읽는다")
            net.linkOf(c)
        }

        clients.forEachIndexed { i, c -> assertTrue(c.send(byteArrayOf(i.toByte()))) }
        assertEquals(links.mapIndexed { i, l -> l.value to listOf(i.toByte()) }, hostInbound)

        links.forEachIndexed { i, l -> assertTrue(host.send(l, byteArrayOf((10 + i).toByte()))) }
        received.forEachIndexed { i, r -> assertEquals(listOf(listOf((10 + i).toByte())), r) }
    }

    @Test
    fun wrongSessionTagFailsToConnect() = runTest {
        val net = FakeSessionNetwork()
        net.host().open(sessionTag = 1, sessionInfo = byteArrayOf())
        assertNull(net.client().connect(HostRef(2)))
    }

    @Test
    fun dropRuleLosesFramesAndDisconnectEmitsDownOnBothSides() = runTest {
        val net = FakeSessionNetwork()
        val host = net.host()
        host.open(1, byteArrayOf())
        val client = net.client()
        val got = mutableListOf<List<Byte>>()
        val hostEvents = mutableListOf<LinkEvent>()
        val clientEvents = mutableListOf<LinkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.inbound.collect { got += it.toList() } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.linkEvents.toList(hostEvents) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.linkEvents.toList(clientEvents) }
        client.connect(HostRef(1))
        val link = net.linkOf(client)

        net.dropRule = { route -> route.toClient && route.bytes.first() == 2.toByte() }
        host.send(link, byteArrayOf(1))
        host.send(link, byteArrayOf(2))
        host.send(link, byteArrayOf(3))
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(3)), got)

        net.disconnect(link)
        assertFalse(host.send(link, byteArrayOf(4)))
        assertFalse(client.send(byteArrayOf(5)))
        assertEquals(listOf(LinkEvent.Up(link), LinkEvent.Down(link)), hostEvents)
        assertEquals(listOf(LinkEvent.Up(link), LinkEvent.Down(link)), clientEvents)
    }

    @Test
    fun heldFramesAreDeliveredOnlyWhenFlushed() = runTest {
        val net = FakeSessionNetwork()
        val host = net.host()
        host.open(1, byteArrayOf())
        val client = net.client()
        val got = mutableListOf<List<Byte>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.inbound.collect { got += it.toList() } }
        client.connect(HostRef(1))

        net.autoDeliver = false
        host.send(net.linkOf(client), byteArrayOf(7))
        assertTrue(got.isEmpty())
        net.deliverPending()
        assertEquals(listOf(listOf<Byte>(7)), got)
    }
}
