package com.zobo.burung.explore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A landmark line for a 동: "서울숲 · 서울특별시 성동구에 있는 공원". */
data class Story(val title: String, val line: String)

/** A Wikipedia page near a 동, as the geosearch returns it (pure data, for [LocalStories.pick]). */
data class StoryCandidate(val title: String, val description: String?, val extract: String?, val distanceRank: Int)

/**
 * "동네 이야기": the most notable Korean Wikipedia article near the centre of a 읍·면·동, shown when
 * the 동 is first explored. Only the 동's label point is sent (never the car's position), results are
 * cached on disk per 동 (also "nothing found"), and it is off when the driver turns it off in Settings.
 */
class LocalStories(context: Context) {
    private val cacheFile = File(context.filesDir, "stories.json")
    private val mutex = Mutex()
    private var cache: JSONObject? = null

    /** Story for the 동 [key] (its code) at ([lat], [lng]); null when none or offline. */
    suspend fun storyFor(key: String, lat: Double, lng: Double): Story? = mutex.withLock {
        val c = cache ?: load().also { cache = it }
        if (c.has(key)) return@withLock c.optJSONObject(key)?.let { Story(it.getString("t"), it.getString("l")) }
        val story = runCatching { fetch(lat, lng) }
            .onFailure { Log.w(TAG, "story lookup failed: ${it.message}") }
            .getOrElse { return@withLock null }  // offline: try again next time, don't cache
        c.put(key, story?.let { JSONObject().put("t", it.title).put("l", it.line) } ?: JSONObject.NULL)
        withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(c.toString()) } }
        story
    }

    private suspend fun load(): JSONObject = withContext(Dispatchers.IO) {
        runCatching { JSONObject(cacheFile.readText()) }.getOrElse { JSONObject() }
    }

    private suspend fun fetch(lat: Double, lng: Double): Story? = withContext(Dispatchers.IO) {
        val url = URL(
            "https://ko.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
                "&generator=geosearch&ggscoord=" + URLEncoder.encode(String.format(Locale.US, "%.4f|%.4f", lat, lng), "UTF-8") +
                "&ggsradius=$RADIUS_M&ggslimit=$LIMIT&prop=description%7Cextracts&exintro=1&explaintext=1&exsentences=1&exlimit=$LIMIT",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        // Wikimedia asks API clients to identify themselves.
        conn.setRequestProperty("User-Agent", "FogDrive/0.1 (Pleos Connect app; team zobo)")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages") ?: return@withContext null
            val candidates = (0 until pages.length()).map { i ->
                val p = pages.getJSONObject(i)
                StoryCandidate(
                    title = p.getString("title"),
                    description = p.optString("description").takeIf { it.isNotBlank() },
                    extract = p.optString("extract").takeIf { it.isNotBlank() },
                    distanceRank = p.optInt("index", i),
                )
            }
            pick(candidates)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "FogStories"
        /** About one 동 across: farther pages belong to the neighbours (a temple two districts over). */
        private const val RADIUS_M = 1_500
        private const val LIMIT = 15
        private const val TIMEOUT_MS = 5_000
        private const val MAX_LINE = 60

        /** Administrative areas and stations say little about a place; landmarks come first. */
        private val ADMIN_SUFFIX = Regex("(동|가|리|읍|면|구|시|군|역|선|로|길|IC|JC|나들목|분기점)$")
        private val ADMIN_WORDS = Regex("행정동|법정동|행정 구역|도로|노선|철도역|지하철역|버스|학교|아파트")

        /**
         * The nearest page that reads like a landmark, with a one-line description: its short
         * description if it has one, else the first sentence of the intro, trimmed to [MAX_LINE].
         */
        fun pick(candidates: List<StoryCandidate>): Story? = candidates
            .sortedBy { it.distanceRank }
            .firstOrNull { c ->
                val text = (c.description ?: "") + " " + (c.extract ?: "")
                !ADMIN_SUFFIX.containsMatchIn(c.title.substringBefore(" (")) &&
                    !ADMIN_WORDS.containsMatchIn(text) && (c.description != null || c.extract != null)
            }
            ?.let { c ->
                val line = (c.description ?: firstSentence(c.extract!!)).trim()
                Story(c.title, if (line.length > MAX_LINE) line.take(MAX_LINE - 1) + "…" else line)
            }

        /** Korean intros end their first sentence with "다."; fall back to a plain period. */
        private fun firstSentence(text: String): String {
            val i = text.indexOf("다.")
            return if (i >= 0) text.substring(0, i + 2) else text.substringBefore(". ")
        }
    }
}
