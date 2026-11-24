package com.alhysson.battlerc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Velocímetro Motorsport real desenhado em Canvas (arco de 240°, marcas de graduação,
 * ponteiro suave com inércia física, zona de redline e leitura digital PWM/marcha).
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var targetSpeedPct = 0f      // 0..100
    private var displayedSpeedPct = 0f   // suavizado com inércia
    private var maxLimitPct = 100        // 60, 85 ou 100
    private var gearText = "N"

    private val arcRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0A0D14")
        style = Paint.Style.FILL
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3140")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val redlineTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55EF4444")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66CBD5E1")
        strokeCap = Paint.Cap.ROUND
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        strokeCap = Paint.Cap.ROUND
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.FILL
    }

    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setTelemetry(active: Boolean, limitPct: Int, gear: String) {
        maxLimitPct = limitPct
        gearText = gear
        targetSpeedPct = if (active) limitPct.toFloat() else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Física suave de aceleração / desaceleração do ponteiro
        val diff = targetSpeedPct - displayedSpeedPct
        if (kotlin.math.abs(diff) > 0.4f) {
            val factor = if (diff > 0) 0.24f else 0.16f
            displayedSpeedPct += diff * factor
            postInvalidateOnAnimation()
        } else {
            displayedSpeedPct = targetSpeedPct
        }

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.46f
        val stroke = radius * 0.13f

        // Fundo circular vidro escuro + anel metálico fino
        canvas.drawCircle(cx, cy, radius * 1.04f, bgPaint)
        canvas.drawCircle(cx, cy, radius * 1.03f, rimPaint)

        val arcRadius = radius * 0.84f
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)

        trackPaint.strokeWidth = stroke
        redlineTrackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        // Arco base (150° até 390° = 240° sweep)
        canvas.drawArc(arcRect, 150f, 240f, false, trackPaint)
        // Zona redline (últimos 20% = 48°)
        canvas.drawArc(arcRect, 342f, 48f, false, redlineTrackPaint)

        // Cor dinâmica do arco de potência (Branco Titânio -> Âmbar -> Vermelho Redline)
        progressPaint.color = when {
            displayedSpeedPct >= 82f -> Color.parseColor("#EF4444")
            displayedSpeedPct >= 55f -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#F8FAFC")
        }
        val sweep = (displayedSpeedPct / 100f) * 240f
        if (sweep > 0.5f) {
            canvas.drawArc(arcRect, 150f, sweep, false, progressPaint)
        }

        // Graduações de precisão (21 traços)
        for (i in 0..20) {
            val angleDeg = 150f + (i / 20f) * 240f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val isMajor = (i % 5 == 0)
            val innerR = arcRadius - stroke * (if (isMajor) 1.35f else 0.95f)
            val outerR = arcRadius - stroke * 0.58f
            tickPaint.strokeWidth = if (isMajor) 3.2f else 1.6f
            tickPaint.color = if (i >= 16) Color.parseColor("#CCEF4444") else Color.parseColor("#88CBD5E1")
            canvas.drawLine(
                cx + (innerR * cos(angleRad)).toFloat(),
                cy + (innerR * sin(angleRad)).toFloat(),
                cx + (outerR * cos(angleRad)).toFloat(),
                cy + (outerR * sin(angleRad)).toFloat(),
                tickPaint
            )
        }

        // Ponteiro motorsport
        val needleAngle = Math.toRadians((150f + sweep).toDouble())
        val needleLen = arcRadius - stroke * 0.7f
        needlePaint.strokeWidth = radius * 0.045f
        canvas.drawLine(
            cx,
            cy,
            cx + (needleLen * cos(needleAngle)).toFloat(),
            cy + (needleLen * sin(needleAngle)).toFloat(),
            needlePaint
        )
        canvas.drawCircle(cx, cy, radius * 0.08f, hubPaint)

        // Leitura digital central (PWM / %) + Marcha
        val pwmValue = ((displayedSpeedPct / 100f) * 255f).toInt()
        valueTextPaint.textSize = radius * 0.34f
        unitTextPaint.textSize = radius * 0.13f
        gearPaint.textSize = radius * 0.18f

        canvas.drawText(pwmValue.toString(), cx, cy + radius * 0.48f, valueTextPaint)
        canvas.drawText("PWM • MAX ${maxLimitPct}%", cx, cy + radius * 0.65f, unitTextPaint)

        gearPaint.color = when (gearText) {
            "R" -> Color.parseColor("#F59E0B")
            "P" -> Color.parseColor("#EF4444")
            "N" -> Color.parseColor("#94A3B8")
            else -> Color.parseColor("#10B981")
        }
        canvas.drawText(gearText, cx, cy - radius * 0.25f, gearPaint)
    }
}
