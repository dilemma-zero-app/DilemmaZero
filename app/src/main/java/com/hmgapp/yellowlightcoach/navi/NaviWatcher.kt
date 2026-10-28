package com.hmgapp.yellowlightcoach.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.SystemClock
import com.hmgapp.yellowlightcoach.coach.TurnIntent
import com.hmgapp.yellowlightcoach.location.Geo
import kotlin.math.abs

/**
 * 순정 내비 정보 받기 (Pleos NaviHelper SDK 3.0.5)
 *
 * 쓰는 것
 *  - onTBTInfo        : 다음 회전 종류·남은 거리 → 앞 교차로에서 좌회전하는지 미리 앎
 *  - onCurrentRoadSpeedLimit : 지금 도로 제한속도 (1초마다)
 *  - onNavigationStatus / onRouteStateInfo : 경로 안내 중인지, 모의 주행인지
 *  - requestRoute     : 테스트용 목적지 보내기 (에뮬레이터에서 경로를 만드는 방법)
 */
class NaviWatcher(
    context: Context,
    private val log: (String) -> Unit
) {
    private val navi = NaviHelper(context.applicationContext)

    @Volatile var connected = false
        private set
    @Volatile var tbtList: List<TBTInfo> = emptyList()
        private set
    @Volatile var tbtAtElapsed = 0L
        private set
    @Volatile var speedLimit: Int? = null
        private set
    @Volatile var status: NavigationStatus? = null
        private set
    @Volatile var naviMode: NaviMode? = null
        private set
    /** 내비가 알고 있는 현재 위치 (우리 위치와 같은지 확인용) */
    @Volatile var naviLocation: CurrentLocationInfo? = null
        private set
    private var lastLocLog = 0L

    private var lastLoggedTbt = ""

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            tbtList = tbtInfoList
            tbtAtElapsed = SystemClock.elapsedRealtime()
            val first = tbtInfoList.firstOrNull()
            val key = first?.let { "${it.type}" } ?: "없음"
            if (key != lastLoggedTbt) {   // 회전 종류가 바뀔 때만 기록
                lastLoggedTbt = key
                log("🧭 TBT: " + (first?.let { "${it.type} %.0fm '${it.description}'".format(it.distance) } ?: "없음"))
            }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            naviLocation = currentLocationInfo
            val now = SystemClock.elapsedRealtime()
            if (now - lastLocLog > 3_000L) {   // 3초에 한 번만 기록
                lastLocLog = now
                log("🧭 내비 위치: %.5f, %.5f '${currentLocationInfo.address}'".format(
                    currentLocationInfo.latitude, currentLocationInfo.longitude))
            }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            this@NaviWatcher.speedLimit = speedLimit
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            this@NaviWatcher.status = status
            log("🧭 내비 상태: $status")
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            naviMode = routeStateInfo.naviMode
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            log("🧭 경로 탐색 시작: ${routeStartInfo.name} (${routeStartInfo.distance}m)")

        override fun onRoutingCompleted() = log("🧭 경로 탐색 완료")
        override fun onRouteCancelled() = log("🧭 경로 취소됨")
        override fun onError(errorCode: NaviErrorCode) = log("❌ 내비 오류: $errorCode")
    }

    fun init() {
        navi.initialize(
            onConnected = {
                connected = true
                navi.addListener(listener)
                log("🧭 NaviHelper 연결 성공")
            },
            onFailed = { t -> log("❌ NaviHelper 연결 실패: ${t.message}") }
        )
    }

    /** 테스트용: 순정 내비에 목적지 보내기 (poiId 없이 좌표만) */
    fun requestRoute(lat: Double, lon: Double, name: String) {
        if (!connected) { log("NaviHelper 아직 연결 안 됨"); return }
        navi.requestRoute(
            RouteInfo(
                latitude = lat, longitude = lon,
                poiName = name, poiId = "", address = name, poiSubId = "0"
            )
        )
        log("🧭 목적지 요청: $name")
    }

    /**
     * 내비에 지금 정보 물어보기 — 값은 바로 오지 않고 각 콜백(onCurrentLocationInfo 등)으로 온다 (문서)
     */
    fun query() {
        if (!connected) { log("NaviHelper 아직 연결 안 됨"); return }
        lastLocLog = 0L
        navi.getCurrentLocationInfo()
        navi.getRouteStateInfo()
        navi.getTBTInfo()
        log("🧭 내비 정보 요청 (위치·경로 상태·TBT)")
    }

    fun cancelRoute() {
        if (connected) navi.cancelRoute()
    }

    /**
     * 앞 교차로(중심까지 distanceM 앞)에서 내비가 안내하는 회전
     *  - TBT 거리와 교차로 거리 차이가 TOLERANCE_M 안이면 그 교차로의 안내로 본다
     *  - 오래된 TBT(STALE_MS 초과)는 쓰지 않는다
     *  - 내비가 아는 위치가 우리 위치와 MAX_POS_GAP_M 넘게 다르면 쓰지 않는다
     *    (10/28: 에뮬레이터 내비 위치는 판교(37.41272, 127.09468)에 고정, GPX를 따라오지 않음)
     *  - null = 내비 정보로는 모름 (경로 없음, 다른 교차로 안내, 위치 불일치 등)
     */
    fun turnAt(
        distanceM: Double, myLat: Double, myLon: Double,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): TurnIntent? {
        if (nowElapsed - tbtAtElapsed > STALE_MS) return null
        val loc = naviLocation ?: return null
        if (Geo.distanceM(loc.latitude, loc.longitude, myLat, myLon) > MAX_POS_GAP_M) return null
        val match = tbtList.firstOrNull { abs(it.distance - distanceM) <= TOLERANCE_M } ?: return null
        return intentOf(match.type)
    }

    fun release() {
        if (connected) navi.removeListener(listener)
        navi.release()
        connected = false
    }

    companion object {
        const val TOLERANCE_M = 50.0
        const val STALE_MS = 5_000L
        const val MAX_POS_GAP_M = 200.0

        /** 내비 회전 종류 → 신호 판단용 의도. 직진 계열은 NONE, 상관없는 안내는 null */
        fun intentOf(type: TurnType): TurnIntent? = when (type) {
            TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN -> TurnIntent.LEFT
            TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> TurnIntent.RIGHT
            TurnType.TURN_STRAIGHT, TurnType.TURN_KEEP_L, TurnType.TURN_KEEP_R -> TurnIntent.NONE
            else -> null
        }
    }
}