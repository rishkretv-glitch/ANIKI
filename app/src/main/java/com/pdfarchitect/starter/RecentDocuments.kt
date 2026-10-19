package com.pdfarchitect.starter

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(val uri: Uri, val title: String)

/** SAF URIs are stored only in this app's private SharedPreferences. */
class RecentDocuments(context: Context) {
    private val prefs = context.getSharedPreferences("recent_docs", Context.MODE_PRIVATE)

    fun list(): List<RecentDocument> = runCatching {
        val array = JSONArray(prefs.getString("documents", "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { o ->
                val raw = o.optString("uri")
                if (raw.isBlank()) null else RecentDocument(Uri.parse(raw), o.optString("title", "PDF"))
            }
        }
    }.getOrDefault(emptyList())

    fun add(uri: Uri, title: String) {
        val recent = (listOf(RecentDocument(uri, title)) + list().filter { it.uri != uri }).take(12)
        val encoded = JSONArray()
        recent.forEach { encoded.put(JSONObject().put("uri", it.uri.toString()).put("title", it.title)) }
        prefs.edit().putString("documents", encoded.toString()).apply()
    }
}
