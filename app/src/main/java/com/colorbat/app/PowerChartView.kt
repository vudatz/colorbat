package com.colorbat.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

class PowerChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private var avgPowerVal: Float = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val path = Path()
    private val fillPath = Path()

    init {
        updateColors()
    }

    private fun updateColors() {
        linePaint.color = ContextCompat.getColor(context, R.color.primary)
        gridPaint.color = ContextCompat.getColor(context, R.color.card_stroke)
        avgLinePaint.color = ContextCompat.getColor(context, R.color.secondary)
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    fun setPoints(points: List<Float>, avgPower: Float = 0f) {
        dataPoints.clear()
        dataPoints.addAll(points)
        avgPowerVal = avgPower
        invalidate()
    }

    fun addPoint(powerWatt: Float, avgPower: Float = 0f) {
        dataPoints.add(powerWatt)
        avgPowerVal = avgPower
        invalidate()
    }

    fun clearData() {
        dataPoints.clear()
        avgPowerVal = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateColors()

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingHorizontal = 40f
        val paddingTop = 36f
        val paddingBottom = 32f

        if (w <= 0 || h <= 0) return

        val usableHeight = h - paddingTop - paddingBottom
        val midY = paddingTop + usableHeight / 2f

        // Draw horizontal grid lines
        canvas.drawLine(paddingHorizontal, midY, w - paddingHorizontal, midY, gridPaint)
        canvas.drawLine(paddingHorizontal, paddingTop, w - paddingHorizontal, paddingTop, gridPaint)
        canvas.drawLine(paddingHorizontal, h - paddingBottom, w - paddingHorizontal, h - paddingBottom, gridPaint)

        if (dataPoints.isEmpty()) {
            val emptyText = "Đang chờ dữ liệu phiên đo sạc..."
            val textWidth = textPaint.measureText(emptyText)
            canvas.drawText(emptyText, (w - textWidth) / 2f, midY + 8f, textPaint)
            return
        }

        val rawMax = dataPoints.maxOrNull() ?: 5f
        val maxVal = max(rawMax * 1.15f, 5f)
        val minVal = 0f
        val range = max(maxVal - minVal, 1f)

        // Draw Peak text top left
        canvas.drawText(String.format(Locale.US, "Đỉnh: %.1f W", rawMax), paddingHorizontal, paddingTop - 8f, textPaint)
        canvas.drawText("0 W", paddingHorizontal, h - 8f, textPaint)

        path.reset()
        fillPath.reset()

        val stepX = (w - paddingHorizontal * 2f) / max(dataPoints.size - 1, 1)

        dataPoints.forEachIndexed { i, value ->
            val x = paddingHorizontal + i * stepX
            val normalizedY = (value - minVal) / range
            val y = (h - paddingBottom) - (normalizedY * usableHeight)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h - paddingBottom)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val lastX = paddingHorizontal + (dataPoints.size - 1) * stepX
        fillPath.lineTo(lastX, h - paddingBottom)
        fillPath.close()

        // Gradient fill
        fillPaint.shader = LinearGradient(
            0f, paddingTop,
            0f, h - paddingBottom,
            ContextCompat.getColor(context, R.color.primary_light),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // Draw Average line if available
        if (avgPowerVal > 0f && avgPowerVal <= maxVal) {
            val avgNormY = (avgPowerVal - minVal) / range
            val avgY = (h - paddingBottom) - (avgNormY * usableHeight)
            canvas.drawLine(paddingHorizontal, avgY, w - paddingHorizontal, avgY, avgLinePaint)

            val avgLabel = String.format(Locale.US, "TB: %.1f W", avgPowerVal)
            val labelW = textPaint.measureText(avgLabel)
            canvas.drawText(avgLabel, w - paddingHorizontal - labelW, avgY - 6f, textPaint)
        }
    }
}
