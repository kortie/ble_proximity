package dev.proximity.core.matching

import dev.proximity.core.api.Role
import dev.proximity.core.ble.IdRotator
import dev.proximity.core.ble.ProximityAdvertiser
import dev.proximity.core.ble.ProximityScanner
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.sensing.Bump
import dev.proximity.core.state.CandidatePhase
import dev.proximity.core.state.MatchEffect
import dev.proximity.core.state.MatchEnv
import dev.proximity.core.state.MatchEvent
import dev.proximity.core.state.MatchState
import dev.proximity.core.state.MatchStateMachine
import dev.proximity.core.state.PeerRef
import dev.proximity.core.state.RefuseReason
import dev.proximity.testing.FakeAirspace
import dev.proximity.testing.FakeClock
import dev.proximity.testing.FakeCryptoBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fake 전체 스택: 광고 → 스캔 → 관측 표 → 후보 선택 → 상태 기계 (S4). */
@OptIn(ExperimentalCoroutinesApi::class)
class S4_FakeStackMatchingTest {
    private val matching = ProximityConfig.Matching(
        windowMs = 300, rssiMinDbm = -70, rssiStrongDbm = -30, weightTime = 0.3, weightRssi = 0.7, bumpFlagWaitMs = 800,
        maxAttempts = 2, attemptTimeoutMs = 3000, retryCooldownMs = 10_000, rdvBackoffMs = 300,
    )
    private val env = MatchEnv(maxAttempts = 2, candidateMs = 5000, attemptTimeoutMs = 3000, confirmingMs = 20000, joiningMs = 10000)

    private class Device(val name: String, val coordinator: MatchCoordinator, val machine: MatchStateMachine, val advertiser: ProximityAdvertiser) {
        val external = mutableListOf<MatchEffect>()
    }

    @Test
    fun bumpingPairPicksEachOtherAndCloserBystanderIsExcluded() = runTest {
        val clock = FakeClock()
        val air = FakeAirspace(clock)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val devices = listOf("A", "B", "C", "D").mapIndexed { i, name ->
            val transport = air.device(name)
            val rotator = IdRotator(300_000, clock, FakeCryptoBox(seed = i + 1))
            val advertiser = ProximityAdvertiser(transport, 1, rotator)
            val scanner = ProximityScanner(transport, 1, clock, 5000, scope)
            val machine = MatchStateMachine(env, clock, Role.Player)
            lateinit var device: Device
            val coordinator = MatchCoordinator(machine, advertiser, rotator, scanner, PeerMatcher(matching), Cooldown(clock, 10_000), clock, matching) {
                device.external += it
            }
            device = Device(name, coordinator, machine, advertiser)
            device
        }
        val (a, b, c, d) = devices
        air.setRssi("A", "B", -35)
        air.setRssi("A", "C", -28) // A에 가장 가깝지만 범프하지 않는다
        air.setRssi("A", "D", -60)
        air.setRssi("B", "C", -50)
        air.setRssi("B", "D", -55)
        air.setRssi("C", "D", -40)
        devices.forEach { it.coordinator.dispatch(MatchEvent.StartAdvertising) }
        clock.advanceBy(1000)

        a.coordinator.onBump(Bump(atMs = clock.now(), peakG = 3.0))
        clock.advanceBy(10)
        b.coordinator.onBump(Bump(atMs = clock.now(), peakG = 3.0))
        clock.advanceBy(matching.bumpFlagWaitMs)

        val bId = PeerRef(b.advertiser.state.tempId)
        val aId = PeerRef(a.advertiser.state.tempId)
        assertEquals(bId, (a.machine.state as MatchState.Candidate).attemptingPeer(), "A는 B에게 먼저 시도")
        assertEquals(aId, (b.machine.state as MatchState.Candidate).attemptingPeer(), "B는 A에게 먼저 시도")
        assertTrue(MatchEffect.RequestConfirm(bId) in a.external)
        assertTrue(!a.advertiser.state.flags.busy, "시도 중에는 busy가 아니다 — 서로 고른 상대가 나를 F4로 거르지 않게")
        a.coordinator.dispatch(MatchEvent.RendezvousReady(bId))
        assertTrue(a.advertiser.state.flags.busy, "확인 화면부터 busy")
        assertEquals(MatchState.Advertising, c.machine.state)

        // 범프하지 않은 C에게 A가 요청하면 C의 상태 기계가 NotBumped로 거절한다
        c.coordinator.onIncomingConfirm(aId)
        assertTrue(MatchEffect.RefuseIncoming(aId, RefuseReason.NotBumped) in c.external)
    }

    private fun MatchState.Candidate.attemptingPeer() = (phase as CandidatePhase.Attempting).peer
}
