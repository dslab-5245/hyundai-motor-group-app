package io.drivesymphony.engine

import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.MusicDirector
import io.drivesymphony.engine.director.ScoreState
import io.drivesymphony.engine.remix.TrackProcessor
import io.drivesymphony.engine.theory.Genre
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Numerical cross-check of the "기존 음악 변주하기" mode: a song the app plays must stay recognizable and
 * essentially untouched while driving smoothly, and go audibly "underwater" (darker, quieter, narrower)
 * while driving erratically — without clipping or clicks.
 */
class TrackProcessorTest {
    private val sr = 48000
    private val block = 256

    private class Stats(val rms: Float, val peak: Float, val corr: Float, val hf: Float, val maxDelta: Float)

    /** Synthetic stereo "song": bass + two-voice chord (different voices per side) + eighth-note noise hats. */
    private fun song(seconds: Float, hats: Boolean, seed: Int = 5): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n * 2)
        val rng = java.util.Random(seed.toLong())
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val bass = 0.22 * sin(2 * PI * 110 * t)
            val l = 0.12 * sin(2 * PI * 440 * t) + 0.07 * sin(2 * PI * 659.25 * t)
            val r = 0.12 * sin(2 * PI * 554.37 * t) + 0.07 * sin(2 * PI * 880 * t)
            var hat = 0.0
            if (hats) {
                val beat = (t * 8) % 1.0
                // attack is ramped over 2 ms so the input itself has no clicks
                val env = (if (beat < 0.002) beat / 0.002 else exp(-(beat - 0.002) * 30)) * 0.16
                hat = (rng.nextFloat() * 2 - 1) * env
            }
            out[i * 2] = (bass + l + hat * 0.9).toFloat()
            out[i * 2 + 1] = (bass + r + hat * 1.1).toFloat()
        }
        return out
    }

    private fun stats(buf: FloatArray, fromFrame: Int, toFrame: Int): Stats {
        var sumSq = 0.0; var peak = 0f; var sl = 0.0; var sr2 = 0.0; var sll = 0.0; var srr = 0.0; var slr = 0.0
        var hf = 0.0; var maxDelta = 0f
        var pl = buf[fromFrame * 2]; var pr = buf[fromFrame * 2 + 1]
        val n = toFrame - fromFrame
        for (f in fromFrame until toFrame) {
            val l = buf[f * 2]; val r = buf[f * 2 + 1]
            assertTrue(!l.isNaN() && !r.isNaN(), "NaN at frame $f")
            sumSq += (l * l + r * r).toDouble()
            peak = maxOf(peak, abs(l), abs(r))
            sl += l; sr2 += r; sll += l * l.toDouble(); srr += r * r.toDouble(); slr += l * r.toDouble()
            val dl = l - pl; val dr = r - pr
            hf += (dl * dl + dr * dr).toDouble()
            maxDelta = maxOf(maxDelta, abs(dl), abs(dr))
            pl = l; pr = r
        }
        val ml = sl / n; val mr = sr2 / n
        val cov = slr / n - ml * mr
        val vl = sll / n - ml * ml; val vr = srr / n - mr * mr
        return Stats(sqrt(sumSq / (2 * n)).toFloat(), peak, (cov / sqrt(vl * vr)).toFloat(), (hf / n).toFloat(), maxDelta)
    }

    private fun db(x: Float) = 20f * log10(x)

    /** Runs the song through a fresh processor, switching the driving state at [switchAtSec] if given. */
    private fun process(input: FloatArray, first: ScoreState, second: ScoreState? = null, switchAtSec: Float = 0f): FloatArray {
        val director = MusicDirector(Genre.LOFI)
        val tp = TrackProcessor(sr)
        val sig = DrivingSignals(0.0, gear = Gear.DRIVE)
        val frames = input.size / 2
        val out = FloatArray(input.size)
        val inBlk = FloatArray(block * 2); val outBlk = FloatArray(block * 2)
        var f = 0
        while (f < frames) {
            val n = minOf(block, frames - f)
            val state = if (second != null && f >= switchAtSec * sr) second else first
            val p = director.update(state, sig, n.toFloat() / sr)
            System.arraycopy(input, f * 2, inBlk, 0, n * 2)
            tp.process(inBlk, outBlk, n, p)
            System.arraycopy(outBlk, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    private val safe = ScoreState(flow = 1f, instant = 1f, streakSec = 60f, moving = true)
    private val erratic = ScoreState(flow = 0.3f, instant = 0.2f, penaltyPulse = 0.7f, moving = true)

    @Test
    fun smoothDrivingLeavesTheSongPracticallyUntouched() {
        val song = song(6f, hats = true)
        val out = process(song, safe)
        val a = stats(song, sr, 6 * sr); val b = stats(out, sr, 6 * sr)
        val gain = db(b.rms / a.rms)
        println("remix safe: gain ${"%.2f".format(gain)} dB, hf ratio ${"%.2f".format(b.hf / a.hf)}, corr in ${"%.2f".format(a.corr)} out ${"%.2f".format(b.corr)}, peak ${"%.2f".format(b.peak)}")
        assertTrue(abs(gain) < 1.5f, "safe remix should keep loudness (got $gain dB)")
        assertTrue(b.hf / a.hf > 0.7f, "safe remix should keep the top end (hf ratio ${b.hf / a.hf})")
        assertTrue(abs(b.corr - a.corr) < 0.12f, "safe remix should keep the stereo image (corr in ${a.corr} out ${b.corr})")
        assertTrue(b.peak <= 1f, "no clipping")
    }

    @Test
    fun erraticDrivingPutsTheSongUnderwater() {
        val song = song(6f, hats = true)
        val good = stats(process(song, safe), sr, 6 * sr)
        val bad = stats(process(song, erratic), sr, 6 * sr)
        val drop = db(bad.rms / good.rms)
        println("remix erratic vs safe: level ${"%.2f".format(drop)} dB, hf ratio ${"%.2f".format(bad.hf / good.hf)}, corr safe ${"%.2f".format(good.corr)} erratic ${"%.2f".format(bad.corr)}")
        assertTrue(drop < -3f, "erratic remix should be clearly quieter (got $drop dB)")
        assertTrue(bad.hf / good.hf < 0.25f, "erratic remix should lose most of the top end (hf ratio ${bad.hf / good.hf})")
        assertTrue(bad.corr > good.corr + 0.03f, "erratic remix should be narrower (corr safe ${good.corr} erratic ${bad.corr})")
        assertTrue(bad.rms > good.rms * 0.25f, "but never near-silent (ratio ${bad.rms / good.rms})")
        assertTrue(bad.peak <= 1f, "no clipping")
    }

    @Test
    fun stateSwitchesDoNotClick() {
        // pure tones: any sample-to-sample jump beyond the tones' own slope is a click from parameter changes
        val song = song(6f, hats = false)
        val out = process(song, safe, erratic, switchAtSec = 3f)
        val steady = stats(out, sr, 3 * sr - block)
        val around = stats(out, 3 * sr - block, (3.5f * sr).toInt())
        println("remix switch: steady max delta ${"%.4f".format(steady.maxDelta)}, around switch ${"%.4f".format(around.maxDelta)}")
        assertTrue(around.maxDelta < steady.maxDelta * 1.5f + 0.01f,
            "switch safe->erratic must not click (steady ${steady.maxDelta}, around ${around.maxDelta})")
        val back = process(song, erratic, safe, switchAtSec = 3f)
        val steadyB = stats(back, sr, 3 * sr - block)
        val aroundB = stats(back, 3 * sr - block, (3.5f * sr).toInt())
        assertTrue(aroundB.maxDelta < maxOf(steadyB.maxDelta, steady.maxDelta) * 1.5f + 0.01f,
            "switch erratic->safe must not click (steady ${steadyB.maxDelta}, around ${aroundB.maxDelta})")
    }
}
