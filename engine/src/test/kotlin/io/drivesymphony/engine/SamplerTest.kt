package io.drivesymphony.engine

import io.drivesymphony.engine.dsp.DrumSampler
import io.drivesymphony.engine.dsp.SampleBank
import io.drivesymphony.engine.dsp.SampleInstrument
import io.drivesymphony.engine.dsp.SampleZone
import io.drivesymphony.engine.dsp.Sampler
import io.drivesymphony.engine.music.StereoOut
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SamplerTest {
    private val sr = 48000f

    /** Looped sine: `cycles` whole periods of `hz` at `rate` so the loop is seamless, plus a short tail after the loop. */
    private fun sineZone(hz: Float = 440f, rate: Int = 48000, cycles: Int = 44, root: Int = 69, looped: Boolean = true, amp: Float = 16000f): SampleZone {
        val loopLen = (cycles * rate / hz).roundToInt()
        val n = loopLen + 64
        val pcm = ShortArray(n) { (sin(2.0 * PI * hz * it / rate) * amp).roundToInt().toShort() }
        return SampleZone(0, 127, 0, 127, root, 0, rate, 0, if (looped) loopLen else -1, 0f, pcm)
    }

    private fun instrument(vararg zones: SampleZone, attack: Float = 0.001f, decay: Float = 100f, sustain: Float = 1f, release: Float = 0.2f) =
        SampleInstrument("test", zones.toList(), attack, decay, sustain, release)

    /** Renders `frames` and returns (left, right). */
    private fun render(frames: Int, step: (Int) -> Unit = {}, draw: (StereoOut) -> Unit): Pair<FloatArray, FloatArray> {
        val l = FloatArray(frames); val r = FloatArray(frames)
        val out = StereoOut()
        for (i in 0 until frames) {
            step(i)
            out.l = 0f; out.r = 0f
            draw(out)
            assertTrue(!out.l.isNaN() && !out.r.isNaN() && !out.l.isInfinite() && !out.r.isInfinite(), "NaN/Inf at frame $i")
            l[i] = out.l; r[i] = out.r
        }
        return l to r
    }

    private fun zeroCrossingsPerSecond(x: FloatArray, from: Int, to: Int): Double {
        var n = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0f && x[i] >= 0f) || (x[i - 1] >= 0f && x[i] < 0f)) n++
        return n.toDouble() * sr / (to - from)
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += (x[i] * x[i]).toDouble()
        return sqrt(s / (to - from)).toFloat()
    }

    @Test
    fun bankFormatRoundTrips() {
        val z1 = SampleZone(36, 59, 0, 90, 48, -7, 22050, 100, 900, -1.5f, ShortArray(1000) { (it * 37 - 16000).toShort() })
        val z2 = SampleZone(60, 96, 91, 127, 72, 12, 44100, -1, -1, 2.25f, ShortArray(333) { (it * -91).toShort() })
        val a = SampleInstrument("ep", listOf(z1, z2), 0.002f, 1.5f, 0.25f, 0.4f, 0.75f)
        val b = SampleInstrument("drums", listOf(z2), 0.001f, 100f, 1f, 0.05f)
        val bytes = SampleBank(linkedMapOf("ep" to a, "drums" to b)).toBytes()
        assertEquals("DSBK", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, bytes[4].toInt())
        val back = SampleBank.read(bytes)
        assertEquals(listOf("ep", "drums"), back.instruments.keys.toList())
        val ep = back["ep"]!!
        assertEquals(0.002f, ep.attackSec); assertEquals(0.75f, ep.holdSec); assertEquals(1.5f, ep.decaySec)
        assertEquals(0.25f, ep.sustain); assertEquals(0.4f, ep.releaseSec)
        assertEquals(2, ep.zones.size)
        val r1 = ep.zones[0]
        assertEquals(36, r1.lowKey); assertEquals(59, r1.highKey); assertEquals(0, r1.lowVel); assertEquals(90, r1.highVel)
        assertEquals(48, r1.rootKey); assertEquals(-7, r1.fineCents); assertEquals(22050, r1.sampleRate)
        assertEquals(100, r1.loopStart); assertEquals(900, r1.loopEnd); assertEquals(-1.5f, r1.gainDb)
        assertTrue(r1.pcm.contentEquals(z1.pcm))
        val r2 = ep.zones[1]
        assertEquals(-1, r2.loopEnd); assertEquals(12, r2.fineCents); assertEquals(2.25f, r2.gainDb)
        assertTrue(r2.pcm.contentEquals(z2.pcm))
        assertEquals(0.05f, back["drums"]!!.releaseSec)
        assertEquals(z1.pcm.size + z2.pcm.size + z2.pcm.size, (back.totalBytes / 2).toInt())
    }

    @Test
    fun pitchFollowsMidiNote() {
        val inst = instrument(sineZone())
        val frames = (1.2f * sr).toInt()
        fun zcr(midi: Int): Double {
            val s = Sampler(sr, inst, 4, 1)
            s.noteOn(midi, 1f, 0)
            val (l, _) = render(frames) { s.render(it, 1f) }
            return zeroCrossingsPerSecond(l, (0.1f * sr).toInt(), frames)
        }
        val root = zcr(69)
        val octave = zcr(81)
        assertTrue(abs(root - 880.0) < 880.0 * 0.02, "root zero-crossing rate $root should be ~880/s")
        assertTrue(abs(octave - 2 * root) < 2 * root * 0.02, "octave up should double the rate: $root -> $octave")
    }

    @Test
    fun releaseReachesSilence() {
        val inst = instrument(sineZone(), release = 0.3f)
        val s = Sampler(sr, inst, 4, 1)
        val hold = (0.5f * sr).toInt()
        s.noteOn(69, 0.9f, hold.toLong())
        val frames = hold + (1.0f * sr).toInt()
        val (l, r) = render(frames) { s.render(it, 1f) }
        assertTrue(rms(l, 0, hold) > 0.05f, "note should sound while held")
        val tail = frames - (0.2f * sr).toInt()
        assertTrue(rms(l, tail, frames) < 1e-4f && rms(r, tail, frames) < 1e-4f, "release should decay to silence")
        assertEquals(0, s.activeVoices, "voice must be freed after the release")
    }

    @Test
    fun loopPointsDoNotClick() {
        val inst = instrument(sineZone())
        for (midi in intArrayOf(69, 70, 64)) {      // integer rate, fractional rate, and a down-shift
            val s = Sampler(sr, inst, 4, 1)
            s.noteOn(midi, 1f, 0)
            val frames = (2.5f * sr).toInt()
            val (l, _) = render(frames) { s.render(it, 1f) }
            val from = (0.1f * sr).toInt()
            var peak = 0f; var maxStep = 0f
            for (i in from until frames) {
                val a = abs(l[i]); if (a > peak) peak = a
                val d = abs(l[i] - l[i - 1]); if (d > maxStep) maxStep = d
            }
            val hz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val bound = (2.0 * PI * hz / sr * peak * 1.25).toFloat()
            assertTrue(peak > 0.2f, "signal present (peak $peak)")
            assertTrue(maxStep <= bound, "midi $midi: step $maxStep exceeds sine bound $bound -> click at a loop point")
        }
    }

    @Test
    fun voiceStealingNeverExceedsMaxVoices() {
        val inst = instrument(sineZone(), release = 0.5f)
        val s = Sampler(sr, inst, 4, 3)
        var fired = 0
        val frames = (1.0f * sr).toInt()
        render(frames, step = { i ->
            if (i % 2400 == 0 && fired < 20) { s.noteOn(48 + fired, 0.8f, 0); fired++ }
            assertTrue(s.activeVoices <= 4, "active voices ${s.activeVoices} > maxVoices")
        }) { s.render(it, 1f) }
        assertEquals(20, fired)
        assertEquals(4, s.activeVoices)
        s.allOff()
        render((2f * sr).toInt()) { s.render(it, 1f) }
        assertEquals(0, s.activeVoices)
    }

    @Test
    fun samplerIsDeterministicAndNoteOffReleasesOnlyThatKey() {
        val inst = instrument(sineZone(), release = 0.1f)
        fun run(): FloatArray {
            val s = Sampler(sr, inst, 4, 7).apply { humanizeCents = 4f }
            s.noteOn(60, 0.7f, 0); s.noteOn(67, 0.7f, 0)
            val (l, _) = render((0.6f * sr).toInt(), step = { if (it == (0.3f * sr).toInt()) s.noteOff(60) }) { s.render(it, 1f) }
            assertEquals(1, s.activeVoices)
            return l
        }
        assertTrue(run().contentEquals(run()), "same seed must render identical audio")
    }

    @Test
    fun drumSamplerPlaysOneShotsWithinVoiceLimit() {
        val hat = sineZone(hz = 3000f, cycles = 300, root = 42, looped = false).let { SampleZone(42, 42, 0, 127, 42, 0, 48000, -1, -1, 0f, it.pcm) }
        val kick = sineZone(hz = 60f, cycles = 12, root = 36, looped = false).let { SampleZone(36, 36, 0, 127, 36, 0, 48000, -1, -1, 0f, it.pcm) }
        val kit = SampleInstrument("kit", listOf(kick, hat), 0.001f, 100f, 1f, 0.05f)
        val d = DrumSampler(sr, kit, 3)
        assertTrue(d.has(36) && d.has(42) && !d.has(38))
        d.hit(38, 1f)                      // missing note: silently ignored
        assertEquals(0, d.activeVoices)
        var hits = 0
        val (l, r) = render((1.0f * sr).toInt(), step = { i ->
            if (i % 1200 == 0 && hits < 30) { d.hit(if (hits % 2 == 0) 36 else 42, 0.9f); hits++ }
            assertTrue(d.activeVoices <= 3)
        }) { d.render(it, 1f) }
        assertTrue(rms(l, 0, l.size) > 0.01f && rms(r, 0, r.size) > 0.01f)
        var sumL = 0f; var sumR = 0f
        for (i in l.indices) { sumL += abs(l[i]); sumR += abs(r[i]) }
        assertTrue(sumL != sumR, "hats left / kick centred should give an asymmetric image")
        d.allOff()
        render((0.2f * sr).toInt()) { d.render(it, 1f) }
        assertEquals(0, d.activeVoices)
    }

    @Test
    fun defaultBankLoadsWithExpectedInstruments() {
        val t0 = System.nanoTime()
        val bank = SampleBank.loadDefault()
        val ms = (System.nanoTime() - t0) / 1e6
        assertNotNull(bank, "resource /samples/fluid_lofi.dsb should be on the class path")
        for (name in listOf("ep", "bass", "fbass", "vibes", "drums", "brush")) {
            val inst = bank[name]
            assertNotNull(inst, "instrument $name missing")
            assertTrue(inst.zones.isNotEmpty(), "$name has zones")
            for (z in inst.zones) {
                assertTrue(z.pcm.isNotEmpty() && z.lowKey <= z.highKey && z.lowVel <= z.highVel)
                if (z.loopEnd >= 0) assertTrue(z.looped, "$name loop ${z.loopStart}..${z.loopEnd} inside ${z.pcm.size}")
            }
        }
        assertTrue(bank["ep"]!!.hasKey(36) && bank["ep"]!!.hasKey(96))
        for (n in intArrayOf(36, 38, 37, 42, 46, 51, 70)) assertTrue(bank["drums"]!!.hasKey(n), "drums note $n")
        for (n in intArrayOf(36, 38, 40, 42, 46, 51, 37)) assertTrue(bank["brush"]!!.hasKey(n), "brush note $n")
        assertTrue(bank.totalBytes <= 10_000_000L, "bank ${bank.totalBytes} bytes over budget")
        assertTrue(ms < 1000.0, "bank load took $ms ms")
        // the real Rhodes plays and decays to silence after release
        val s = Sampler(sr, bank["ep"]!!, 8, 1)
        s.noteOn(60, 0.8f, (0.5f * sr).toLong())
        val frames = (3f * sr).toInt()
        val (l, _) = render(frames) { s.render(it, 1f) }
        assertTrue(rms(l, 0, (0.4f * sr).toInt()) > 0.01f, "EP should sound")
        assertTrue(rms(l, frames - 4800, frames) < 1e-3f, "EP should be silent 2.5 s after release")
    }
}
