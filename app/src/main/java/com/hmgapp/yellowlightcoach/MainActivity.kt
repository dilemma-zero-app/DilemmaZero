package com.hmgapp.yellowlightcoach

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.content.pm.CarPackageManager
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hmgapp.yellowlightcoach.ui.theme.YellowLightCoachTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2단계 실험 화면: Car API를 켜면 주행 중 앱이 차단되는 원인 찾기
 *
 * 버튼으로 Car API를 한 단계씩 켜고, 매번 기어 P → D로 바꿔서
 * 앱 화면이 계속 보이는지 확인한다.
 *   ① Car 연결 (+ 시스템이 우리 앱을 "주행 중 안전한 앱(DO)"으로 보는지 확인)
 *   ② 속도 구독 시작
 *   ③ 속도 구독 중지
 *   ④ Car 연결 해제
 * 차단되면 화면이 안 보이므로, 무슨 일이 있었는지는 아래 "기록"에 남긴다. (P로 돌아오면 확인)
 */
class MainActivity : ComponentActivity() {

    private val tag = "YLC"

    // ---------- Pleos Vehicle SDK ----------
    private lateinit var vehicle: Vehicle

    // ---------- 안드로이드 표준 Car API ----------
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedSubscribed = false

    // ---------- 화면 값 ----------
    private val gearText = mutableStateOf("-")
    private val brakeText = mutableStateOf("-")
    private val steeringText = mutableStateOf("-")
    private val turnSignalText = mutableStateOf("-")
    private val carText = mutableStateOf("연결 안 함")
    private val speedText = mutableStateOf("구독 안 함")
    private val doText = mutableStateOf("-")
    private val uxText = mutableStateOf("-")
    private val logs = mutableStateListOf<String>()

    // ---------- Vehicle SDK 리스너 ----------
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = ui {
            gearText.value = gearLabel(gear)
            log("기어 → ${gearLabel(gear)}")
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
            ui { steeringText.value = angle?.let { "%.1f°".format(it) } ?: "-" }
        override fun onFailed(e: Exception) = log("조향각 오류: ${e.message}")
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
            ui { turnSignalText.value = state?.name ?: "-" }
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
        ui {
            uxText.value = uxLabel(r)
            log("주행 제한 변경 → ${uxLabel(r)}")
        }
    }

    private val speedPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("CAR_SPEED 권한 ${if (granted) "허용" else "거부"}")
            if (granted) subscribeSpeed()
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

        setContent {
            YellowLightCoachTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }

    // ① Car 연결 + DO 인정 여부 + 주행 제한 상태 확인
    private fun connectCar() {
        if (car != null) { log("이미 Car 연결됨"); return }
        try {
            val c = Car.createCar(this)
            car = c
            carText.value = "✅ 연결됨"
            log("① Car 연결")

            // 시스템이 우리 화면을 "주행 중 안전한 화면(DO)"으로 보는지
            val pm = c.getCarManager(Car.PACKAGE_SERVICE) as? CarPackageManager
            val isDo = pm?.isActivityDistractionOptimized(packageName, MainActivity::class.java.name)
            doText.value = when (isDo) {
                true -> "✅ 인정됨"
                false -> "❌ 인정 안 됨"
                null -> "확인 불가"
            }
            log("DO 인정 여부: ${doText.value}")

            // 현재 주행 제한 상태 + 변화 구독
            uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.currentCarUxRestrictions?.let { uxText.value = uxLabel(it) }
            uxManager?.registerListener(uxListener)

            propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (e: Exception) {
            log("① 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ② 속도 구독 시작 (권한 없으면 요청 — 반드시 P에서)
    private fun startSpeed() {
        if (car == null) { log("먼저 ① Car 연결을 누르세요"); return }
        if (ContextCompat.checkSelfPermission(this, Car.PERMISSION_SPEED)
            == PackageManager.PERMISSION_GRANTED
        ) subscribeSpeed() else speedPermissionLauncher.launch(Car.PERMISSION_SPEED)
    }

    private fun subscribeSpeed() {
        try {
            val ok = propertyManager?.registerCallback(
                speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL
            ) ?: false
            speedSubscribed = ok
            speedText.value = if (ok) "구독 중…" else "❌ 구독 실패"
            log("② 속도 구독 ${if (ok) "시작" else "실패"}")
        } catch (e: Exception) {
            log("② 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ③ 속도 구독 중지
    private fun stopSpeed() {
        if (!speedSubscribed) { log("속도 구독 중이 아님"); return }
        propertyManager?.unregisterCallback(speedCallback)
        speedSubscribed = false
        speedText.value = "구독 안 함"
        log("③ 속도 구독 중지")
    }

    // ④ Car 연결 해제
    private fun disconnectCar() {
        if (speedSubscribed) stopSpeed()
        uxManager?.unregisterListener()
        car?.disconnect()
        car = null; propertyManager = null; uxManager = null
        carText.value = "연결 안 함"; doText.value = "-"; uxText.value = "-"
        log("④ Car 연결 해제")
    }

    override fun onDestroy() {
        log("앱 종료(onDestroy)")
        vehicle.getGear().unregisterGearSelection(gearListener)
        vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
        vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
        vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
        vehicle.release()
        disconnectCar()
        super.onDestroy()
    }

    // ---------- 도우미 ----------
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
        if (!r.isRequiresDistractionOptimization) "제한 없음"
        else "제한 중 (active=${r.activeRestrictions})"

    // ---------- 화면 ----------
    @Composable
    private fun TestScreen(modifier: Modifier) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("노란불 코치 · Car API 차단 원인 실험", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            DataRow("기어 / 브레이크", "${gearText.value}  /  ${brakeText.value}")
            DataRow("조향각 / 방향지시등", "${steeringText.value}  /  ${turnSignalText.value}")
            DataRow("Car 연결", carText.value)
            DataRow("DO 인정 여부", doText.value)
            DataRow("주행 제한 상태", uxText.value)
            DataRow("차량 속도", speedText.value)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { connectCar() }) { Text("① Car 연결") }
                Button(onClick = { startSpeed() }) { Text("② 속도 구독") }
                Button(onClick = { stopSpeed() }) { Text("③ 구독 중지") }
                Button(onClick = { disconnectCar() }) { Text("④ 연결 해제") }
            }
            Spacer(Modifier.height(4.dp))
            Text("기록 (최신순)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            logs.forEach { Text(it, fontSize = 15.sp) }
        }
    }

    @Composable
    private fun DataRow(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 18.sp, modifier = Modifier.width(220.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}