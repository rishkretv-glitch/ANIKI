package com.pdfarchitect.starter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.Typeface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Native CONTENT overlay export. Unlike the PdfRenderer raster export, this
 * rewrites a COPY of the original document with its existing pages still intact.
 * The new strokes are PDF vector paths; text/image stamps are rasterized only
 * individually to render Hebrew, Russian, Latin and emoji without font assets.
 *
 * Important: not a secure redaction tool, not signed-PDF safe. Input immutable.
 */
class PreservingPdfExporter(
    private val context: Context,
    private val editsStore: InkStore,
    private val images: ImageAssets,
) {
    suspend fun export(
        original: Uri,
        destination: Uri,
        reportProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val sourceCopy = File.createTempFile("architect_in_", ".pdf", context.cacheDir)
        val outputCopy = File.createTempFile("architect_out_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(original)?.use { input ->
                sourceCopy.outputStream().use { dest -> input.copyTo(dest, 64 * 1024) }
            } ?: error("Невозможно прочитать исходный PDF")
            PDDocument.load(sourceCopy).use { document ->
                // Source PDF pages are not rasterized and therefore retain their
                // vector geometry, embedded text, links and original page sizes.
                for (index in 0 until document.numberOfPages) {
                    val edits = editsStore.load(original, index)
                    if (edits.hasVisibleMarkup()) {
                        val page = document.getPage(index)
                        val crop = page.cropBox
                        val space = PageTransform.forPage(
                            crop.lowerLeftX, crop.lowerLeftY,
                            crop.width, crop.height, page.rotation,
                        )
                        PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND,
                            true, true,
                        ).use { stream ->
                            stream.saveGraphicsState()
                            // Work in screen coordinates (top left / y down),
                            // but append to actual PDF user space.
                            stream.transform(Matrix(space.a, space.b, space.c, space.d,
                                space.e, space.f))
                            drawInk(stream, edits.strokes, space)
                            drawSegments(document, stream, edits, space)
                            drawAreas(document, stream, edits, space)
                            drawImages(document, stream, edits, space)
                            drawTextStamps(document, stream, edits, space)
                            stream.restoreGraphicsState()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        reportProgress(index + 1, document.numberOfPages)
                    }
                }
                document.save(outputCopy)
            }
            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                outputCopy.inputStream().use { source -> source.copyTo(out, 64 * 1024) }
            } ?: error("Невозможно записать результирующий PDF")
        } finally {
            sourceCopy.delete()
            outputCopy.delete()
        }
    }

    private fun drawInk(
        stream: PDPageContentStream, strokes: List<InkStroke>, space: PageTransform,
    ) {
        stream.setLineCapStyle(1)
        stream.setLineJoinStyle(1)
        strokes.forEach { s ->
            if (s.points.isEmpty()) return@forEach
            stream.saveGraphicsState()
            val color = s.color
            stream.setStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
            stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                strokingAlphaConstant = s.opacity.coerceIn(0f, 1f)
            })
            if (s.points.size == 1) {
                // A near-zero line with a round cap creates a visible pen dot.
                val x = s.points[0].x * space.widthPt
                val y = s.points[0].y * space.heightPt
                stream.setLineWidth(s.widthPt.coerceIn(0.3f, 40f))
                stream.moveTo(x, y)
                stream.lineTo(x + 0.001f, y)
                stream.stroke()
            } else {
                s.points.zipWithNext().forEach { (a, b) ->
                    val pressure = ((a.pressure + b.pressure) / 2f).coerceIn(0f, 1.6f)
                    stream.setLineWidth(s.widthPt.coerceIn(0.3f, 40f) *
                        (0.55f + 0.45f * pressure))
                    stream.moveTo(a.x * space.widthPt, a.y * space.heightPt)
                    stream.lineTo(b.x * space.widthPt, b.y * space.heightPt)
                    stream.stroke()
                }
            }
            stream.restoreGraphicsState()
        }
    }

    private fun drawSegments(
        document: PDDocument, stream: PDPageContentStream,
        edits: PageEdits, space: PageTransform,
    ) {
        fun segment(s: Segment, label: String, red: Int, green: Int, blue: Int) {
            stream.saveGraphicsState()
            stream.setStrokingColor(red, green, blue)
            stream.setLineWidth(1.3f)
            val x1 = s.start.x * space.widthPt
            val y1 = s.start.y * space.heightPt
            val x2 = s.end.x * space.widthPt
            val y2 = s.end.y * space.heightPt
            stream.moveTo(x1, y1)
            stream.lineTo(x2, y2)
            stream.stroke()
            stream.restoreGraphicsState()
            drawTextBitmap(document, stream, label, Color.rgb(red, green, blue),
                10f, (x1 + x2) / 2f + 5f,
                (y1 + y2) / 2f - 5f,
                max(60f, space.widthPt * 0.55f))
        }
        edits.calibration?.let { segment(it.reference,
            "Эталон ${MeasureMath.label(it.meters)}", 12, 130, 110) }
        edits.measurements.forEach { seg ->
            segment(seg, MeasureMath.label(
                MeasureMath.meters(seg, edits.calibration,
                    space.widthPt.toInt(), space.heightPt.toInt())), 190, 78, 15)
        }
    }

    private fun drawAreas(
        document: PDDocument, stream: PDPageContentStream,
        edits: PageEdits, space: PageTransform,
    ) {
        edits.areas.forEach { area ->
            if (area.points.size < 3) return@forEach
            stream.saveGraphicsState()
            stream.setStrokingColor(12, 130, 110)
            stream.setLineWidth(1.5f)
            val first = area.points.first()
            stream.moveTo(first.x * space.widthPt, first.y * space.heightPt)
            area.points.drop(1).forEach { point ->
                stream.lineTo(point.x * space.widthPt, point.y * space.heightPt)
            }
            stream.closePath()
            stream.stroke()
            stream.restoreGraphicsState()
            val centroidX = area.points.map { it.x }.average().toFloat() * space.widthPt
            val centroidY = area.points.map { it.y }.average().toFloat() * space.heightPt
            drawTextBitmap(document, stream,
                MeasureMath.areaLabel(MeasureMath.squareMeters(
                    area, edits.calibration, space.widthPt.toInt(), space.heightPt.toInt())),
                Color.rgb(12, 130, 110), 10f, centroidX, centroidY, 120f)
        }
    }

    private fun drawImages(
        document: PDDocument, stream: PDPageContentStream,
        edits: PageEdits, space: PageTransform,
    ) {
        edits.images.forEach { stamp ->
            val bitmap = images.load(stamp.fileName) ?: return@forEach
            val obj = LosslessFactory.createFromImage(document, bitmap)
            val x = stamp.x * space.widthPt
            val y = stamp.y * space.heightPt
            val width = stamp.width * space.widthPt
            val height = stamp.height * space.heightPt
            // Bitmap top left -> on-screen top left; correct even on rotated PDF.
            stream.drawImage(obj, Matrix(width, 0f, 0f, -height, x, y + height))
        }
    }

    private fun drawTextStamps(
        document: PDDocument, stream: PDPageContentStream,
        edits: PageEdits, space: PageTransform,
    ) {
        edits.texts.forEach { stamp ->
            drawTextBitmap(document, stream, stamp.text, stamp.color,
                stamp.sizePt, stamp.x * space.widthPt,
                stamp.y * space.heightPt, max(50f, space.widthPt * (1f - stamp.x)))
        }
    }

    private fun drawTextBitmap(
        document: PDDocument, stream: PDPageContentStream, text: String,
        color: Int, sizePt: Float, xPt: Float, baselinePt: Float, maxWidthPt: Float,
    ) {
        if (text.isBlank() || maxWidthPt < 8f) return
        val density = 2f
        val lines = text.take(500)
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = sizePt.coerceIn(6f, 72f) * density
        }
        val boundsW = (maxWidthPt * density).toInt().coerceIn(1, 2600)
        val layout = StaticLayout.Builder.obtain(lines, 0, lines.length, paint, boundsW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(15)
            .build()
        val bitmapW = max(1, min(boundsW, layout.width))
        val bitmapH = max(1, min(layout.height, 1500))
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { layout.draw(this) }
            val image = LosslessFactory.createFromImage(document, bitmap)
            val widthPt = bitmapW / density
            val heightPt = bitmapH / density
            // In the editor stamp.y is the baseline, not the top of the first glyph.
            val topPt = baselinePt - sizePt * 0.86f
            stream.drawImage(image, Matrix(widthPt, 0f, 0f, -heightPt,
                xPt, topPt + heightPt))
        } finally {
            bitmap.recycle()
        }
    }
}
