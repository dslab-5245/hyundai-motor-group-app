package io.drivesymphony.app

import android.content.Context
import io.drivesymphony.engine.theory.Genre

/** Tiny SharedPreferences-backed settings. No DataStore dependency to keep the APK lean. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("drive_symphony", Context.MODE_PRIVATE)

    var genre: Genre
        get() = runCatching { Genre.valueOf(prefs.getString(KEY_GENRE, Genre.LOFI.name)!!) }.getOrDefault(Genre.LOFI)
        set(v) = prefs.edit().putString(KEY_GENRE, v.name).apply()

    /** 0 = gentle, 1 = standard, 2 = strong feedback (scales how hard harsh events hit the music). */
    var feedbackLevel: Int
        get() = prefs.getInt(KEY_FEEDBACK, 1)
        set(v) = prefs.edit().putInt(KEY_FEEDBACK, v.coerceIn(0, 2)).apply()

    /** When true the demo scenario replaces vehicle data (for PT / emulator without Virtual Console). */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO, false)
        set(v) = prefs.edit().putBoolean(KEY_DEMO, v).apply()

    var demoScenarioId: String
        get() = prefs.getString(KEY_DEMO_ID, "story") ?: "story"
        set(v) = prefs.edit().putString(KEY_DEMO_ID, v).apply()

    /** Remix mode: content URI of the last picked local track (null = generative mode). */
    var remixTrackUri: String?
        get() = prefs.getString(KEY_REMIX, null)
        set(v) = prefs.edit().putString(KEY_REMIX, v).apply()

    var autoStartOnDrive: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSTART, v).apply()

    companion object {
        private const val KEY_GENRE = "genre"
        private const val KEY_FEEDBACK = "feedback"
        private const val KEY_DEMO = "demo"
        private const val KEY_DEMO_ID = "demo_id"
        private const val KEY_REMIX = "remix_uri"
        private const val KEY_AUTOSTART = "autostart"
    }
}
