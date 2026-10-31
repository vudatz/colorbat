package com.colorbat.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("StaticFieldLeak")
object LiveAlertPillManager {

    private var windowManager: WindowManager? = null
    private var pillView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExpanded = false

    // Views
    private var layoutCollapsed: LinearLayout? = null
    private var layoutExpanded: LinearLayout? = null
    private var tvPillPower: TextView? = null
    private var tvPillTimer: TextView? = null
    private var tvPillLevel: TextView? = null

    private var tvExpProtocol: TextView? = null
    private var tvExpClose: TextView? = null
    private var tvExpPower: TextView? = null
    private var tvExpMetrics: TextView? = null
    private var tvExpDetails: TextView? = null
    private var btnExpOpenApp: Button? = null

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun showOrUpdate(
        context: Context,
        watts: Double,
        currentMa: Int,
        voltageMv: Int,
        batteryLevel: Int,
        timeString: String,
        protocol: String,
        chargedMah: Double,
        avgWatts: Double,
        peakWatts: Double
    ) {
        if (!canDrawOverlays(context)) {
            return
        }

        mainHandler.post {
            try {
                if (pillView == null) {
                    initPillView(context)
                }

                updateContent(
                    context = context,
                    watts = watts,
                    currentMa = currentMa,
                    voltageMv = voltageMv,
                    batteryLevel = batteryLevel,
                    timeString = timeString,
                    protocol = protocol,
                    chargedMah = chargedMah,
                    avgWatts = avgWatts,
                    peakWatts = peakWatts
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initPillView(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_live_alert_pill, null)
        pillView = view

        layoutCollapsed = view.findViewById(R.id.layoutCollapsed)
        layoutExpanded = view.findViewById(R.id.layoutExpanded)
        tvPillPower = view.findViewById(R.id.tvPillPower)
        tvPillTimer = view.findViewById(R.id.tvPillTimer)
        tvPillLevel = view.findViewById(R.id.tvPillLevel)

        tvExpProtocol = view.findViewById(R.id.tvExpProtocol)
        tvExpClose = view.findViewById(R.id.tvExpClose)
        tvExpPower = view.findViewById(R.id.tvExpPower)
        tvExpMetrics = view.findViewById(R.id.tvExpMetrics)
        tvExpDetails = view.findViewById(R.id.tvExpDetails)
        btnExpOpenApp = view.findViewById(R.id.btnExpOpenApp)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 20 // Canh ngay dưới đỉnh nốt ruồi camera
        }
        layoutParams = params

        // Handle Touch: Drag or Tap
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (hypot(dx.toDouble(), dy.toDouble()) > 15) {
                        hasMoved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }

        tvExpClose?.setOnClickListener {
            collapse()
        }

        btnExpOpenApp?.setOnClickListener {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
            collapse()
        }

        wm.addView(view, params)
    }

    private fun toggleExpanded() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }

    private fun expand() {
        isExpanded = true
        layoutCollapsed?.visibility = View.GONE
        layoutExpanded?.visibility = View.VISIBLE
    }

    private fun collapse() {
        isExpanded = false
        layoutExpanded?.visibility = View.GONE
        layoutCollapsed?.visibility = View.VISIBLE
    }

    private fun updateContent(
        context: Context,
        watts: Double,
        currentMa: Int,
        voltageMv: Int,
        batteryLevel: Int,
        timeString: String,
        protocol: String,
        chargedMah: Double,
        avgWatts: Double,
        peakWatts: Double
    ) {
        val safeWatts = if (watts > 300.0 || watts < 0.0) 0.0 else watts
        val absCurrent = abs(currentMa)

        // Collapsed pill
        tvPillPower?.text = String.format(Locale.US, "%.1f W", safeWatts)
        tvPillTimer?.text = timeString
        tvPillLevel?.text = "$batteryLevel%"

        // Expanded card
        tvExpProtocol?.text = protocol
        tvExpPower?.text = String.format(Locale.US, "+ %.2f W", safeWatts)
        tvExpMetrics?.text = String.format(
            Locale.US,
            "%.3f V • %s%d mA",
            voltageMv / 1000.0,
            if (currentMa >= 0) "+" else "-",
            absCurrent
        )
        tvExpDetails?.text = String.format(
            Locale.US,
            "⏱️ %s | +%.0f mAh | TB: %.1f W | Đỉnh: %.1f W",
            timeString,
            chargedMah,
            avgWatts,
            peakWatts
        )
    }

    fun dismiss() {
        mainHandler.post {
            try {
                if (pillView != null && windowManager != null) {
                    windowManager?.removeView(pillView)
                }
            } catch (e: Exception) {
                // Ignore if already removed
            } finally {
                pillView = null
                layoutParams = null
                windowManager = null
                isExpanded = false
            }
        }
    }
}
