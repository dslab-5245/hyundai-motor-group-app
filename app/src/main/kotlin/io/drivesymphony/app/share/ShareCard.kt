package io.drivesymphony.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.theory.Genre
import java.io.File
import java.io.FileOutputStream

/**
 * Renders "여정의 곡" as a shareable image (portrait card) and as a square artwork for the car's media center.
 * Pure android.graphics so the service can use it without Compose.
 */
object ShareCard {
    private const val BG = 0xFF0B0F14.toInt()
    private const val TEXT = 0xFFF2F4F7.toInt()
    private const val MUTED = 0xFFA7B1BE.toInt()
    private const val TRACK = 0xFF1E2731.toInt()

    fun accentArgb(genre: Genre): Int = when (genre) {
        Genre.LOFI -> 0xFFE8A860.toInt()
        Genre.JAZZ -> 0xFF8C9BFF.toInt()
        Genre.AMBIENT -> 0xFF5FD1C2.toInt()
    }

    fun caption(s: TripSummary): String = buildString {
        append("\uD83C\uDFA7 ${s.title}\n")
        append("평균 Flow ${(s.averageFlow * 100).toInt()} · 최장 부드러운 구간 ${s.bestStreakSec.toInt()}초 · 멜로디 ${s.bloomSec.toInt()}초\n")
        append("${s.genre.label} · ${s.keyLabel} · ${s.bpm.toInt()} BPM · ${s.bars}마디\n")
        append("#DriveSymphony #PleosAppMarket")
    }

    /** 1080 x 1350 portrait card (feed-friendly). */
    fun render(s: TripSummary): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = accentArgb(s.genre)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), BG, blend(BG, accent, 0.18f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 44f; strokeCap = Paint.Cap.ROUND }
        val rect = RectF(w / 2f - 230f, 150f, w / 2f + 230f, 610f)
        ring.color = TRACK; c.drawArc(rect, 135f, 270f, false, ring)
        ring.color = accent; c.drawArc(rect, 135f, 270f * s.averageFlow.coerceIn(0.02f, 1f), false, ring)

        val big = text(TEXT, 150f, Typeface.DEFAULT).apply { textAlign = Paint.Align.CENTER }
        c.drawText("${(s.averageFlow * 100).toInt()}", w / 2f, 430f, big)
        c.drawText("FLOW", w / 2f, 500f, text(MUTED, 34f, Typeface.DEFAULT).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.25f })

        val title = text(TEXT, 64f, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        c.drawText(fit(s.title, title, w - 120f), w / 2f, 740f, title)
        c.drawText("${s.genre.label} · ${s.keyLabel} · ${s.bpm.toInt()} BPM · ${s.bars}마디", w / 2f, 810f, text(MUTED, 36f, Typeface.DEFAULT).apply { textAlign = Paint.Align.CENTER })

        val label = text(MUTED, 36f, Typeface.DEFAULT)
        val value = text(TEXT, 40f, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.RIGHT }
        val rows = listOf(
            "최장 부드러운 구간" to "${s.bestStreakSec.toInt()}초",
            "멜로디가 열린 시간" to "${s.bloomSec.toInt()}초",
            "연주 길이" to "${(s.durationSec / 60).toInt()}분 ${(s.durationSec % 60).toInt()}초",
            "음악이 가라앉은 순간" to "${s.eventCounts.filterKeys { it.isPenalty }.values.sum()}회",
        )
        var y = 920f
        for ((k, v) in rows) { c.drawText(k, 90f, y, label); c.drawText(v, w - 90f, y, value); y += 76f }
        c.drawText("Drive Symphony · 주행이 연주가 되는 차", w / 2f, h - 70f, text(blend(MUTED, accent, 0.5f), 32f, Typeface.DEFAULT).apply { textAlign = Paint.Align.CENTER })
        return bmp
    }

    /** 512 x 512 artwork for MediaSession metadata (media center / now playing). */
    fun artwork(title: String, genre: Genre): Bitmap {
        val n = 512
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = accentArgb(genre)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, n.toFloat(), n.toFloat(), blend(BG, accent, 0.35f), BG, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), bg)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 26f; strokeCap = Paint.Cap.ROUND; color = accent }
        c.drawArc(RectF(136f, 96f, 376f, 336f), 135f, 270f, false, ring)
        val t = text(TEXT, 30f, Typeface.DEFAULT_BOLD).apply { textAlign = Paint.Align.CENTER }
        c.drawText(fit(title, t, n - 48f), n / 2f, 410f, t)
        c.drawText(genre.label.uppercase(), n / 2f, 456f, text(MUTED, 22f, Typeface.DEFAULT).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.2f })
        return bmp
    }

    /** Writes the card into cache/share and returns an ACTION_SEND intent with a FileProvider URI. */
    fun shareIntent(context: Context, s: TripSummary): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".png") }?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        val file = File(dir, "drive-symphony-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { render(s).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption(s))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun text(color: Int, size: Float, face: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = face }

    private fun fit(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var t = s
        while (t.length > 1 && p.measureText("$t\u2026") > maxWidth) t = t.dropLast(1)
        return "$t\u2026"
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(16), ch(8), ch(0))
    }
}
