package com.example.carrothud

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class HudCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentSpeed: Int = 0
    var targetSpeed: Int = 0
    var distanceToCar: Float = 0f
    var hasLeadCar: Boolean = false
    var isWarning: Boolean = false

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val speedPaint = Paint().apply {
        color = Color.WHITE
        textSize = 160f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint().apply {
        color = Color.GRAY
        textSize = 40f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val targetSpeedPaint = Paint().apply {
        color = Color.CYAN
        textSize = 50f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val carIconPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val textInfoPaint = Paint().apply {
        color = Color.GREEN
        textSize = 45f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun updateHudData(speed: Int, target: Int, dist: Float, hasCar: Boolean, warning: Boolean) {
        this.currentSpeed = speed
        this.targetSpeed = target
        this.distanceToCar = dist
        this.hasLeadCar = hasCar
        this.isWarning = warning
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (hasLeadCar) {
            carIconPaint.color = if (isWarning) Color.RED else Color.GREEN
            textInfoPaint.color = if (isWarning) Color.RED else Color.GREEN
            val boxWidth = 140f
            val boxHeight = 70f
            val boxTop = h * 0.22f
            canvas.drawRoundRect(centerX - boxWidth/2, boxTop, centerX + boxWidth/2, boxTop + boxHeight, 15f, 15f, carIconPaint)
            canvas.drawText("${distanceToCar.toInt()}m", centerX, boxTop + boxHeight + 50f, textInfoPaint)
        }

        speedPaint.color = if (isWarning) Color.RED else Color.WHITE
        canvas.drawText("$currentSpeed", centerX, h * 0.65f, speedPaint)
        canvas.drawText("km/h", centerX, h * 0.75f, unitPaint)

        if (targetSpeed > 0) {
            canvas.drawText("ACC $targetSpeed km/h", centerX, h * 0.88f, targetSpeedPaint)
        }
    }
}
