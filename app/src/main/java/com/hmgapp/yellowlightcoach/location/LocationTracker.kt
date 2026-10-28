package com.hmgapp.yellowlightcoach.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
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
    /** 진행 방향 (°) — 직전 위치와 비교해 계산한 값. 아직 모르면 null */
    val headingDeg: Double?,
    /** SDK가 준 진행 방향 (°) — FusedLocation일 때만. 비교·검증용 */
    val sdkHeadingDeg: Double?,
    /** SDK가 준 신뢰도 — FusedLocation일 때만 */
    val confidence: Float?,
    /** 위치 출처: "Pleos FusedLocation" 또는 "Android GPS" */
    val source: String,
    /** 이 위치를 받은 시각 (부팅 후 경과 ms) */
    val atElapsed: Long
)

/**
 * 위치 받기
 *
 * 1순위: Pleos FusedLocation SDK 1.1.3 (Pleos Connect의 위치 서비스)
 * 2순위: 안드로이드 기본 GPS — SDK를 지원하지 않거나 오류가 나면 자동 전환
 *
 * 진행 방향은 두 출처 모두 "직전 위치 → 지금 위치"로 직접 계산한다 (5m 이상 움직였을 때).
 * FusedLocation의 heading은 따로 담아서 화면에서 비교한다.
 * (10/28: 에뮬레이터 GPX 주입은 방향 없이 위치만 주므로, SDK heading이 맞는지 먼저 검증)
 */
class LocationTracker(
    context: Context,
    private val log: (String) -> Unit,
    private val onFix: (Fix) -> Unit
) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var fused: FusedLocation? = null

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var heading: Double? = null

    var source: String = "꺼짐"
        private set
    val running: Boolean get() = source != "꺼짐"

    // ---------- Pleos FusedLocation ----------
    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return   // null은 유효한 위치가 아님 (문서)
            handle(
                location.latitude, location.longitude,
                sdkHeading = location.heading.toDouble(),
                confidence = location.confidence
            )
        }

        override fun onError(exception: Exception) {
            log("❌ FusedLocation 오류: ${exception.message} → 기본 GPS로 전환")
            stopFused()
            startAndroidGps()
        }
    }

    // ---------- 안드로이드 기본 GPS ----------
    private val gpsListener = LocationListener { loc: Location ->
        handle(loc.latitude, loc.longitude, sdkHeading = null, confidence = null)
    }

    /** 위치 권한(FUSED_LOCATION, ACCESS_FINE_LOCATION)이 있는 상태에서 부를 것 */
    fun start(useFused: Boolean): Boolean {
        if (running) return true
        if (useFused && startFused()) return true
        return startAndroidGps()
    }

    @SuppressLint("MissingPermission")
    private fun startFused(): Boolean {
        return try {
            val f = FusedLocation(appContext)
            if (!f.isSupported()) {
                log("FusedLocation 미지원 환경 → 기본 GPS 사용")
                return false
            }
            f.initialize()
            f.registerFusedLocationCallback(fusedListener)
            fused = f
            source = "Pleos FusedLocation"
            log("위치 출처: Pleos FusedLocation")
            true
        } catch (e: Exception) {
            log("❌ FusedLocation 시작 실패: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAndroidGps(): Boolean {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            source = "꺼짐"
            log("❌ GPS가 꺼져 있어요")
            return false
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, Looper.getMainLooper())
        source = "Android GPS"
        log("위치 출처: Android GPS")
        return true
    }

    private fun stopFused() {
        fused?.let {
            try {
                it.unregisterFusedLocationCallback(fusedListener)
                it.release()
            } catch (_: Exception) {
            }
        }
        fused = null
    }

    fun stop() {
        stopFused()
        lm.removeUpdates(gpsListener)
        source = "꺼짐"
    }

    private fun handle(lat: Double, lon: Double, sdkHeading: Double?, confidence: Float?) {
        if (anchorLat.isNaN()) {
            anchorLat = lat; anchorLon = lon
        } else {
            val moved = Geo.distanceM(anchorLat, anchorLon, lat, lon)
            if (moved >= MIN_MOVE_M) {
                heading = Geo.bearingDeg(anchorLat, anchorLon, lat, lon)
                anchorLat = lat; anchorLon = lon
            }
        }
        onFix(Fix(lat, lon, heading, sdkHeading, confidence, source, SystemClock.elapsedRealtime()))
    }

    companion object {
        private const val MIN_MOVE_M = 5.0
    }
}