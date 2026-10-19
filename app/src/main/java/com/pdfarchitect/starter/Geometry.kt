package com.pdfarchitect.starter

import java.util.Locale
import kotlin.math.hypot

/** All annotation positions use fractions of the page dimensions, independent of screen zoom. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)
data class InkStroke(
    val points: List<InkPoint>,
    val color: Int = 0xFF2370ED.toInt(),
    val widthPt: Float = 2.4f,
    val opacity: Float = 1f,
)
data class TextStamp(
    val x: Float, val y: Float, val text: String,
    val color: Int = 0xFF1450B4.toInt(),
    val sizePt: Float = 13f,
)
data class ImageStamp(
    val fileName: String,
    val x: Float, val y: Float,
    val width: Float, val height: Float,
)
data class Segment(val start: InkPoint, val end: InkPoint)
/** Closed construction area in page-relative coordinates; first point is NOT duplicated. */
data class AreaPolygon(val points: List<InkPoint>)
data class Calibration(val reference: Segment, val meters: Double)
data class PageEdits(
    val strokes: List<InkStroke> = emptyList(),
    val texts: List<TextStamp> = emptyList(),
    val images: List<ImageStamp> = emptyList(),
    val measurements: List<Segment> = emptyList(),
    val areas: List<AreaPolygon> = emptyList(),
    val calibration: Calibration? = null,
)

/** True when edits need to be appended to a preserving PDF export. */
fun PageEdits.hasVisibleMarkup(): Boolean =
    strokes.isNotEmpty() || texts.isNotEmpty() || images.isNotEmpty() ||
        measurements.isNotEmpty() || areas.isNotEmpty() || calibration != null

enum class EditorTool(val title: String) {
    PAN("Рука"), PEN("Перо"), HIGHLIGHTER("Маркер"),
    ERASER("Ластик"), TEXT("Текст"), IMAGE("Фото"), CALIBRATE("Масштаб"),
    MEASURE("Линейка"), AREA("Площадь")
}

/** Distances must use the PDF page aspect ratio, NEVER screen pixels. */
object MeasureMath {
    fun distancePt(segment: Segment, pageWidthPt: Int, pageHeightPt: Int): Double = hypot(
        (segment.end.x - segment.start.x).toDouble() * pageWidthPt,
        (segment.end.y - segment.start.y).toDouble() * pageHeightPt,
    )

    fun meters(segment: Segment, calibration: Calibration?, widthPt: Int, heightPt: Int): Double? {
        val c = calibration ?: return null
        val baseline = distancePt(c.reference, widthPt, heightPt)
        if (!baseline.isFinite() || baseline < 0.001 || !c.meters.isFinite() || c.meters <= 0) {
            return null
        }
        return distancePt(segment, widthPt, heightPt) * c.meters / baseline
    }

    /** Shoelace formula in page points, scaled with the calibrated linear ratio. */
    fun squareMeters(area: AreaPolygon, calibration: Calibration?, widthPt: Int,
                     heightPt: Int): Double? {
        val reference = calibration ?: return null
        val baseline = distancePt(reference.reference, widthPt, heightPt)
        if (baseline < 0.001 || !baseline.isFinite() ||
            reference.meters <= 0 || !reference.meters.isFinite() || area.points.size < 3) return null
        var signedDoubleArea = 0.0
        val vertices = area.points
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            signedDoubleArea += (a.x.toDouble() * widthPt) * (b.y.toDouble() * heightPt) -
                (b.x.toDouble() * widthPt) * (a.y.toDouble() * heightPt)
        }
        val ratio = reference.meters / baseline
        val result = kotlin.math.abs(signedDoubleArea) * 0.5 * ratio * ratio
        return result.takeIf { it.isFinite() }
    }

    fun areaLabel(value: Double?): String = if (value == null || !value.isFinite()) "—"
        else String.format(Locale.getDefault(), "%.2f м²", value)

    fun label(value: Double?): String = if (value == null || !value.isFinite()) "—"
        else String.format(Locale.getDefault(), "%.2f м", value)
}

