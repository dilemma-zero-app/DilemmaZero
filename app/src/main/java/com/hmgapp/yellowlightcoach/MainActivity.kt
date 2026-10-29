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
import com.hmgapp.yellowlightcoach.navi.NaviWatcher
import com.hmgapp.yellowlightcoach.coach.TurnIntent
import com.hmgapp.yellowlightcoach.coach.AlertPolicy
import com.hmgapp.yellowlightcoach.coach.DilemmaEvaluator
import com.hmgapp.yellowlightcoach.coach.DriverProfile
import com.hmgapp.yellowlightcoach.coach.FatigueTracker
import com.hmgapp.yellowlightcoach.coach.Level
import com.hmgapp.yellowlightcoach.coach.Situation
import com.hmgapp.yellowlightcoach.coach.Verdict
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

    // ---------- 순정 내비 (Pleos NaviHelper) ----------
    private lateinit var navi: NaviWatcher

    // ---------- 판정 (3b단계) ----------
    @Volatile private var speedMps = 0.0
    @Volatile private var isDrive = false
    private val fatigue = FatigueTracker()
    private val policy = AlertPolicy()
    private val profile = DriverProfile()          // 기본 모드 (반응 1.0초, 감속 3.0m/s²)
    private val sitState = mutableStateOf<Situation?>(null)
    private val verdict = mutableStateOf<Verdict?>(null)
    private val intentText = mutableStateOf("-")
    private val beeper by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    private val logs = mutableStateListOf<String>()

    // ---------- Vehicle SDK 리스너 ----------
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = ui {
            gearText.value = gearLabel(gear)
            val now = SystemClock.elapsedRealtime()
            isDrive = gear == VehicleGear.GEAR_DRIVE
            fatigue.onGear(isDrive, now)
            // 2시간 넘게 달린 뒤 P에서 한 번만 휴식 권유
            if (!isDrive && fatigue.shouldRemindRest(now)) {
                voice.speak("2시간째 운전 중이에요. 쉬었다 가세요")
            }
        }
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
            speedMps = mps.toDouble()
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
            val fused = result[FUSED_LOCATION_PERMISSION] == true
            val gps = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            log("위치 권한: Pleos ${if (fused) "허용" else "거부"} · GPS ${if (gps) "허용" else "거부"}")
            if (fused || gps) startTracker()
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

        tracker = LocationTracker(this, log = { msg -> log(msg) }) { f -> ui { onFix(f) } }

        voice = VoiceGuide(this) { msg -> log(msg) }
        voice.init()

        navi = NaviWatcher(this) { msg -> log(msg) }
        navi.init()

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
                    coachTick(now)
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
        val need = arrayOf(
            FUSED_LOCATION_PERMISSION,
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { !granted(it) }
        if (need.isEmpty()) {
            startTracker()
        } else {
            // 권한 창은 주차(P) 상태에서만 (주행 중엔 차단됨)
            locationPermissionLauncher.launch(need.toTypedArray())
        }
    }

    private fun startTracker() {
        val ok = tracker.start(useFused = granted(FUSED_LOCATION_PERMISSION))
        locationText.value = if (ok) "위치 기다리는 중… (${tracker.source})" else "❌ 위치를 받을 수 없어요"
        log(if (ok) "위치 받기 시작" else "GPS 꺼짐")
    }

    private fun onFix(f: Fix) {
        fix.value = f
        val head = f.headingDeg?.let { "%s %.0f°".format(Geo.compass(it), it) } ?: "모름(움직이면 계산)"
        val sdk = f.sdkHeadingDeg?.let { " · SDK 방향 %.0f° (신뢰도 %.2f)".format(it, f.confidence ?: 0f) } ?: ""
        locationText.value = "[${f.source}] %.6f, %.6f · 계산 방향 %s%s".format(f.lat, f.lon, head, sdk)
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

    // ================= 판정 + 음성 =================

    /** 0.25초마다: 지금 상황 → 판정 → 필요하면 한 번 말하기 */
    private fun coachTick(now: Long) {
        // 회전 의도: 깜빡이(3초 확인)가 우선, 없으면 내비 TBT(거리·위치가 맞을 때만)
        val signalIntent = turnTracker.intent(now)
        val myFix = fix.value
        val naviIntent = ahead.value?.let { a -> myFix?.let { f -> navi.turnAt(a.distanceM, f.lat, f.lon, now) } }
        val intent = if (signalIntent != TurnIntent.NONE) signalIntent else naviIntent ?: TurnIntent.NONE
        intentText.value = "$intent (출처: " + when {
            signalIntent != TurnIntent.NONE -> "깜빡이"
            naviIntent != null -> "내비 TBT"
            else -> "-"
        } + ")"

        val sit = SituationBuilder.build(ahead.value, intent, turning.value, repo, now)
        sitState.value = sit
        val sig = sit.signal
        val v = when {
            !isDrive -> Verdict(Level.INACTIVE, "기어 D 아님")
            sit.inactiveReason != null || sig == null || sit.stopLineM == null ->
                Verdict(Level.INACTIVE, sit.inactiveReason ?: "신호 없음")
            else -> DilemmaEvaluator.evaluate(
                speedMps, sit.stopLineM, sig.phase, sig.remainingMs, profile, fatigue.factor(now)
            )
        }
        verdict.value = v

        val text = policy.next(sit.ahead?.intersection?.id, v.level) ?: return
        log("🗣 ${v.level.label}: \"$text\" (${sit.ahead?.intersection?.name}, " +
                "%.0fkm/h, 정지선 %.0fm, 녹색 %.1f초)".format(speedMps * 3.6, sit.stopLineM ?: 0.0, v.greenSec))
        voice.speak(text)
    }

    private fun levelColor(l: Level) = when (l) {
        Level.CALM -> Color(0xFF2E9D5B)
        Level.CAUTION -> Color(0xFFE0A400)
        Level.PREPARE_STOP -> Color(0xFFC8392F)
        Level.INACTIVE -> Color.Gray
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
        navi.release()
        beeper.release()
        super.onDestroy()
    }

    // ================= 도우미 =================

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
            // 겹침 테스트: "주의"를 말하는 도중(0.5초 뒤) "정지 준비"가 들어오면?
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    log("── 겹침 A: 그냥 이어서 요청")
                    voice.speak("앞 신호, 속도 줄이세요")
                    lifecycleScope.launch { delay(500); voice.speak("정지 준비") }
                }) { Text("🧪 겹침 A (그냥)") }
                Button(onClick = {
                    log("── 겹침 B: 멈추고 요청")
                    voice.speak("앞 신호, 속도 줄이세요")
                    lifecycleScope.launch { delay(500); voice.stop(); voice.speak("정지 준비") }
                }) { Text("🧪 겹침 B (끊고)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 서→동으로 달리다 달동사거리에서 좌회전(북쪽)하게 되는 목적지
                Button(onClick = { navi.requestRoute(LEFT_TEST_LAT, LEFT_TEST_LON, "좌회전 테스트") }) { Text("🧭 좌회전 경로 요청") }
                Button(onClick = { navi.cancelRoute() }) { Text("🧭 경로 취소") }
                Button(onClick = { navi.query() }) { Text("🧭 내비 위치 확인") }
            }
            val firstTbt = navi.tbtList.firstOrNull()
            Text(
                "내비: ${if (navi.connected) "연결" else "미연결"} · 상태 ${navi.status ?: "-"} · 모드 ${navi.naviMode ?: "-"} · " +
                        "제한속도 ${navi.speedLimit?.let { "${it}km/h" } ?: "-"} · " +
                        "TBT ${firstTbt?.let { "${it.type} %.0fm".format(it.distance) } ?: "-"} · " +
                        "내비 위치 ${navi.naviLocation?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "-"}",
                fontSize = 15.sp
            )

            // 앞 교차로
            Spacer(Modifier.height(4.dp))
            Text("현재 위치: ${locationText.value}", fontSize = 15.sp)
            // 판정은 coachTick()에서 0.25초마다 계산, 화면은 결과만 보여줌
            val sit = sitState.value ?: SituationBuilder.build(ahead.value, TurnIntent.NONE, turning.value, repo, now)
            Text("회전 판단: ${intentText.value}", fontSize = 15.sp)
            if (turnTracker.rawIntent != TurnIntent.NONE && turnTracker.intent(now) == TurnIntent.NONE) {
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
            verdict.value?.let { v ->
                Text(
                    "판정: ${v.level.label} — ${v.reason}",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = levelColor(v.level)
                )
                if (v.comfortStopM > 0) {
                    Text(
                        "편안한 정지거리 %.0fm · 급제동 %.0fm · 정지선 도착 %.1f초 · 녹색(여유 뺀) %.1f초 · 황색 순간 남는 거리 %.0fm"
                            .format(v.comfortStopM, v.hardStopM, v.arriveSec, v.greenSec, v.atYellowM),
                        fontSize = 14.sp
                    )
                }
            }
            Text(
                "연속 운전 %d분 · 피로 계수 %.1f".format(fatigue.drivingMs(now) / 60_000, fatigue.factor(now)),
                fontSize = 14.sp
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
        private const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        // 좌회전 테스트 목적지: 달동사거리(35.534043, 129.314474)에서 북쪽으로 약 450m
        private const val LEFT_TEST_LAT = 35.5381
        private const val LEFT_TEST_LON = 129.3140
    }
}