package io.drivesymphony.engine.tools

import io.drivesymphony.engine.SymphonyEngine
import io.drivesymphony.engine.scenario.DriveScenario
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.wav.WavWriter
import java.io.File
import kotlin.math.sqrt

/**
 * Offline renderer: produces WAV previews for every genre x scenario so the concept can be heard
 * without a car or emulator. Also prints a flow timeline so reviewers can correlate sound and driving.
 *
 * Usage: PreviewMain <outputDir> [seconds]
 */
object PreviewMain {
    const val SAMPLE_RATE = 48000
    const val BLOCK = 256

    data class RenderResult(val file: File, val peak: Float, val rms: Float, val meanFlow: Float, val meanCutoff: Float, val meanDensity: Float)

    fun render(genre: Genre, scenario: DriveScenario, seconds: Float, outDir: File, seed: Int = 20261030): RenderResult {
        val engine = SymphonyEngine(SAMPLE_RATE, genre, seed, hourOfDay = scenario.signalsAt(0.0).hourOfDay, roadName = scenario.signalsAt(0.0).roadName)
        val totalFrames = (seconds * SAMPLE_RATE).toInt()
        val buf = FloatArray(totalFrames * 2)
        val block = FloatArray(BLOCK * 2)
        var frame = 0
        var nextControl = 0.0
        val dt = 0.04f
        var peak = 0f
        var sumSq = 0.0
        var flowSum = 0.0; var cutSum = 0.0; var denSum = 0.0; var n = 0
        while (frame < totalFrames) {
            val t = frame.toDouble() / SAMPLE_RATE
            while (nextControl <= t) {
                engine.update(scenario.signalsAt(nextControl), dt)
                nextControl += dt
                flowSum += engine.state.flow; cutSum += engine.params.cutoffHz; denSum += engine.params.density; n++
            }
            val frames = minOf(BLOCK, totalFrames - frame)
            engine.render(block, frames)
            for (i in 0 until frames * 2) {
                val v = block[i]
                buf[frame * 2 + i] = v
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                sumSq += (v * v).toDouble()
            }
            frame += frames
        }
        val file = File(outDir, "${genre.name.lowercase()}_${scenario.id}.wav")
        WavWriter.write(file, buf, totalFrames, SAMPLE_RATE)
        val rms = sqrt(sumSq / (totalFrames * 2)).toFloat()
        return RenderResult(file, peak, rms, (flowSum / n).toFloat(), (cutSum / n).toFloat(), (denSum / n).toFloat())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: "previews")
        val seconds = args.getOrNull(1)?.toFloatOrNull() ?: 60f
        outDir.mkdirs()
        println("Rendering previews to ${outDir.absolutePath} (${seconds}s each)")
        for (g in Genre.values()) for (s in Scenarios.all) {
            val secs = minOf(seconds, s.durationSec)
            val r = render(g, s, secs, outDir)
            println(String.format("%-28s peak=%.2f rms=%.3f meanFlow=%.2f meanCutoff=%5.0fHz meanDensity=%.2f", r.file.name, r.peak, r.rms, r.meanFlow, r.meanCutoff, r.meanDensity))
        }
        for (s in Scenarios.abPair) {
            val r = render(Genre.LOFI, s, s.durationSec, outDir)
            println(String.format("%-28s peak=%.2f rms=%.3f meanFlow=%.2f meanCutoff=%5.0fHz", r.file.name, r.peak, r.rms, r.meanFlow, r.meanCutoff))
        }
        // flow timeline for the story scenario (for the README / PT)
        val engine = SymphonyEngine(SAMPLE_RATE, Genre.LOFI, 20261030, 19, "강변북로")
        val sb = StringBuilder("t(s)\tflow\tpulse\tdensity\tcutoffHz\tevent\n")
        val block = FloatArray(1920 * 2)
        val steps = (Scenarios.story.durationSec / 0.04f).toInt()
        for (i in 0 until steps) {
            val t = i * 0.04
            engine.update(Scenarios.story.signalsAt(t), 0.04f)
            engine.render(block, 1920) // 40 ms of audio per 40 ms tick = real time
            if (i % 50 == 0) { // every 2 s
                val st = engine.state
                val p = engine.params
                sb.append(String.format("%.0f\t%.2f\t%.2f\t%.2f\t%.0f\t%s\n", t, st.flow, st.pulse, p.density, p.cutoffHz, if (st.lastEventAgeSec < 2f) st.lastEvent?.label ?: "" else ""))
            }
        }
        File(outDir, "story_flow_timeline.tsv").writeText(sb.toString())
        println("story: ${engine.state.sags} tape sags, events ${engine.summary().eventCounts}")
        println("Wrote story_flow_timeline.tsv")
    }
}
