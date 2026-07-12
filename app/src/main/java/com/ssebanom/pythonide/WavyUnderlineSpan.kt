package com.ssebanom.pythonide

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.style.LineBackgroundSpan

/**
 * Draws an IDE-style wavy underline beneath the text of the line(s) it spans,
 * used to mark syntax errors (red) and lint warnings (amber).
 */
class WavyUnderlineSpan(
    private val color: Int,
    private val amplitude: Float,
    private val strokeWidth: Float,
    private val waveLength: Float
) : LineBackgroundSpan {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@WavyUnderlineSpan.strokeWidth
        color = this@WavyUnderlineSpan.color
    }
    private val path = Path()

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val textWidth = paint.measureText(text, start, end)
        if (textWidth <= 0f) return
        val startX = left.toFloat()
        val endX = startX + textWidth
        val y = bottom.toFloat() - strokeWidth

        path.reset()
        path.moveTo(startX, y)
        var x = startX
        var up = true
        val half = waveLength / 2f
        while (x < endX) {
            val nextX = (x + half).coerceAtMost(endX)
            path.lineTo(nextX, if (up) y - amplitude else y + amplitude)
            up = !up
            x = nextX
        }
        canvas.drawPath(path, wavePaint)
    }
}
