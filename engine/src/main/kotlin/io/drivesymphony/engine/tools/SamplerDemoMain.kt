package io.drivesymphony.engine.tools

import io.drivesymphony.engine.dsp.DrumSampler
import io.drivesymphony.engine.dsp.SampleBank
import io.drivesymphony.engine.dsp.SampleInstrument
import io.drivesymphony.engine.dsp.Sampler
import io.drivesymphony.engine.dsp.dbToGain
import io.drivesymphony.engine.music.Bass
import io.drivesymphony.engine.music.Drums
import io.drivesymphony.engine.music.EPiano
import io.drivesymphony.engine.music.StereoOut
import io.drivesymphony.engine.wav.WavWriter
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Renders a 12-second demo WAV per instrument of the bundled FluidR3 bank (scale, then chords; a simple pattern for
 * the drum kits) plus the synth EPiano playing the same phrase as a level reference. Prints peak / RMS per file.
 *
 * Usage: SamplerDemoMain <outputDir>      (Gradle: `gradlew :engine:samplerDemo`)
 */
object SamplerDemoMain {
    const val SAMPLE_RATE = 48000
    const val SECONDS = 12f

    /** Peak / RMS / DC measured at unity gain; the WAV itself is written normalised to [TARGET_PEAK] dBFS. */
    class Stats(val file: File, val peak: Float, val rms: Float, val dc: Float, val finite: Boolean, val appliedDb: Float) {
        override fun toString() = String.format("%-24s at gain 1: peak %6.2f dBFS  rms %6.2f dBFS  dc %+.5f  %s   (wav written %+.1f dB)",
            file.name, db(peak), db(rms), dc, if (finite) "ok" else "NaN/Inf!", appliedDb)
    }
    const val TARGET_PEAK = -3f

    private fun db(x: Float) = if (x <= 0f) -120f else 20f * log10(x)

    /** Scale (8 x 0.5 s) then four chords (4 x 2 s) inside the instrument's key range. */
    private fun phrase(lowKey: Int, highKey: Int): List<Triple<Float, Int, Float>> {   // (time, midi, duration)
        var root = 60
        while (root - 12 >= lowKey + 0 && root > lowKey + 7) root -= 12
        while (root < lowKey) root += 12
        if (root + 12 > highKey) root = ((lowKey + 12) / 12) * 12
        val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        val events = ArrayList<Triple<Float, Int, Float>>()
        for ((i, s) in scale.withIndex()) {
            val m = (root + s).coerceIn(lowKey, highKey)
            events.add(Triple(i * 0.5f, m, 0.45f))
        }
        val chords = listOf(intArrayOf(0, 4, 7, 11), intArrayOf(-3, 0, 4, 7), intArrayOf(-7, -3, 0, 4), intArrayOf(-5, -1, 2, 5))
        for ((c, ch) in chords.withIndex()) {
            val t = 4f + c * 2f
            val notes = ch.map { root + it }.filter { it in lowKey..highKey }.distinct()
            val use = if (highKey - lowKey < 24) notes.take(2) else notes   // bass ranges: dyads only
            for (m in use) events.add(Triple(t, m, 1.9f))
        }
        return events
    }

    fun renderMelodic(file: File, inst: SampleInstrument, levelDb: Float = 0f): Stats {
        val s = Sampler(SAMPLE_RATE.toFloat(), inst, 8, 1).apply { this.levelDb = levelDb }
        return render(file, phrase(inst.lowKey, inst.highKey), { m, v, d -> s.noteOn(m, v, d) }) { out -> s.render(out, 1f) }
    }

    /** Synth references: the same phrase / pattern through the existing instruments, so levels can be matched. */
    fun renderSynthEp(file: File): Stats {
        val ep = EPiano(SAMPLE_RATE.toFloat(), 1)
        return render(file, phrase(36, 96), { m, v, d -> ep.noteOn(m, v, d) }) { out -> ep.render(out, 1f) }
    }

    fun renderSynthBass(file: File): Stats {
        val b = Bass(SAMPLE_RATE.toFloat(), 1)
        return render(file, phrase(28, 60), { m, v, d -> b.noteOn(m, v, d) }) { out -> b.render(out, 1f) }
    }

    fun renderSynthDrums(file: File, brush: Boolean): Stats {
        val d = Drums(SAMPLE_RATE.toFloat(), 1).apply { brushed = brush }
        return render(file, drumPattern(brush), { m, v, _ ->
            when (m) {
                36 -> d.kick(v); 38, 40 -> d.snare(v); 42 -> d.hat(v, false); 46 -> d.hat(v, true)
                51 -> d.ride(v); 70 -> d.shaker(v); 37 -> d.rim(v)
            }
        }) { out -> d.render(out, 1f) }
    }

    fun renderDrums(file: File, inst: SampleInstrument, brush: Boolean): Stats {
        val d = DrumSampler(SAMPLE_RATE.toFloat(), inst, 10)
        return render(file, drumPattern(brush), { m, v, _ -> d.hit(m, v) }) { out -> d.render(out, 1f) }
    }

    private fun drumPattern(brush: Boolean): List<Triple<Float, Int, Float>> {
        val events = ArrayList<Triple<Float, Int, Float>>()
        val step = 60f / 90f / 4f   // 16ths at 90 BPM
        var i = 0
        while (i * step < SECONDS) {
            val t = i * step
            val beat = i % 16
            val bar = i / 16
            if (beat == 0 || beat == 10) events.add(Triple(t, 36, 0.95f))
            if (beat == 4 || beat == 12) events.add(Triple(t, if (brush && bar % 2 == 1) 40 else 38, 0.85f))
            if (beat % 2 == 0) events.add(Triple(t, if (beat == 14) 46 else 42, if (beat % 4 == 0) 0.7f else 0.45f))
            if (bar >= 3 && beat % 4 == 2) events.add(Triple(t, 51, 0.6f))
            if (bar >= 2 && beat % 2 == 1) events.add(Triple(t, if (brush) 37 else 70, 0.4f))
            if (bar == 5 && beat == 8) events.add(Triple(t, 37, 0.8f))
            i++
        }
        return events
    }

    private fun render(file: File, events: List<Triple<Float, Int, Float>>, on: (Int, Float, Long) -> Unit, draw: (StereoOut) -> Unit): Stats {
        val frames = (SECONDS * SAMPLE_RATE).toInt()
        val buf = FloatArray(frames * 2)
        val out = StereoOut()
        val sorted = events.sortedBy { it.first }
        var next = 0
        var peak = 0f; var sumSq = 0.0; var sum = 0.0; var finite = true
        for (f in 0 until frames) {
            val t = f.toFloat() / SAMPLE_RATE
            while (next < sorted.size && sorted[next].first <= t) {
                val (_, m, dur) = sorted[next]
                on(m, 0.8f, (dur * SAMPLE_RATE).toLong())
                next++
            }
            out.l = 0f; out.r = 0f
            draw(out)
            if (out.l.isNaN() || out.r.isNaN() || out.l.isInfinite() || out.r.isInfinite()) { finite = false; out.l = 0f; out.r = 0f }
            buf[f * 2] = out.l; buf[f * 2 + 1] = out.r
            for (v in floatArrayOf(out.l, out.r)) {
                val a = if (v < 0f) -v else v
                if (a > peak) peak = a
                sumSq += (v * v).toDouble(); sum += v.toDouble()
            }
        }
        // write the file at a fixed peak so every demo sits in the same place; the numbers above are at unity gain
        val norm = if (peak > 0f) dbToGain(TARGET_PEAK) / peak else 1f
        for (i in buf.indices) buf[i] *= norm
        WavWriter.write(file, buf, frames, SAMPLE_RATE)
        return Stats(file, peak, sqrt(sumSq / (frames * 2)).toFloat(), (sum / (frames * 2)).toFloat(), finite, db(norm))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: "engine/build/sampler-demo")
        outDir.mkdirs()
        val bank = SampleBank.loadDefault() ?: error("resource ${SampleBank.DEFAULT_RESOURCE} not found on the class path")
        println("bank: ${bank.instruments.size} instruments, ${bank.totalBytes / 1e6} MB PCM -> ${outDir.absolutePath}")
        for ((name, inst) in bank.instruments) {
            val file = File(outDir, "$name.wav")
            val stats = if (name == "drums" || name == "brush") renderDrums(file, inst, brush = name == "brush") else renderMelodic(file, inst)
            println(stats)
        }
        println(renderSynthEp(File(outDir, "synth_ep_reference.wav")))
        println(renderSynthBass(File(outDir, "synth_bass_reference.wav")))
        println(renderSynthDrums(File(outDir, "synth_drums_reference.wav"), brush = false))
        println(renderSynthDrums(File(outDir, "synth_brush_reference.wav"), brush = true))
    }
}
