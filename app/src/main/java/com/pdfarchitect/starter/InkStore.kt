package com.pdfarchitect.starter

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Atomic, versioned, private sidecars; annotations do not modify their original PDF. */
class InkStore(context: Context) {
    // Keep the same directory to allow migrating v1 strokes from the starter project.
    private val folder = File(context.filesDir, "ink-v1").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun load(uri: Uri, pageIndex: Int): PageEdits = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = entryFile(uri, pageIndex)
            if (!file.exists()) return@withLock PageEdits()
            val root = JSONObject(AtomicFile(file).openRead().use {
                it.readBytes().toString(Charsets.UTF_8)
            })
            when (root.optInt("schema")) {
                1 -> PageEdits(strokes = decodeStrokes(root.getJSONArray("strokes"), old = true))
                2, 3, 4 -> {
                    val texts = root.optJSONArray("texts") ?: JSONArray()
                    val measurements = root.optJSONArray("measurements") ?: JSONArray()
                    val images = root.optJSONArray("images") ?: JSONArray()
                    val areaItems = root.optJSONArray("areas") ?: JSONArray()
                    val calibrationJson = root.optJSONObject("calibration")
                    PageEdits(
                        strokes = decodeStrokes(root.optJSONArray("strokes") ?: JSONArray(), old = false),
                        texts = (0 until texts.length()).mapNotNull { i ->
                            texts.optJSONObject(i)?.let { j -> TextStamp(
                                j.optDouble("x").toFloat().coerceIn(0f, 1f),
                                j.optDouble("y").toFloat().coerceIn(0f, 1f),
                                j.optString("text").take(3000), j.optInt("color", 0xFF1450B4.toInt()),
                                j.optDouble("size", 13.0).toFloat().coerceIn(6f, 72f),
                            ) }
                        },
                        images = (0 until images.length()).mapNotNull { i ->
                            images.optJSONObject(i)?.let { j ->
                                val name = j.optString("file")
                                if (!name.matches(Regex("[a-f0-9-]{36}\\.png"))) null
                                else ImageStamp(name,
                                    j.optDouble("x").toFloat().coerceIn(0f, 1f),
                                    j.optDouble("y").toFloat().coerceIn(0f, 1f),
                                    j.optDouble("w").toFloat().coerceIn(0.01f, 1f),
                                    j.optDouble("h").toFloat().coerceIn(0.01f, 1f))
                            }
                        },
                        measurements = (0 until measurements.length()).mapNotNull { i ->
                            measurements.optJSONArray(i)?.let(::decodeSegment)
                        },
                        areas = (0 until areaItems.length()).mapNotNull { i ->
                            val polygon = areaItems.optJSONArray(i) ?: return@mapNotNull null
                            val points = (0 until polygon.length()).mapNotNull { j ->
                                polygon.optJSONArray(j)?.let(::decodePoint)
                            }.take(2_000)
                            if (points.size >= 3) AreaPolygon(points) else null
                        },
                        calibration = calibrationJson?.let { j ->
                            val segment = decodeSegment(j.optJSONArray("segment") ?: return@let null)
                            val meters = j.optDouble("meters")
                            if (segment != null && meters.isFinite() && meters > 0) {
                                Calibration(segment, meters)
                            } else null
                        },
                    )
                }
                else -> error("Неизвестная версия файла аннотаций")
            }
        }
    }

    suspend fun save(uri: Uri, pageIndex: Int, edits: PageEdits) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = JSONObject().put("schema", 4)
            root.put("strokes", JSONArray().apply {
                edits.strokes.forEach { s -> put(JSONObject()
                    .put("color", s.color).put("width", s.widthPt.toDouble())
                    .put("opacity", s.opacity.toDouble())
                    .put("points", JSONArray().apply {
                        s.points.forEach { p -> put(encodePoint(p)) }
                    })) }
            })
            root.put("texts", JSONArray().apply {
                edits.texts.forEach { t -> put(JSONObject()
                    .put("x", t.x.toDouble()).put("y", t.y.toDouble())
                    .put("text", t.text).put("color", t.color).put("size", t.sizePt.toDouble())) }
            })
            root.put("images", JSONArray().apply {
                edits.images.forEach { img -> put(JSONObject()
                    .put("file", img.fileName).put("x", img.x.toDouble()).put("y", img.y.toDouble())
                    .put("w", img.width.toDouble()).put("h", img.height.toDouble())) }
            })
            root.put("measurements", JSONArray().apply {
                edits.measurements.forEach { put(encodeSegment(it)) }
            })
            root.put("areas", JSONArray().apply {
                edits.areas.forEach { polygon -> put(JSONArray().apply {
                    polygon.points.forEach { put(encodePoint(it)) }
                }) }
            })
            root.put("calibration", edits.calibration?.let { c -> JSONObject()
                .put("meters", c.meters).put("segment", encodeSegment(c.reference)) })
            val atomic = AtomicFile(entryFile(uri, pageIndex))
            val stream = atomic.startWrite()
            try {
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    private fun decodeStrokes(strokes: JSONArray, old: Boolean): List<InkStroke> =
        (0 until strokes.length()).mapNotNull { i ->
            val json = strokes.opt(i)
            val obj = if (!old) json as? JSONObject else null
            val points = if (old) json as? JSONArray else obj?.optJSONArray("points")
            if (points == null) return@mapNotNull null
            val decoded = (0 until points.length()).mapNotNull { index ->
                points.optJSONArray(index)?.let(::decodePoint)
            }.take(100_000)
            if (decoded.isEmpty()) null else InkStroke(
                decoded,
                obj?.optInt("color", 0xFF2370ED.toInt()) ?: 0xFF2370ED.toInt(),
                (obj?.optDouble("width", 2.4) ?: 2.4).toFloat().coerceIn(0.3f, 40f),
                (obj?.optDouble("opacity", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f),
            )
        }

    private fun encodePoint(p: InkPoint): JSONArray = JSONArray()
        .put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble())

    private fun decodePoint(p: JSONArray): InkPoint? = runCatching {
        InkPoint(p.getDouble(0).toFloat().coerceIn(0f, 1f),
            p.getDouble(1).toFloat().coerceIn(0f, 1f),
            p.optDouble(2, 1.0).toFloat().coerceIn(0f, 2f))
    }.getOrNull()

    private fun encodeSegment(s: Segment): JSONArray = JSONArray()
        .put(encodePoint(s.start)).put(encodePoint(s.end))

    private fun decodeSegment(s: JSONArray): Segment? {
        val a = s.optJSONArray(0)?.let(::decodePoint)
        val b = s.optJSONArray(1)?.let(::decodePoint)
        return if (a != null && b != null) Segment(a, b) else null
    }

    private fun entryFile(uri: Uri, pageIndex: Int): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(folder, "${key}_$pageIndex.json")
    }
}
