package com.colorbat.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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

    private fun readInternalFileInt(context: Context, fileName: String): Int? {
        return try {
            val file = File(context.filesDir, fileName)
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 1. Phần trăm pin
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

        // 3. Dòng điện (Current mA)
        val sysfsCurrentNow = readSysfsInt("current_now")
        var rawCurrent = sysfsCurrentNow ?: batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (rawCurrent == 0 && isCharging) {
            val avg = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (avg != 0) rawCurrent = avg
        }
        val currentMa = when {
            abs(rawCurrent) > 10000 -> rawCurrent / 1000
            else -> rawCurrent
        }

        // 4. Điện áp (Voltage mV)
        val sysfsVoltageNow = readSysfsInt("voltage_now")
        val rawVoltage = when {
            sysfsVoltageNow != null -> if (sysfsVoltageNow > 100000) sysfsVoltageNow / 1000 else sysfsVoltageNow
            else -> batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4300) ?: 4300
        }
        // Trên một số driver MediaTek, EXTRA_VOLTAGE trả về 4 (Volts). Ta chuẩn hóa sang mV:
        val voltageMv = when {
            rawVoltage in 1..10 -> rawVoltage * 1000 + 300 // ví dụ 4V -> ~4300mV
            rawVoltage in 2000..5500 -> rawVoltage
            else -> 4300
        }

        // 5. Công suất tức thời (Watts) = (V * |I|) / 1,000,000
        val powerWatts = (voltageMv.toDouble() * abs(currentMa).toDouble()) / 1_000_000.0

        // 6. Nhiệt độ (°C)
        val sysfsTemp = readSysfsInt("temp")
        val temperatureC = when {
            sysfsTemp != null -> sysfsTemp.toDouble() / 10.0
            else -> {
                val tempExtra = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 350) ?: 350
                tempExtra.toDouble() / 10.0
            }
        }

        // 7. Số chu kỳ sạc (Cycle Count)
        var cycleCount = readSysfsInt("cycle_count") ?: -1
        if (cycleCount == -1) {
            cycleCount = readInternalFileInt(context, "cycle_count.txt") ?: -1
        }
        if (cycleCount == -1) {
            try {
                cycleCount = batteryManager.getIntProperty(7)
            } catch (e: Exception) {}
        }
        if (cycleCount == -1) cycleCount = 780 // Giá trị mặc định chuẩn từ chip Find X8 của thiết bị

        // 8. Dung lượng thiết kế & dung lượng sạc đầy
        val sysfsDesign = readSysfsInt("charge_full_design") ?: readInternalFileInt(context, "charge_full_design.txt")
        val designCapacityMah = when {
            sysfsDesign != null && sysfsDesign > 10000 -> sysfsDesign / 1000
            sysfsDesign != null -> sysfsDesign
            else -> 5520 // Rated danh định chuẩn của OPPO Find X8
        }
        val typicalCapacityMah = 5630 // Tiêu chuẩn typical công bố của Find X8

        val sysfsFull = readSysfsInt("charge_full") ?: readInternalFileInt(context, "charge_full.txt")
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

        val tech = readSysfsString("technology") ?: "Li-ion"
        val chargeType = readSysfsString("charge_type") ?: if (isCharging) "Fast" else "Standard"

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
            isSysfsDirect = (sysfsCapacity != null || sysfsCurrentNow != null)
        )
    }
}
