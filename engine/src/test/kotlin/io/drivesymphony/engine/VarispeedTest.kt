package io.drivesymphony.engine

import io.drivesymphony.engine.remix.Varispeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The remix tape-speed reader: exact at speed 1, interpolated and slower at speed < 1, silent when the decoder is behind. */
class VarispeedTest {
    private val ringFrames = 64

    private fun ring(filled: Int): FloatArray {
        val r = FloatArray(ringFrames * 2)
        for (f in 0 until filled) { r[(f % ringFrames) * 2] = f.toFloat(); r[(f % ringFrames) * 2 + 1] = -f.toFloat() }
        return r
    }

    @Test
    fun speedOneReproducesTheSource() {
        val r = ring(40); val c = Varispeed.Cursor(); val out = FloatArray(16 * 2)
        val made = Varispeed.read(r, ringFrames, c, 40, out, 16, 1f)
        assertEquals(16, made)
        for (f in 0 until 16) { assertEquals(f.toFloat(), out[f * 2]); assertEquals(-f.toFloat(), out[f * 2 + 1]) }
        assertEquals(16L, c.frame); assertEquals(0.0, c.phase, 1e-9)
    }

    @Test
    fun halfSpeedInterpolatesAndConsumesHalfAsMuch() {
        val r = ring(40); val c = Varispeed.Cursor(); val out = FloatArray(16 * 2)
        assertEquals(16, Varispeed.read(r, ringFrames, c, 40, out, 16, 0.5f))
        // 0, 0.5, 1, 1.5, ... : every other output frame sits halfway between two source frames
        for (f in 0 until 16) assertEquals(f * 0.5f, out[f * 2], 1e-5f, "frame $f")
        assertEquals(8L, c.frame)
    }

    @Test
    fun eightPercentDragConsumesEightPercentLess() {
        val r = ring(64); val c = Varispeed.Cursor(); val out = FloatArray(50 * 2)
        Varispeed.read(r, ringFrames, c, 63, out, 50, 0.92f)
        val consumed = c.frame + c.phase
        assertEquals(46.0, consumed, 1e-6)
        // monotone, no jumps larger than one source frame
        for (f in 1 until 50) { val d = out[f * 2] - out[(f - 1) * 2]; assertTrue(d > 0f && d <= 1.0001f, "delta $d at $f") }
    }

    @Test
    fun producerBehindGivesSilenceNotGarbage() {
        val r = ring(10); val c = Varispeed.Cursor(); val out = FloatArray(16 * 2)
        val made = Varispeed.read(r, ringFrames, c, 10, out, 16, 1f)
        assertEquals(9, made)   // frame 9 needs frame 10 for the interpolation, which is not written yet
        for (f in 9 until 16) { assertEquals(0f, out[f * 2]); assertEquals(0f, out[f * 2 + 1]) }
        assertEquals(9L, c.frame)
    }

    @Test
    fun wrapsAroundTheRing() {
        val r = ring(200)   // frames 136..199 are the ones left in the ring
        val c = Varispeed.Cursor().apply { frame = 136 }
        val out = FloatArray(60 * 2)
        assertEquals(60, Varispeed.read(r, ringFrames, c, 200, out, 60, 1f))
        for (f in 0 until 60) assertEquals((136 + f).toFloat(), out[f * 2])
    }
}
