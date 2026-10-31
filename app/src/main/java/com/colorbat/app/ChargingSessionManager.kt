package com.colorbat.app

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

object ChargingSessionManager {
    var isRunning: Boolean = false
    var isPaused: Boolean = false

    private var sessionStartRealtime: Long = 0L
    private var totalPausedMs: Long = 0L
    private var pauseStartRealtime: Long = 0L
    private var lastSampleRealtime: Long = 0L

    val sessionSeconds: Long
        get() = getElapsedSeconds()

    val powerPoints = mutableListOf<Float>()
    var totalPowerSum: Double = 0.0
    var peakPower: Float = 0f
    var totalMahCharged: Double = 0.0
    var startBatteryLevel: Int = -1
    var currentBatteryLevel: Int = -1

    var onUpdateListener: (() -> Unit)? = null

    fun getElapsedSeconds(): Long {
        if (!isRunning || sessionStartRealtime == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val activePaused = if (isPaused && pauseStartRealtime > 0L) (now - pauseStartRealtime) else 0L
        val elapsedMs = now - sessionStartRealtime - totalPausedMs - activePaused
        return max(0L, elapsedMs / 1000L)
    }

    fun start() {
        val now = SystemClock.elapsedRealtime()
        sessionStartRealtime = now
        totalPausedMs = 0L
        pauseStartRealtime = 0L
        lastSampleRealtime = now
        isRunning = true
        isPaused = false
        onUpdateListener?.invoke()
    }

    fun pause() {
        if (isRunning && !isPaused) {
            isPaused = true
            pauseStartRealtime = SystemClock.elapsedRealtime()
            onUpdateListener?.invoke()
        }
    }

    fun resume() {
        if (isRunning && isPaused) {
            isPaused = false
            if (pauseStartRealtime > 0L) {
                totalPausedMs += (SystemClock.elapsedRealtime() - pauseStartRealtime)
                pauseStartRealtime = 0L
            }
            lastSampleRealtime = SystemClock.elapsedRealtime()
            onUpdateListener?.invoke()
        }
    }

    fun getAveragePower(): Double {
        return if (powerPoints.isNotEmpty()) totalPowerSum / powerPoints.size else 0.0
    }

    fun addSample(info: BatteryInfo) {
        if (!isRunning || isPaused) return

        val now = SystemClock.elapsedRealtime()
        val dt = if (lastSampleRealtime > 0L) (now - lastSampleRealtime) / 1000.0 else 1.0
        lastSampleRealtime = now
        val safeDt = dt.coerceIn(0.1, 5.0)

        if (startBatteryLevel == -1 && info.level > 0) {
            startBatteryLevel = info.level
        }
        if (info.level > 0) {
            currentBatteryLevel = info.level
        }

        val absPower = abs(info.powerWatts.toFloat())
        val signedPower = if (info.isCharging) absPower else -absPower

        powerPoints.add(signedPower)
        totalPowerSum += signedPower

        if (info.isCharging) {
            if (absPower > peakPower) {
                peakPower = absPower
            }
        } else if (peakPower == 0f) {
            peakPower = absPower
        }

        // Tính dòng điện hiệu dụng thực tế (mA) bất kể chiều quy ước kernel trả về
        val rawAbsCurrent = abs(info.currentMa)
        val effCurrentMa = if (rawAbsCurrent > 50) {
            rawAbsCurrent
        } else if (info.voltageMv > 0) {
            ((absPower * 1_000_000.0) / info.voltageMv).toInt()
        } else {
            0
        }

        // Khi sạc thì nạp thêm (+mAh), khi xả pin thì trừ dung lượng (-mAh)
        if (info.isCharging) {
            totalMahCharged += (effCurrentMa * safeDt / 3600.0)
        } else {
            totalMahCharged -= (effCurrentMa * safeDt / 3600.0)
        }

        onUpdateListener?.invoke()
    }

    fun reset() {
        isRunning = false
        isPaused = false
        sessionStartRealtime = 0L
        totalPausedMs = 0L
        pauseStartRealtime = 0L
        lastSampleRealtime = 0L
        powerPoints.clear()
        totalPowerSum = 0.0
        peakPower = 0f
        totalMahCharged = 0.0
        startBatteryLevel = -1
        currentBatteryLevel = -1
        onUpdateListener?.invoke()
    }
}
