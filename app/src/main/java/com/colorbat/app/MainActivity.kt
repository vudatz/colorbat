package com.colorbat.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var topAppBar: MaterialToolbar

    // Card 1: Overview
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvChargingStatusBadge: TextView
    private lateinit var tvLivePowerWatts: TextView
    private lateinit var pbBatteryLevel: ProgressBar
    private lateinit var tvCurrentMa: TextView
    private lateinit var tvVoltageV: TextView
    private lateinit var tvTemperature: TextView

    // Card 2: Session & Chart
    private lateinit var tvSessionBadge: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var btnToggleSession: Button
    private lateinit var layoutChartContainer: View
    private lateinit var powerChartView: PowerChartView
    private var isSessionActive = false
    private var sessionSeconds = 0L

    // Card 3: Detailed Info
    private lateinit var tvDeviceName: TextView
    private lateinit var tvHealthPercent: TextView
    private lateinit var tvCycleCount: TextView
    private lateinit var tvCycleAlertWarning: TextView
    private lateinit var tvDesignCapacity: TextView
    private lateinit var tvTypicalCapacity: TextView
    private lateinit var tvChargeCounter: TextView
    private lateinit var rowBatteryReplaced: View
    private lateinit var tvReplacedDate: TextView
    private lateinit var tvTech: TextView
    private lateinit var tvChargeType: TextView

    // Footer
    private lateinit var tvFooter: TextView

    private val PREFS_NAME = "colorbat_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        startRealtimeMonitoring()
    }

    private fun initViews() {
        topAppBar = findViewById(R.id.topAppBar)

        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvChargingStatusBadge = findViewById(R.id.tvChargingStatusBadge)
        tvLivePowerWatts = findViewById(R.id.tvLivePowerWatts)
        pbBatteryLevel = findViewById(R.id.pbBatteryLevel)
        tvCurrentMa = findViewById(R.id.tvCurrentMa)
        tvVoltageV = findViewById(R.id.tvVoltageV)
        tvTemperature = findViewById(R.id.tvTemperature)

        tvSessionBadge = findViewById(R.id.tvSessionBadge)
        tvSessionTimer = findViewById(R.id.tvSessionTimer)
        btnToggleSession = findViewById(R.id.btnToggleSession)
        layoutChartContainer = findViewById(R.id.layoutChartContainer)
        powerChartView = findViewById(R.id.powerChartView)

        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvHealthPercent = findViewById(R.id.tvHealthPercent)
        tvCycleCount = findViewById(R.id.tvCycleCount)
        tvCycleAlertWarning = findViewById(R.id.tvCycleAlertWarning)
        tvDesignCapacity = findViewById(R.id.tvDesignCapacity)
        tvTypicalCapacity = findViewById(R.id.tvTypicalCapacity)
        tvChargeCounter = findViewById(R.id.tvChargeCounter)
        rowBatteryReplaced = findViewById(R.id.rowBatteryReplaced)
        tvReplacedDate = findViewById(R.id.tvReplacedDate)
        tvTech = findViewById(R.id.tvTech)
        tvChargeType = findViewById(R.id.tvChargeType)

        tvFooter = findViewById(R.id.tvFooter)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        tvFooter.text = "Made with ❤️ by @vudatz - ColorBat v$versionName"
    }

    private fun setupListeners() {
        topAppBar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_settings) {
                showSettingsDialog()
                true
            } else {
                false
            }
        }

        btnToggleSession.setOnClickListener {
            isSessionActive = !isSessionActive
            if (isSessionActive) {
                btnToggleSession.text = "Dừng đo"
                btnToggleSession.setBackgroundColor(ContextCompat.getColor(this, R.color.error))
                layoutChartContainer.visibility = View.VISIBLE
                tvSessionBadge.text = "ĐANG ĐO..."
                tvSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary))
                tvSessionBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_light))
            } else {
                btnToggleSession.text = "Bắt đầu đo"
                btnToggleSession.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                tvSessionBadge.text = "SẴN SÀNG"
                tvSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                tvSessionBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
                layoutChartContainer.visibility = View.GONE
            }
        }
    }

    private fun startRealtimeMonitoring() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val info = BatteryReader.getBatteryInfo(applicationContext)

                if (isSessionActive) {
                    sessionSeconds++
                }

                withContext(Dispatchers.Main) {
                    applyBatteryInfo(info)

                    if (isSessionActive) {
                        val hours = sessionSeconds / 3600
                        val minutes = (sessionSeconds % 3600) / 60
                        val secs = sessionSeconds % 60
                        tvSessionTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)

                        // Thêm điểm công suất vào biểu đồ
                        powerChartView.addPoint(info.powerWatts.toFloat())
                    }
                }
                delay(1000)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyBatteryInfo(info: BatteryInfo) {
        tvDeviceName.text = info.deviceName
        tvBatteryPercent.text = "${info.level}%"
        pbBatteryLevel.progress = info.level

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

        tvVoltageV.text = String.format(Locale.US, "%.3f V", info.voltageMv / 1000.0)
        tvTemperature.text = String.format(Locale.US, "%.1f °C", info.temperatureC)

        // Sức khỏe pin SOH (89.0%)
        tvHealthPercent.text = String.format(Locale.US, "%.1f %%", info.healthPercent)
        tvCycleCount.text = "${info.cycleCount} lần"
        tvDesignCapacity.text = String.format(Locale.US, "%,d mAh", info.designCapacityMah)
        tvTypicalCapacity.text = String.format(Locale.US, "%,d mAh", info.typicalCapacityMah)
        tvChargeCounter.text = String.format(Locale.US, "%,d mAh", info.chargeCounterMah)
        tvTech.text = "${info.technology} (Silicon-Carbon Glacier)"
        tvChargeType.text = if (info.chargeType.isNotEmpty()) info.chargeType else "Fast"

        // Đọc cài đặt chu kỳ cảnh báo & thay pin
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAlertEnabled = prefs.getBoolean("cycle_alert_enabled", false)
        val cycleLimit = prefs.getInt("cycle_alert_limit", 1000)

        if (isAlertEnabled && info.cycleCount >= cycleLimit) {
            tvCycleAlertWarning.visibility = View.VISIBLE
            tvCycleAlertWarning.text = "⚠️ Cảnh báo: Đã đạt ngưỡng chu kỳ sạc (${info.cycleCount}/$cycleLimit lần)!"
        } else {
            tvCycleAlertWarning.visibility = View.GONE
        }

        val isReplaced = prefs.getBoolean("battery_replaced", false)
        val replaceDate = prefs.getString("replace_date", "") ?: ""

        if (isReplaced && replaceDate.isNotEmpty()) {
            rowBatteryReplaced.visibility = View.VISIBLE
            tvReplacedDate.text = replaceDate
        } else {
            rowBatteryReplaced.visibility = View.GONE
        }
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val switchCycleAlert = dialog.findViewById<SwitchMaterial>(R.id.switchCycleAlert)
        val etCycleLimit = dialog.findViewById<TextInputEditText>(R.id.etCycleLimit)
        val etCustomCapacity = dialog.findViewById<TextInputEditText>(R.id.etCustomCapacity)
        val cbBatteryReplaced = dialog.findViewById<MaterialCheckBox>(R.id.cbBatteryReplaced)
        val etReplaceDate = dialog.findViewById<TextInputEditText>(R.id.etReplaceDate)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveSettings)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelSettings)

        // Load existing values
        switchCycleAlert.isChecked = prefs.getBoolean("cycle_alert_enabled", false)
        etCycleLimit.setText(prefs.getInt("cycle_alert_limit", 1000).toString())
        etCustomCapacity.setText(prefs.getInt("custom_capacity", 5520).toString())
        cbBatteryReplaced.isChecked = prefs.getBoolean("battery_replaced", false)
        etReplaceDate.setText(prefs.getString("replace_date", ""))

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val alertEnabled = switchCycleAlert.isChecked
            val cycleLimit = etCycleLimit.text.toString().toIntOrNull() ?: 1000
            val customCap = etCustomCapacity.text.toString().toIntOrNull() ?: 5520
            val isReplaced = cbBatteryReplaced.isChecked
            val replaceDate = etReplaceDate.text.toString().trim()

            prefs.edit()
                .putBoolean("cycle_alert_enabled", alertEnabled)
                .putInt("cycle_alert_limit", cycleLimit)
                .putInt("custom_capacity", customCap)
                .putBoolean("battery_replaced", isReplaced)
                .putString("replace_date", replaceDate)
                .apply()

            // Ghi custom_capacity ra file nội bộ để BatteryReader đọc
            try {
                File(filesDir, "custom_capacity.txt").writeText(customCap.toString())
            } catch (e: Exception) {}

            Toast.makeText(this, "Đã lưu cài đặt thành công!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}
