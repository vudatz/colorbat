package com.colorbat.app

object ChargingSessionManager {
    var isRunning: Boolean = false
    var isPaused: Boolean = false
    var sessionSeconds: Long = 0L

    val powerPoints = mutableListOf<Float>()
    var totalPowerSum: Double = 0.0
    var peakPower: Float = 0f
    var totalMahCharged: Double = 0.0
    var startBatteryLevel: Int = -1
    var currentBatteryLevel: Int = -1

    var onUpdateListener: (() -> Unit)? = null

    fun getAveragePower(): Double {
        return if (powerPoints.isNotEmpty()) totalPowerSum / powerPoints.size else 0.0
    }

    fun addSample(powerW: Float, currentMa: Int, level: Int) {
        if (startBatteryLevel == -1 && level > 0) {
            startBatteryLevel = level
        }
        if (level > 0) {
            currentBatteryLevel = level
        }

        powerPoints.add(powerW)
        totalPowerSum += powerW
        if (powerW > peakPower) {
            peakPower = powerW
        }
        if (currentMa > 0) {
            totalMahCharged += (currentMa / 3600.0)
        }

        onUpdateListener?.invoke()
    }

    fun reset() {
        isRunning = false
        isPaused = false
        sessionSeconds = 0L
        powerPoints.clear()
        totalPowerSum = 0.0
        peakPower = 0f
        totalMahCharged = 0.0
        startBatteryLevel = -1
        currentBatteryLevel = -1
        onUpdateListener?.invoke()
    }
}
