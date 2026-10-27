package com.hmgapp.yellowlightcoach.coach

import com.hmgapp.yellowlightcoach.location.Ahead
import com.hmgapp.yellowlightcoach.signal.Movement
import com.hmgapp.yellowlightcoach.signal.SignalRepository
import com.hmgapp.yellowlightcoach.signal.SignalState
import kotlin.math.abs

/** 깜빡이로 알아낸 운전자의 의도 */
enum class TurnIntent { NONE, LEFT, RIGHT }

/**
 * 지금 이 순간의 상황 정리 — 3단계 판단 엔진의 입력
 *
 * inactiveReason == null 이면 안내 가능,
 * 아니면 그 이유로 안내를 멈춘다(틀린 안내보다 안내 안 하는 게 안전).
 */
data class Situation(
    val ahead: Ahead?,
    /** 내가 봐야 할 신호 종류 (직진 / 좌회전) */
    val movement: Movement,
    /** 좌회전 깜빡이인데 좌회전 신호가 없어 직진 신호로 대신 봤는지 */
    val leftFallback: Boolean,
    val signal: SignalState?,
    /** 정지선까지 거리 (m) = 교차로 중심 거리 − 25m */
    val stopLineM: Double?,
    val inactiveReason: String?
)

object SituationBuilder {
    /** 교차로 중심 → 정지선 거리. 왕복 4~8차로 교차로 기준 대략값 */
    const val STOP_LINE_OFFSET_M = 25.0
    /** 신호를 이 시간 넘게 못 받으면 오래된 데이터로 본다 */
    const val STALE_MS = 30_000L

    fun build(
        ahead: Ahead?,
        turn: TurnIntent,
        turning: Boolean,
        repo: SignalRepository,
        nowElapsed: Long
    ): Situation {
        if (ahead == null) return Situation(null, Movement.STRAIGHT, false, null, null, "앞 교차로 없음")

        val id = ahead.intersection.id
        val stopLine = (ahead.distanceM - STOP_LINE_OFFSET_M).coerceAtLeast(0.0)

        // 1) 깜빡이로 신호 종류 고르기
        val wantLeft = turn == TurnIntent.LEFT
        val hasLeft = repo.hasSignal(id, ahead.approach, Movement.LEFT)
        val movement = if (wantLeft && hasLeft) Movement.LEFT else Movement.STRAIGHT
        val leftFallback = wantLeft && !hasLeft

        val signal = repo.current(id, ahead.approach, movement, nowElapsed)

        // 2) 안내를 멈춰야 하는 경우 (위에서부터 우선)
        val age = repo.dataAgeMs(nowElapsed)
        val reason = when {
            age > STALE_MS ->
                if (age == Long.MAX_VALUE) "신호 정보 없음 (아직 못 받음)"
                else "신호 정보가 오래됨 (${age / 1000}초 전)"
            turning -> "회전 중"
            turn == TurnIntent.RIGHT -> "우회전 (신호 안내 없음)"
            signal == null -> "이 방향 신호 정보 없음"
            signal.remainingMs == null -> "남은 시간 모름"
            else -> null
        }
        return Situation(ahead, movement, leftFallback, signal, stopLine, reason)
    }

    /** 방향지시등 이름 → 방향 (LEFT / RIGHT / 없음). 비상등(양쪽)은 없음 */
    fun turnIntentOf(stateName: String?): TurnIntent {
        val n = stateName?.uppercase() ?: return TurnIntent.NONE
        val left = "LEFT" in n
        val right = "RIGHT" in n
        return when {
            left && !right -> TurnIntent.LEFT
            right && !left -> TurnIntent.RIGHT
            else -> TurnIntent.NONE
        }
    }
}

/**
 * 핸들 회전 중인지 판단 (깜빡임 방지: 켜는 기준 90°, 끄는 기준 30°)
 * 교차로를 도는 중엔 앞 교차로·진행 방향이 계속 바뀌어 안내가 의미 없다.
 */
class TurningDetector {
    var turning = false
        private set

    fun update(angleDeg: Float): Boolean {
        val a = abs(angleDeg)
        if (!turning && a >= ON_DEG) turning = true
        else if (turning && a <= OFF_DEG) turning = false
        return turning
    }

    companion object {
        const val ON_DEG = 90f
        const val OFF_DEG = 30f
    }
}

/**
 * 깜빡이 → 회전 의도 (차선 변경과 구분)
 *
 * 10/27 실험: SDK는 Blink(회전)와 Flash(차선 변경 원터치)를 구분하지 않고 둘 다 LEFT로 준다.
 * 차선 변경 깜빡이는 보통 2~3초면 꺼지므로,
 * 같은 방향 깜빡이가 3초 넘게 계속 켜져 있을 때만 회전 의도로 본다.
 * (그 전까지는 직진 신호로 안내 — 안전한 쪽)
 */
class TurnIntentTracker {
    private var raw = TurnIntent.NONE
    private var sinceElapsed = 0L

    fun update(stateName: String?, nowElapsed: Long) {
        val v = SituationBuilder.turnIntentOf(stateName)
        if (v != raw) {
            raw = v
            sinceElapsed = nowElapsed
        }
    }

    /** 지금 깜빡이 방향 (켜진 시간과 상관없이) */
    val rawIntent: TurnIntent get() = raw

    fun intent(nowElapsed: Long): TurnIntent =
        if (raw != TurnIntent.NONE && nowElapsed - sinceElapsed >= CONFIRM_MS) raw else TurnIntent.NONE

    companion object {
        const val CONFIRM_MS = 3_000L
    }
}