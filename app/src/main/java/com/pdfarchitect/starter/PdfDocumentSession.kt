package com.pdfarchitect.starter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RenderedPage(val bitmap: Bitmap, val widthPt: Int, val heightPt: Int)

/** Narrow renderer adapter; production can swap in a tiled Apryse/Nutrient engine. */
class PdfDocumentSession private constructor(
    private val renderer: PdfRenderer,
    private val temporaryFile: File?,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    val pageCount = renderer.pageCount

    fun renderPage(pageIndex: Int, maxPixels: Int = 5_500_000): RenderedPage = synchronized(lock) {
        check(!closed) { "PDF уже закрыт" }
        require(pageIndex in 0 until pageCount)
        renderer.openPage(pageIndex).use { page ->
            val scale = minOf(
                3200.0 / page.width,
                3200.0 / page.height,
                sqrt(maxPixels.toDouble() / (page.width.toDouble() * page.height)),
            )
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPage(bmp, page.width, page.height)
            } catch (error: Throwable) {
                bmp.recycle()
                throw error
            }
        }
    }

    /**
     * Explicitly FLATTENED PDF copy: new PDF pages contain rendered pixels + annotations.
     * Existing selectable text, hyperlinks, form widgets and vector objects are NOT preserved.
     * This method never overwrites the input document and uses bounded per-page raster memory.
     */
    suspend fun exportFlattened(
        context: Context,
        originalUri: Uri,
        outputUri: Uri,
        store: InkStore,
        imageAssets: ImageAssets,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val scratch = File.createTempFile("pdf_architect_export_", ".pdf", context.cacheDir)
        val output = PdfDocument()
        try {
            for (i in 0 until pageCount) {
                val source = renderPage(i, maxPixels = 4_000_000)
                try {
                    val edits = store.load(originalUri, i)
                    val info = PdfDocument.PageInfo.Builder(
                        source.widthPt, source.heightPt, i + 1
                    ).create()
                    val page = output.startPage(info)
                    try {
                        val bounds = RectF(0f, 0f, source.widthPt.toFloat(), source.heightPt.toFloat())
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawBitmap(source.bitmap, null, bounds,
                            Paint(Paint.FILTER_BITMAP_FLAG))
                        AnnotationPainter.draw(page.canvas, bounds, edits,
                            source.widthPt, source.heightPt, imageAssets::load)
                    } finally {
                        output.finishPage(page)
                    }
                } finally {
                    source.bitmap.recycle()
                }
                withContext(Dispatchers.Main) { progress(i + 1, pageCount) }
            }
            scratch.outputStream().use { output.writeTo(it) }
            context.contentResolver.openOutputStream(outputUri, "w")?.use { dest ->
                scratch.inputStream().use { src -> src.copyTo(dest, bufferSize = 64 * 1024) }
            } ?: error("Не удалось открыть файл для сохранения")
        } finally {
            output.close()
            scratch.delete()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { renderer.close() }
            finally { temporaryFile?.delete() }
        }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): PdfDocumentSession =
            withContext(Dispatchers.IO) {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Не удалось прочитать PDF")
                try {
                    PdfDocumentSession(PdfRenderer(descriptor), null)
                } catch (nonSeekable: IllegalArgumentException) {
                    descriptor.close()
                    val copy = File.createTempFile("pdf_architect_", ".pdf", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            copy.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Не удалось прочитать PDF из облака")
                        val local = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                        try { PdfDocumentSession(PdfRenderer(local), copy) }
                        catch (error: Throwable) { local.close(); throw error }
                    } catch (error: Throwable) {
                        copy.delete()
                        throw error
                    }
                } catch (error: Throwable) {
                    descriptor.close()
                    throw error
                }
            }
    }
}
