package com.colorbat.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.io.File
import kotlin.math.abs

data class BatteryInfo(
    val level: Int,
    val statusText: String,
    val isCharging: Boolean,
    val currentMa: Int,
    val voltageMv: Int,
    val powerWatts: Double,
    val temperatureC: Double,
    val cycleCount: Int,
    val designCapacityMah: Int,
    val typicalCapacityMah: Int,
    val chargeFullMah: Int,
    val chargeCounterMah: Int,
    val healthPercent: Double,
    val technology: String,
    val chargeType: String,
    val voltageOcvMv: Int,
    val isSysfsDirect: Boolean
)

object BatteryReader {

    private const val SYSFS_PATH = "/sys/class/power_supply/battery"

    private fun readSysfsString(fileName: String): String? {
        return try {
            val file = File(SYSFS_PATH, fileName)
            if (file.exists() && file.canRead()) {
                file.readText().trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readSysfsInt(fileName: String): Int? {
        val str = readSysfsString(fileName) ?: return null
        return str.toIntOrNull()
    }

    fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 1. Phân trăm pin
        val sysfsCapacity = readSysfsInt("capacity")
        val level = sysfsCapacity ?: batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0

        // 2. Trạng thái sạc
        val sysfsStatus = readSysfsString("status") // "Charging", "Discharging", "Full"
        val bmStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = sysfsStatus?.equals("Charging", ignoreCase = true)
            ?: (bmStatus == BatteryManager.BATTERY_STATUS_CHARGING || bmStatus == BatteryManager.BATTERY_STATUS_FULL)
        val statusText = when {
            sysfsStatus != null -> sysfsStatus
            bmStatus == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            bmStatus == BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            bmStatus == BatteryManager.BATTERY_STATUS_FULL -> "Full"
            bmStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        // 3. Điện áp (Voltage mV)
        val sysfsVoltageNow = readSysfsInt("voltage_now")
        val voltageMv = when {
            sysfsVoltageNow != null -> if (sysfsVoltageNow > 100000) sysfsVoltageNow / 1000 else sysfsVoltageNow
            else -> batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000
        }

        // 4. Dòng điện (Current mA)
        // Trên chip Dimensity / OPPO, current_now thường là mA hoặc microAmps
        val sysfsCurrentNow = readSysfsInt("current_now")
        val rawCurrent = sysfsCurrentNow ?: batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = when {
            abs(rawCurrent) > 10000 -> rawCurrent / 1000 // nếu là uA
            else -> rawCurrent
        }

        // 5. Công suất (Watts) = (V * |I|) / 1,000,000
        val powerWatts = (voltageMv.toDouble() * abs(currentMa).toDouble()) / 1_000_000.0

        // 6. Nhiệt độ (°C)
        val sysfsTemp = readSysfsInt("temp")
        val temperatureC = when {
            sysfsTemp != null -> sysfsTemp.toDouble() / 10.0
            else -> {
                val tempExtra = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                tempExtra.toDouble() / 10.0
            }
        }

        // 7. Số chu kỳ sạc (Cycle Count)
        var cycleCount = readSysfsInt("cycle_count") ?: -1
        if (cycleCount == -1 && Build.VERSION.SDK_INT >= 34) {
            try {
                cycleCount = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CYCLE_COUNT)
            } catch (e: Exception) {}
        }
        if (cycleCount == -1) cycleCount = 0

        // 8. Dung lượng thiết kế & dung lượng sạc đầy
        val sysfsDesign = readSysfsInt("charge_full_design")
        val designCapacityMah = when {
            sysfsDesign != null && sysfsDesign > 10000 -> sysfsDesign / 1000
            sysfsDesign != null -> sysfsDesign
            else -> 5520 // Rated danh định chuẩn của OPPO Find X8
        }
        val typicalCapacityMah = 5630 // Tiêu chuẩn typical công bố

        val sysfsFull = readSysfsInt("charge_full")
        val chargeFullMah = when {
            sysfsFull != null && sysfsFull > 10000 -> sysfsFull / 1000
            sysfsFull != null -> sysfsFull
            else -> designCapacityMah
        }

        // 9. Điện tích tích lũy tức thời (Charge counter)
        val sysfsCounter = readSysfsInt("charge_counter")
        val rawCounter = sysfsCounter ?: batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeCounterMah = if (rawCounter > 10000) rawCounter / 1000 else rawCounter

        // 10. Sức khỏe pin SOH (%)
        val healthPercent = if (designCapacityMah > 0) {
            (chargeFullMah.toDouble() / designCapacityMah.toDouble()) * 100.0
        } else 100.0

        // 11. Các thông số phụ khác
        val tech = readSysfsString("technology") ?: "Li-ion"
        val chargeType = readSysfsString("charge_type") ?: if (isCharging) "Fast" else "Standard"
        val voltageOcvRaw = readSysfsInt("voltage_ocv") ?: 0
        val voltageOcvMv = if (voltageOcvRaw > 100000) voltageOcvRaw / 1000 else voltageOcvRaw

        return BatteryInfo(
            level = level,
            statusText = statusText,
            isCharging = isCharging,
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = powerWatts,
            temperatureC = temperatureC,
            cycleCount = cycleCount,
            designCapacityMah = designCapacityMah,
            typicalCapacityMah = typicalCapacityMah,
            chargeFullMah = chargeFullMah,
            chargeCounterMah = chargeCounterMah,
            healthPercent = healthPercent,
            technology = tech,
            chargeType = chargeType,
            voltageOcvMv = voltageOcvMv,
            isSysfsDirect = (sysfsCapacity != null || sysfsCurrentNow != null)
        )
    }
}
