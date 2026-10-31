package com.hmgapp.yellowlightcoach.coach

import com.hmgapp.yellowlightcoach.signal.Phase

/** 운전자 모드 — 같은 교차로라도 반응이 느리거나 세게 멈추기 어려우면 더 일찍 안내 */
enum class DriveMode(val label: String, val profile: DriverProfile) {
    /** 반응 1.0초, 편안한 감속 3.0 m/s² (승용차 측정값 약 3.1) */
    STANDARD("기본", DriverProfile(reactionSec = 1.0, comfortDecel = 3.0)),
    /** 반응 1.5초, 편안한 감속 2.0 m/s² (화물차 측정값 약 2.5보다 여유 있게, 초보 포함) */
    RELAXED("여유 (초보·화물차)", DriverProfile(reactionSec = 1.5, comfortDecel = 2.0))
}

/**
 * 교차로 안전 점수 (5단계, 10/31)
 *
 * 한 번의 주행 동안 신호 교차로에서의 운전을 집계해, 주차(P)하면 요약을 보여준다.
 *  - 지난 신호 교차로 수: 정지선을 넘는 순간(정지선까지 거리 > 0 → ≤ 0) 1곳
 *  - 황색·적색 진입: 정지선을 넘는 순간 내 신호가 황색 또는 적색
 *  - 급정거: 20km/h 이상에서 1초 동안 4 m/s² 넘게 감속 (3초 안 중복 제외)
 *  - 미리 안내: 주의 / 정지 준비를 말한 횟수
 *
 *  점수 = 100 − 60 × (황색·적색 진입 ÷ 교차로 수) − 40 × (급정거 ÷ 교차로 수), 0~100
 *  (기존 보험 점수가 과속·급가속·급감속만 보는 것과 달리 '교차로'라는 새 지표)
 *
 * 순수 계산 (시간은 밖에서 넣어 줌) → 단위 테스트
 */
class TripScore {
    data class Summary(
        val intersections: Int,
        val cautions: Int,
        val prepares: Int,
        val redYellowEntries: Int,
        val hardBrakes: Int,
        /** 교차로를 하나도 안 지났으면 null */
        val score: Int?
    )

    private var intersections = 0
    private var cautions = 0
    private var prepares = 0
    private var entries = 0
    private var hardBrakes = 0

    private var curId: String? = null
    private var lastStopLineM: Double? = null
    private var counted = false

    private var refSpeed = Double.NaN
    private var refAt = 0L
    private var lastHardBrakeAt = Long.MIN_VALUE / 2

    var started = false
        private set

    fun startTrip() {
        intersections = 0; cautions = 0; prepares = 0; entries = 0; hardBrakes = 0
        curId = null; lastStopLineM = null; counted = false
        refSpeed = Double.NaN; refAt = 0L; lastHardBrakeAt = Long.MIN_VALUE / 2
        started = true
    }

    fun onAlert(level: Level) {
        when (level) {
            Level.CAUTION -> cautions++
            Level.PREPARE_STOP -> prepares++
            else -> Unit
        }
    }

    /** 주행(D) 중 0.25초마다 */
    fun onTick(intersectionId: String?, stopLineM: Double?, phase: Phase?, speedMps: Double, nowMs: Long) {
        // --- 교차로 통과 ---
        if (intersectionId != curId) {
            // 정지선 바로 앞(15m 안)까지 왔다가 앞 교차로가 바뀌었으면 지난 것으로 셈
            val prev = lastStopLineM
            if (curId != null && !counted && prev != null && prev < 15.0) intersections++
            curId = intersectionId
            lastStopLineM = null
            counted = false
        }
        if (intersectionId != null && stopLineM != null) {
            val prev = lastStopLineM
            if (!counted && prev != null && prev > 0.0 && stopLineM <= 0.0) {
                counted = true
                intersections++
                if (phase == Phase.YELLOW || phase == Phase.RED) entries++
            }
            lastStopLineM = stopLineM
        }

        // --- 급정거 (1초 간격으로 비교) ---
        if (refSpeed.isNaN()) { refSpeed = speedMps; refAt = nowMs; return }
        val dt = (nowMs - refAt) / 1000.0
        if (dt >= 1.0) {
            val decel = (refSpeed - speedMps) / dt
            if (refSpeed >= HARD_BRAKE_MIN_SPEED && decel >= HARD_BRAKE_DECEL && nowMs - lastHardBrakeAt > 3_000) {
                hardBrakes++
                lastHardBrakeAt = nowMs
            }
            refSpeed = speedMps
            refAt = nowMs
        }
    }

    fun summary(): Summary {
        val score = if (intersections == 0) null else {
            val n = intersections.toDouble()
            (100 - 60 * entries / n - 40 * hardBrakes / n).coerceIn(0.0, 100.0).toInt()
        }
        return Summary(intersections, cautions, prepares, entries, hardBrakes, score)
    }

    companion object {
        const val HARD_BRAKE_DECEL = 4.0          // m/s²
        const val HARD_BRAKE_MIN_SPEED = 20 / 3.6 // m/s
    }
}