package com.pdfarchitect.starter

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Offline, text-layer-only search. Scans need OCR before they can be searched. */
data class PdfSearchHit(val pageIndex: Int, val snippet: String)

class PdfTextSearch(private val context: Context) {
    suspend fun search(uri: Uri, query: String, limit: Int = 100): List<PdfSearchHit> =
        withContext(Dispatchers.IO) {
            val needle = query.trim().take(100)
            if (needle.isBlank()) return@withContext emptyList()
            PDFBoxResourceLoader.init(context.applicationContext)
            val working = File.createTempFile("pdf_search_", ".pdf", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    working.outputStream().use { dest -> source.copyTo(dest, 64 * 1024) }
                } ?: error("Нет доступа к PDF")
                PDDocument.load(working).use { pdf ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    val hits = mutableListOf<PdfSearchHit>()
                    for (p in 1..pdf.numberOfPages) {
                        coroutineContext.ensureActive()
                        stripper.startPage = p
                        stripper.endPage = p
                        val pageText = stripper.getText(pdf).replace(Regex("\\s+"), " ")
                        var from = 0
                        while (hits.size < limit) {
                            val at = pageText.indexOf(needle, startIndex = from, ignoreCase = true)
                            if (at < 0) break
                            val before = (at - 50).coerceAtLeast(0)
                            val after = (at + needle.length + 90).coerceAtMost(pageText.length)
                            hits += PdfSearchHit(p - 1, pageText.substring(before, after))
                            from = at + needle.length
                        }
                        if (hits.size >= limit) break
                    }
                    hits
                }
            } finally {
                working.delete()
            }
        }
}
