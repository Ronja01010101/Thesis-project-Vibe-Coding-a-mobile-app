package com.example.thesisproject.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Canvas drawing helpers for the widget's "route line" gauge and
 * "time scale" gauge. Both produce [Bitmap]s that fit into a RemoteViews
 * ImageView via `setImageViewBitmap`.
 *
 * RemoteViews has no Canvas composable equivalent, so all dynamic geometry
 * (route progress, delta indicator) goes through this path. Same approach
 * Glance forces — the underlying limitation comes from RemoteViews itself,
 * not the framework on top (per Technical Constraint added to REQUIREMENTS
 * in 2026-05-08 plan revision).
 *
 * All sizes are passed in pixels by the caller; the caller is responsible
 * for converting from dp using device density. Functions are pure — same
 * input → same bitmap; no I/O, no external state.
 */
object WidgetBitmapRenderer {

    /**
     * Horizontal abstract route. Grey baseline, equally-spaced filled dots
     * for stops, larger ringed dot at the user's stop, and a circular bus
     * marker at [WidgetCommuteState.busIndex] with the line designation
     * inside. Bus-marker fill colour is phase-driven per the design handoff:
     * red when Late/LeaveNow/Deviation, purple when Early, green when OnTime,
     * grey when Passed/Dormant. When [WidgetCommuteState.phase] is Passed
     * the bus marker is omitted entirely.
     */
    fun renderRouteLine(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        state: WidgetCommuteState
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (state.stopCount < 2) return bitmap

        val density = context.resources.displayMetrics.density
        val padX = density * 12f
        val midY = heightPx / 2f
        val span = widthPx - 2 * padX

        // Baseline route line.
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROUTE_LINE
            strokeWidth = density * 1.5f
        }
        canvas.drawLine(padX, midY, widthPx - padX, midY, linePaint)

        // Stop dots — small filled circles for non-user stops, larger ringed
        // dot at the user's stop so they can locate it at a glance.
        val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROUTE_LINE
            style = Paint.Style.FILL
        }
        val userOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_USER_STOP
            style = Paint.Style.STROKE
            strokeWidth = density * 2f
        }
        val userInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_USER_STOP
            style = Paint.Style.FILL
        }
        val stopRadius = density * 3f
        val userOuterRadius = density * 6f
        val userInnerRadius = density * 2.5f

        for (i in 0 until state.stopCount) {
            val x = padX + span * i / (state.stopCount - 1).toFloat()
            if (i == state.userStopIndex) {
                canvas.drawCircle(x, midY, userOuterRadius, userOuterPaint)
                canvas.drawCircle(x, midY, userInnerRadius, userInnerPaint)
            } else {
                canvas.drawCircle(x, midY, stopRadius, stopPaint)
            }
        }

        // Bus marker — only when we have a position AND the bus hasn't passed.
        val busIndex = state.busIndex
        if (busIndex != null && state.phase != Phase.Passed) {
            val clamped = max(0f, min((state.stopCount - 1).toFloat(), busIndex))
            val busX = padX + span * clamped / (state.stopCount - 1).toFloat()
            val busFill = busColorFor(state.phase)
            val busRadius = density * 11f

            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(busX, midY, busRadius + density * 1.5f, outline)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = busFill
                style = Paint.Style.FILL
            }
            canvas.drawCircle(busX, midY, busRadius, fill)

            val text = state.lineDesignation.takeIf { it.isNotBlank() } ?: "?"
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = density * 11f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textY = midY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, busX, textY, textPaint)
        }

        return bitmap
    }

    /**
     * Horizontal "early ← 0 → late" gauge. Center tick at 0, end ticks at
     * ±[MAX_DELTA_MIN] (5 min). Coloured dot at the current delta, clamped
     * to the visible range — text label next to the gauge still shows the
     * full integer value, the gauge is just the visual hint.
     *
     * No dot drawn when [WidgetCommuteState.deltaMinutes] is null (e.g.
     * SL hasn't predicted this departure yet) — the empty axis itself
     * conveys "no data".
     */
    fun renderTimeScale(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        state: WidgetCommuteState
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val padX = density * 6f
        val midY = heightPx / 2f
        val span = widthPx - 2 * padX

        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROUTE_LINE
            strokeWidth = density * 1.2f
        }
        canvas.drawLine(padX, midY, widthPx - padX, midY, axisPaint)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ROUTE_LINE
            strokeWidth = density * 1.5f
        }
        val centerX = padX + span / 2
        canvas.drawLine(centerX, midY - density * 4f, centerX, midY + density * 4f, tickPaint)
        canvas.drawLine(padX, midY - density * 2.5f, padX, midY + density * 2.5f, tickPaint)
        canvas.drawLine(widthPx - padX, midY - density * 2.5f, widthPx - padX, midY + density * 2.5f, tickPaint)

        val delta = state.deltaMinutes
        if (delta != null) {
            val clamped = max(-MAX_DELTA_MIN.toFloat(), min(MAX_DELTA_MIN.toFloat(), delta.toFloat()))
            val ratio = (clamped + MAX_DELTA_MIN) / (2f * MAX_DELTA_MIN)
            val dotX = padX + span * ratio
            val dotColor = when {
                delta.absoluteValue < 1 -> COLOR_ON_TIME
                delta > 0 -> COLOR_LATE
                else -> COLOR_EARLY
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = dotColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(dotX, midY, density * 4.5f, dotPaint)
            val dotOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = density * 1.5f
            }
            canvas.drawCircle(dotX, midY, density * 4.5f, dotOutline)
        }

        return bitmap
    }

    private fun busColorFor(phase: Phase): Int = when (phase) {
        Phase.Late, Phase.LeaveNow, Phase.Deviation -> COLOR_LATE
        Phase.Early -> COLOR_EARLY
        Phase.OnTime -> COLOR_ON_TIME
        Phase.Passed, Phase.Dormant -> COLOR_NEUTRAL
    }

    /** Visible delta range on the gauge, in minutes. Larger deltas peg at the ends. */
    private const val MAX_DELTA_MIN = 5

    // Colours match Step 6's map-marker palette so the visual identity is
    // continuous between map markers and widget bus markers.
    private const val COLOR_ROUTE_LINE = 0xFFBDBDBD.toInt()
    private const val COLOR_USER_STOP = 0xFF1976D2.toInt()
    private const val COLOR_ON_TIME = 0xFF43A047.toInt()
    private const val COLOR_LATE = 0xFFE53935.toInt()
    private const val COLOR_EARLY = 0xFF8E24AA.toInt()
    private const val COLOR_NEUTRAL = 0xFF9E9E9E.toInt()
}
