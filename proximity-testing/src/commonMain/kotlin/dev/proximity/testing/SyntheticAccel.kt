package dev.proximity.testing

import dev.proximity.core.interfaces.AccelSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 시드 고정 합성 가속도 트레이스 (100Hz, 단위 g). S3 판정 로직을 실기기 없이 검증하는 용도다.
 * 실제 정확도를 보장하지 않는다 — 실측 트레이스(HW-S3)로 다시 확인한다.
 * 생성 값은 판정 결과를 보기 전에 물리적 직관으로 정했다(S3 검증 기록).
 */
object SyntheticAccel {
    const val PERIOD_MS = 10L

    /** [expectedBumpAtMs]: 부딪힘 트레이스의 정답 시각(최대 충격 시점), 아니면 null. */
    class Trace(val kind: String, val samples: List<AccelSample>, val expectedBumpAtMs: Long?)

    /** 폰을 들고 멈춘 뒤 다른 폰과 부딪힌다: 짧고 강한 충격 + 작은 반동. */
    fun bump(seed: Int): Trace {
        val r = Random(seed)
        val b = Builder(r, durationMs = 2000, gravity = randomGravity(r))
        b.tremor(sigma = 0.03, amp = 0.04, hz = 8.0)
        val start = 1000L + r.nextLong(0, 200)
        val width = r.nextLong(20, 41)
        val amp = r.uniform(2.8, 5.0)
        val dir = randomUnit(r)
        b.pulse(start, width, amp, dir)
        b.pulse(start + width, 30, -0.35 * amp, dir)
        return Trace("bump", b.samples(), expectedBumpAtMs = start + width / 2)
    }

    /** 걷기: 1.8~2.2Hz 발걸음 충격 + 좌우 흔들림. */
    fun walk(seed: Int): Trace {
        val r = Random(seed)
        val g = randomGravity(r)
        val b = Builder(r, durationMs = 3000, gravity = g)
        b.tremor(sigma = 0.05, amp = 0.2, hz = 1.0)
        val period = (1000.0 / r.uniform(1.8, 2.2)).toLong()
        var t = r.nextLong(0, period)
        while (t < 3000) {
            b.pulse(t, 150, r.uniform(0.3, 0.9), g.normalized())
            t += period + r.nextLong(-20, 21)
        }
        return Trace("walk", b.samples(), null)
    }

    /** 뛰기: 2.6~3.2Hz, 충격이 크고(일부는 임계치 초과) 몸 흔들림이 크다. */
    fun run(seed: Int): Trace {
        val r = Random(seed)
        val g = randomGravity(r)
        val b = Builder(r, durationMs = 3000, gravity = g)
        b.tremor(sigma = 0.25, amp = 0.4, hz = 1.4)
        val period = (1000.0 / r.uniform(2.6, 3.2)).toLong()
        var t = r.nextLong(0, period)
        while (t < 3000) {
            b.pulse(t, r.nextLong(80, 121), r.uniform(1.5, 3.2), g.normalized())
            t += period + r.nextLong(-15, 16)
        }
        return Trace("run", b.samples(), null)
    }

    /** 주머니에서 꺼내기: 느리고 큰 움직임 + 폰 방향 회전. */
    fun pocket(seed: Int): Trace {
        val r = Random(seed)
        val g0 = randomGravity(r)
        val g1 = rotate(g0, randomUnit(r), r.uniform(60.0, 120.0) * PI / 180)
        val b = Builder(r, durationMs = 2000, gravity = g0)
        b.tremor(sigma = 0.04, amp = 0.05, hz = 3.0)
        val start = 500L + r.nextLong(0, 200)
        val dur = r.nextLong(300, 601)
        b.rotateGravity(start, dur, g1)
        b.smoothPush(start, dur, r.uniform(0.5, 1.5), randomUnit(r))
        return Trace("pocket", b.samples(), null)
    }

    /** 테이블 위 폰을 손가락으로 가볍게 두드리기: 1~3번, 짧고 약한 충격. */
    fun tap(seed: Int): Trace {
        val r = Random(seed)
        val b = Builder(r, durationMs = 2000, gravity = Vec(0.0, 0.0, 1.0))
        b.tremor(sigma = 0.02, amp = 0.0, hz = 1.0)
        var t = 400L + r.nextLong(0, 300)
        repeat(r.nextInt(1, 4)) {
            b.pulse(t, r.nextLong(10, 21), r.uniform(0.8, 2.2), Vec(0.0, 0.0, -1.0))
            t += r.nextLong(200, 450)
        }
        return Trace("tap", b.samples(), null)
    }

    /** S3 오탐 코퍼스 구성: 걷기 30, 뛰기 25, 주머니 25, 두드리기 20 (시드 1000번대부터). */
    fun negativeCorpus(): List<Trace> =
        (0 until 30).map { walk(1000 + it) } + (0 until 25).map { run(2000 + it) } +
            (0 until 25).map { pocket(3000 + it) } + (0 until 20).map { tap(4000 + it) }

    fun bumpCorpus(): List<Trace> = (0 until 10).map { bump(100 + it) }

    // ---- 내부 ----

    class Vec(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)

        operator fun times(k: Double) = Vec(x * k, y * k, z * k)

        fun norm() = sqrt(x * x + y * y + z * z)

        fun normalized() = this * (1 / norm())
    }

    private class Builder(private val r: Random, durationMs: Long, gravity: Vec) {
        private val n = (durationMs / PERIOD_MS).toInt()
        private val gravity = Array(n) { gravity }
        private val extra = Array(n) { Vec(0.0, 0.0, 0.0) }

        /** 손 떨림: 가우스 잡음 + 사인 흔들림. */
        fun tremor(sigma: Double, amp: Double, hz: Double) {
            val phase = r.uniform(0.0, 2 * PI)
            val axis = randomUnit(r)
            for (i in 0 until n) {
                val t = i * PERIOD_MS / 1000.0
                extra[i] = extra[i] + Vec(r.gaussian() * sigma, r.gaussian() * sigma, r.gaussian() * sigma) +
                    axis * (amp * sin(2 * PI * hz * t + phase))
            }
        }

        /** 반 사인 충격. */
        fun pulse(startMs: Long, widthMs: Long, amp: Double, dir: Vec) {
            val from = (startMs / PERIOD_MS).toInt()
            val steps = (widthMs / PERIOD_MS).toInt().coerceAtLeast(1)
            for (k in 0..steps) {
                val i = from + k
                if (i !in 0 until n) continue
                extra[i] = extra[i] + dir * (amp * sin(PI * k / steps))
            }
        }

        /** 가우스 모양으로 천천히 밀었다 멈추는 움직임. */
        fun smoothPush(startMs: Long, durMs: Long, amp: Double, dir: Vec) {
            val center = startMs + durMs / 2
            val sigma = durMs / 4.0
            for (i in 0 until n) {
                val t = i * PERIOD_MS
                val d = (t - center) / sigma
                extra[i] = extra[i] + dir * (amp * exp(-0.5 * d * d))
            }
        }

        /** [startMs]부터 [durMs] 동안 중력 방향을 [target]으로 서서히 바꾼다. */
        fun rotateGravity(startMs: Long, durMs: Long, target: Vec) {
            val from = gravity[0]
            for (i in 0 until n) {
                val t = i * PERIOD_MS
                val u = ((t - startMs).toDouble() / durMs).coerceIn(0.0, 1.0)
                val s = (1 - cos(PI * u)) / 2
                gravity[i] = (from * (1 - s) + target * s).normalized()
            }
        }

        fun samples(): List<AccelSample> = (0 until n).map { i ->
            val a = gravity[i] + extra[i]
            AccelSample(i * PERIOD_MS, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
        }
    }

    private fun randomUnit(r: Random): Vec {
        while (true) {
            val v = Vec(r.uniform(-1.0, 1.0), r.uniform(-1.0, 1.0), r.uniform(-1.0, 1.0))
            val norm = v.norm()
            if (norm in 0.1..1.0) return v * (1 / norm)
        }
    }

    /** 대체로 화면이 위를 향한 채 기울어진 중력 1g. */
    private fun randomGravity(r: Random): Vec =
        Vec(r.uniform(-0.4, 0.4), r.uniform(-0.4, 0.4), 1.0).normalized()

    /** 로드리게스 회전. */
    private fun rotate(v: Vec, axis: Vec, angle: Double): Vec {
        val k = axis.normalized()
        val c = cos(angle)
        val s = sin(angle)
        val dot = v.x * k.x + v.y * k.y + v.z * k.z
        val cross = Vec(k.y * v.z - k.z * v.y, k.z * v.x - k.x * v.z, k.x * v.y - k.y * v.x)
        return v * c + cross * s + k * (dot * (1 - c))
    }

    private fun Random.uniform(from: Double, to: Double) = from + nextDouble() * (to - from)

    /** 박스-뮬러 (common에는 nextGaussian이 없다). */
    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}
