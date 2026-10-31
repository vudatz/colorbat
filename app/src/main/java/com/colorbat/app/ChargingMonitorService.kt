package com.colorbat.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

class ChargingMonitorService : Service() {

    private val CHANNEL_ID = "colorbat_charging_session"
    private val NOTIFICATION_ID = 2001

    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColorBat::ChargingSessionWakeLock").apply {
            acquire(6 * 3600 * 1000L) // Giữ wake lock để đo liên tục kể cả khi tắt màn hình
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification())
                    }
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                startMonitoring()
            }
            ACTION_PAUSE -> {
                ChargingSessionManager.isPaused = true
                updateNotification()
            }
            ACTION_RESUME -> {
                ChargingSessionManager.isPaused = false
                updateNotification()
            }
            ACTION_STOP -> {
                stopMonitoring()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        if (job != null && job?.isActive == true) return

        ChargingSessionManager.isRunning = true
        ChargingSessionManager.isPaused = false

        job = scope.launch {
            var counter = 0
            while (isActive && ChargingSessionManager.isRunning) {
                if (!ChargingSessionManager.isPaused) {
                    ChargingSessionManager.sessionSeconds++

                    val info = BatteryReader.getBatteryInfo(applicationContext)
                    ChargingSessionManager.addSample(
                        info.powerWatts.toFloat(),
                        info.currentMa,
                        info.level
                    )

                    counter++
                    if (counter % 2 == 0) {
                        updateNotification()
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        ChargingSessionManager.isRunning = false
        job?.cancel()
        job = null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val hours = ChargingSessionManager.sessionSeconds / 3600
        val mins = (ChargingSessionManager.sessionSeconds % 3600) / 60
        val secs = ChargingSessionManager.sessionSeconds % 60
        val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        val statusText = if (ChargingSessionManager.isPaused) "Tạm dừng" else "Đang đo"
        val content = String.format(
            Locale.US,
            "[%s] TG: %s | TB: %.1f W | Đỉnh: %.1f W | +%.0f mAh",
            statusText,
            timeStr,
            ChargingSessionManager.getAveragePower(),
            ChargingSessionManager.peakPower,
            ChargingSessionManager.totalMahCharged
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ColorBat - Phiên đo sạc")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phiên Đo Sạc ColorBat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi công suất và thời gian sạc thời gian thực trong nền"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        scope.cancel()
    }

    companion object {
        const val ACTION_START = "com.colorbat.app.ACTION_START"
        const val ACTION_PAUSE = "com.colorbat.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.colorbat.app.ACTION_RESUME"
        const val ACTION_STOP = "com.colorbat.app.ACTION_STOP"
    }
}
