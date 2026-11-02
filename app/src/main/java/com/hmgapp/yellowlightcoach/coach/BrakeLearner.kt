package com.hmgapp.yellowlightcoach.coach

/**
 * 브레이크 습관 기록 (6단계)
 *
 * - 반응시간: 안내("주의"/"정지 준비")를 말한 순간 → 처음 브레이크를 밟은 순간
 * - 편안한 감속도: 브레이크를 밟은 순간 속도 → 거의 멈출 때까지, 줄어든 속도 ÷ 걸린 시간
 *   (중간에 페달을 잠깐 떼도 계속 줄어 멈추면 기록. 다시 5km/h 넘게 빨라지면 제외)
 *
 * 새 값이 들어올 때마다 조금씩 반영한다 (지수 이동평균, 0.8 × 기존 + 0.2 × 새 값).
 * 첫 값의 "기존"은 지금 고른 안내 모드 값(base)에서 시작한다.
 * 5번 넘게 모이면(학습됨) 판정에도 반영 — 단, 안내가 더 일찍 나오는 쪽으로만 (judgeProfile).
 *
 * 순수 계산 (안드로이드 없음) → 단위 테스트 가능. 모든 함수는 기록 문구(로그용)나 null을 돌려준다.
 */
class BrakeLearner(var stats: Stats = Stats()) {

    data class Stats(
        val reactionSec: Double? = null,   // 내 반응시간 (기록 없으면 null)
        val reactionCount: Int = 0,
        val decel: Double? = null,         // 내 편안한 감속도 m/s² (기록 없으면 null)
        val decelCount: Int = 0,
        val ignoredCount: Int = 0          // 안내 후 4초 안에 브레이크 없음
    ) {
        /** 둘 다 5번 넘게 모이면 "학습됨" */
        val learned: Boolean get() = reactionCount >= MIN_SAMPLES && decelCount >= MIN_SAMPLES
    }

    /** 출발점 (지금 고른 안내 모드 값) */
    var base: DriverProfile = DriverProfile()

    private var alertAt: Long? = null      // 아직 브레이크를 기다리는 안내 시각
    private var braking = false
    private var stopping = false           // 감속 구간 재는 중
    private var stopStartAt = 0L
    private var stopStartV = 0.0
    private var stopMinV = 0.0

    /** 안내를 말한 순간 */
    fun onAlert(nowMs: Long, speedMps: Double): String? {
        if (braking) return "안내 전에 이미 브레이크를 밟고 있음 → 반응시간 제외"
        if (alertAt != null) return null                 // 같은 교차로 두 번째 안내 → 첫 안내 기준
        if (speedMps < MIN_SPEED_MPS) return null
        alertAt = nowMs
        return null
    }

    /** 브레이크 페달이 바뀐 순간 */
    fun onBrake(pressed: Boolean, speedMps: Double, nowMs: Long): String? {
        if (pressed == braking) return null
        braking = pressed
        if (!pressed) return null              // 떼도 감속 구간은 계속 (다시 빨라지면 그때 제외)
        // 밟음: 감속 구간 시작 (20km/h 이상에서만, 이미 재는 중이면 처음 밟은 순간 유지)
        if (speedMps >= MIN_SPEED_MPS && !stopping) {
            stopping = true
            stopStartAt = nowMs
            stopStartV = speedMps
            stopMinV = speedMps
        }
        // 반응시간
        val a = alertAt ?: return null
        alertAt = null
        if (speedMps < MIN_SPEED_MPS) return "밟을 때 속도가 20km/h 미만 → 반응시간 제외"
        val rt = (nowMs - a) / 1000.0
        if (rt < MIN_REACTION || rt > MAX_REACTION) return "반응시간 %.1f초 → 범위 밖이라 제외".format(rt)
        val s = stats
        val v = ema(s.reactionSec ?: base.reactionSec, rt).coerceIn(0.7, 2.0)
        stats = s.copy(reactionSec = v, reactionCount = s.reactionCount + 1)
        return "반응시간 %.1f초 기록 → 내 반응시간 %.2f초 (%d번째)".format(rt, v, stats.reactionCount)
    }

    /** 속도가 들어올 때마다 (안내 무시 판단 + 감속 구간 끝) */
    fun onSpeed(speedMps: Double, nowMs: Long): String? {
        alertAt?.let {
            if (nowMs - it > (MAX_REACTION * 1000).toLong()) {
                alertAt = null
                stats = stats.copy(ignoredCount = stats.ignoredCount + 1)
                return "안내 후 4초 안에 브레이크 없음 (안내 무시 ${stats.ignoredCount}번)"
            }
        }
        if (!stopping) return null
        if (nowMs - stopStartAt > MAX_STOP_MS) {
            stopping = false
            return "30초 넘게 안 멈춤 → 감속도 제외"
        }
        if (speedMps > stopMinV + REACCEL_MPS) {
            stopping = false
            return "멈추기 전에 다시 가속 → 감속도 제외"
        }
        stopMinV = minOf(stopMinV, speedMps)
        if (speedMps > STOP_SPEED_MPS) return null
        stopping = false
        val dt = (nowMs - stopStartAt) / 1000.0
        if (dt < 0.5) return "너무 짧은 감속 → 제외"
        val a = (stopStartV - speedMps) / dt
        if (a >= HARD_DECEL) return "감속 %.1f m/s² = 급정거 → 제외".format(a)
        if (a < 0.5) return null
        val s = stats
        val v = ema(s.decel ?: base.comfortDecel, a).coerceIn(1.5, 3.5)
        stats = s.copy(decel = v, decelCount = s.decelCount + 1)
        return "감속 %.1f m/s² 기록 (%.0f→%.0fkm/h, %.1f초) → 내 편안한 감속 %.2f (%d번째)"
            .format(a, stopStartV * 3.6, speedMps * 3.6, dt, v, stats.decelCount)
    }

    /**
     * 판정에 쓸 값 (11/2): "학습됨"일 때만, 안내가 **더 일찍** 나오는 쪽으로만 반영
     *   반응시간 = max(모드 값, 내 값),  감속도 = min(모드 값, 내 값)
     * → 반응이 빠르고 세게 서는 사람이라도 안내가 모드 기준보다 늦어지는 일은 없다
     */
    fun judgeProfile(mode: DriverProfile): DriverProfile {
        val s = stats
        if (!s.learned) return mode
        return mode.copy(
            reactionSec = maxOf(mode.reactionSec, s.reactionSec ?: mode.reactionSec),
            comfortDecel = minOf(mode.comfortDecel, s.decel ?: mode.comfortDecel)
        )
    }

    /** 기록 모두 지우기 (개발자 보기) */
    fun reset() {
        stats = Stats()
        alertAt = null
        stopping = false
    }

    private fun ema(old: Double, new: Double) = 0.8 * old + 0.2 * new

    companion object {
        const val MIN_SAMPLES = 5
        const val MIN_SPEED_MPS = 20 / 3.6
        const val STOP_SPEED_MPS = 5 / 3.6
        const val MIN_REACTION = 0.2
        const val MAX_REACTION = 4.0
        const val HARD_DECEL = 4.0
        const val MAX_STOP_MS = 30_000L
        const val REACCEL_MPS = 5 / 3.6
    }
}