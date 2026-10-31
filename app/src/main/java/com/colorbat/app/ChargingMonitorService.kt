package com.colorbat.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.max

class ChargingMonitorService : Service() {

    private val CHANNEL_LIVE_ALERT = "colorbat_live_alert_channel"
    private val CHANNEL_NORMAL = "colorbat_charging_session"
    private val NOTIFICATION_ID = 2001

    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Icon nhận diện thương hiệu ColorBat mới cho Live Alert / Fluid Cloud
    private val capsuleIconBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ic_capsule_icon)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

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
                ChargingSessionManager.pause()
                updateNotification()
            }
            ACTION_RESUME -> {
                ChargingSessionManager.resume()
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

        ChargingSessionManager.start()

        job = scope.launch {
            while (isActive && ChargingSessionManager.isRunning) {
                val tickStart = SystemClock.elapsedRealtime()

                if (!ChargingSessionManager.isPaused) {
                    val info = BatteryReader.getBatteryInfo(applicationContext)
                    ChargingSessionManager.addSample(info)
                    updateNotification()
                }

                // Tự động bù trừ thời gian đọc I/O thực tế để mỗi nhịp đếm chuẩn xác 1000ms, không bị lệch giây
                val elapsed = SystemClock.elapsedRealtime() - tickStart
                val sleepMs = max(100L, 1000L - elapsed)
                delay(sleepMs)
            }
        }
    }

    private fun stopMonitoring() {
        job?.cancel()
        job = null
    }

    private fun buildNotification(): Notification {
        val prefs = applicationContext.getSharedPreferences("colorbat_prefs", Context.MODE_PRIVATE)
        val isLiveAlert = prefs.getBoolean("live_alert_enabled", true)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Tính toán đồng hồ đếm giờ chuẩn từng giây theo phần cứng
        val totalSecs = ChargingSessionManager.sessionSeconds
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        val timeStr = String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)

        val livePower = ChargingSessionManager.powerPoints.lastOrNull() ?: 0f
        val maxPower = ChargingSessionManager.peakPower

        // Định dạng thống nhất theo yêu cầu:
        // (đồng hồ đếm giờ) | (Công suất hiện tại) | Max: (CS tối đa)
        val statLine = String.format(
            Locale.US,
            "%s | %.1f W | Max: %.1f W",
            timeStr,
            livePower,
            maxPower
        )

        val channelId = if (isLiveAlert) CHANNEL_LIVE_ALERT else CHANNEL_NORMAL
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ColorBat")
            .setContentText(statLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)

        if (isLiveAlert) {
            // Chuẩn Promoted Ongoing ColorOS Fluid Cloud
            // Đưa icon thương hiệu mới lên Live Alert Capsule
            builder.setSmallIcon(R.drawable.ic_capsule_icon)
            builder.setLargeIcon(capsuleIconBitmap)
            builder.setSubText(null) // Loại bỏ chữ Fluid Cloud

            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }

            // Gắn AOSP Live Notification & ColorOS promoted ongoing tag
            val shortText = String.format(Locale.US, "%.1fW", livePower)
            val extras = android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putCharSequence("android.shortCriticalText", shortText)
                putString("android.substName", "ColorBat")
                putBoolean("android.showSmallIcon", false)
            }
            builder.addExtras(extras)

            // Tiến trình phần trăm sạc
            val currentLvl = if (ChargingSessionManager.currentBatteryLevel > 0) ChargingSessionManager.currentBatteryLevel else 100
            builder.setProgress(100, currentLvl, false)

            // Sửa icon nút bấm: Khi Tạm dừng -> Hiện icon Tiếp tục (Play), Khi Tiếp tục -> Hiện icon Tạm dừng (Pause)
            val toggleAction = if (ChargingSessionManager.isPaused) ACTION_RESUME else ACTION_PAUSE
            val toggleLabel = if (ChargingSessionManager.isPaused) "Tiếp tục" else "Tạm dừng"
            val toggleIcon = if (ChargingSessionManager.isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            val toggleIntent = Intent(this, ChargingMonitorService::class.java).apply {
                action = toggleAction
            }
            val togglePending = PendingIntent.getService(
                this, 101, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.addAction(toggleIcon, toggleLabel, togglePending)
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher)
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val liveAlertChannel = NotificationChannel(
                CHANNEL_LIVE_ALERT,
                "Cảnh Báo Trực Tiếp (Fluid Cloud)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Hiển thị viên thuốc sạc tương tác (ColorOS Fluid Cloud Capsule)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(liveAlertChannel)

            val normalChannel = NotificationChannel(
                CHANNEL_NORMAL,
                "Phiên Đo Sạc Tiêu Chuẩn",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi công suất và thời gian sạc thời gian thực trong nền"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(normalChannel)
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
