package com.pdfarchitect.starter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Imports images into private storage; no persistent external image permissions needed. */
class ImageAssets(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "page-images").apply { mkdirs() }
    private val lock = Any()
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun importImage(uri: Uri): Pair<String, Float> = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(uri) ?: error("Нельзя прочитать изображение")
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Некорректное изображение")
        val decode = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (max(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 1600) {
            decode.inSampleSize *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
            ?: error("Формат изображения не поддерживается")
        val name = "${UUID.randomUUID()}.png"
        try {
            File(directory, name).outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Не удалось сохранить изображение"
                }
            }
            synchronized(lock) { cache.put(name, bitmap) }
            name to bitmap.width.toFloat() / bitmap.height
        } catch (error: Exception) {
            File(directory, name).delete()
            bitmap.recycle()
            throw error
        }
    }

    fun load(name: String): Bitmap? {
        if (!name.matches(Regex("[a-f0-9-]{36}\\.png"))) return null
        synchronized(lock) { cache.get(name)?.let { return it } }
        val path = File(directory, name)
        if (!path.isFile) return null
        val bitmap = BitmapFactory.decodeFile(path.absolutePath) ?: return null
        synchronized(lock) { cache.put(name, bitmap) }
        return bitmap
    }
}
