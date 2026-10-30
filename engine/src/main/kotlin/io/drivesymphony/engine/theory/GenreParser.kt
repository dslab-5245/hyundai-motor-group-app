package io.drivesymphony.engine.theory

/** Maps free text (voice command, media search, Gleo argument) to a [Genre]. Null when nothing matches. */
object GenreParser {
    fun parse(text: String?): Genre? {
        val t = text?.lowercase()?.trim() ?: return null
        if (t.isEmpty()) return null
        return when {
            t.contains("jazz") || t.contains("재즈") || t.contains("째즈") -> Genre.JAZZ
            t.contains("ambient") || t.contains("앰비언트") || t.contains("엠비언트") -> Genre.AMBIENT
            t.contains("lofi") || t.contains("lo-fi") || t.contains("lo fi") || t.contains("로파이") || t.contains("로우파이") -> Genre.LOFI
            else -> Genre.values().firstOrNull { it.name.equals(t, ignoreCase = true) || it.label.equals(t, ignoreCase = true) }
        }
    }
}
