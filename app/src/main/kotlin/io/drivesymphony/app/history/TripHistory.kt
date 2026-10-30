package io.drivesymphony.app.history

import android.content.Context
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.theory.Genre
import org.json.JSONArray
import org.json.JSONObject

/** One finished trip composition, as kept in the local history. */
data class TripRecord(
    val title: String,
    val genre: Genre,
    val keyLabel: String,
    val bpm: Int,
    val durationSec: Int,
    val bars: Int,
    val averageFlow: Float,
    val bestStreakSec: Int,
    val bloomSec: Int,
    val penalties: Int,
    val endedAtMs: Long,
)

/** Keeps the last [MAX] trip compositions in SharedPreferences (JSON) for the composer's "여정의 곡 기록" card. */
class TripHistory(context: Context) {
    private val prefs = context.getSharedPreferences("drive_symphony_trips", Context.MODE_PRIVATE)

    fun add(s: TripSummary, endedAtMs: Long = System.currentTimeMillis()) {
        val arr = JSONArray()
        arr.put(
            JSONObject()
                .put("title", s.title).put("genre", s.genre.name).put("key", s.keyLabel).put("bpm", s.bpm.toInt())
                .put("dur", s.durationSec.toInt()).put("bars", s.bars).put("flow", s.averageFlow.toDouble())
                .put("streak", s.bestStreakSec.toInt()).put("bloom", s.bloomSec.toInt())
                .put("pen", s.eventCounts.filterKeys { it.isPenalty }.values.sum()).put("t", endedAtMs)
        )
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until minOf(old.length(), MAX - 1)) old.optJSONObject(i)?.let { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun all(): List<TripRecord> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val out = ArrayList<TripRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val genre = runCatching { Genre.valueOf(o.optString("genre", Genre.LOFI.name)) }.getOrDefault(Genre.LOFI)
            out.add(
                TripRecord(
                    title = o.optString("title", ""), genre = genre, keyLabel = o.optString("key", ""), bpm = o.optInt("bpm", 0),
                    durationSec = o.optInt("dur", 0), bars = o.optInt("bars", 0), averageFlow = o.optDouble("flow", 0.0).toFloat(),
                    bestStreakSec = o.optInt("streak", 0), bloomSec = o.optInt("bloom", 0), penalties = o.optInt("pen", 0), endedAtMs = o.optLong("t", 0L),
                )
            )
        }
        return out
    }

    fun clear() { prefs.edit().remove(KEY).apply() }

    companion object { private const val KEY = "trips"; const val MAX = 10 }
}
