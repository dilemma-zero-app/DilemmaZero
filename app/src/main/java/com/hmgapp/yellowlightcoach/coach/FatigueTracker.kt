package com.hmgapp.yellowlightcoach.coach

/**
 * 연속 운전 시간(피로) — 기어 D로 달린 시간
 *
 *  - D(주행)로 바뀐 순간부터 잰다. 신호 대기 같은 짧은 P는 이어서 센다
 *  - P(주차)에서 REST_RESET_MS(15분) 이상 쉬면 0으로 초기화
 *  - 피로 계수: 1시간 미만 1.0 / 1~2시간 1.1 / 2시간 이상 1.2 → 반응시간에 곱함
 *  - 2시간이 넘으면 다음 P에서 한 번 휴식 권유
 *
 * 시간은 밖에서 넣어 준다 (단위 테스트에서 시간을 마음대로 돌릴 수 있게)
 */
class FatigueTracker {
    private var driving = false
    private var segmentStart = 0L      // 이번 주행 구간 시작
    private var accumulated = 0L       // 이전 구간들 합
    private var parkedSince = 0L
    private var restReminded = false

    fun onGear(isDrive: Boolean, nowMs: Long) {
        if (isDrive == driving) return
        if (isDrive) {
            if (parkedSince != 0L && nowMs - parkedSince >= REST_RESET_MS) {
                accumulated = 0L
                restReminded = false
            }
            segmentStart = nowMs
        } else {
            accumulated += nowMs - segmentStart
            parkedSince = nowMs
        }
        driving = isDrive
    }

    fun drivingMs(nowMs: Long): Long =
        accumulated + if (driving) nowMs - segmentStart else 0L

    fun factor(nowMs: Long): Double {
        val h = drivingMs(nowMs) / HOUR_MS.toDouble()
        return when {
            h < 1.0 -> 1.0
            h < 2.0 -> 1.1
            else -> 1.2
        }
    }

    /** P 상태이고 2시간 넘게 달렸고 아직 말 안 했으면 true (한 번만) */
    fun shouldRemindRest(nowMs: Long): Boolean {
        if (driving || restReminded) return false
        if (drivingMs(nowMs) < 2 * HOUR_MS) return false
        restReminded = true
        return true
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val REST_RESET_MS = 15 * 60_000L
    }
}