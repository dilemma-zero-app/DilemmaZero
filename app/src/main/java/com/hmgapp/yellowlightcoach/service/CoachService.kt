package com.hmgapp.yellowlightcoach.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 백그라운드 유지 서비스 (10/29, 방법 A)
 *
 * 운전자는 순정 내비를 보고, 딜레마 제로는 화면 뒤에서 계속 판정해야 한다.
 * 안드로이드는 화면에 없는 앱의 위치 수신·작업을 제한하므로,
 * "작동 중" 알림을 단 포그라운드 서비스(위치 유형)로 앱이 계속 살아 있게 한다.
 *
 * 판정 자체는 지금은 MainActivity가 한다 (화면 뒤에서도 루프가 계속 돎).
 * 이 서비스는 "앱을 살려 두는 역할"만 한다.
 */
class CoachService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "작동 상태", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("딜레마 제로 작동 중")
            .setContentText("앞 신호를 지켜보고 있어요")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "coach_running"
        private const val ID = 2

        fun start(context: Context) =
            context.startForegroundService(Intent(context, CoachService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, CoachService::class.java))
    }
}