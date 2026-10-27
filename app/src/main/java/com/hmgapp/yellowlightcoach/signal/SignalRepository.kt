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

    /**
     * 교차로별 카운트다운 기준 시각 (부팅 후 경과 ms)
     *
     * API의 남은 시간은 "받은 순간"이 아니라 "regDt(신호 기록 시각)" 기준 값이다.
     * 지연이 0~3초로 들쭉날쭉하므로, 받은 시각이 아니라 regDt부터 빼야 정확하다.
     *   기준 = 받은 시각 − 지연(받은 실제 시각 − regDt)
     * regDt는 초 단위로 잘려 있어 최대 1초 더 빼게 되는데,
     * 그러면 녹색이 실제보다 짧게 보여 "더 일찍 조심"하는 쪽이라 안전하다.
     * 기기 시계가 이상해서 지연이 말이 안 되는 값이면(−1초 미만, 10초 초과) 받은 시각을 쓴다.
     */
    @Volatile private var baseElapsedById: Map<String, Long> = emptyMap()

    suspend fun refreshSignals() = withContext(Dispatchers.IO) {
        val list = api.fetchSignals(stdgCd)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        baseElapsedById = list.associate { s ->
            val lag = nowWall - s.regDtEpochMs
            val base = if (s.regDtEpochMs > 0 && lag in -1_000L..10_000L) {
                nowElapsed - lag.coerceAtLeast(0L)
            } else {
                nowElapsed
            }
            s.id to base
        }
        snapshot = list.associateBy { it.id }
        receivedAtElapsed = nowElapsed
        receivedAtWall = nowWall
    }

    /** 이 교차로·진입 방향에 이 종류(직진/좌회전) 신호가 있는지 */
    fun hasSignal(id: String, approach: Approach, movement: Movement): Boolean =
        snapshot[id]?.signals?.containsKey(approach to movement) == true

    /** 마지막으로 신호를 받은 뒤 지난 시간(ms). 한 번도 못 받았으면 Long.MAX_VALUE */
    fun dataAgeMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        if (receivedAtElapsed == 0L) Long.MAX_VALUE else nowElapsed - receivedAtElapsed

    /** 이 교차로에서 신호 정보가 있는 진입 방향들 */
    fun approachesWithSignal(id: String): Set<Approach> =
        snapshot[id]?.signals?.keys?.map { it.first }?.toSet() ?: emptySet()

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
        val base = baseElapsedById[id] ?: receivedAtElapsed
        val remaining = s.remainingMs?.let { it - (nowElapsed - base) }
        return SignalState(s.phase, remaining)
    }

    companion object {
        const val ULSAN = "3100000000"
    }
}