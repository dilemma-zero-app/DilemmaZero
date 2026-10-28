package com.hmgapp.yellowlightcoach

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.Manifest
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hmgapp.yellowlightcoach.coach.SituationBuilder
import com.hmgapp.yellowlightcoach.coach.TurnIntentTracker
import com.hmgapp.yellowlightcoach.coach.TurningDetector
import com.hmgapp.yellowlightcoach.location.Ahead
import com.hmgapp.yellowlightcoach.location.AheadFinder
import com.hmgapp.yellowlightcoach.location.Fix
import com.hmgapp.yellowlightcoach.location.Geo
import com.hmgapp.yellowlightcoach.location.LocationTracker
import com.hmgapp.yellowlightcoach.signal.Approach
import com.hmgapp.yellowlightcoach.signal.Movement
import com.hmgapp.yellowlightcoach.signal.Phase
import com.hmgapp.yellowlightcoach.signal.SignalApi
import com.hmgapp.yellowlightcoach.signal.SignalRepository
import com.hmgapp.yellowlightcoach.signal.SignalState
import com.hmgapp.yellowlightcoach.ui.theme.YellowLightCoachTheme
import com.hmgapp.yellowlightcoach.voice.VoiceGuide
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2단계 테스트 화면: 공공 신호 API 연동 + 위치로 앞 교차로 찾기
 *  + 깜빡이로 직진/좌회전, 회전 중 안내 멈춤, 정지선 거리, 오래된 데이터 거르기
 *
 *  - 울산 교차로 목록을 한 번 받고
 *  - 신호는 10초마다 받으며, 그 사이엔 앱 안에서 1초씩 카운트다운
 *  - 신호가 바뀌는 순간(남은 시간 0)엔 바로 다시 받기 (최소 3초 간격)
 *  - 시연 구간(공업탑로터리 → 터미널사거리) 10개 교차로의 직진 신호 표시
 *    · 서→동으로 달리면 서쪽(wt)에서 진입 → wt 신호
 *    · 동→서로 달리면 동쪽(et)에서 진입 → et 신호
 */
class MainActivity : ComponentActivity() {

    private val tag = "YLC"

    // ---------- 시연 구간 (서 → 동 순서) ----------
    private val corridor = listOf(
        "109" to "공업탑로터리", "12" to "울산남부경찰서", "5" to "달동사거리",
        "229" to "태화신협앞", "11" to "파크랜드", "6" to "동평중학교입구",
        "2" to "번영사거리", "10" to "목화예식장", "7" to "현대백화점사거리", "8" to "터미널사거리"
    )

    // ---------- 신호 ----------
    private val repo = SignalRepository(SignalApi(BuildConfig.SIGNAL_API_KEY))
    private val tick = mutableStateOf(0L)              // 화면 카운트다운 갱신용
    private val signalStatus = mutableStateOf("준비 중…")

    // ---------- Pleos Vehicle SDK ----------
    private lateinit var vehicle: Vehicle
    private val gearText = mutableStateOf("-")
    private val brakeText = mutableStateOf("-")
    private val steeringText = mutableStateOf("-")
    private val turnSignalText = mutableStateOf("-")
    private val turnTracker = TurnIntentTracker()
    private val turningDetector = TurningDetector()
    private val turning = mutableStateOf(false)

    // ---------- AAOS Car API (속도, 주행 제한) ----------
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedSubscribed = false
    private val speedText = mutableStateOf("꺼짐")
    private val uxText = mutableStateOf("-")

    // ---------- 위치 ----------
    private lateinit var tracker: LocationTracker
    private val fix = mutableStateOf<Fix?>(null)
    private val ahead = mutableStateOf<Ahead?>(null)
    private val locationText = mutableStateOf("꺼짐")

    // ---------- 음성 (Pleos TextToSpeech) ----------
    private lateinit var voice: VoiceGuide
    private val beeper by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    private val logs = mutableStateListOf<String>()

    // ---------- Vehicle SDK 리스너 ----------
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = ui { gearText.value = gearLabel(gear) }
        override fun onFailed(e: Exception) = log("기어 오류: ${e.message}")
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) =
            ui { brakeText.value = if (status == true) "🟥 밟음" else "⬜ 뗌" }
        override fun onFailed(e: Exception) = log("브레이크 오류: ${e.message}")
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) =
            ui {
                steeringText.value = angle?.let { "%.1f°".format(it) } ?: "-"
                if (angle != null) {
                    val t = turningDetector.update(angle)
                    if (t != turning.value) log(if (t) "회전 시작 → 안내 멈춤" else "회전 끝 → 안내 재개")
                    turning.value = t
                }
            }
        override fun onFailed(e: Exception) = log("조향각 오류: ${e.message}")
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
            ui {
                turnSignalText.value = state?.name ?: "-"
                turnTracker.update(state?.name, SystemClock.elapsedRealtime())
            }
        override fun onFailed(e: Exception) = log("방향지시등 오류: ${e.message}")
    }

    // ---------- Car API 콜백 ----------
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            ui { speedText.value = "%.1f km/h".format(mps * 3.6f) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = log("속도 오류 propId=$propId")
    }
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        ui { uxText.value = uxLabel(r) }
    }
    private val speedPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("CAR_SPEED 권한 ${if (granted) "허용" else "거부"}")
            if (granted) subscribeSpeed()
        }
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            log("위치 권한 ${if (granted) "허용" else "거부"}")
            if (granted) startTracker()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        log("앱 시작")

        vehicle = Vehicle(this)
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)

        tracker = LocationTracker(this) { f -> onFix(f) }

        voice = VoiceGuide(this) { msg -> log(msg) }
        voice.init()

        startSignalLoop()

        setContent {
            YellowLightCoachTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }

    // ================= 신호 받기 루프 =================

    private fun startSignalLoop() {
        if (BuildConfig.SIGNAL_API_KEY.isBlank()) {
            signalStatus.value = "❌ local.properties에 SIGNAL_API_KEY가 없어요"
            return
        }
        lifecycleScope.launch {
            // 화면이 보일 때만 동작 (안 보이면 멈춰서 호출 한도 아끼기)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (repo.intersections.isEmpty()) {
                    try {
                        repo.loadIntersections()
                        log("교차로 ${repo.intersections.size}개 받음")
                        logCorridorCoords()
                    } catch (e: Exception) {
                        log("교차로 받기 실패: ${e.message}")
                    }
                }
                var nextRefresh = 0L
                var lastRefresh = 0L
                var failCount = 0
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextRefresh) {
                        // 성공·실패와 상관없이 "시도한 시각"을 기록 → 실패해도 연달아 부르지 않음
                        lastRefresh = now
                        val ok = try {
                            repo.refreshSignals()
                            updateStatus()
                            failCount = 0
                            true
                        } catch (e: Exception) {
                            failCount++
                            signalStatus.value = "❌ 신호 받기 실패 (${failCount}회 연속): ${e.message}"
                            if (failCount == 1 || failCount % 5 == 0) log("신호 받기 실패 ${failCount}회: ${e.message}")
                            false
                        }
                        // 실패하면 5초 → 10초 → 20초 → 최대 30초 간격으로 다시 시도
                        val wait = if (ok) REFRESH_MS
                        else minOf(RETRY_MAX_MS, RETRY_BASE_MS shl (failCount - 1).coerceAtMost(3))
                        nextRefresh = SystemClock.elapsedRealtime() + wait
                    } else if (failCount == 0 && now - lastRefresh > MIN_GAP_MS && anyCorridorSignalChanged(now)) {
                        // 화면에 보이는 신호가 바뀌는 순간 → 다음 신호 길이를 알려면 바로 다시 받기
                        nextRefresh = now
                        continue
                    }
                    tick.value = now
                    delay(250)
                }
            }
        }
    }

    /** 시연 구간 + 앞 교차로 신호 중 남은 시간이 0 이하가 된 게 있는지 */
    private fun anyCorridorSignalChanged(now: Long): Boolean {
        val a = ahead.value
        if (a != null) {
            for (m in Movement.values()) {
                val r = repo.current(a.intersection.id, a.approach, m, now)?.remainingMs
                if (r != null && r <= 0) return true
            }
        }
        return corridor.any { (id, _) ->
            listOf(Approach.W, Approach.E).any { ap ->
                val r = repo.current(id, ap, Movement.STRAIGHT, now)?.remainingMs
                r != null && r <= 0
            }
        }
    }

    private fun updateStatus() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(repo.receivedAtWall))
        val lag = repo.dataLagSec()?.let { "%.0f초".format(it.coerceAtLeast(0.0)) } ?: "?"
        signalStatus.value =
            "✅ 신호 ${repo.snapshot.size}개 · 받은 시각 $time · 데이터 지연 $lag · 호출 ${repo.callCount}회"
    }

    // ================= 위치 =================

    private fun startLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            startTracker()
        } else {
            // 권한 창은 주차(P) 상태에서만 (주행 중엔 차단됨)
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun startTracker() {
        val ok = tracker.start()
        locationText.value = if (ok) "위치 기다리는 중…" else "❌ GPS가 꺼져 있어요"
        log(if (ok) "위치 받기 시작" else "GPS 꺼짐")
    }

    private fun onFix(f: Fix) {
        fix.value = f
        val head = f.headingDeg?.let { "%s %.0f°".format(Geo.compass(it), it) } ?: "모름(움직이면 계산)"
        locationText.value = "%.6f, %.6f · 진행 방향 %s".format(f.lat, f.lon, head)
        val newAhead = AheadFinder.find(
            f, repo.intersections.values, { id -> repo.approachesWithSignal(id) }, ahead.value
        )
        if (newAhead?.intersection?.id != ahead.value?.intersection?.id) {
            log(
                newAhead?.let { "앞 교차로: ${it.intersection.name} (%.0fm 앞, 옆 %.0fm, ${it.approach.prefix})".format(it.distanceM, it.lateralM) }
                    ?: "앞 교차로: 없음"
            )
        }
        ahead.value = newAhead
    }

    /** 테스트 경로(GPX) 만들기용: 시연 구간 교차로 좌표를 Logcat에 출력 */
    private fun logCorridorCoords() {
        corridor.forEach { (id, name) ->
            val x = repo.intersections[id] ?: return@forEach
            Log.d(tag, "COORD $id,$name,${x.lat},${x.lon}")
        }
    }

    // ================= Car API =================

    private fun startCarSpeed() {
        if (car == null) {
            try {
                val c = Car.createCar(this)
                car = c
                propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                uxManager?.currentCarUxRestrictions?.let { uxText.value = uxLabel(it) }
                uxManager?.registerListener(uxListener)
            } catch (e: Exception) {
                log("Car 연결 실패: ${e.message}")
                return
            }
        }
        // 권한 요청은 반드시 주차(P) 상태에서 (주행 중엔 권한 창이 차단됨)
        if (ContextCompat.checkSelfPermission(this, Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            subscribeSpeed()
        } else {
            speedPermissionLauncher.launch(Car.PERMISSION_SPEED)
        }
    }

    private fun subscribeSpeed() {
        if (speedSubscribed) return
        val ok = propertyManager?.registerCallback(
            speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL
        ) ?: false
        speedSubscribed = ok
        speedText.value = if (ok) "받는 중…" else "❌ 구독 실패"
    }

    override fun onDestroy() {
        vehicle.getGear().unregisterGearSelection(gearListener)
        vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
        vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
        vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
        vehicle.release()
        if (speedSubscribed) propertyManager?.unregisterCallback(speedCallback)
        uxManager?.unregisterListener()
        car?.disconnect()
        tracker.stop()
        voice.release()
        beeper.release()
        super.onDestroy()
    }

    // ================= 도우미 =================

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun log(msg: String) {
        Log.d(tag, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        ui {
            logs.add(0, "$time  $msg")
            if (logs.size > 12) logs.removeAt(logs.lastIndex)
        }
    }

    private fun gearLabel(g: VehicleGear?) = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        else -> "?"
    }

    private fun uxLabel(r: CarUxRestrictions): String =
        if (!r.isRequiresDistractionOptimization) "제한 없음" else "제한 중(${r.activeRestrictions})"

    private fun phaseColor(p: Phase) = when (p) {
        Phase.GREEN -> Color(0xFF2E9D5B)
        Phase.YELLOW -> Color(0xFFE0A400)
        Phase.RED -> Color(0xFFC8392F)
        Phase.UNKNOWN -> Color.Gray
    }

    private fun signalLabel(s: SignalState?): String {
        if (s == null) return "-"
        val r = s.remainingMs
        val sec = when {
            r == null -> "?"
            r <= 0 -> "전환 중"
            else -> "${(r + 999) / 1000}초"
        }
        return "${s.phase.label} $sec"
    }

    // ================= 화면 =================

    @Composable
    private fun TestScreen(modifier: Modifier) {
        val now = tick.value   // 0.25초마다 바뀌어서 카운트다운이 다시 그려짐
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("노란불 코치 · 신호 데이터 연동 테스트", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "기어 ${gearText.value}  ·  브레이크 ${brakeText.value}  ·  조향 ${steeringText.value}  ·  " +
                        "깜빡이 ${turnSignalText.value}  ·  속도 ${speedText.value}  ·  주행 제한 ${uxText.value}",
                fontSize = 15.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { startCarSpeed() }) { Text("차량 속도 켜기 (P에서)") }
                Button(onClick = { startLocation() }) { Text("위치 켜기 (P에서)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { voice.speak("앞 신호, 속도 줄이세요") }) { Text("🔊 속도 줄이세요") }
                Button(onClick = { voice.speak("정지 준비") }) { Text("🔊 정지 준비") }
                Button(onClick = { voice.stop() }) { Text("■ 멈춤") }
                Button(onClick = { log("🔔 삐 요청"); beeper.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }) { Text("🔔 삐 (비교용)") }
            }

            // 앞 교차로
            Spacer(Modifier.height(4.dp))
            Text("현재 위치: ${locationText.value}", fontSize = 15.sp)
            val intent = turnTracker.intent(now)
            val sit = SituationBuilder.build(ahead.value, intent, turning.value, repo, now)
            if (turnTracker.rawIntent != intent) {
                Text("깜빡이 ${turnTracker.rawIntent} 확인 중… (3초 넘게 켜져 있으면 회전으로 판단)", fontSize = 15.sp, color = Color.Gray)
            }
            val a = sit.ahead
            if (a == null) {
                Text("앞 교차로: 없음 (500m 안, 내 진행선 ±35m)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    "앞 교차로: ${a.intersection.name} (${a.intersection.id}) · 정지선까지 %.0fm (중심 %.0fm) · 옆 %.0fm"
                        .format(sit.stopLineM ?: 0.0, a.distanceM, a.lateralM),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                val kind = sit.movement.label + if (sit.leftFallback) " (좌회전 신호 없음 → 직진 신호)" else ""
                Text(
                    "진입 방향: ${a.approach.label}쪽(${a.approach.prefix}) · $kind 신호 ${signalLabel(sit.signal)}",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = phaseColor(sit.signal?.phase ?: Phase.UNKNOWN)
                )
            }
            Text(
                sit.inactiveReason?.let { "⏸ 안내 멈춤: $it" } ?: "▶ 안내 가능",
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = if (sit.inactiveReason == null) Color(0xFF2E9D5B) else Color.Gray
            )

            Spacer(Modifier.height(4.dp))
            Text(signalStatus.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

            // 표 머리
            Row(Modifier.fillMaxWidth()) {
                Text("교차로", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(220.dp))
                Text("서→동 (wt 직진)", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(200.dp))
                Text("동→서 (et 직진)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            corridor.forEach { (id, fallbackName) ->
                val name = repo.intersections[id]?.name ?: fallbackName
                val w = repo.current(id, Approach.W, Movement.STRAIGHT, now)
                val e = repo.current(id, Approach.E, Movement.STRAIGHT, now)
                Row(Modifier.fillMaxWidth()) {
                    Text("$name ($id)", fontSize = 15.sp, modifier = Modifier.width(220.dp))
                    Text(signalLabel(w), fontSize = 15.sp, color = phaseColor(w?.phase ?: Phase.UNKNOWN),
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.width(200.dp))
                    Text(signalLabel(e), fontSize = 15.sp, color = phaseColor(e?.phase ?: Phase.UNKNOWN),
                        fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("기록 (최신순)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            logs.forEach { Text(it, fontSize = 13.sp) }
        }
    }

    companion object {
        private const val REFRESH_MS = 10_000L   // 정기 동기화 주기
        private const val MIN_GAP_MS = 3_000L    // 신호 전환 시 재호출 최소 간격
        private const val RETRY_BASE_MS = 5_000L // 실패 시 첫 재시도 간격
        private const val RETRY_MAX_MS = 30_000L // 실패 시 최대 재시도 간격
    }
}