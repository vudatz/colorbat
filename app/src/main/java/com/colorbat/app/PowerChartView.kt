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
    private val maxPoints = 60

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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
    }

    private val path = Path()
    private val fillPath = Path()

    init {
        updateColors()
    }

    private fun updateColors() {
        linePaint.color = ContextCompat.getColor(context, R.color.primary)
        gridPaint.color = ContextCompat.getColor(context, R.color.card_stroke)
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    fun addPoint(powerWatt: Float) {
        if (dataPoints.size >= maxPoints) {
            dataPoints.removeAt(0)
        }
        dataPoints.add(powerWatt)
        invalidate()
    }

    fun clearData() {
        dataPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateColors()

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 32f

        if (w <= 0 || h <= 0) return

        // Draw horizontal grid lines
        val midY = h / 2f
        canvas.drawLine(padding, midY, w - padding, midY, gridPaint)
        canvas.drawLine(padding, padding, w - padding, padding, gridPaint)
        canvas.drawLine(padding, h - padding, w - padding, h - padding, gridPaint)

        if (dataPoints.isEmpty()) {
            val emptyText = "Đang chờ dữ liệu phiên đo..."
            val textWidth = textPaint.measureText(emptyText)
            canvas.drawText(emptyText, (w - textWidth) / 2f, midY + 10f, textPaint)
            return
        }

        val maxVal = max(dataPoints.maxOrNull() ?: 5f, 2f)
        val minVal = 0f
        val range = max(maxVal - minVal, 1f)

        path.reset()
        fillPath.reset()

        val stepX = (w - padding * 2f) / max(dataPoints.size - 1, 1)

        dataPoints.forEachIndexed { i, value ->
            val x = padding + i * stepX
            val normalized = (value - minVal) / range
            val y = (h - padding) - (normalized * (h - padding * 2f))

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h - padding)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Fill area under curve
        val lastX = padding + (dataPoints.size - 1) * stepX
        fillPath.lineTo(lastX, h - padding)
        fillPath.close()

        val gradient = LinearGradient(
            0f, padding, 0f, h - padding,
            Color.argb(70, 0, 200, 100),
            Color.argb(0, 0, 200, 100),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)

        // Draw line curve
        canvas.drawPath(path, linePaint)

        // Draw max label
        val maxLabel = String.format(Locale.US, "Max: %.1f W", maxVal)
        canvas.drawText(maxLabel, padding, padding + 24f, textPaint)

        // Draw current label at last point
        val currentVal = dataPoints.last()
        val curLabel = String.format(Locale.US, "Hiện tại: %.2f W", currentVal)
        val curWidth = textPaint.measureText(curLabel)
        canvas.drawText(curLabel, w - padding - curWidth, padding + 24f, textPaint)
    }
}
