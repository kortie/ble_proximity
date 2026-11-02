package dev.proximity.core.matching

import dev.proximity.core.ble.AdvFlags
import dev.proximity.core.ble.PeerEntry
import dev.proximity.core.ble.PeerSeen
import dev.proximity.core.config.ProximityConfig
import dev.proximity.core.config.SimpleYaml
import dev.proximity.core.config.repoFile
import dev.proximity.core.state.PeerRef
import dev.proximity.testing.FakeClock
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 혼잡 매칭 시뮬레이션 (S4, 관측 수준 모델). 모델 값은 결과를 보기 전에 정했다:
 * - N대 = N/2쌍, 각 쌍은 0~1500ms 사이에 범프, 쌍 안의 감지 시각 차 ±40ms(σ15)
 * - 상대의 bumpSeq 변경을 관측하기까지 100~700ms, 3%는 시간창 안에 관측 실패
 * - RSSI: 짝 −45~−30dBm(σ3), 5%는 손·몸에 가려 −12dB / 나머지 −80~−50dBm(σ5)
 * - 이미 시도를 시작한 기기(자기 선택 시각 + 200ms 이후)는 busy로 보인다
 * - 최종 성공: 짝이 내 상위 max_attempts 안에 있거나, 내 후보가 비어(AwaitingIncoming) 짝이 나를 상위 max_attempts 안에 골랐을 때
 * 결과: build/reports/s4-simulation.txt
 */
class S4_MatchingSimulationTest {
    private val config = ProximityConfig.fromYaml(repoFile("config/default.yaml").readText()).matching
    private val thresholds = SimpleYaml.parse(repoFile("config/thresholds.yaml").readText()).getValue("matching")
    private val report = File(repoFile("proximity-core/build/reports"), "s4-simulation.txt").also { it.parentFile.mkdirs() }

    @Test
    fun crowdedTablesMeetThresholds() {
        val lines = mutableListOf("S4 matching simulation (500 trials per N, every device evaluated)")
        val failures = mutableListOf<String>()
        for (n in listOf(2, 4, 8)) {
            var first = 0
            var final = 0
            var total = 0
            repeat(TRIALS) { trial ->
                val outcome = simulate(n, Random(n * 10_000 + trial))
                first += outcome.first
                final += outcome.second
                total += n
            }
            val firstRate = first.toDouble() / total
            val finalRate = final.toDouble() / total
            val firstMin = thresholds.getValue("first_try_rate_n$n").toDouble()
            val finalMin = thresholds.getValue("final_rate_n$n").toDouble()
            lines += "N=$n first-try ${"%.4f".format(firstRate)} (min $firstMin), final ${"%.4f".format(finalRate)} (min $finalMin)"
            if (firstRate < firstMin) failures += "N=$n first-try $firstRate < $firstMin"
            if (finalRate < finalMin) failures += "N=$n final $finalRate < $finalMin"
        }
        report.writeText(lines.joinToString("\n") + "\n")
        assertTrue(failures.isEmpty(), (lines + failures).joinToString("\n"))
    }

    /** 한 번의 혼잡 상황에서 (첫 시도 정답 기기 수, 최종 정답 기기 수). */
    private fun simulate(n: Int, r: Random): Pair<Int, Int> {
        val pairBump = List(n / 2) { r.nextLong(0, 1501) }
        val bumpAt = List(n) { i -> pairBump[i / 2] + gaussian(r, 15.0).toLong().coerceIn(-40, 40) }
        val selectAt = List(n) { bumpAt[it] + config.bumpFlagWaitMs }
        val partner = { i: Int -> i xor 1 }
        val picks = (0 until n).map { me -> select(me, n, bumpAt, selectAt, partner, r) }
        var first = 0
        var final = 0
        for (me in 0 until n) {
            val want = PeerRef(partner(me) + 1)
            val picked = picks[me]
            if (picked.firstOrNull() == want) first++
            val fromOtherSide = picked.isEmpty() && PeerRef(me + 1) in picks[partner(me)].take(config.maxAttempts)
            if (want in picked.take(config.maxAttempts) || fromOtherSide) final++
        }
        return first to final
    }

    private fun select(me: Int, n: Int, bumpAt: List<Long>, selectAt: List<Long>, partner: (Int) -> Int, r: Random): List<PeerRef> {
        run {
            val clock = FakeClock(selectAt[me])
            val peers = (0 until n).filter { it != me }.map { s ->
                val latency = r.nextLong(100, 701)
                val missed = r.nextDouble() < 0.03
                val observedChange = (bumpAt[s] + latency).takeIf { !missed && it <= selectAt[me] }
                val rssi = if (s == partner(me)) {
                    val base = r.nextDouble(-45.0, -30.0) - if (r.nextDouble() < 0.05) 12.0 else 0.0
                    base + gaussian(r, 3.0)
                } else {
                    r.nextDouble(-80.0, -50.0) + gaussian(r, 5.0)
                }
                val busy = selectAt[s] + 200 < selectAt[me]
                PeerEntry(
                    PeerSeen(s + 1, rssi.toInt(), selectAt[me], AdvFlags(busy = busy, bumpSeq = 1), null),
                    firstSeenMs = 0,
                    lastBumpChangeAtMs = observedChange,
                )
            }
            return PeerMatcher(config).select(MatchSelf(null, bumpAt[me]), peers, Cooldown(clock, config.retryCooldownMs)).peers
        }
    }

    private fun gaussian(r: Random, sigma: Double): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2) * sigma
    }

    private companion object {
        const val TRIALS = 500
    }
}
