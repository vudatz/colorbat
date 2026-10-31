package com.colorbat.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var topAppBar: MaterialToolbar

    // Card 1: Tổng quan
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvChargingStatusBadge: TextView
    private lateinit var tvLivePowerWatts: TextView
    private lateinit var pbBatteryLevel: ProgressBar
    private lateinit var tvCurrentMa: TextView
    private lateinit var tvVoltageV: TextView
    private lateinit var tvTemperature: TextView

    // Card 2: Biểu đồ sạc (Bấm title để thu gọn/mở rộng, không có nút dừng)
    private lateinit var layoutChartHeader: View
    private lateinit var tvChartToggleArrow: TextView
    private var isChartExpanded = true
    private lateinit var tvSessionBadge: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var btnToggleSession: Button
    private lateinit var btnResetSession: Button
    private lateinit var layoutChartContainer: View
    private lateinit var tvSessionAvgWatts: TextView
    private lateinit var tvSessionPeakWatts: TextView
    private lateinit var tvSessionChargedMah: TextView
    private lateinit var tvSessionLevelDelta: TextView
    private lateinit var powerChartView: PowerChartView

    // Card 3: Detailed Info
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnGrantShizuku: Button
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
    private val SHIZUKU_REQUEST_CODE = 1001
    private val NOTIF_PERMISSION_REQUEST_CODE = 1002

    private fun showBottomNotice(message: String, isLong: Boolean = false) {
        val duration = if (isLong) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        Snackbar.make(findViewById(android.R.id.content), message, duration).show()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showBottomNotice("Đã cấp quyền Shizuku thành công!")
            } else {
                showBottomNotice("Quyền Shizuku bị từ chối")
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Khi cắm cáp USB hoặc có tín hiệu nguồn, lập tức làm mới thông số
            lifecycleScope.launch(Dispatchers.IO) {
                val info = BatteryReader.getBatteryInfo(applicationContext)
                withContext(Dispatchers.Main) {
                    applyBatteryInfo(info)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupShizuku()
        registerUsbReceiver()
        checkNotificationAndLiveAlertPermissions()
        checkFirstLaunchAlert()
        syncSessionUi()
        startRealtimeMonitoring()

        // Tự động kiểm tra bản cập nhật OTA trong nền khi mở ứng dụng
        OtaUpdateManager.checkUpdate(this, isManual = false)
    }

    private fun checkNotificationAndLiveAlertPermissions() {
        // 1. Tự động hiện popup xin quyền thông báo hệ thống (Android 13+ / ColorOS 13-17)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_REQUEST_CODE
                )
                return
            }
        }

        // 2. Tự động kiểm tra nhắc bật Cảnh báo trực tiếp (ColorOS Fluid Cloud Capsule)
        checkLiveAlertPrompt()
    }

    private fun checkLiveAlertPrompt() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val liveAlertPrompted = prefs.getBoolean("live_alert_prompt_v2", false)
        if (!liveAlertPrompted) {
            val isOppo = Build.MANUFACTURER.contains("OPPO", ignoreCase = true) ||
                         Build.MANUFACTURER.contains("REALME", ignoreCase = true) ||
                         Build.MANUFACTURER.contains("ONEPLUS", ignoreCase = true)
            if (isOppo) {
                AlertDialog.Builder(this)
                    .setTitle("Cảnh Báo Trực Tiếp (Fluid Cloud)")
                    .setMessage("Để viên thuốc sạc Dynamic Island hiển thị tự động trên thanh trạng thái ColorOS, hãy đảm bảo mục 'Cảnh báo trực tiếp' trong cài đặt thông báo đã được BẬT.")
                    .setPositiveButton("Mở cài đặt") { _, _ ->
                        prefs.edit().putBoolean("live_alert_prompt_v2", true).apply()
                        openNotificationSettings()
                    }
                    .setNegativeButton("Đã hiểu") { _, _ ->
                        prefs.edit().putBoolean("live_alert_prompt_v2", true).apply()
                    }
                    .show()
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, "colorbat_live_alert_channel")
                }
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (ex: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBottomNotice("Đã cấp quyền thông báo thành công!")
                checkLiveAlertPrompt()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ChargingSessionManager.onUpdateListener = {
            runOnUiThread {
                syncSessionUi()
            }
        }
        syncSessionUi()
    }

    override fun onPause() {
        super.onPause()
        ChargingSessionManager.onUpdateListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction("android.hardware.usb.action.USB_STATE")
        }
        registerReceiver(usbReceiver, filter)
    }

    // 3. Alert thông báo mở app (Đã loại bỏ hoàn toàn biểu tượng tia sét)
    private fun checkFirstLaunchAlert() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alertShown = prefs.getBoolean("alert_dual_cell_v4", false)

        if (!alertShown) {
            AlertDialog.Builder(this)
                .setTitle("Lưu ý")
                .setMessage("Một số thiết bị hiện nay sử dụng pin 2 cell nối tiếp với điện áp tổng gấp đôi (x2) điện áp 1 cell để tối ưu hóa việc sạc nhanh . Kiểm tra kĩ thông tin cell pin của bạn rồi vào mục Cài đặt (icon ⚙️) để bật chế độ 'Pin 2 cell' nhé!")
                .setPositiveButton("Vào Cài đặt") { _, _ ->
                    prefs.edit().putBoolean("alert_dual_cell_v4", true).apply()
                    showSettingsDialog()
                }
                .setNegativeButton("Đã hiểu") { _, _ ->
                    prefs.edit().putBoolean("alert_dual_cell_v4", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun setupShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            // Tuyệt đối không tự động popup xin quyền Shizuku khi mở app
        } catch (e: Exception) {}
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

        // Card 2
        layoutChartHeader = findViewById(R.id.layoutChartHeader)
        tvChartToggleArrow = findViewById(R.id.tvChartToggleArrow)
        tvSessionBadge = findViewById(R.id.tvSessionBadge)
        tvSessionTimer = findViewById(R.id.tvSessionTimer)
        btnToggleSession = findViewById(R.id.btnToggleSession)
        btnResetSession = findViewById(R.id.btnResetSession)
        layoutChartContainer = findViewById(R.id.layoutChartContainer)
        tvSessionAvgWatts = findViewById(R.id.tvSessionAvgWatts)
        tvSessionPeakWatts = findViewById(R.id.tvSessionPeakWatts)
        tvSessionChargedMah = findViewById(R.id.tvSessionChargedMah)
        tvSessionLevelDelta = findViewById(R.id.tvSessionLevelDelta)
        powerChartView = findViewById(R.id.powerChartView)

        // Card 3
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnGrantShizuku = findViewById(R.id.btnGrantShizuku)
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
            "1.2.0"
        }
        tvFooter.text = "Made with ❤️ by @vudatz - ColorBat v$versionName"
    }

    private fun setupListeners() {
        // Cập nhật màu icon trên topAppBar theo chế độ sáng/tối
        val iconColor = ContextCompat.getColor(this, R.color.top_bar_icon)
        topAppBar.menu.findItem(R.id.action_facebook)?.icon?.setTint(iconColor)
        topAppBar.menu.findItem(R.id.action_update)?.icon?.setTint(iconColor)
        topAppBar.menu.findItem(R.id.action_settings)?.icon?.setTint(iconColor)

        topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_facebook -> {
                    try {
                        val fbIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://facebook.com/vudatz"))
                        startActivity(fbIntent)
                    } catch (e: Exception) {
                        showBottomNotice("Không thể mở Facebook")
                    }
                    true
                }
                R.id.action_update -> {
                    OtaUpdateManager.checkUpdate(this, isManual = true)
                    true
                }
                R.id.action_settings -> {
                    showSettingsDialog()
                    true
                }
                else -> false
            }
        }

        btnGrantShizuku.setOnClickListener {
            if (ShizukuHelper.isRunning()) {
                ShizukuHelper.requestPermission(SHIZUKU_REQUEST_CODE)
            } else {
                showBottomNotice("Hãy mở app Shizuku và bấm 'Khởi động' trước!", true)
            }
        }

        // Bấm vào tiêu đề "Biểu đồ sạc" để thu gọn / mở rộng khung biểu đồ
        layoutChartHeader.setOnClickListener {
            isChartExpanded = !isChartExpanded
            syncSessionUi()
        }

        // 4. Bắt đầu đo / Tạm dừng / Tiếp tục (Loại bỏ hoàn toàn nút Dừng đo)
        btnToggleSession.setOnClickListener {
            if (!ChargingSessionManager.isRunning) {
                // Chưa chạy -> Bắt đầu đo mới
                startSessionService(ChargingMonitorService.ACTION_START)
            } else if (ChargingSessionManager.isPaused) {
                // Đang tạm dừng -> Tiếp tục đo
                startSessionService(ChargingMonitorService.ACTION_RESUME)
            } else {
                // Đang đo -> Tạm dừng (hiện nút Đặt lại và nút Tiếp tục)
                startSessionService(ChargingMonitorService.ACTION_PAUSE)
            }
            syncSessionUi()
        }

        // Đặt lại phiên đo trực tiếp trong app (hiển thị thông báo sạch không kèm icon app)
        btnResetSession.setOnClickListener {
            startSessionService(ChargingMonitorService.ACTION_STOP)
            ChargingSessionManager.reset()
            powerChartView.clearData()
            syncSessionUi()
            showBottomNotice("Đã đặt lại phiên đo!")
        }
    }

    private fun startSessionService(action: String) {
        val intent = Intent(this, ChargingMonitorService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun syncSessionUi() {
        val hours = ChargingSessionManager.sessionSeconds / 3600
        val minutes = (ChargingSessionManager.sessionSeconds % 3600) / 60
        val secs = ChargingSessionManager.sessionSeconds % 60
        tvSessionTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)

        // Cập nhật trạng thái mũi tên và ẩn/hiện biểu đồ theo tương tác người dùng
        tvChartToggleArrow.text = if (isChartExpanded) "▲" else "▼"

        if (ChargingSessionManager.isRunning) {
            layoutChartContainer.visibility = if (isChartExpanded) View.VISIBLE else View.GONE

            if (ChargingSessionManager.isPaused) {
                // TẠM DỪNG -> Hiện nút Đặt lại và nút Tiếp tục
                tvSessionBadge.text = "TẠM DỪNG"
                tvSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                tvSessionBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
                btnToggleSession.text = "Tiếp tục"
                btnToggleSession.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                btnResetSession.visibility = View.VISIBLE
            } else {
                // ĐANG ĐO -> Nút Tạm dừng, ẩn nút Đặt lại
                tvSessionBadge.text = "ĐANG ĐO..."
                tvSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary))
                tvSessionBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_light))
                btnToggleSession.text = "Tạm dừng"
                btnToggleSession.setBackgroundColor(ContextCompat.getColor(this, R.color.secondary))
                btnResetSession.visibility = View.GONE
            }
        } else {
            // Chưa đo hoặc đã đặt lại về ban đầu
            layoutChartContainer.visibility = if (isChartExpanded && ChargingSessionManager.powerPoints.isNotEmpty()) View.VISIBLE else View.GONE
            tvSessionBadge.text = "SẴN SÀNG"
            tvSessionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvSessionBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke))
            btnToggleSession.text = "Bắt đầu đo"
            btnToggleSession.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            btnResetSession.visibility = View.GONE
        }

        // Cập nhật thống kê tóm tắt
        val avgWatts = ChargingSessionManager.getAveragePower()
        tvSessionAvgWatts.text = String.format(Locale.US, "%.1f W", avgWatts)
        tvSessionPeakWatts.text = String.format(Locale.US, "%.1f W", ChargingSessionManager.peakPower)
        
        val chargedMah = ChargingSessionManager.totalMahCharged
        tvSessionChargedMah.text = String.format(Locale.US, "%+d mAh", chargedMah.toInt())

        if (ChargingSessionManager.startBatteryLevel > 0 && ChargingSessionManager.currentBatteryLevel > 0) {
            val delta = ChargingSessionManager.currentBatteryLevel - ChargingSessionManager.startBatteryLevel
            val sign = if (delta >= 0) "+" else ""
            tvSessionLevelDelta.text = "${ChargingSessionManager.startBatteryLevel}% → ${ChargingSessionManager.currentBatteryLevel}% ($sign${delta}%)"
        } else {
            tvSessionLevelDelta.text = "--%"
        }

        // Cập nhật biểu đồ toàn session
        if (ChargingSessionManager.powerPoints.isNotEmpty()) {
            powerChartView.setPoints(ChargingSessionManager.powerPoints, avgWatts.toFloat())
        }
    }

    private fun startRealtimeMonitoring() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val info = BatteryReader.getBatteryInfo(applicationContext)

                withContext(Dispatchers.Main) {
                    applyBatteryInfo(info)
                    syncSessionUi()
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

        // Trạng thái kết nối phần cứng / ADB
        if (info.shizukuConnected || info.cycleCount > 0) {
            tvShizukuStatus.text = "✓ Quyền phần cứng: Đã kết nối\n${info.pmicSource}"
            tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            btnGrantShizuku.visibility = View.GONE
        } else {
            tvShizukuStatus.text = "Cắm máy tính có ADB để đồng bộ thông số phần cứng"
            tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantShizuku.visibility = View.GONE
        }

        val chargingColor = ContextCompat.getColor(this, R.color.charging)
        val dischargingColor = ContextCompat.getColor(this, R.color.discharging)
        val absCurrent = abs(info.currentMa)
        val safeWatts = if (info.powerWatts > 300.0 || info.powerWatts < 0.0) 0.0 else info.powerWatts

        if (info.isCharging) {
            tvChargingStatusBadge.text = info.chargeType
            tvChargingStatusBadge.setTextColor(chargingColor)
            tvLivePowerWatts.setTextColor(chargingColor)
            tvLivePowerWatts.text = String.format(Locale.US, "+ %.2f W", safeWatts)
            tvCurrentMa.setTextColor(chargingColor)
            tvCurrentMa.text = String.format(Locale.US, "+ %d mA", absCurrent)
        } else {
            tvChargingStatusBadge.text = "ĐANG XẢ PIN"
            tvChargingStatusBadge.setTextColor(dischargingColor)
            tvLivePowerWatts.setTextColor(dischargingColor)
            tvLivePowerWatts.text = String.format(Locale.US, "- %.2f W", safeWatts)
            tvCurrentMa.setTextColor(dischargingColor)
            tvCurrentMa.text = String.format(Locale.US, "- %d mA", absCurrent)
        }

        // Hiển thị điện áp chính xác từng mV
        tvVoltageV.text = String.format(Locale.US, "%.3f V", info.voltageMv / 1000.0)
        tvTemperature.text = String.format(Locale.US, "%.1f °C", info.temperatureC)

        // Sức khỏe pin SOH - Đọc thật
        if (info.healthPercent >= 0.0) {
            tvHealthPercent.text = String.format(Locale.US, "%.1f %%", info.healthPercent)
            tvHealthPercent.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvHealthPercent.text = "-- %"
            tvHealthPercent.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Chu kỳ sạc Cycle Count - Đọc thật
        if (info.cycleCount >= 0) {
            tvCycleCount.text = "${info.cycleCount} lần"
            tvCycleCount.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            tvCycleCount.text = "-- lần"
            tvCycleCount.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        tvDesignCapacity.text = String.format(Locale.US, "%,d mAh", info.designCapacityMah)
        tvTypicalCapacity.text = String.format(Locale.US, "%,d mAh", info.typicalCapacityMah)
        tvChargeCounter.text = String.format(Locale.US, "%,d mAh", info.chargeCounterMah)
        tvTech.text = info.technology
        tvChargeType.text = info.chargeType

        // Đọc cài đặt chu kỳ cảnh báo & thay pin
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAlertEnabled = prefs.getBoolean("cycle_alert_enabled", false)
        val cycleLimit = prefs.getInt("cycle_alert_limit", 1000)

        if (isAlertEnabled && info.cycleCount >= cycleLimit && info.cycleCount > 0) {
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

        val switchDualCell = dialog.findViewById<SwitchMaterial>(R.id.switchDualCell)
        val switchLiveAlert = dialog.findViewById<SwitchMaterial>(R.id.switchLiveAlert)
        val switchCycleAlert = dialog.findViewById<SwitchMaterial>(R.id.switchCycleAlert)
        val etCycleLimit = dialog.findViewById<TextInputEditText>(R.id.etCycleLimit)
        val etCustomCapacity = dialog.findViewById<TextInputEditText>(R.id.etCustomCapacity)
        val cbBatteryReplaced = dialog.findViewById<MaterialCheckBox>(R.id.cbBatteryReplaced)
        val etReplaceDate = dialog.findViewById<TextInputEditText>(R.id.etReplaceDate)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveSettings)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelSettings)
        val btnCheckUpdate = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCheckUpdate)

        btnCheckUpdate?.setOnClickListener {
            dialog.dismiss()
            OtaUpdateManager.checkUpdate(this, isManual = true)
        }

        // Load existing values
        switchDualCell.isChecked = prefs.getBoolean("dual_cell_enabled", false)
        switchLiveAlert.isChecked = prefs.getBoolean("live_alert_enabled", true)
        switchCycleAlert.isChecked = prefs.getBoolean("cycle_alert_enabled", false)
        etCycleLimit.setText(prefs.getInt("cycle_alert_limit", 1000).toString())
        etCustomCapacity.setText(prefs.getInt("custom_capacity", 5520).toString())
        cbBatteryReplaced.isChecked = prefs.getBoolean("battery_replaced", false)
        etReplaceDate.setText(prefs.getString("replace_date", ""))

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val dualCell = switchDualCell.isChecked
            val liveAlert = switchLiveAlert.isChecked
            val alertEnabled = switchCycleAlert.isChecked
            val cycleLimit = etCycleLimit.text.toString().toIntOrNull() ?: 1000
            val customCap = etCustomCapacity.text.toString().toIntOrNull() ?: 5520
            val isReplaced = cbBatteryReplaced.isChecked
            val replaceDate = etReplaceDate.text.toString().trim()

            prefs.edit()
                .putBoolean("dual_cell_enabled", dualCell)
                .putBoolean("live_alert_enabled", liveAlert)
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

            showBottomNotice("Đã lưu cài đặt thành công!")
            dialog.dismiss()

            // Làm mới ngay
            lifecycleScope.launch(Dispatchers.IO) {
                val info = BatteryReader.getBatteryInfo(applicationContext)
                withContext(Dispatchers.Main) {
                    applyBatteryInfo(info)
                }
            }
        }

        dialog.show()
    }
}
