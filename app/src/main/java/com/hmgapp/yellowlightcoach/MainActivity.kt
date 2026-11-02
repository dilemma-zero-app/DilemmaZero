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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import com.hmgapp.yellowlightcoach.service.CoachService
import com.hmgapp.yellowlightcoach.demo.DemoRecorder
import com.hmgapp.yellowlightcoach.demo.DemoReplay
import com.hmgapp.yellowlightcoach.signal.Intersection
import com.hmgapp.yellowlightcoach.signal.IntersectionSignals
import com.hmgapp.yellowlightcoach.coach.TurnIntent
import com.hmgapp.yellowlightcoach.coach.AlertPolicy
import com.hmgapp.yellowlightcoach.coach.DilemmaEvaluator
import com.hmgapp.yellowlightcoach.coach.DriverProfile
import com.hmgapp.yellowlightcoach.coach.FatigueTracker
import com.hmgapp.yellowlightcoach.coach.PositionStallDetector
import com.hmgapp.yellowlightcoach.coach.DriveMode
import com.hmgapp.yellowlightcoach.coach.TripScore
import com.hmgapp.yellowlightcoach.coach.BrakeLearner
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

    // ---------- 4단계: 주행 기록 · 재생 ----------
    private lateinit var recorder: DemoRecorder
    private lateinit var replay: DemoReplay
    /** 재생 중에도 실제 차량 값은 따로 기억해 두었다가, 재생이 끝나면 되돌린다 */
    private var liveDrive = false
    private var liveGearLabel = "-"
    private var liveSpeedMps = 0.0

    // ---------- 판정 (3b단계) ----------
    @Volatile private var speedMps = 0.0
    @Volatile private var isDrive = false
    /** 자동 시작: 권한 창을 이미 띄웠는지 / 위치 받는 중인지 */
    private var autoAsked = false
    private var trackerRunning = false
    private val fatigue = FatigueTracker()
    private val stall = PositionStallDetector()
    private var stallLogged = false
    /** 주차(P)를 시작한 시각 — 5분 넘으면 신호 API 쉬기 (하루 5,000건 한도) */
    @Volatile private var parkedSince = 0L
    @Volatile private var resumeRefresh = false
    private var policy = AlertPolicy()
    /** 운전자 모드 (주차 중에만 바꿀 수 있음, 앱을 다시 켜도 유지) */
    private val driveMode = mutableStateOf(DriveMode.STANDARD)
    private val profile get() = driveMode.value.profile
    /** 판정에 쓰는 값: 브레이크 습관이 "학습됨"이면 더 일찍 안내하는 쪽으로만 반영, 시연 재생 중엔 모드 값 그대로 */
    private val judgeProfile get() = if (replay.active) profile else brake.judgeProfile(profile)
    /** 교차로 안전 점수 (이번 주행) — 주차하면 요약 */
    private val trip = TripScore()
    private val tripSummary = mutableStateOf<TripScore.Summary?>(null)
    // 6단계: 브레이크 습관 기록 (반응시간·편안한 감속도)
    private val brake = BrakeLearner()
    private val brakeStats = mutableStateOf(BrakeLearner.Stats())
    private val sitState = mutableStateOf<Situation?>(null)
    private val verdict = mutableStateOf<Verdict?>(null)
    private val intentText = mutableStateOf("-")
    private val beeper by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    private val logs = mutableStateListOf<String>()

    // ---------- Vehicle SDK 리스너 ----------
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = ui {
            liveDrive = gear == VehicleGear.GEAR_DRIVE
            liveGearLabel = gearLabel(gear)
            // 주행 중이라 권한 창이 막혔었다면, P가 되는 순간 다시 자동 시작
            if (!liveDrive && !autoAsked && !(speedSubscribed && trackerRunning)) autoStart()
            if (replay.active) return@ui      // 재생 중엔 기록된 기어를 씀
            applyGear(liveDrive, liveGearLabel)
        }
        override fun onFailed(e: Exception) = log("기어 오류: ${e.message}")
    }
    /** 기어 반영 (실시간·재생 공통) */
    private fun applyGear(drive: Boolean, label: String) {
        gearText.value = label
        val now = SystemClock.elapsedRealtime()
        if (drive && !isDrive && parkedSince != 0L) resumeRefresh = true   // 쉬다가 D → 바로 다시 받기
        // 새 주행 시작: 처음이거나 30초 넘게 주차했다가 D
        if (drive && !isDrive && (!trip.started || (parkedSince != 0L && now - parkedSince > 30_000))) {
            trip.startTrip()
            tripSummary.value = null
        }
        // 주차: 이번 주행 요약 (교차로를 하나 이상 지났을 때만 화면에)
        if (!drive && isDrive && trip.started) {
            val sm = trip.summary()
            tripSummary.value = sm
            log("🏁 주행 요약: 교차로 ${sm.intersections}곳 · 주의 ${sm.cautions} · 정지 준비 ${sm.prepares} · " +
                    "황색·적색 진입 ${sm.redYellowEntries} · 급정거 ${sm.hardBrakes} · 점수 ${sm.score ?: "-"}")
        }
        isDrive = drive
        parkedSince = if (drive) 0L else (if (parkedSince == 0L) now else parkedSince)
        fatigue.onGear(isDrive, now)
        recorder.gear(isDrive)
        // 2시간 넘게 달린 뒤 P에서 한 번만 휴식 권유
        if (!isDrive && fatigue.shouldRemindRest(now)) {
            voice.speak("2시간째 운전 중이에요. 쉬었다 가세요")
        }
    }

    /** 조향각 반영 (실시간·재생 공통) */
    private fun applySteering(angle: Float) {
        steeringText.value = "%.1f°".format(angle)
        recorder.steering(angle)
        val t = turningDetector.update(angle)
        if (t != turning.value) log(if (t) "회전 시작 → 안내 멈춤" else "회전 끝 → 안내 재개")
        turning.value = t
    }

    /** 깜빡이 반영 (실시간·재생 공통) */
    private fun applyTurnSignal(name: String?) {
        turnSignalText.value = name ?: "-"
        turnTracker.update(name, SystemClock.elapsedRealtime())
        recorder.turnSignal(name)
    }

    /** 속도 반영 (실시간·재생 공통) */
    private fun applySpeed(mps: Double) {
        speedMps = mps
        recorder.speed(mps)
        ui {
            speedText.value = "%.1f km/h".format(mps * 3.6)
            if (!replay.active) brakeLog(brake.onSpeed(mps, SystemClock.elapsedRealtime()))
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) =
            ui {
                brakeText.value = if (status == true) "🟥 밟음" else "⬜ 뗌"
                if (!replay.active) brakeLog(brake.onBrake(status == true, speedMps, SystemClock.elapsedRealtime()))
            }
        override fun onFailed(e: Exception) = log("브레이크 오류: ${e.message}")
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) =
            ui {
                if (replay.active) return@ui
                if (angle == null) steeringText.value = "-" else applySteering(angle)
            }
        override fun onFailed(e: Exception) = log("조향각 오류: ${e.message}")
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
            ui {
                if (replay.active) return@ui
                applyTurnSignal(state?.name)
            }
        override fun onFailed(e: Exception) = log("방향지시등 오류: ${e.message}")
    }

    // ---------- Car API 콜백 ----------
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            liveSpeedMps = mps.toDouble()
            if (replay.active) return       // 재생 중엔 기록된 속도를 씀
            applySpeed(liveSpeedMps)
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
    /** 앱 시작 때 한 번에 묻는 권한 창 (속도 + 위치 + 내비) */
    private val allPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys.map { it.substringAfterLast('.') }
            log(if (denied.isEmpty()) "권한 모두 허용" else "권한 거부: $denied")
            // 주행 중이라 창이 막혀 거부된 경우 → P가 되면 다시 묻기
            if (denied.isNotEmpty() && isDrive) autoAsked = false
            startSensors()
        }
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            log("알림 권한 ${if (ok) "허용 → 팝업 테스트 다시 누르세요" else "거부"}")
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
        driveMode.value = runCatching {
            DriveMode.valueOf(getSharedPreferences("dz", MODE_PRIVATE).getString("mode", null) ?: "STANDARD")
        }.getOrDefault(DriveMode.STANDARD)
        loadBrake()
        // 차량 리스너보다 먼저 만들어야 함 (리스너가 등록 즉시 불릴 수 있음)
        recorder = DemoRecorder(this) { msg -> log(msg) }
        replay = DemoReplay(this) { msg -> log(msg) }

        vehicle = Vehicle(this)
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener)

        tracker = LocationTracker(this, log = { msg -> log(msg) }) { f -> ui { if (!replay.active) onFix(f) } }

        voice = VoiceGuide(this) { msg -> log(msg) }
        voice.init()

        navi = NaviWatcher(this) { msg -> log(msg) }
        navi.init()

        startSignalLoop()

        setContent {
            YellowLightCoachTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 기본은 운전 화면, 개발 중 확인은 "개발자 보기"로
                    if (devView.value) TestScreen(Modifier.padding(innerPadding))
                    else DriveScreen(Modifier.padding(innerPadding))
                }
            }
        }

        // 버튼 없이 바로 동작: 권한이 이미 있으면 그대로 시작, 없으면 한 번에 묻기
        autoStart()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "신호 안내", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)   // 소리는 우리 음성 안내로만
            }
        )
    }

    // ================= 4단계: 주행 기록 =================

    private fun toggleRecording() {
        if (recorder.recording) {
            recorder.stop()
            return
        }
        recorder.start()
        // 시작 순간의 상태를 바로 남겨 둔다 (재생 첫 장면이 비지 않게)
        recorder.gear(isDrive)
        recorder.speed(speedMps)
        fix.value?.let { recorder.fix(it) }
        recorder.signals(repo, fix.value?.lat, fix.value?.lon)
        if (fix.value == null) log("⚠ 아직 위치가 없어요 — 위치가 들어오면 다음 신호부터 기록돼요")
    }

    private fun recordingLabel(now: Long): String {
        if (!recorder.recording) return recorder.file?.let { "마지막 기록: ${it.name}" } ?: "기록 없음"
        val sec = recorder.elapsedMs(now) / 1000
        return "● 기록 중 %d:%02d · %d줄 · %s".format(sec / 60, sec % 60, recorder.lineCount, recorder.file?.name ?: "")
    }

    private fun setMode(m: DriveMode) {
        if (isDrive) return   // 주행 중엔 바꾸지 않음
        driveMode.value = m
        getSharedPreferences("dz", MODE_PRIVATE).edit().putString("mode", m.name).apply()
        log("안내 모드: ${m.label}")
    }


        // ---------- 6단계: 브레이크 습관 저장 ----------
        private fun loadBrake() {
            val p = getSharedPreferences("dz", MODE_PRIVATE)
            brake.stats = BrakeLearner.Stats(
                reactionSec = if (p.contains("br_rt")) p.getFloat("br_rt", 0f).toDouble() else null,
                reactionCount = p.getInt("br_rtn", 0),
                decel = if (p.contains("br_a")) p.getFloat("br_a", 0f).toDouble() else null,
                decelCount = p.getInt("br_an", 0),
                ignoredCount = p.getInt("br_ign", 0)
            )
            brakeStats.value = brake.stats
        }

        private fun saveBrake() {
            val s = brake.stats
            getSharedPreferences("dz", MODE_PRIVATE).edit().apply {
                if (s.reactionSec != null) putFloat("br_rt", s.reactionSec.toFloat()) else remove("br_rt")
                putInt("br_rtn", s.reactionCount)
                if (s.decel != null) putFloat("br_a", s.decel.toFloat()) else remove("br_a")
                putInt("br_an", s.decelCount)
                putInt("br_ign", s.ignoredCount)
            }.apply()
        }

        /** 브레이크 기록 결과를 로그에 남기고 저장 */
        private fun brakeLog(msg: String?) {
            if (msg == null) return
            log("🦶 $msg")
            brakeStats.value = brake.stats
            saveBrake()
        }

        private fun resetBrake() {
            brake.reset()
            brakeStats.value = brake.stats
            saveBrake()
            log("🦶 브레이크 기록 지움")
        }

        /** 주차 중: 내 브레이크 습관 (6단계) */
        @Composable
        private fun BrakeHabitLine() {
            val s = brakeStats.value
            val text = if (s.reactionSec == null && s.decel == null) {
                "내 브레이크 습관 · 아직 기록 없음 (안내 뒤 브레이크를 밟으면 기록돼요)"
            } else {
                val rt = s.reactionSec?.let { "반응시간 %.1f초".format(it) } ?: "반응시간 -"
                val a = s.decel?.let { "편안한 감속 %.1f".format(it) } ?: "편안한 감속 -"
                val n = minOf(s.reactionCount, s.decelCount)
                "내 브레이크 습관 · $rt · $a · " +
                        when {
                            !s.learned -> "기록 ${n}/${BrakeLearner.MIN_SAMPLES}"
                            brake.judgeProfile(profile) != profile -> "학습됨 · 안내를 더 일찍"
                            else -> "학습됨 · 기본 시점 유지"
                        }
            }
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = if (s.learned) Color(0xFF7FD6A4) else Color(0xFFD5D9DE))
        }

    // ================= 4단계: 재생 =================

    private val replaySink = object : DemoReplay.Sink {
        override fun onIntersection(ix: Intersection) = repo.replayIntersection(ix)
        override fun onSignals(list: List<IntersectionSignals>, baseOffsetMs: Map<String, Long>, recordedWall: Long) {
            repo.replaySignals(list, baseOffsetMs.mapValues { replay.startElapsed + it.value }, recordedWall)
            updateStatus()
        }
        override fun onFix(f: Fix) = this@MainActivity.onFix(f)
        override fun onSpeed(mps: Double) = applySpeed(mps)
        override fun onGear(isDrive: Boolean) = applyGear(isDrive, if (isDrive) "D" else "P")
        override fun onTurn(state: String?) = applyTurnSignal(state)
        override fun onSteer(deg: Float) = applySteering(deg)
        override fun onEnd() {
            // 실시간으로 되돌리기: 교차로·신호는 다음 호출에서 새로, 기어·속도는 실제 값으로
            repo.endReplay()
            stall.reset()
            ahead.value = null
            policy = AlertPolicy()
            applyGear(liveDrive, liveGearLabel)
            applySpeed(liveSpeedMps)
            signalStatus.value = "실시간으로 돌아가는 중…"
        }
    }

    private fun toggleReplay(useAsset: Boolean = false) {
        if (replay.active) {
            replay.stop(replaySink)
            return
        }
        if (recorder.recording) recorder.stop()
        // 새 장면: 앞 교차로·안내 기록을 비우고 시작
        repo.beginReplay()
        stall.reset()
        ahead.value = null
        policy = AlertPolicy()
        if (!replay.start(lifecycleScope, replaySink, useAsset)) repo.endReplay()
    }

    private fun replayLabel(now: Long): String {
        if (!replay.active) return replay.latestRecording()?.let { "재생할 기록: ${it.name}" } ?: "재생할 기록 없음 (assets/demo.jsonl 사용)"
        val e = replay.elapsedMs(now) / 1000
        val d = replay.durationMs / 1000
        return "▶ 시연용 재생 데이터 %d:%02d / %d:%02d · %s".format(e / 60, e % 60, d / 60, d % 60, replay.sourceName)
    }

    // ================= 팝업 테스트 (10/29) =================
    // 질문: 순정 내비를 보고 있을 때, 우리 앱이 화면 위에 짧은 팝업을 띄울 수 있나?

    private fun testPopup() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        log("🧪 5초 뒤 팝업 — 지금 내비 화면으로 가세요")
        lifecycleScope.launch {
            delay(5_000)
            showAlert(Level.PREPARE_STOP, "정지 준비", "앞 신호 · 팝업 테스트")
            voice.speak("정지 준비")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showAlert(level: Level, title: String, text: String) {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setLargeIcon(dot(if (level == Level.CAUTION) 0xFFF5B700.toInt() else 0xFFD93B30.toInt()))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(4_000)   // 4초 뒤 저절로 사라짐
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, n)
        log("📣 팝업: $title")
    }

    // ================= 테두리 테스트 (10/29) =================
    // 질문: 순정 내비를 보고 있을 때, 화면 가장자리를 색으로 물들일 수 있나? (다른 앱 위에 그리기)

    private fun testEdgeGlow() {
        if (!Settings.canDrawOverlays(this)) {
            log("🧪 '다른 앱 위에 그리기' 권한 없음 → PowerShell에서 appops 명령 실행 후 다시")
            return
        }
        log("🧪 5초 뒤 테두리 — 지금 내비 화면으로 가세요")
        lifecycleScope.launch {
            delay(5_000)
            showEdgeGlow(Level.PREPARE_STOP)
            voice.speak("정지 준비")
        }
    }

    /** 화면 가장자리에 색 테두리를 3초 동안 (가운데는 투명 → 내비 그대로 보임, 터치도 통과) */
    private var glowView: View? = null

    private fun showEdgeGlow(level: Level) {
        val wm = getSystemService(WindowManager::class.java)
        // 주의 → 정지 준비로 바로 올라가면 이전 테두리를 지우고 새 색으로
        glowView?.let { old -> try { wm.removeView(old) } catch (_: Exception) {} }
        glowView = null
        val color = if (level == Level.CAUTION) 0xFFF5B700.toInt() else 0xFFD93B30.toInt()
        val view = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x00000000)                 // 가운데 투명
                setStroke(36, color)                 // 가장자리 두께(px)
                cornerRadius = 24f
            }
            alpha = 0.85f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
            glowView = view
            log((if (level == Level.CAUTION) "🟨" else "🟥") + " 테두리: ${level.label}")
            lifecycleScope.launch {
                delay(3_000)
                if (glowView === view) {
                    try { wm.removeView(view) } catch (_: Exception) {}
                    glowView = null
                }
            }
        } catch (e: Exception) {
            log("❌ 테두리 실패: ${e.message}")
        }
    }

    /** 팝업 왼쪽 색 동그라미 (노랑=주의, 빨강=정지 준비) */
    private fun dot(color: Int): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(b).drawCircle(48f, 48f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return b
    }

    // ================= 자동 시작 =================

    private val sensorPermissions get() = listOfNotNull(
        Car.PERMISSION_SPEED,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        FUSED_LOCATION_PERMISSION,
        "pleos.car.permission.NAVI_ROUTE",
        "pleos.car.permission.NAVI_ROUTE_SEARCH",
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private fun autoStart() {
        if (!connectCar()) return
        val need = sensorPermissions.filter { !granted(it) }
        if (need.isEmpty()) {
            startSensors()
        } else if (!autoAsked) {
            autoAsked = true
            log("권한 요청: ${need.map { it.substringAfterLast('.') }}")
            allPermissionLauncher.launch(need.toTypedArray())
        }
    }

    /** 허용된 것만 시작 (속도·위치 각각) */
    private fun startSensors() {
        if (granted(Car.PERMISSION_SPEED)) subscribeSpeed()
        if (!trackerRunning && (granted(FUSED_LOCATION_PERMISSION) || granted(Manifest.permission.ACCESS_FINE_LOCATION))) {
            startTracker()
        }
    }

    // ================= 신호 받기 루프 =================

    private fun startSignalLoop() {
        if (BuildConfig.SIGNAL_API_KEY.isBlank()) {
            signalStatus.value = "❌ local.properties에 SIGNAL_API_KEY가 없어요"
            return
        }
        lifecycleScope.launch {
            // 방법 A: 순정 내비를 보는 동안(우리 앱이 화면 뒤에 있어도) 계속 동작
            // → CoachService(포그라운드 서비스)가 앱을 살려 둔다
            run {
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
                    val restingInPark = !isDrive && parkedSince != 0L && now - parkedSince > PARK_REST_MS
                    if (resumeRefresh) { resumeRefresh = false; nextRefresh = now }
                    if (replay.active) {
                        // 재생 중: 신호 API를 부르지 않고 기록된 신호만 사용
                    } else if (restingInPark) {
                        // 주차 5분 넘음: 신호 API 쉬기 (D가 되면 바로 다시 받음)
                        signalStatus.value = "⏸ 주차 5분이 넘어 신호 받기를 쉬는 중 (D가 되면 다시) · 호출 ${repo.callCount}회"
                    } else if (now >= nextRefresh) {
                        // 성공·실패와 상관없이 "시도한 시각"을 기록 → 실패해도 연달아 부르지 않음
                        lastRefresh = now
                        val ok = try {
                            // 앱 시작 때 교차로 목록을 못 받았으면(네트워크 준비 전 등) 여기서 다시 받기
                            // (10/30: 시작 직후 실패 → 교차로 0개로 계속 "앞 교차로 없음"이던 문제)
                            if (repo.intersections.isEmpty()) {
                                repo.loadIntersections()
                                log("교차로 ${repo.intersections.size}개 받음 (다시 시도)")
                            }
                            repo.refreshSignals()
                            recorder.signals(repo, fix.value?.lat, fix.value?.lon)
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
        if (trackerRunning) return
        val ok = tracker.start(useFused = granted(FUSED_LOCATION_PERMISSION))
        trackerRunning = ok
        // 위치 권한이 있을 때만 위치 유형 포그라운드 서비스를 시작할 수 있다
        if (ok) try { CoachService.start(this) } catch (e: Exception) { log("서비스 시작 실패: ${e.message}") }
        locationText.value = if (ok) "위치 기다리는 중… (${tracker.source})" else "❌ 위치를 받을 수 없어요"
        log(if (ok) "위치 받기 시작" else "GPS 꺼짐")
    }

    private fun onFix(f: Fix) {
        fix.value = f
        recorder.fix(f)
        stall.onFix(f.lat, f.lon, SystemClock.elapsedRealtime())
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
        val stalled = stall.stalled(speedMps, now)
        if (stalled != stallLogged) {
            stallLogged = stalled
            log(if (stalled) "⚠ 속도 %.0fkm/h인데 위치가 5초째 그대로 → 안내 멈춤".format(speedMps * 3.6) else "위치 다시 움직임 → 안내 재개")
        }
        val v = when {
            !isDrive -> Verdict(Level.INACTIVE, "기어 D 아님")
            stalled -> Verdict(Level.INACTIVE, "위치가 멈춤 (속도와 안 맞음)")
            sit.inactiveReason != null || sig == null || sit.stopLineM == null ->
                Verdict(Level.INACTIVE, sit.inactiveReason ?: "신호 없음")
            else -> DilemmaEvaluator.evaluate(
                speedMps, sit.stopLineM, sig.phase, sig.remainingMs, judgeProfile, fatigue.factor(now)
            )
        }
        verdict.value = v
        if (isDrive) trip.onTick(sit.ahead?.intersection?.id, sit.stopLineM, sig?.phase, speedMps, now)

        val text = policy.next(sit.ahead?.intersection?.id, v.level) ?: return
        trip.onAlert(v.level)
        if (!replay.active) {
            brake.base = profile
            brakeLog(brake.onAlert(SystemClock.elapsedRealtime(), speedMps))
        }
        log("🗣 ${v.level.label}: \"$text\" (${sit.ahead?.intersection?.name}, " +
                "%.0fkm/h, 정지선 %.0fm, 녹색 %.1f초)".format(speedMps * 3.6, sit.stopLineM ?: 0.0, v.greenSec))
        voice.speak(text)
        // 우리 앱이 화면 뒤에 있을 때(순정 내비를 보는 중)만 팝업 — 앱이 보이면 큰 배너가 이미 있음
        // 표시 방식: '다른 앱 위에 그리기'가 허용되면 화면 테두리 색, 아니면 팝업 (10/29)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (Settings.canDrawOverlays(this)) {
                showEdgeGlow(v.level)
            } else {
                val name = sit.ahead?.intersection?.name
                showAlert(v.level, if (v.level == Level.CAUTION) "속도 줄이세요" else "정지 준비",
                    name?.let { "앞 신호 · $it" } ?: "앞 신호")
            }
        }
    }

    private fun levelColor(l: Level) = when (l) {
        Level.CALM -> Color(0xFF2E9D5B)
        Level.CAUTION -> Color(0xFFE0A400)
        Level.PREPARE_STOP -> Color(0xFFC8392F)
        Level.INACTIVE -> Color.Gray
    }

    // ================= Car API =================

    /** Car 서비스 연결 (한 번만). 실패하면 false */
    private fun connectCar(): Boolean {
        if (car != null) return true
        return try {
            val c = Car.createCar(this)
            car = c
            propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.currentCarUxRestrictions?.let { uxText.value = uxLabel(it) }
            uxManager?.registerListener(uxListener)
            true
        } catch (e: Exception) {
            log("Car 연결 실패: ${e.message}")
            false
        }
    }

    private fun startCarSpeed() {
        if (!connectCar()) return
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
        recorder.release()
        if (replay.active) replay.stop(replaySink)
        CoachService.stop(this)
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

    /** true = 개발자 보기(지금까지의 테스트 화면), false = 운전 화면 */
    private val devView = mutableStateOf(false)

    /**
     * 운전 화면 — 한눈에 보이는 것 하나만
     *  - 큰 배너: 상태 색 + 짧은 행동 문장 (음성과 같은 말)
     *  - 숫자(남은 초)는 보여주지 않는다: "숫자를 보여주는 앱"이 아니라 "할 일을 알려주는 앱"
     *  - 운전 중엔 버튼을 누를 일이 없게 (개발자 보기 버튼은 작게 구석에)
     */
    @Composable
    private fun DriveScreen(modifier: Modifier) {
        val now = tick.value
        val v = verdict.value
        val level = v?.level ?: Level.INACTIVE
        val name = sitState.value?.ahead?.intersection?.name

        val (bg, fg) = when (level) {
            Level.PREPARE_STOP -> Color(0xFFD93B30) to Color.White
            Level.CAUTION -> Color(0xFFF5B700) to Color(0xFF1A1A1A)
            // 여유는 '조용한' 상태 → 어두운 초록 (밤에 화면 가득 밝은 초록은 눈부심·주의 분산)
            Level.CALM -> Color(0xFF173B2B) to Color(0xFF7FD6A4)
            Level.INACTIVE -> Color(0xFF3A3F45) to Color(0xFFD5D9DE)
        }
        val (main, sub) = when (level) {
            Level.PREPARE_STOP -> "정지 준비" to (name?.let { "앞 신호 · $it" } ?: "앞 신호")
            Level.CAUTION -> "속도 줄이세요" to (name?.let { "앞 신호 · $it" } ?: "앞 신호")
            Level.CALM -> "여유" to (name?.let { "앞 신호 · $it" } ?: "")
            Level.INACTIVE -> "대기" to when {
                !isDrive -> "주차 중"
                name == null -> "앞에 안내할 신호가 없어요"
                else -> "$name · 지금은 안내하지 않아요"
            }
        }

        Column(
            modifier = modifier.fillMaxSize().background(Color(0xFF121417)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("딜레마 제로", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(16.dp))
                Text("기어 ${gearText.value} · ${speedText.value}", fontSize = 16.sp, color = Color(0xFF9AA3AD),
                    modifier = Modifier.weight(1f))
                if (recorder.recording) {
                    Text("● 기록 중", fontSize = 16.sp, color = Color(0xFFFF6B5E), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                }
                if (replay.active) {
                    Text("▶ 시연용 재생 데이터", fontSize = 16.sp, color = Color(0xFFF5B700), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                }
                // 주행 중에는 조작할 버튼을 보이지 않음 (주차 중에만)
                if (!isDrive) {
                    OutlinedButton(onClick = { devView.value = true }) { Text("개발자 보기", color = Color(0xFF9AA3AD)) }
                }
            }

            val sm = tripSummary.value
            if (!isDrive && sm?.score != null) {
                TripSummaryCard(sm, Modifier.fillMaxWidth().weight(1f))
            } else Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(bg, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(main, fontSize = 88.sp, fontWeight = FontWeight.Black, color = fg, textAlign = TextAlign.Center)
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(sub, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center)
                    }
                }
            }

            // 주차 중에만: 안내 모드 선택
            if (!isDrive) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("안내 모드", fontSize = 18.sp, color = Color(0xFF9AA3AD))
                    DriveMode.values().forEach { m ->
                        if (m == driveMode.value) {
                            Button(onClick = { setMode(m) }) { Text(m.label, fontSize = 16.sp) }
                        } else {
                            OutlinedButton(onClick = { setMode(m) }) { Text(m.label, fontSize = 16.sp, color = Color(0xFF9AA3AD)) }
                        }
                    }
                    Text(if (driveMode.value == DriveMode.RELAXED) "더 일찍 알려줘요" else "",
                        fontSize = 16.sp, color = Color(0xFF9AA3AD))
                }
            }
            if (!isDrive) BrakeHabitLine()
            Text(
                "연속 운전 %d분 · 안내 모드 %s".format(fatigue.drivingMs(now) / 60_000, driveMode.value.label),
                fontSize = 18.sp, color = Color(0xFF9AA3AD)
            )
        }
    }

    /** 주차 중 이번 주행 요약 (배너 대신) */
    @Composable
    private fun TripSummaryCard(sm: TripScore.Summary, modifier: Modifier) {
        Box(
            modifier = modifier.background(Color(0xFF1E2A38), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이번 주행 교차로 안전 점수", fontSize = 26.sp, color = Color(0xFFB8C4D2))
                Text("${sm.score}점", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("신호 교차로 ${sm.intersections}곳 · 미리 안내 ${sm.cautions + sm.prepares}번",
                    fontSize = 26.sp, color = Color(0xFFD5D9DE))
                Text("황색·적색 진입 ${sm.redYellowEntries}번 · 급정거 ${sm.hardBrakes}번",
                    fontSize = 26.sp, color = if (sm.redYellowEntries + sm.hardBrakes == 0) Color(0xFF7FD6A4) else Color(0xFFF5B700))
            }
        }
    }

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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("딜레마 제로 · 개발자 보기", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Button(onClick = { devView.value = false }) { Text("← 운전 화면") }
            }
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
                Button(onClick = { testPopup() }) { Text("🧪 팝업 (5초 뒤)") }
                Button(onClick = { testEdgeGlow() }) { Text("🧪 테두리 (5초 뒤)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 서→동으로 달리다 달동사거리에서 좌회전(북쪽)하게 되는 목적지
                Button(onClick = { navi.requestRoute(LEFT_TEST_LAT, LEFT_TEST_LON, "좌회전 테스트") }) { Text("🧭 좌회전 경로 요청") }
                Button(onClick = { navi.cancelRoute() }) { Text("🧭 경로 취소") }
                Button(onClick = { navi.query() }) { Text("🧭 내비 위치 확인") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { toggleRecording() }) {
                    Text(if (recorder.recording) "⏹ 기록 끝" else "⏺ 주행 기록 시작")
                }
                Text(recordingLabel(now), fontSize = 15.sp,
                    color = if (recorder.recording) Color(0xFFC8392F) else Color.Gray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { toggleReplay() }) {
                    Text(if (replay.active) "■ 재생 멈춤" else "▶ 기록 재생")
                }
                if (!replay.active) {
                    Button(onClick = { toggleReplay(useAsset = true) }) { Text("▶ 시연 파일 재생") }
                }
                Text(replayLabel(now), fontSize = 15.sp,
                    color = if (replay.active) Color(0xFFB07800) else Color.Gray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    // 시험용: 지금 속도에서 "주의" 안내가 나간 것처럼 (신호를 기다리지 않고 브레이크 기록 시험)
                    if (speedMps < BrakeLearner.MIN_SPEED_MPS) {
                        log("🦶 안내 흉내는 20km/h 이상에서 눌러 주세요 (지금 %.0fkm/h)".format(speedMps * 3.6))
                    } else {
                        brake.base = profile
                        brakeLog(brake.onAlert(SystemClock.elapsedRealtime(), speedMps)
                            ?: "안내 흉내 (%.0fkm/h) → 브레이크를 밟아 보세요".format(speedMps * 3.6))
                        voice.speak("앞 신호, 속도 줄이세요")
                    }
                }) { Text("🦶 안내 흉내") }
                Button(onClick = { resetBrake() }) { Text("🦶 브레이크 기록 지우기") }
                val b = brakeStats.value
                Text("반응 ${b.reactionSec?.let { "%.2f초".format(it) } ?: "-"} (${b.reactionCount}번) · " +
                        "감속 ${b.decel?.let { "%.2f".format(it) } ?: "-"} (${b.decelCount}번) · 안내 무시 ${b.ignoredCount}번",
                    fontSize = 15.sp, color = Color.Gray)
            }
            // 테두리 권한 상태 (에뮬레이터를 새로 켜면 풀릴 수 있음)
            if (Settings.canDrawOverlays(this@MainActivity)) {
                Text("테두리 권한: 있음 ✓ (내비 위에 화면 테두리로 안내)", fontSize = 15.sp, color = Color(0xFF2E9D5B))
            } else {
                Text("테두리 권한: 없음 → 팝업으로 안내 중. 테두리로 바꾸려면 PowerShell:\n" +
                        "adb shell appops set --user 10 com.hmgapp.yellowlightcoach SYSTEM_ALERT_WINDOW allow",
                    fontSize = 15.sp, color = Color(0xFFB07800))
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
        private const val PARK_REST_MS = 5 * 60_000L // 주차가 이보다 길면 신호 API 쉬기
        private const val ALERT_CHANNEL = "signal_alert"
        private const val ALERT_ID = 1
        private const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        // 좌회전 테스트 목적지: 달동사거리(35.534043, 129.314474)에서 북쪽으로 약 450m
        private const val LEFT_TEST_LAT = 35.5381
        private const val LEFT_TEST_LON = 129.3140
    }
}