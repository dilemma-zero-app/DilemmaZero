package com.hmgapp.yellowlightcoach.coach

import com.hmgapp.yellowlightcoach.signal.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 판정 엔진 시나리오 테스트 — 에뮬레이터 없이 실행
 * (Android Studio: 이 파일 우클릭 → Run 'CoachScenarioTest')
 *
 * 기본값: 반응시간 1.0초, 편안한 감속도 3.0 m/s², 신호 안전 여유 1초
 */
class CoachScenarioTest {

    private fun kmh(v: Double) = v / 3.6
    private fun judge(
        kmh: Double, stopLineM: Double, phase: Phase, remainingMs: Long?,
        profile: DriverProfile = DriverProfile(), fatigue: Double = 1.0
    ) = DilemmaEvaluator.evaluate(kmh(kmh), stopLineM, phase, remainingMs, profile, fatigue).level

    // ---------------- 판정 엔진 ----------------

    @Test fun `60kmh 정지선 90m 녹색 5초 - 주의`() =
        assertEquals(Level.CAUTION, judge(60.0, 90.0, Phase.GREEN, 5_000))

    @Test fun `60kmh 정지선 90m 녹색 10초 - 녹색 중 도착이라 여유`() =
        assertEquals(Level.CALM, judge(60.0, 90.0, Phase.GREEN, 10_000))

    @Test fun `60kmh 정지선 150m 녹색 5초 - 황색 때 멈출 거리 충분해 여유`() =
        assertEquals(Level.CALM, judge(60.0, 150.0, Phase.GREEN, 5_000))

    @Test fun `60kmh 정지선 55m 녹색 4초 - 세게 밟아야 멈춰 정지 준비`() =
        assertEquals(Level.PREPARE_STOP, judge(60.0, 55.0, Phase.GREEN, 4_000))

    @Test fun `60kmh 정지선 60m 녹색 2점5초 - 곧 황색이라 정지 준비`() =
        assertEquals(Level.PREPARE_STOP, judge(60.0, 60.0, Phase.GREEN, 2_500))

    @Test fun `60kmh 정지선 30m 녹색 2점5초 - 급제동도 무리라 침묵`() =
        assertEquals(Level.INACTIVE, judge(60.0, 30.0, Phase.GREEN, 2_500))

    @Test fun `60kmh 정지선 200m 녹색 2점5초 - 아직 멀어서 여유`() =
        assertEquals(Level.CALM, judge(60.0, 200.0, Phase.GREEN, 2_500))

    @Test fun `황색 - 멈출 수 있으면 정지 준비, 너무 가까우면 침묵`() {
        assertEquals(Level.PREPARE_STOP, judge(60.0, 80.0, Phase.YELLOW, null))
        assertEquals(Level.INACTIVE, judge(60.0, 30.0, Phase.YELLOW, null))
    }

    @Test fun `황색인데 아주 멀면 어차피 적색에 도착하니 말하지 않음`() =
        assertEquals(Level.CALM, judge(10.0, 68.0, Phase.YELLOW, null))   // 롯데호텔앞 실제 상황

    @Test fun `주의는 도착 12초 이내에서만 - 멀면 기다렸다가 가까워지면 안내`() {
        assertEquals(Level.CALM, judge(60.0, 250.0, Phase.GREEN, 14_000))     // 15초 거리: 아직
        assertEquals(Level.CAUTION, judge(60.0, 190.0, Phase.GREEN, 10_400))  // 3.6초 뒤 같은 상황: 이제 안내
    }

    @Test fun `적색 신호 모름 남은시간 모름 - 침묵`() {
        assertEquals(Level.INACTIVE, judge(60.0, 90.0, Phase.RED, 30_000))
        assertEquals(Level.INACTIVE, judge(60.0, 90.0, Phase.UNKNOWN, 5_000))
        assertEquals(Level.INACTIVE, judge(60.0, 90.0, Phase.GREEN, null))
    }

    @Test fun `정차 중 - 여유`() =
        assertEquals(Level.CALM, judge(3.0, 90.0, Phase.GREEN, 5_000))

    @Test fun `어떤 경우에도 통과하라는 판정은 없다`() {
        // 속도 20~100km/h, 거리 10~300m, 녹색 0~20초 전부 돌려도 결과는 네 가지 중 하나
        for (v in 20..100 step 10) for (d in 10..300 step 10) for (g in 0..20_000 step 500) {
            val level = judge(v.toDouble(), d.toDouble(), Phase.GREEN, g.toLong())
            assertTrue(level in setOf(Level.INACTIVE, Level.CALM, Level.CAUTION, Level.PREPARE_STOP))
        }
    }

    // ---------------- 개인화 ----------------

    @Test fun `초보 운전자는 같은 상황에서 더 일찍 안내`() {
        val novice = DriverProfile(reactionSec = 1.5, comfortDecel = 2.0)
        assertEquals(Level.CALM, judge(40.0, 80.0, Phase.GREEN, 5_000))
        assertEquals(Level.CAUTION, judge(40.0, 80.0, Phase.GREEN, 5_000, novice))
    }

    @Test fun `2시간 넘게 운전하면 같은 상황에서 더 일찍 안내`() {
        assertEquals(Level.CALM, judge(60.0, 131.0, Phase.GREEN, 5_000, fatigue = 1.0))
        assertEquals(Level.CAUTION, judge(60.0, 131.0, Phase.GREEN, 5_000, fatigue = 1.2))
    }

    // ---------------- 피로 ----------------

    private val min = 60_000L

    @Test fun `연속 운전 시간에 따른 피로 계수`() {
        val f = FatigueTracker()
        f.onGear(true, 0)
        assertEquals(1.0, f.factor(30 * min), 0.0)
        assertEquals(1.1, f.factor(90 * min), 0.0)
        assertEquals(1.2, f.factor(130 * min), 0.0)
    }

    @Test fun `짧은 정차는 이어서 세고 15분 넘게 쉬면 초기화`() {
        val f = FatigueTracker()
        f.onGear(true, 0)
        f.onGear(false, 50 * min)          // 50분 운전 후 5분 정차
        f.onGear(true, 55 * min)
        assertEquals(1.1, f.factor(70 * min), 0.0)   // 50 + 15 = 65분
        f.onGear(false, 70 * min)          // 20분 휴식
        f.onGear(true, 90 * min)
        assertEquals(1.0, f.factor(100 * min), 0.0)  // 초기화 후 10분
    }

    @Test fun `휴식 권유는 2시간 넘은 뒤 정차 중에 한 번만`() {
        val f = FatigueTracker()
        f.onGear(true, 0)
        assertFalse(f.shouldRemindRest(130 * min))   // 주행 중에는 말하지 않음
        f.onGear(false, 130 * min)
        assertTrue(f.shouldRemindRest(131 * min))
        assertFalse(f.shouldRemindRest(132 * min))   // 한 번만
    }

    // ---------------- 위치-속도 불일치 ----------------

    @Test fun `60kmh인데 위치가 5초 넘게 그대로면 불일치`() {
        val d = PositionStallDetector()
        d.onFix(35.0, 129.0, 0)
        assertFalse(d.stalled(kmh(60.0), 3_000))
        d.onFix(35.0, 129.0, 3_000)                  // 같은 자리
        assertTrue(d.stalled(kmh(60.0), 6_000))
    }

    @Test fun `잘 움직이면 불일치 아님 - 출발 직후엔 5초 여유`() {
        val d = PositionStallDetector()
        d.onFix(35.0, 129.0, 0)
        assertFalse(d.stalled(0.0, 60_000))           // 1분 정차 (느리면 정상)
        assertFalse(d.stalled(kmh(40.0), 62_000))     // 막 출발: 여유 시간 안
        d.onFix(35.0002, 129.0, 63_000)              // 약 22m 이동
        assertFalse(d.stalled(kmh(40.0), 66_000))
    }

    // ---------------- 언제 말할지 ----------------

    @Test fun `같은 교차로에서는 더 위험해질 때만 한 번씩`() {
        val p = AlertPolicy()
        assertNull(p.next("5", Level.CALM))
        assertEquals("앞 신호, 속도 줄이세요", p.next("5", Level.CAUTION))
        assertNull(p.next("5", Level.CAUTION))                    // 반복 안 함
        assertEquals("정지 준비", p.next("5", Level.PREPARE_STOP))
        assertNull(p.next("5", Level.CAUTION))                    // 낮아져도 말 안 함
        assertEquals("앞 신호, 속도 줄이세요", p.next("229", Level.CAUTION))  // 다음 교차로는 새로
    }
}