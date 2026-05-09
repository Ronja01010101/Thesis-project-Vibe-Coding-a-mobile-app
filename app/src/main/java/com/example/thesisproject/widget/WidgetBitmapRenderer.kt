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
 * The route gauge is **windowed** to the last 5 stops ending at the user's
 * stop (rightmost dot). Bus marker is drawn when the bus is within that
 * window; out-of-window positions are conveyed as a small "← N stops away"
 * chip baked into the upper-left of the bitmap.
 */
object WidgetBitmapRenderer {

    /**
     * Horizontal abstract route gauge ending at the user's stop. Grey
     * baseline, equally-spaced filled dots for each visible stop (small
     * blue ring at the rightmost = user's stop), circular bus marker at
     * [WidgetCommuteState.visibleBusIndex] when present (line designation
     * inside, phase-driven fill colour). When the bus is out of window
     * the marker is omitted and a "← N stops away" chip is drawn at the
     * upper-left as the off-gauge indicator.
     */
    fun renderRouteLine(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        state: WidgetCommuteState
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (state.visibleStopCount < 1) return bitmap

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

        // Stop dots — small filled circles, larger ringed dot at rightmost
        // (the user's stop is always at index visibleStopCount - 1).
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

        val userIndex = state.visibleStopCount - 1
        val divisor = max(1, state.visibleStopCount - 1).toFloat()
        for (i in 0 until state.visibleStopCount) {
            val x = if (state.visibleStopCount == 1) {
                widthPx - padX  // single stop = anchor at right (user's stop)
            } else {
                padX + span * i / divisor
            }
            if (i == userIndex) {
                canvas.drawCircle(x, midY, userOuterRadius, userOuterPaint)
                canvas.drawCircle(x, midY, userInnerRadius, userInnerPaint)
            } else {
                canvas.drawCircle(x, midY, stopRadius, stopPaint)
            }
        }

        // BUG-028: additional vehicle markers (non-locked vehicles within
        // the visible window) drawn FIRST so the locked marker, when it
        // overlaps, sits visually on top. Smaller (6dp radius), muted
        // blue-grey, no line text inside — they're context, not the focus.
        // Drawn even in Phase.Passed because that's exactly when the user
        // wants to see "where's the next bus" (their original locked one
        // has gone past).
        if (state.additionalBusIndices.isNotEmpty()) {
            val extraOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val extraFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_EXTRA_BUS
                style = Paint.Style.FILL
            }
            val extraRadius = density * 6f
            for (rawIdx in state.additionalBusIndices) {
                val clamped = max(0f, min((state.visibleStopCount - 1).toFloat(), rawIdx))
                val x = padX + span * clamped / divisor
                canvas.drawCircle(x, midY, extraRadius + density * 1f, extraOutline)
                canvas.drawCircle(x, midY, extraRadius, extraFill)
            }
        }

        // Bus marker — only when in window AND not Passed.
        val visibleBus = state.visibleBusIndex
        if (visibleBus != null && state.phase != Phase.Passed) {
            val clamped = max(0f, min((state.visibleStopCount - 1).toFloat(), visibleBus))
            val busX = padX + span * clamped / divisor
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

        // Off-gauge case: the route gauge stays empty (no bus marker). The
        // "Bus is N stops away" hint goes in a dedicated TextView in the
        // layout (widget_stops_away) — keeps the gauge a clean indicator of
        // "where on the route my stop is" without competing visual chips.
        return bitmap
    }

    /**
     * Horizontal "early ← 0 → late" gauge. Center tick at 0, end ticks at
     * ±[MAX_DELTA_MIN] (5 min). Coloured dot at the current delta, clamped
     * to the visible range — text label next to the gauge still shows the
     * full integer value, the gauge is just the visual hint.
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

    private const val MAX_DELTA_MIN = 5

    private const val COLOR_ROUTE_LINE = 0xFFBDBDBD.toInt()
    private const val COLOR_USER_STOP = 0xFF1976D2.toInt()
    private const val COLOR_ON_TIME = 0xFF43A047.toInt()
    private const val COLOR_LATE = 0xFFE53935.toInt()
    private const val COLOR_EARLY = 0xFF8E24AA.toInt()
    private const val COLOR_NEUTRAL = 0xFF9E9E9E.toInt()
    // BUG-028: additional (non-locked) bus markers — Material blue-grey 600,
    // visually distinct from both the route line (#BDBDBD light grey) and
    // the locked-bus phase colours.
    private const val COLOR_EXTRA_BUS = 0xFF607D8B.toInt()
}
