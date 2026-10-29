package com.hmgapp.yellowlightcoach.demo

import android.content.Context
import android.os.SystemClock
import com.hmgapp.yellowlightcoach.location.Fix
import com.hmgapp.yellowlightcoach.location.Geo
import com.hmgapp.yellowlightcoach.signal.SignalRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 4단계 ① 주행 기록 (10/29)
 *
 * 주행 중 "판정에 들어가는 모든 입력"을 시간 순서대로 파일에 남긴다.
 * 나중에 재생 모드가 이 파일을 그대로 다시 넣으면, 몇 번을 틀어도 같은 장면이 나온다.
 *
 * 파일: 앱 전용 폴더 /recordings/rec_날짜_시각.jsonl (한 줄 = 사건 하나, JSON)
 *   t    = 기록 시작부터 지난 시간(ms)
 *   type = start · ix(교차로 위치) · sig(신호) · fix(위치) · spd(속도) · gear · turn(깜빡이) · steer(조향) · end
 *
 * 신호는 용량을 줄이려고 "지금 위치에서 1.5km 안" 교차로만 남긴다.
 * 카운트다운 기준 시각(base)도 함께 남겨서, 재생 때 남은 시간이 실제와 똑같이 줄어들게 한다.
 */
class DemoRecorder(context: Context, private val log: (String) -> Unit) {

    private val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()   // 파일 쓰기는 화면 스레드 밖에서

    @Volatile var recording = false
        private set
    @Volatile var file: File? = null
        private set
    private var out: BufferedWriter? = null
    private var startElapsed = 0L
    private var lines = 0
    private val sentIx = mutableSetOf<String>()      // 교차로 위치는 한 번만
    private var lastSpeed = Double.NaN
    private var lastSteerBucket = Int.MIN_VALUE

    val lineCount: Int get() = lines
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) = if (recording) now - startElapsed else 0L

    fun start() {
        if (recording) return
        val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + ".jsonl"
        val f = File(dir, name)
        file = f
        startElapsed = SystemClock.elapsedRealtime()
        lines = 0
        sentIx.clear()
        lastSpeed = Double.NaN
        lastSteerBucket = Int.MIN_VALUE
        io.execute { out = f.bufferedWriter() }
        recording = true
        write(JSONObject().put("type", "start").put("wall", System.currentTimeMillis()).put("ver", 1))
        log("⏺ 기록 시작: ${f.name}")
    }

    fun stop() {
        if (!recording) return
        write(JSONObject().put("type", "end"))
        recording = false
        val f = file
        io.execute {
            out?.flush(); out?.close(); out = null
        }
        log("⏹ 기록 끝: ${f?.name} (${lines}줄)")
    }

    // ---------- 입력 사건 ----------

    /** 신호를 새로 받을 때마다: 내 위치 1.5km 안 교차로의 신호 + 카운트다운 기준 */
    fun signals(repo: SignalRepository, nearLat: Double?, nearLon: Double?) {
        if (!recording || nearLat == null || nearLon == null) return
        val near = repo.intersections.values.filter {
            Geo.distanceM(nearLat, nearLon, it.lat, it.lon) <= NEAR_M
        }
        // 처음 보는 교차로는 위치부터 (재생 때 인터넷 없이 교차로 찾기)
        for (ix in near) if (sentIx.add(ix.id)) {
            write(JSONObject().put("type", "ix").put("id", ix.id).put("name", ix.name)
                .put("lat", ix.lat).put("lon", ix.lon))
        }
        val arr = JSONArray()
        for (ix in near) {
            val s = repo.snapshot[ix.id] ?: continue
            val sigs = JSONArray()
            for ((key, st) in s.signals) {
                sigs.put(JSONObject().put("a", key.first.name).put("m", key.second.name)
                    .put("p", st.phase.name).put("r", st.remainingMs ?: -1))
            }
            arr.put(JSONObject().put("id", ix.id).put("reg", s.regDtEpochMs)
                .put("base", repo.baseElapsed(ix.id) - startElapsed)   // 기록 시작 기준으로
                .put("s", sigs))
        }
        write(JSONObject().put("type", "sig").put("list", arr))
    }

    fun fix(f: Fix) {
        if (!recording) return
        write(JSONObject().put("type", "fix").put("lat", f.lat).put("lon", f.lon)
            .put("hd", f.headingDeg ?: JSONObject.NULL).put("shd", f.sdkHeadingDeg ?: JSONObject.NULL)
            .put("cf", f.confidence?.toDouble() ?: JSONObject.NULL).put("src", f.source))
    }

    /** 속도는 0.1km/h 넘게 바뀔 때만 */
    fun speed(mps: Double) {
        if (!recording) return
        if (!lastSpeed.isNaN() && kotlin.math.abs(mps - lastSpeed) < 0.03) return
        lastSpeed = mps
        write(JSONObject().put("type", "spd").put("v", mps))
    }

    fun gear(isDrive: Boolean) {
        if (!recording) return
        write(JSONObject().put("type", "gear").put("d", isDrive))
    }

    fun turnSignal(state: String?) {
        if (!recording) return
        write(JSONObject().put("type", "turn").put("s", state ?: JSONObject.NULL))
    }

    /** 조향각은 5° 단위로 바뀔 때만 */
    fun steering(angle: Float) {
        if (!recording) return
        val bucket = (angle / 5f).toInt()
        if (bucket == lastSteerBucket) return
        lastSteerBucket = bucket
        write(JSONObject().put("type", "steer").put("deg", angle.toDouble()))
    }

    fun release() {
        stop()
        io.shutdown()
    }

    private fun write(o: JSONObject) {
        o.put("t", SystemClock.elapsedRealtime() - startElapsed)
        val line = o.toString()
        lines++
        io.execute {
            out?.apply { write(line); newLine() }
            if (lines % 20 == 0) out?.flush()   // 앱이 갑자기 꺼져도 대부분 남게
        }
    }

    companion object {
        const val NEAR_M = 1_500.0
    }
}