package com.hmgapp.yellowlightcoach.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/** 위치 한 번의 결과 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** 진행 방향 (°). 아직 모르면 null (출발 직후, 정지 중) */
    val headingDeg: Double?,
    /** 이 위치를 받은 시각 (부팅 후 경과 ms) */
    val atElapsed: Long
)

/**
 * 안드로이드 기본 위치(GPS) 받기
 *
 * 진행 방향은
 *  1) GPS가 방향(bearing)을 주면 그 값을 쓰고
 *  2) 안 주면(에뮬레이터 위치 주입 등) 직전 위치 → 지금 위치 방향을 직접 계산한다.
 *     너무 가까운 두 점은 방향이 흔들리므로 5m 이상 움직였을 때만 새로 계산.
 */
class LocationTracker(
    context: Context,
    private val onFix: (Fix) -> Unit
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var anchor: Location? = null     // 방향 계산 기준점
    private var heading: Double? = null
    var running = false
        private set

    private val listener = LocationListener { loc -> handle(loc) }

    /** 위치 권한이 있는 상태에서만 부를 것 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()
        )
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { handle(it) }
        running = true
        return true
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        running = false
    }

    private fun handle(loc: Location) {
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) {
            heading = loc.bearing.toDouble()
            anchor = loc
        } else {
            val a = anchor
            if (a == null) {
                anchor = loc
            } else {
                val moved = Geo.distanceM(a.latitude, a.longitude, loc.latitude, loc.longitude)
                if (moved >= MIN_MOVE_M) {
                    heading = Geo.bearingDeg(a.latitude, a.longitude, loc.latitude, loc.longitude)
                    anchor = loc
                }
            }
        }
        onFix(Fix(loc.latitude, loc.longitude, heading, SystemClock.elapsedRealtime()))
    }

    companion object {
        private const val MIN_MOVE_M = 5.0
    }
}