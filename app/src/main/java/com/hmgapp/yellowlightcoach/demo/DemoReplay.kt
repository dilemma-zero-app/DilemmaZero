package com.hmgapp.yellowlightcoach.demo

import android.content.Context
import android.os.SystemClock
import com.hmgapp.yellowlightcoach.location.Fix
import com.hmgapp.yellowlightcoach.signal.Approach
import com.hmgapp.yellowlightcoach.signal.Intersection
import com.hmgapp.yellowlightcoach.signal.IntersectionSignals
import com.hmgapp.yellowlightcoach.signal.Movement
import com.hmgapp.yellowlightcoach.signal.Phase
import com.hmgapp.yellowlightcoach.signal.SignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 4단계 ② 재생 모드 (10/29)
 *
 * DemoRecorder가 남긴 기록(jsonl)을 기록된 시간 간격 그대로 다시 넣는다.
 * 신호 API·GPS·차량 신호 대신 파일의 값을 쓰므로, 몇 번을 틀어도 같은 장면이 나온다.
 * (심사 당일 신호·네트워크·에뮬레이터 상태에 영향받지 않음)
 *
 * 무엇을 틀까: [▶ 기록 재생] 앱 안의 마지막 기록(없으면 시연 파일) / [▶ 시연 파일 재생] assets/demo.jsonl
 */
class DemoReplay(private val context: Context, private val log: (String) -> Unit) {

    /** 재생 값을 받아 앱에 넣는 쪽 (MainActivity) */
    interface Sink {
        fun onIntersection(ix: Intersection)
        /** baseOffsetMs = 기록 시작 기준 카운트다운 기준 시각 */
        fun onSignals(list: List<IntersectionSignals>, baseOffsetMs: Map<String, Long>, recordedWall: Long)
        fun onFix(f: Fix)
        fun onSpeed(mps: Double)
        fun onGear(isDrive: Boolean)
        fun onTurn(state: String?)
        fun onSteer(deg: Float)
        fun onEnd()
    }

    @Volatile var active = false
        private set
    var sourceName = ""
        private set
    var durationMs = 0L
        private set
    /** 재생을 시작한 시각 (부팅 후 경과 ms) — 기록의 t=0이 이 시각에 해당 */
    var startElapsed = 0L
        private set
    private var job: Job? = null

    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()) = if (active) now - startElapsed else 0L

    fun latestRecording(): File? =
        File(context.filesDir, "recordings").listFiles { f -> f.name.endsWith(".jsonl") }
            ?.maxByOrNull { it.name }

    /** 재생 시작. 틀 파일이 없으면 false */
    fun start(scope: CoroutineScope, sink: Sink, useAsset: Boolean = false): Boolean {
        if (active) return true
        val (name, lines) = load(useAsset) ?: run { log("❌ 재생할 기록이 없어요"); return false }
        val events = lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        if (events.isEmpty()) { log("❌ 기록이 비어 있어요: $name"); return false }
        sourceName = name
        durationMs = events.last().optLong("t")
        val recWall = events.firstOrNull { it.optString("type") == "start" }?.optLong("wall") ?: 0L

        active = true
        startElapsed = SystemClock.elapsedRealtime()
        log("▶ 재생 시작: $name (%d분 %02d초)".format(durationMs / 60_000, durationMs / 1000 % 60))

        job = scope.launch {
            for (o in events) {
                val wait = o.optLong("t") - (SystemClock.elapsedRealtime() - startElapsed)
                if (wait > 0) delay(wait)
                dispatch(o, sink, recWall)
            }
            finish(sink, "끝까지 재생")
        }
        return true
    }

    fun stop(sink: Sink) {
        if (!active) return
        job?.cancel()
        finish(sink, "멈춤")
    }

    private fun finish(sink: Sink, why: String) {
        if (!active) return
        active = false
        job = null
        log("⏹ 재생 $why: $sourceName")
        sink.onEnd()
    }

    private fun dispatch(o: JSONObject, sink: Sink, recWall: Long) {
        val t = o.optLong("t")
        when (o.optString("type")) {
            "ix" -> sink.onIntersection(
                Intersection(o.getString("id"), o.optString("name"), o.getDouble("lat"), o.getDouble("lon"))
            )
            "sig" -> {
                val arr = o.getJSONArray("list")
                val list = ArrayList<IntersectionSignals>(arr.length())
                val bases = HashMap<String, Long>()
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    val sigs = x.getJSONArray("s")
                    val map = HashMap<Pair<Approach, Movement>, SignalState>()
                    for (j in 0 until sigs.length()) {
                        val s = sigs.getJSONObject(j)
                        val r = s.optLong("r", -1)
                        map[Approach.valueOf(s.getString("a")) to Movement.valueOf(s.getString("m"))] =
                            SignalState(Phase.valueOf(s.getString("p")), if (r >= 0) r else null)
                    }
                    val id = x.getString("id")
                    list += IntersectionSignals(id, x.optLong("reg"), map)
                    bases[id] = x.optLong("base")
                }
                sink.onSignals(list, bases, recWall + t)
            }
            "fix" -> sink.onFix(
                Fix(
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    headingDeg = o.optNullableDouble("hd"),
                    sdkHeadingDeg = o.optNullableDouble("shd"),
                    confidence = o.optNullableDouble("cf")?.toFloat(),
                    source = "재생",
                    atElapsed = SystemClock.elapsedRealtime()
                )
            )
            "spd" -> sink.onSpeed(o.getDouble("v"))
            "gear" -> sink.onGear(o.getBoolean("d"))
            "turn" -> sink.onTurn(if (o.isNull("s")) null else o.getString("s"))
            "steer" -> sink.onSteer(o.getDouble("deg").toFloat())
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    /** useAsset = true면 시연 파일(assets/demo.jsonl)만, 아니면 앱 안의 마지막 기록 → 없으면 시연 파일 */
    private fun load(useAsset: Boolean): Pair<String, List<String>>? {
        if (!useAsset) latestRecording()?.let { f -> return f.name to f.readLines() }
        return try {
            context.assets.open(ASSET_NAME).bufferedReader().use { ASSET_NAME to it.readLines() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val ASSET_NAME = "demo.jsonl"
    }
}