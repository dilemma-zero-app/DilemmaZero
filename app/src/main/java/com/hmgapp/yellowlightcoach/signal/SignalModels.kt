package com.hmgapp.yellowlightcoach.signal

/**
 * 공공 신호 API(행정안전부 교통안전 신호등 실시간 정보) 데이터 모델
 *
 * 방향은 "차가 교차로로 들어오는 쪽(진입 방향)" 기준이다.
 * 예) 서쪽에서 들어와 동쪽으로 가는 차는 W(wt) 신호를 본다.
 */
enum class Approach(val prefix: String, val label: String) {
    N("nt", "북"), E("et", "동"), S("st", "남"), W("wt", "서"),
    NE("ne", "북동"), SE("se", "남동"), SW("sw", "남서"), NW("nw", "북서")
}

/** 신호 종류 (울산은 직진·좌회전만 제공) */
enum class Movement(val code: String, val label: String) {
    STRAIGHT("Stsg", "직진"),
    LEFT("Ltsg", "좌회전")
}

/** 신호 색 (V2X 표준 상태 이름 → 색) */
enum class Phase(val label: String) {
    GREEN("녹색"), YELLOW("황색"), RED("적색"), UNKNOWN("?");

    companion object {
        fun from(name: String?): Phase = when (name) {
            "protected-Movement-Allowed", "permissive-Movement-Allowed" -> GREEN
            "protected-clearance", "permissive-clearance" -> YELLOW
            "stop-And-Remain", "stop-Then-Proceed" -> RED
            else -> UNKNOWN
        }
    }
}

/** 교차로 위치 (좌표는 교차로 중심) */
data class Intersection(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double
)

/** 신호 하나의 상태. remainingMs == null 이면 잔여시간 모름(API 값 255초) */
data class SignalState(
    val phase: Phase,
    val remainingMs: Long?
)

/** 교차로 하나의 전체 신호 (방향 × 신호 종류) */
data class IntersectionSignals(
    val id: String,
    val regDtEpochMs: Long,
    val signals: Map<Pair<Approach, Movement>, SignalState>
)