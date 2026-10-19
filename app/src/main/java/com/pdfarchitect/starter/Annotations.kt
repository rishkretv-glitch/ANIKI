package com.pdfarchitect.starter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max

/** Single paint path used by the onscreen preview and flattened PDF export. */
object AnnotationPainter {
    private fun xy(p: InkPoint, rect: RectF) = Pair(
        rect.left + p.x * rect.width(), rect.top + p.y * rect.height()
    )

    fun draw(
        canvas: Canvas, rect: RectF, edits: PageEdits,
        pageWidthPt: Int, pageHeightPt: Int,
        resolveImage: (String) -> Bitmap? = { null },
    ) {
        if (rect.isEmpty || pageWidthPt <= 0 || pageHeightPt <= 0) return
        val sx = rect.width() / pageWidthPt
        val sy = rect.height() / pageHeightPt
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        edits.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            pen.color = stroke.color
            pen.alpha = (stroke.opacity.coerceIn(0f, 1f) * 255).toInt()
            val width = stroke.widthPt.coerceIn(0.3f, 40f) * (sx + sy) / 2f
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                val (x, y) = xy(p, rect)
                canvas.drawCircle(x, y, width / 2f, Paint(pen).apply { style = Paint.Style.FILL })
            } else {
                stroke.points.zipWithNext().forEach { (a, b) ->
                    val (ax, ay) = xy(a, rect)
                    val (bx, by) = xy(b, rect)
                    pen.strokeWidth = width * (0.55f + 0.45f *
                        ((a.pressure + b.pressure) / 2f).coerceIn(0f, 1.6f))
                    canvas.drawLine(ax, ay, bx, by, pen)
                }
            }
        }
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        edits.images.forEach { placement ->
            resolveImage(placement.fileName)?.let { bitmap ->
                canvas.drawBitmap(bitmap, null, RectF(
                    rect.left + placement.x * rect.width(),
                    rect.top + placement.y * rect.height(),
                    rect.left + (placement.x + placement.width) * rect.width(),
                    rect.top + (placement.y + placement.height) * rect.height(),
                ), imagePaint)
            }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        edits.texts.forEach { stamp ->
            textPaint.color = stamp.color
            textPaint.textSize = stamp.sizePt.coerceIn(6f, 72f) * sy
            val (x, y) = xy(InkPoint(stamp.x, stamp.y), rect)
            stamp.text.take(3000).split('\n').take(20).forEachIndexed { index, line ->
                canvas.drawText(line, x, y + index * textPaint.textSize * 1.25f, textPaint)
            }
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(214, 108, 25)
            strokeWidth = max(1f, (sx + sy) * 1.3f)
            strokeCap = Paint.Cap.ROUND
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(160, 65, 0)
            textSize = max(11f, sy * 10f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        fun drawSegment(segment: Segment, label: String, color: Int) {
            val (ax, ay) = xy(segment.start, rect)
            val (bx, by) = xy(segment.end, rect)
            linePaint.color = color
            canvas.drawLine(ax, ay, bx, by, linePaint)
            val radius = max(3f, 3f * sx)
            canvas.drawCircle(ax, ay, radius, linePaint)
            canvas.drawCircle(bx, by, radius, linePaint)
            tagPaint.color = color
            canvas.drawText(label, (ax + bx) / 2f + 6f * sx,
                (ay + by) / 2f - 5f * sy, tagPaint)
        }
        edits.calibration?.let { c ->
            drawSegment(c.reference, "Эталон " + MeasureMath.label(c.meters), Color.rgb(12, 130, 110))
        }
        edits.measurements.forEach { segment ->
            drawSegment(segment, MeasureMath.label(
                MeasureMath.meters(segment, edits.calibration, pageWidthPt, pageHeightPt)
            ), Color.rgb(190, 78, 15))
        }
        edits.areas.forEach { polygon ->
            if (polygon.points.size < 3) return@forEach
            val outline = Path()
            polygon.points.forEachIndexed { index, p ->
                val (x, y) = xy(p, rect)
                if (index == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
            }
            outline.close()
            canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(42, 12, 130, 110)
                style = Paint.Style.FILL
            })
            canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(12, 130, 110)
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, sx * 1.5f)
            })
            val centerX = polygon.points.map { it.x }.average().toFloat()
            val centerY = polygon.points.map { it.y }.average().toFloat()
            val (cx, cy) = xy(InkPoint(centerX, centerY), rect)
            tagPaint.color = Color.rgb(8, 94, 84)
            canvas.drawText(MeasureMath.areaLabel(MeasureMath.squareMeters(
                polygon, edits.calibration, pageWidthPt, pageHeightPt)), cx, cy, tagPaint)
        }
    }
}
