package io.drivesymphony.app.audio

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Developer tap (adb `io.drivesymphony.action.RECORD`): writes the rendered blocks, before audio-focus ducking, to a
 * 16-bit stereo WAV so a screen recording can be muxed with gapless audio (the emulator's own output may stutter; the
 * render stream never does). Sample 0 is the moment the tap was created: the first rendered block is preceded by that
 * much silence, so the first video frame showing the REC badge lines up with the start of the file. The audio thread
 * only converts and enqueues one block; a writer thread does the file I/O.
 */
class AudioTap(private val file: File, private val sampleRate: Int) {
    private val queue = ArrayBlockingQueue<ShortArray>(512)
    private val startNs = SystemClock.elapsedRealtimeNanos()
    @Volatile private var closed = false
    private var firstBlock = true
    @Volatile var dropped = 0
        private set
    private var frames = 0L
    private val writer = Thread({ run() }, "DriveSymphonyTap").apply { start() }

    /** Audio thread. */
    fun push(block: FloatArray, n: Int) {
        if (closed) return
        if (firstBlock) {
            firstBlock = false
            val lead = ((SystemClock.elapsedRealtimeNanos() - startNs) * sampleRate / 1_000_000_000L).toInt()
            if (lead > 0) queue.offer(ShortArray(lead * 2))
        }
        val s = ShortArray(n * 2)
        for (i in 0 until n * 2) {
            val v = block[i]
            s[i] = ((if (v > 1f) 1f else if (v < -1f) -1f else v) * 32767f).toInt().toShort()
        }
        if (!queue.offer(s)) dropped++
    }

    /** Any thread but the audio thread: drains the queue, finalises the header, returns when the file is complete. */
    fun close() {
        closed = true
        writer.join(5000)
    }

    private fun run() {
        try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val bb = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    val s = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (s == null) { if (closed) break else continue }
                    var i = 0
                    while (i < s.size) {
                        bb.clear()
                        val n = minOf(s.size - i, bb.capacity() / 2)
                        for (k in 0 until n) bb.putShort(s[i + k])
                        raf.write(bb.array(), 0, n * 2)
                        i += n
                    }
                    frames += s.size / 2
                }
                val dataBytes = frames * 4
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
                h.putInt(sampleRate); h.putInt(sampleRate * 4); h.putShort(4); h.putShort(16)
                h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
                raf.seek(0); raf.write(h.array())
            }
            Log.i(TAG, "recording closed: ${file.absolutePath}, ${"%.1f".format(frames.toDouble() / sampleRate)} s, dropped blocks $dropped")
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
        }
    }

    companion object { private const val TAG = "AudioTap" }
}
