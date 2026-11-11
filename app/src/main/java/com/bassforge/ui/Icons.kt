package com.bassforge.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

enum class Ico { NONE, POWER, RESET, MOVIE, MUSIC, SPINNER, SLIDERS, GEAR, CHECK, SPARK }

/** Small stroke icons drawn with paths so they recolour and animate with the theme. */
object Icons {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val path = Path()

    fun draw(c: Canvas, ico: Ico, cx: Float, cy: Float, size: Float, color: Int, strokeW: Float) {
        if (ico == Ico.NONE) return
        paint.color = color
        paint.strokeWidth = strokeW
        fill.color = color
        val s = size
        val r = s * 0.40f
        when (ico) {
            Ico.POWER -> {
                rect.set(cx - r, cy - r + s * 0.04f, cx + r, cy + r + s * 0.04f)
                c.drawArc(rect, -58f, 296f, false, paint)
                c.drawLine(cx, cy - s * 0.46f, cx, cy - s * 0.06f, paint)
            }
            Ico.RESET -> {
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -60f, 300f, false, paint)
                val ax = cx + r * cos(Math.toRadians(-60.0)).toFloat()
                val ay = cy + r * sin(Math.toRadians(-60.0)).toFloat()
                path.reset()
                path.moveTo(ax + s * 0.02f, ay - s * 0.20f)
                path.lineTo(ax, ay)
                path.lineTo(ax + s * 0.22f, ay + s * 0.04f)
                c.drawPath(path, paint)
            }
            Ico.MOVIE -> {
                rect.set(cx - s * 0.46f, cy - s * 0.34f, cx + s * 0.46f, cy + s * 0.34f)
                c.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                path.reset()
                path.moveTo(cx - s * 0.08f, cy - s * 0.14f)
                path.lineTo(cx + s * 0.18f, cy)
                path.lineTo(cx - s * 0.08f, cy + s * 0.14f)
                path.close()
                c.drawPath(path, fill)
            }
            Ico.MUSIC -> {
                c.drawCircle(cx - s * 0.16f, cy + s * 0.28f, s * 0.17f, fill)
                c.drawLine(cx + s * 0.01f, cy + s * 0.28f, cx + s * 0.01f, cy - s * 0.40f, paint)
                c.drawLine(cx + s * 0.01f, cy - s * 0.40f, cx + s * 0.34f, cy - s * 0.22f, paint)
            }
            Ico.SPINNER -> {
                for (i in 0 until 3) {
                    val a = Math.toRadians((-90 + i * 120).toDouble())
                    val x = cx + s * 0.30f * cos(a).toFloat()
                    val y = cy + s * 0.30f * sin(a).toFloat()
                    c.drawCircle(x, y, s * 0.16f, paint)
                    c.drawLine(cx, cy, cx + (x - cx) * 0.55f, cy + (y - cy) * 0.55f, paint)
                }
                c.drawCircle(cx, cy, s * 0.07f, fill)
            }
            Ico.SLIDERS -> {
                val xs = floatArrayOf(-0.30f, 0f, 0.30f)
                val ys = floatArrayOf(0.12f, -0.14f, 0.16f)
                for (i in 0 until 3) {
                    val x = cx + s * xs[i]
                    c.drawLine(x, cy - s * 0.40f, x, cy + s * 0.40f, paint)
                    c.drawCircle(x, cy + s * ys[i], s * 0.10f, fill)
                }
            }
            Ico.GEAR -> {
                c.drawCircle(cx, cy, s * 0.17f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c1 = cos(a).toFloat()
                    val s1 = sin(a).toFloat()
                    c.drawLine(cx + c1 * s * 0.30f, cy + s1 * s * 0.30f, cx + c1 * s * 0.43f, cy + s1 * s * 0.43f, paint)
                }
                c.drawCircle(cx, cy, s * 0.30f, paint)
            }
            Ico.CHECK -> {
                path.reset()
                path.moveTo(cx - s * 0.26f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.06f, cy + s * 0.22f)
                path.lineTo(cx + s * 0.28f, cy - s * 0.20f)
                c.drawPath(path, paint)
            }
            Ico.SPARK -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.42f)
                path.lineTo(cx + s * 0.10f, cy - s * 0.10f)
                path.lineTo(cx + s * 0.42f, cy)
                path.lineTo(cx + s * 0.10f, cy + s * 0.10f)
                path.lineTo(cx, cy + s * 0.42f)
                path.lineTo(cx - s * 0.10f, cy + s * 0.10f)
                path.lineTo(cx - s * 0.42f, cy)
                path.lineTo(cx - s * 0.10f, cy - s * 0.10f)
                path.close()
                c.drawPath(path, fill)
            }
            Ico.NONE -> {}
        }
    }
}
