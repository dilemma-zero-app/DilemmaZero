package com.hmgapp.yellowlightcoach.location

import com.hmgapp.yellowlightcoach.signal.Approach
import com.hmgapp.yellowlightcoach.signal.Intersection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 앞에 있는 교차로 찾기 결과 */
data class Ahead(
    val intersection: Intersection,
    /** 교차로 중심까지 거리 (m). 정지선 보정은 판단 단계에서 */
    val distanceM: Double,
    /** 내가 들어가는 쪽 */
    val approach: Approach,
    /** 내 진행선에서 교차로 중심이 옆으로 떨어진 거리 (m) — 디버그용 */
    val lateralM: Double
)

/**
 * 진행 방향 앞쪽에서 가장 가까운 "내 길 위의" 교차로를 고르고,
 * 그 교차로에 어느 쪽에서 들어가는지(진입 방향)를 정한다.
 *
 * [내 길 위인지 판단] — v1의 "±35° 부채꼴"은 옆길 교차로도 잡아서 바꿈
 *   진행 방향으로 선을 그었을 때
 *    - 앞쪽 거리(along)  > 0          : 앞에 있음
 *    - 옆으로 떨어진 거리(lateral) ≤ 35m : 내 도로 위 (도로 폭 절반 + GPS 오차)
 *
 * [진입 방향] 교차로에서 나를 바라본 방향 (= 진행 방향 + 180°)
 *    예) 동쪽(90°)으로 달리는 중 → 서쪽(270°)에서 진입 → W(wt) 신호
 *    그 교차로에 실제로 있는 신호 방향 중 가장 가까운 것 (50° 이내).
 *    맞는 방향의 신호가 없는 교차로는 나에게 상관없으므로 건너뛴다.
 *
 * [깜빡임 방지] 조건 경계에 걸리면 1초마다 "있음/없음"이 바뀌므로
 *    이미 잡고 있는 교차로는 조금 느슨한 기준(옆 45m, 550m)으로 유지한다.
 *    그 교차로를 지나치면(앞쪽 거리 ≤ 0) 바로 놓는다.
 */
object AheadFinder {
    private const val MAX_RANGE_M = 500.0
    private const val MAX_LATERAL_M = 35.0
    private const val APPROACH_TOLERANCE_DEG = 50.0
    private const val KEEP_RANGE_M = 550.0
    private const val KEEP_LATERAL_M = 45.0

    /** 신호 방향별 각도 (교차로 중심에서 본 방향) */
    private val approachDeg = mapOf(
        Approach.N to 0.0, Approach.NE to 45.0, Approach.E to 90.0, Approach.SE to 135.0,
        Approach.S to 180.0, Approach.SW to 225.0, Approach.W to 270.0, Approach.NW to 315.0
    )

    fun find(
        fix: Fix,
        intersections: Collection<Intersection>,
        approachesWithSignal: (id: String) -> Set<Approach>,
        previous: Ahead? = null
    ): Ahead? {
        val heading = fix.headingDeg ?: return null
        val fromDeg = (heading + 180.0) % 360.0
        var best: Ahead? = null

        for (x in intersections) {
            val keep = x.id == previous?.intersection?.id
            val range = if (keep) KEEP_RANGE_M else MAX_RANGE_M
            val maxLateral = if (keep) KEEP_LATERAL_M else MAX_LATERAL_M

            val d = Geo.distanceM(fix.lat, fix.lon, x.lat, x.lon)
            if (d > range) continue
            if (best != null && d >= best.distanceM) continue

            // 진행선 기준 앞쪽 거리 / 옆 거리
            val rel = Math.toRadians(Geo.bearingDeg(fix.lat, fix.lon, x.lat, x.lon) - heading)
            val along = d * cos(rel)
            val lateral = abs(d * sin(rel))
            if (along <= 0.0 || lateral > maxLateral) continue

            val approach = approachesWithSignal(x.id)
                .minByOrNull { Geo.angleDiff(approachDeg.getValue(it), fromDeg) }
                ?.takeIf { Geo.angleDiff(approachDeg.getValue(it), fromDeg) <= APPROACH_TOLERANCE_DEG }
                ?: continue

            best = Ahead(x, d, approach, lateral)
        }
        return best
    }
}