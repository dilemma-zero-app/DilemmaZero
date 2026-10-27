package com.hmgapp.yellowlightcoach.signal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 공공데이터포털 신호 API 호출
 *  - 교차로 맵 정보:        /crsrd_map_info
 *  - 신호제어기 잔여시간:   /tl_drct_info
 *
 * serviceKey 는 Decoding 키를 받아서 여기서 URL 인코딩한다.
 * (네트워크 호출이므로 반드시 백그라운드 스레드에서 부를 것)
 */
class SignalApi(private val serviceKey: String) {

    private val baseUrl = "https://apis.data.go.kr/B551982/rti"

    /** 이번 실행에서 API를 부른 횟수 (개발계정 하루 5,000건 한도 관리용) */
    @Volatile
    var callCount = 0
        private set

    private val regDtFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    /** 교차로 목록 (지자체 코드로 거름. 울산 = 3100000000) */
    fun fetchIntersections(stdgCd: String): List<Intersection> =
        fetchAllPages("crsrd_map_info", stdgCd).mapNotNull { o ->
            val lat = o.optString("mapCtptIntLat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("mapCtptIntLot").toDoubleOrNull() ?: return@mapNotNull null
            Intersection(
                id = o.optString("crsrdId"),
                name = o.optString("crsrdNm"),
                lat = lat,
                lon = lon
            )
        }

    /** 모든 교차로의 현재 신호 */
    fun fetchSignals(stdgCd: String): List<IntersectionSignals> =
        fetchAllPages("tl_drct_info", stdgCd).map { o ->
            val signals = mutableMapOf<Pair<Approach, Movement>, SignalState>()
            for (a in Approach.values()) {
                for (m in Movement.values()) {
                    val status = o.optString(a.prefix + m.code + "SttsNm")
                    if (status.isEmpty()) continue
                    val raw = o.optString(a.prefix + m.code + "RmndCs").toLongOrNull()
                    // 255초(255000ms)는 "모름"을 뜻하는 값 → null 로 처리
                    val remaining = if (raw == null || raw >= 255_000L) null else raw
                    signals[a to m] = SignalState(Phase.from(status), remaining)
                }
            }
            IntersectionSignals(
                id = o.optString("crsrdId"),
                regDtEpochMs = parseRegDt(o.optString("regDt")),
                signals = signals
            )
        }

    private fun parseRegDt(s: String): Long =
        try { regDtFormat.parse(s)?.time ?: 0L } catch (e: Exception) { 0L }

    /** totalCount 만큼 페이지를 넘기며 전부 받기 (한 페이지 1,000개) */
    private fun fetchAllPages(path: String, stdgCd: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val body = request(
                path,
                mapOf(
                    "pageNo" to page.toString(),
                    "numOfRows" to "1000",
                    "type" to "JSON",
                    "stdgCd" to stdgCd
                )
            )
            val total = body.optInt("totalCount", 0)
            val items = body.optJSONObject("items")
            val arr = items?.optJSONArray("item")
                ?: items?.optJSONObject("item")?.let { JSONArray().put(it) }
                ?: JSONArray()
            for (i in 0 until arr.length()) result.add(arr.getJSONObject(i))
            if (arr.length() == 0 || result.size >= total) break
            page++
        }
        return result
    }

    /** GET 요청 → 응답의 body 부분 반환 */
    private fun request(path: String, params: Map<String, String>): JSONObject {
        val query = buildString {
            append("serviceKey=").append(URLEncoder.encode(serviceKey, "UTF-8"))
            params.forEach { (k, v) -> append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8")) }
        }
        val conn = URL("$baseUrl/$path?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        callCount++
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(150)}")

            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                // 인증키 오류 등은 XML로 올 때가 있음
                throw IOException("응답 형식 오류: ${text.take(150)}")
            }
            val header = json.optJSONObject("header")
            val resultCode = header?.optString("resultCode") ?: ""
            if (resultCode.isNotEmpty() && resultCode != "K0" && resultCode != "00") {
                throw IOException("API 오류 $resultCode ${header?.optString("resultMsg")}")
            }
            return json.optJSONObject("body") ?: JSONObject()
        } finally {
            conn.disconnect()
        }
    }
}