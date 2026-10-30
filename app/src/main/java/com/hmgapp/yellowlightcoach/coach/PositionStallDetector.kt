package com.hmgapp.yellowlightcoach.coach

import com.hmgapp.yellowlightcoach.location.Geo

/**
 * 위치-속도 불일치 감지 (10/30)
 *
 * 속도는 빠른데 위치가 몇 초째 거의 움직이지 않으면, 둘 중 하나가 틀린 것이다.
 * (에뮬레이터: GPX가 끝났는데 속도만 남음 / 실차: 터널·GPS 끊김)
 * 이때 거리 계산이 틀려 엉뚱한 안내가 나갈 수 있으므로 → "모르면 조용히" (설계 원칙 ⑤)
 *
 *   속도 ≥ 20km/h 인데 STALL_MS(5초) 동안 MIN_MOVE_M(10m)도 못 움직였으면 = 멈춤(불일치)
 *   천천히 가거나 서 있는 동안엔 기준 시각을 계속 새로 잡는다 → 출발 직후엔 5초 여유
 *
 * 시간은 밖에서 넣어 준다 (단위 테스트용)
 */
class PositionStallDetector(
    private val minMoveM: Double = 10.0,
    private val stallMs: Long = 5_000,
    private val minSpeedMps: Double = 20.0 / 3.6
) {
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorAt = 0L

    fun onFix(lat: Double, lon: Double, nowMs: Long) {
        if (anchorLat.isNaN() || Geo.distanceM(anchorLat, anchorLon, lat, lon) >= minMoveM) {
            anchorLat = lat
            anchorLon = lon
            anchorAt = nowMs
        }
    }

    /** true = 속도와 위치가 맞지 않음 → 안내하지 않음 */
    fun stalled(speedMps: Double, nowMs: Long): Boolean {
        if (anchorLat.isNaN()) return false
        if (speedMps < minSpeedMps) {
            anchorAt = nowMs          // 느릴 땐 안 움직여도 정상 → 기준 시각 갱신
            return false
        }
        return nowMs - anchorAt > stallMs
    }

    fun reset() {
        anchorLat = Double.NaN
        anchorLon = Double.NaN
        anchorAt = 0L
    }
}