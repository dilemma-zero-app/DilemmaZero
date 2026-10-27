package com.hmgapp.yellowlightcoach.signal

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 신호 데이터 보관 + 로컬 카운트다운
 *
 * API는 가끔(10초마다, 신호가 바뀔 때) 부르고,
 * 그 사이 남은 시간은 "받은 시각부터 흐른 시간"을 빼서 앱이 직접 계산한다.
 * (10/27 측정: 잔여시간이 실제 시간과 1초에 1초씩 정확히 줄어듦)
 */
class SignalRepository(
    private val api: SignalApi,
    private val stdgCd: String = ULSAN
) {
    @Volatile var intersections: Map<String, Intersection> = emptyMap()
        private set
    @Volatile var snapshot: Map<String, IntersectionSignals> = emptyMap()
        private set

    /** 마지막으로 신호를 받은 시각 (기기 부팅 후 경과 ms, 카운트다운 기준) */
    @Volatile var receivedAtElapsed = 0L
        private set
    /** 마지막으로 신호를 받은 실제 시각 (지연 계산용) */
    @Volatile var receivedAtWall = 0L
        private set

    val callCount: Int get() = api.callCount

    suspend fun loadIntersections() = withContext(Dispatchers.IO) {
        intersections = api.fetchIntersections(stdgCd).associateBy { it.id }
    }

    suspend fun refreshSignals() = withContext(Dispatchers.IO) {
        val list = api.fetchSignals(stdgCd)
        snapshot = list.associateBy { it.id }
        receivedAtElapsed = SystemClock.elapsedRealtime()
        receivedAtWall = System.currentTimeMillis()
    }

    /** 데이터 지연(초) = 받은 시각 − regDt. regDt는 초 단위라 ±1초 오차 */
    fun dataLagSec(): Double? {
        val reg = snapshot.values.firstOrNull()?.regDtEpochMs ?: return null
        if (reg == 0L) return null
        return (receivedAtWall - reg) / 1000.0
    }

    /** 지금 이 순간의 신호 색과 남은 시간(ms). 남은 시간 null = 모름 */
    fun current(
        id: String,
        approach: Approach,
        movement: Movement,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): SignalState? {
        val s = snapshot[id]?.signals?.get(approach to movement) ?: return null
        val remaining = s.remainingMs?.let { it - (nowElapsed - receivedAtElapsed) }
        return SignalState(s.phase, remaining)
    }

    companion object {
        const val ULSAN = "3100000000"
    }
}