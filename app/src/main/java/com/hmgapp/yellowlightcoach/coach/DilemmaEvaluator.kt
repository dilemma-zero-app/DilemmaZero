package com.hmgapp.yellowlightcoach.coach

import com.hmgapp.yellowlightcoach.signal.Phase

/** 안내 상태 (숫자가 클수록 위험) */
enum class Level(val rank: Int, val label: String) {
    INACTIVE(0, "비활성"),
    CALM(1, "여유"),
    CAUTION(2, "주의"),
    PREPARE_STOP(3, "정지 준비")
}

/** 판정 결과 — 화면·기록용 계산값도 함께 담는다 */
data class Verdict(
    val level: Level,
    /** 왜 이 판정인지 (화면·로그용) */
    val reason: String,
    /** 편안한 정지거리 S (m) */
    val comfortStopM: Double = 0.0,
    /** 급제동 정지거리 (m) — 이보다 가까우면 멈추라고 하지 않는다 */
    val hardStopM: Double = 0.0,
    /** 정지선 도착까지 걸리는 시간 (s) */
    val arriveSec: Double = 0.0,
    /** 녹색 남은 시간 (안전 여유 뺀 값, s) */
    val greenSec: Double = 0.0,
    /** 황색이 켜지는 순간 정지선까지 남는 거리 dY (m) */
    val atYellowM: Double = 0.0
)

/** 운전자 특성 */
data class DriverProfile(
    /** 반응시간 (s) — 안내를 듣고 브레이크를 밟기까지 */
    val reactionSec: Double = 1.0,
    /** 편안한 감속도 (m/s²) */
    val comfortDecel: Double = 3.0
)

/**
 * 판정 엔진 — 순수 계산 (안드로이드 의존 없음 → 단위 테스트로 검증)
 *
 *   편안한 정지거리   S    = v·tr' + v² / (2·a)        tr' = tr × 피로계수
 *   급제동 정지거리   Sh   = v·tr' + v² / (2·A_MAX)
 *   정지선 도착 시간  t    = d / v
 *   황색 순간 남은 거리 dY = d − v·Tg                  (Tg = 녹색 남은 시간 − 안전 여유)
 *
 * 원칙
 *  - "통과하세요"는 없다. 결과는 여유(침묵) / 주의 / 정지 준비 / 비활성뿐
 *  - 급제동으로도 못 멈출 거리면 침묵 (갑자기 멈추라고 해서 위험을 만들지 않는다)
 *  - 모르면 침묵 (신호 모름, 적색 등)
 */
object DilemmaEvaluator {
    /** 급제동 감속도 (m/s²) — 일반 승용차가 무리 없이 낼 수 있는 강한 제동 */
    const val HARD_DECEL = 6.0
    /** 신호 데이터 오차 대비 안전 여유 (s) — 녹색을 이만큼 짧게 본다 */
    const val SIGNAL_MARGIN_SEC = 1.0
    /** 이 시간 이하로 녹색이 남으면 "곧 황색" */
    const val YELLOW_SOON_SEC = 2.0
    /** 이보다 느리면 (약 7km/h) 판정하지 않음 */
    const val MIN_SPEED_MPS = 2.0
    /** "정지 준비"는 이 시간 안에 정지선에 닿을 만큼 가까울 때만 (멀리서 미리 말하지 않음) */
    const val NEAR_SEC = 4.0
    /** 이미 황색일 때 "정지 준비"는 이 시간 안에 정지선에 닿을 때만 (멀면 어차피 적색에 도착) */
    const val YELLOW_NEAR_SEC = 6.0
    /** "주의"는 정지선 도착이 이 시간 이내일 때만 (너무 일찍 말하지 않음) */
    const val ALERT_WINDOW_SEC = 12.0

    fun evaluate(
        speedMps: Double,
        stopLineM: Double,
        phase: Phase,
        remainingMs: Long?,
        profile: DriverProfile = DriverProfile(),
        fatigueFactor: Double = 1.0
    ): Verdict {
        if (phase == Phase.RED) return Verdict(Level.INACTIVE, "적색")
        if (phase == Phase.UNKNOWN) return Verdict(Level.INACTIVE, "신호 모름")
        if (speedMps < MIN_SPEED_MPS) return Verdict(Level.CALM, "정차·서행")

        val v = speedMps
        val d = stopLineM
        val tr = profile.reactionSec * fatigueFactor
        val s = v * tr + v * v / (2 * profile.comfortDecel)
        val sh = v * tr + v * v / (2 * HARD_DECEL)
        val t = d / v

        // 이미 황색: 가깝고 급제동 없이 멈출 수 있으면 "정지 준비"
        if (phase == Phase.YELLOW) {
            return when {
                d < sh -> Verdict(Level.INACTIVE, "황색, 정지선이 너무 가까움", s, sh, t)
                t > YELLOW_NEAR_SEC -> Verdict(Level.CALM, "황색이지만 아직 멀다", s, sh, t)
                else -> Verdict(Level.PREPARE_STOP, "황색, 정지 가능", s, sh, t)
            }
        }

        // 녹색
        val rem = remainingMs ?: return Verdict(Level.INACTIVE, "남은 시간 모름", s, sh, t)
        val g = rem / 1000.0 - SIGNAL_MARGIN_SEC
        val dY = d - v * g.coerceAtLeast(0.0)

        fun out(level: Level, why: String) = Verdict(level, why, s, sh, t, g, dY)

        if (t <= g) return out(Level.CALM, "녹색 중에 정지선 도착")

        if (g <= YELLOW_SOON_SEC) {
            return when {
                d < sh -> out(Level.INACTIVE, "곧 황색, 정지선이 너무 가까움")
                t <= g + NEAR_SEC -> out(Level.PREPARE_STOP, "곧 황색")
                else -> out(Level.CALM, "곧 황색이지만 아직 멀다")
            }
        }

        return when {
            dY >= s -> out(Level.CALM, "황색이 켜져도 편하게 멈춤")
            d >= s && t > ALERT_WINDOW_SEC -> out(Level.CALM, "아직 멀다 (가까워지면 다시 판정)")
            d >= s -> out(Level.CAUTION, "지금 줄이면 편하게 멈춤")
            d >= sh -> out(Level.PREPARE_STOP, "세게 밟아야 멈춤")
            else -> out(Level.INACTIVE, "정지선이 너무 가까움")
        }
    }
}