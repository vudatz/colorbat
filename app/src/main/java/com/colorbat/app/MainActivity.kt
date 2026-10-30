package com.colorbat.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvChargingStatusBadge: TextView
    private lateinit var tvLivePowerWatts: TextView
    private lateinit var pbBatteryLevel: ProgressBar
    private lateinit var tvCurrentMa: TextView
    private lateinit var tvVoltageV: TextView
    private lateinit var tvTemperature: TextView

    private lateinit var tvCycleCount: TextView
    private lateinit var tvDesignCapacity: TextView
    private lateinit var tvTypicalCapacity: TextView
    private lateinit var tvChargeFull: TextView
    private lateinit var tvHealthPercent: TextView
    private lateinit var tvChargeCounter: TextView

    private lateinit var tvSysfsSource: TextView
    private lateinit var tvTech: TextView
    private lateinit var tvChargeType: TextView

    private lateinit var switchAutoRefresh: SwitchMaterial
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        startMonitoringLoop()
    }

    private fun initViews() {
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvChargingStatusBadge = findViewById(R.id.tvChargingStatusBadge)
        tvLivePowerWatts = findViewById(R.id.tvLivePowerWatts)
        pbBatteryLevel = findViewById(R.id.pbBatteryLevel)
        tvCurrentMa = findViewById(R.id.tvCurrentMa)
        tvVoltageV = findViewById(R.id.tvVoltageV)
        tvTemperature = findViewById(R.id.tvTemperature)

        tvCycleCount = findViewById(R.id.tvCycleCount)
        tvDesignCapacity = findViewById(R.id.tvDesignCapacity)
        tvTypicalCapacity = findViewById(R.id.tvTypicalCapacity)
        tvChargeFull = findViewById(R.id.tvChargeFull)
        tvHealthPercent = findViewById(R.id.tvHealthPercent)
        tvChargeCounter = findViewById(R.id.tvChargeCounter)

        tvSysfsSource = findViewById(R.id.tvSysfsSource)
        tvTech = findViewById(R.id.tvTech)
        tvChargeType = findViewById(R.id.tvChargeType)

        switchAutoRefresh = findViewById(R.id.switchAutoRefresh)
        btnRefresh = findViewById(R.id.btnRefresh)
    }

    private fun setupListeners() {
        btnRefresh.setOnClickListener {
            updateBatteryUi()
        }
    }

    private fun startMonitoringLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val shouldRefresh = withContext(Dispatchers.Main) {
                    switchAutoRefresh.isChecked
                }
                if (shouldRefresh) {
                    val info = BatteryReader.getBatteryInfo(applicationContext)
                    withContext(Dispatchers.Main) {
                        applyBatteryInfo(info)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun updateBatteryUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = BatteryReader.getBatteryInfo(applicationContext)
            withContext(Dispatchers.Main) {
                applyBatteryInfo(info)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyBatteryInfo(info: BatteryInfo) {
        // Cập nhật mức pin
        tvBatteryPercent.text = "${info.level}%"
        pbBatteryLevel.progress = info.level

        // Màu sắc và trạng thái sạc
        val chargingColor = ContextCompat.getColor(this, R.color.charging)
        val dischargingColor = ContextCompat.getColor(this, R.color.discharging)

        if (info.isCharging) {
            val typeStr = if (info.chargeType.isNotEmpty()) " (${info.chargeType})" else ""
            tvChargingStatusBadge.text = "ĐANG SẠC$typeStr"
            tvChargingStatusBadge.setTextColor(chargingColor)
            tvLivePowerWatts.setTextColor(chargingColor)
            tvLivePowerWatts.text = String.format(Locale.US, "+ %.2f W", info.powerWatts)
            tvCurrentMa.setTextColor(chargingColor)
            tvCurrentMa.text = String.format(Locale.US, "+ %d mA", abs(info.currentMa))
        } else {
            tvChargingStatusBadge.text = "ĐANG XẢ PIN"
            tvChargingStatusBadge.setTextColor(dischargingColor)
            tvLivePowerWatts.setTextColor(dischargingColor)
            tvLivePowerWatts.text = String.format(Locale.US, "- %.2f W", info.powerWatts)
            tvCurrentMa.setTextColor(dischargingColor)
            tvCurrentMa.text = String.format(Locale.US, "- %d mA", abs(info.currentMa))
        }

        // Điện áp & nhiệt độ
        tvVoltageV.text = String.format(Locale.US, "%.3f V", info.voltageMv / 1000.0)
        tvTemperature.text = String.format(Locale.US, "%.1f °C", info.temperatureC)

        // Sức khỏe pin & chu kỳ sạc
        tvCycleCount.text = "${info.cycleCount} lần"
        tvDesignCapacity.text = String.format(Locale.US, "%,d mAh", info.designCapacityMah)
        tvTypicalCapacity.text = String.format(Locale.US, "%,d mAh", info.typicalCapacityMah)
        tvChargeFull.text = String.format(Locale.US, "%,d mAh", info.chargeFullMah)
        tvHealthPercent.text = String.format(Locale.US, "%.1f %%", info.healthPercent)
        tvChargeCounter.text = String.format(Locale.US, "%,d mAh", info.chargeCounterMah)

        // Sysfs & Hardware
        tvSysfsSource.text = if (info.isSysfsDirect) {
            "Phần cứng: /sys/class/power_supply/battery (Trực tiếp)"
        } else {
            "Nguồn: Android BatteryManager API"
        }
        tvTech.text = "${info.technology} (Silicon-Carbon Glacier)"
        tvChargeType.text = if (info.chargeType.isNotEmpty()) info.chargeType else "Standard"
    }
}
