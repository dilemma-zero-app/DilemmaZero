package com.hmgapp.yellowlightcoach.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 브레이크 습관 기록 테스트 (6단계)
 * 출발점: 기본 모드 (반응 1.0초, 감속 3.0 m/s²)
 */
class BrakeLearnerTest {

    private fun kmh(v: Double) = v / 3.6

    /** 안내 → reactMs 뒤 브레이크 → 60→0km/h를 stopMs 동안 고르게 감속 */
    private fun stop(b: BrakeLearner, t0: Long, reactMs: Long, stopMs: Long): Long {
        b.onAlert(t0, kmh(60.0))
        b.onSpeed(kmh(60.0), t0 + reactMs / 2)
        b.onBrake(true, kmh(60.0), t0 + reactMs)
        val steps = 6
        for (i in 1..steps) b.onSpeed(kmh(60.0 - 10.0 * i), t0 + reactMs + stopMs * i / steps)
        b.onBrake(false, 0.0, t0 + reactMs + stopMs + 500)
        return t0 + reactMs + stopMs + 10_000
    }

    @Test fun `안내 1점2초 뒤 브레이크 - 반응시간 기록`() {
        val b = BrakeLearner()
        b.onAlert(0, kmh(60.0))
        val msg = b.onBrake(true, kmh(60.0), 1_200)
        assertEquals(1, b.stats.reactionCount)
        assertEquals(0.8 * 1.0 + 0.2 * 1.2, b.stats.reactionSec!!, 1e-9)   // 1.04
        assertTrue(msg!!.contains("1.2초"))
    }

    @Test fun `60에서 0까지 6초 - 감속도 약 2점8 기록`() {
        val b = BrakeLearner()
        stop(b, 0, 1_000, 6_000)
        val a = kmh(60.0) / 6.0                                   // 2.78 m/s²
        assertEquals(1, b.stats.decelCount)
        assertEquals(0.8 * 3.0 + 0.2 * a, b.stats.decel!!, 1e-9)
    }

    @Test fun `안내 전에 이미 밟고 있으면 반응시간 제외`() {
        val b = BrakeLearner()
        b.onBrake(true, kmh(60.0), 0)
        assertNotNullMsg(b.onAlert(500, kmh(55.0)))
        b.onBrake(false, kmh(50.0), 1_000)
        b.onBrake(true, kmh(50.0), 1_500)
        assertEquals(0, b.stats.reactionCount)
    }

    @Test fun `4초 넘게 안 밟으면 안내 무시로 셈`() {
        val b = BrakeLearner()
        b.onAlert(0, kmh(60.0))
        b.onSpeed(kmh(60.0), 3_000)
        assertEquals(0, b.stats.ignoredCount)
        b.onSpeed(kmh(60.0), 4_500)
        assertEquals(1, b.stats.ignoredCount)
        b.onBrake(true, kmh(60.0), 5_000)                        // 늦게 밟음 → 반응시간 아님
        assertEquals(0, b.stats.reactionCount)
    }

    @Test fun `너무 빠른 반응과 저속은 제외`() {
        val b = BrakeLearner()
        b.onAlert(0, kmh(60.0))
        b.onBrake(true, kmh(60.0), 100)                          // 0.1초: 안내와 무관
        assertEquals(0, b.stats.reactionCount)
        b.onBrake(false, kmh(60.0), 200)
        assertNull(b.onAlert(1_000, kmh(15.0)))                   // 15km/h: 안내 기록 안 함
        b.onBrake(true, kmh(15.0), 2_000)
        assertEquals(0, b.stats.reactionCount)
    }

    @Test fun `급정거는 감속도에서 제외`() {
        val b = BrakeLearner()
        stop(b, 0, 1_000, 3_000)                                  // 60→0 3초 = 5.6 m/s²
        assertEquals(0, b.stats.decelCount)
        assertEquals(1, b.stats.reactionCount)                    // 반응시간은 정상 기록
    }

    @Test fun `멈추기 전에 다시 가속하면 감속도 제외`() {
        val b = BrakeLearner()
        b.onBrake(true, kmh(60.0), 0)
        b.onSpeed(kmh(40.0), 2_000)
        b.onBrake(false, kmh(40.0), 2_500)
        b.onSpeed(kmh(50.0), 4_000)                               // 다시 빨라짐
        b.onSpeed(kmh(0.0), 12_000)
        assertEquals(0, b.stats.decelCount)
    }

    @Test fun `페달을 잠깐 떼도 계속 줄어 멈추면 감속도 기록`() {
        val b = BrakeLearner()
        b.onBrake(true, kmh(60.0), 0)
        b.onBrake(false, kmh(55.0), 300)                          // 콘솔 BRAKE는 누르는 동안만
        for (i in 1..6) b.onSpeed(kmh(60.0 - 10.0 * i), 1_000L * i)
        assertEquals(1, b.stats.decelCount)
    }

    @Test fun `다섯 번 모이면 학습됨 - 값은 안전 범위 안`() {
        val b = BrakeLearner()
        var t = 0L
        repeat(4) { t = stop(b, t, 1_500, 8_000) }
        assertFalse(b.stats.learned)
        t = stop(b, t, 1_500, 8_000)
        assertTrue(b.stats.learned)
        assertTrue(b.stats.reactionSec!! in 1.0..1.5)             // 1.0에서 1.5 쪽으로 이동
        assertTrue(b.stats.decel!! in 2.08..3.0)                  // 3.0에서 2.08 쪽으로 이동
    }

    @Test fun `여유 모드에서 시작하면 출발점이 여유 모드 값`() {
        val b = BrakeLearner()
        b.base = DriveMode.RELAXED.profile                        // 1.5초 · 2.0
        b.onAlert(0, kmh(60.0))
        b.onBrake(true, kmh(60.0), 1_000)
        assertEquals(0.8 * 1.5 + 0.2 * 1.0, b.stats.reactionSec!!, 1e-9)
    }

    private fun assertNotNullMsg(s: String?) = assertTrue(s != null)
}