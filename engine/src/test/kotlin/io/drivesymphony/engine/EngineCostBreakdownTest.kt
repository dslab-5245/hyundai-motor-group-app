package io.drivesymphony.engine

import io.drivesymphony.engine.dsp.Crush
import io.drivesymphony.engine.dsp.DcBlock
import io.drivesymphony.engine.dsp.Limiter
import io.drivesymphony.engine.dsp.Reverb
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.TapeWobble
import io.drivesymphony.engine.music.Bass
import io.drivesymphony.engine.music.Drone
import io.drivesymphony.engine.music.Drums
import io.drivesymphony.engine.music.EPiano
import io.drivesymphony.engine.music.Pad
import io.drivesymphony.engine.music.StereoOut
import io.drivesymphony.engine.music.Texture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the render time goes, component by component (diagnostic; printed, loosely asserted). Each component renders
 * 20 s of audio with typical note activity after a 3 s warm-up, so the figures are comparable with [EnginePerfTest].
 */
class EngineCostBreakdownTest {
    private val sr = 48000
    private val secs = 20

    private inline fun time(name: String, warm: Int = sr * 3, body: (Int) -> Unit): Double {
        for (i in 0 until warm) body(i)
        val t0 = System.nanoTime()
        for (i in 0 until sr * secs) body(i)
        val pct = (System.nanoTime() - t0) / 1e9 / secs * 100
        println("COST ${name.padEnd(14)} ${"%5.2f".format(pct)}% of one core")
        return pct
    }

    @Test
    fun componentCosts() {
        val out = StereoOut()
        val rng = Rng(3)
        val results = HashMap<String, Double>()
        val ep = EPiano(sr.toFloat(), 1)
        results["epiano"] = time("epiano") { i -> if (i % (sr / 2) == 0) ep.noteOn(60 + (i / (sr / 2)) % 7, 0.7f, sr.toLong()); out.l = 0f; out.r = 0f; ep.render(out, 0.5f) }
        val lead = EPiano(sr.toFloat(), 2, maxVoices = 3, bright = 1.4f)
        results["lead"] = time("lead(3v)") { i -> if (i % sr == 0) lead.noteOn(72 + (i / sr) % 5, 0.6f, sr.toLong()); out.l = 0f; out.r = 0f; lead.render(out, 0.4f) }
        val bass = Bass(sr.toFloat(), 3)
        results["bass"] = time("bass") { i -> if (i % sr == 0) bass.noteOn(36 + (i / sr) % 5, 0.8f, (sr * 0.8f).toLong()); out.l = 0f; out.r = 0f; bass.render(out, 0.7f) }
        val pad = Pad(sr.toFloat(), 4)
        results["pad"] = time("pad") { i -> if (i % (sr * 2) == 0) pad.chord(intArrayOf(48, 55, 59, 62), (sr * 2).toLong()); out.l = 0f; out.r = 0f; pad.render(out, 0.5f) }
        val drums = Drums(sr.toFloat(), 5)
        results["drums"] = time("drums") { i ->
            val step = i % (sr / 2)
            if (step == 0) drums.kick(0.9f); if (step == sr / 4) drums.snare(0.8f); if (step % (sr / 8) == 0) drums.hat(0.5f, step % (sr / 4) == sr / 8)
            if (step == sr / 8) drums.shaker(0.4f)
            out.l = 0f; out.r = 0f; drums.render(out, 0.9f)
        }
        val tex = Texture(sr.toFloat(), 6)
        results["texture"] = time("texture") { i -> if (i % (sr * 4) == 0) tex.pulse(); out.l = 0f; out.r = 0f; tex.render(out, 0.6f, 0.5f, 0.8f) }
        val drone = Drone(sr.toFloat()).apply { setRoot(2) }
        results["drone"] = time("drone") { out.l = 0f; out.r = 0f; drone.render(out, 0.3f) }
        val svf = Svf(sr.toFloat()).apply { set(3000f, 0.05f) }
        results["svf x2"] = time("svf x2") { i -> val x = rng.nextBipolar() * 0.3f; svf.process(x); svf.process(x) }
        val crush = Crush(sr.toFloat()).apply { amount = 0.4f }
        results["crush x2"] = time("crush x2") { val x = rng.nextBipolar() * 0.3f; crush.process(x); crush.process(x) }
        val wob = TapeWobble(sr.toFloat(), 7).apply { depth = 0.3f }
        results["wobble x2"] = time("wobble x2") { val x = rng.nextBipolar() * 0.3f; wob.process(x); wob.process(x) }
        val rev = Reverb(sr.toFloat())
        results["reverb x2"] = time("reverb x2") { val x = rng.nextBipolar() * 0.3f; rev.process(x); rev.process(x) }
        val lim = Limiter(sr.toFloat()); val dc = DcBlock()
        results["limiter x2"] = time("limiter x2") { val x = rng.nextBipolar() * 0.3f; lim.process(dc.process(x)); lim.process(dc.process(x)) }
        val total = results.values.sum()
        println("COST ${"sum".padEnd(14)} ${"%5.2f".format(total)}% (engine as a whole is measured in EnginePerfTest)")
        assertTrue(total < 25.0, "component sum unexpectedly high: $total%")
    }
}
