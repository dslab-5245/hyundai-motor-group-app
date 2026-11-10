package io.drivesymphony.engine.remix

/**
 * Fractional-rate reader over an interleaved stereo ring buffer: the "tape speed" of remix mode.
 * rate 1 = the song as recorded; 0.92 = 8 % slower and about a semitone and a half lower, like a tape machine
 * dragging. Linear interpolation between neighbouring frames; the cursor keeps a frame index and a 0..1 phase.
 * Pure Kotlin (no Android types) so it can be unit-tested; the audio thread owns the cursor.
 */
object Varispeed {
    class Cursor {
        var frame = 0L   // index of the source frame under the read head
        var phase = 0.0  // 0..1 position between frame and frame + 1
    }

    /**
     * Produces [frames] output frames into [out] from [ring] (capacity [ringFrames] frames, interleaved L/R),
     * starting at [cursor] and advancing it by frames * [rate]. [endFrame] is the first frame the producer has not
     * written yet. Returns the number of frames that carry audio; the rest are zero-filled when the producer is behind.
     */
    fun read(ring: FloatArray, ringFrames: Int, cursor: Cursor, endFrame: Long, out: FloatArray, frames: Int, rate: Float): Int {
        val step = rate.toDouble()
        var made = 0
        var o = 0
        while (made < frames) {
            if (cursor.frame + 1 >= endFrame) break   // the interpolation needs this frame and the next one
            val i0 = (cursor.frame % ringFrames).toInt() * 2
            var i1 = i0 + 2
            if (i1 >= ring.size) i1 = 0
            val t = cursor.phase.toFloat()
            out[o] = ring[i0] + (ring[i1] - ring[i0]) * t
            out[o + 1] = ring[i0 + 1] + (ring[i1 + 1] - ring[i0 + 1]) * t
            o += 2; made++
            cursor.phase += step
            while (cursor.phase >= 1.0) { cursor.phase -= 1.0; cursor.frame++ }
        }
        var z = made
        while (z < frames) { out[o] = 0f; out[o + 1] = 0f; o += 2; z++ }
        return made
    }
}
