package io.drivesymphony.app.remix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a local audio file (content:// URI from the document picker, or a file on USB) into
 * 48 kHz stereo float PCM on a background thread and serves it to the audio thread through a ring buffer.
 * Loops the track at the end. Any decode problem degrades to silence instead of crashing the audio thread.
 */
class LocalTrackPlayer(private val context: Context, private val uri: Uri, private val outRate: Int = 48000) {
    private val ringFrames = outRate * 6 // 6 s
    private val ring = FloatArray(ringFrames * 2)
    private var writePos = 0L
    private var readPos = 0L
    private val lock = Object()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var title: String = uri.lastPathSegment ?: "track"
    @Volatile var error: String? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ decodeLoop() }, "DriveSymphonyDecoder").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(800)
        thread = null
    }

    /** Audio thread: fills [out] with [frames] stereo frames (zeros on underrun). */
    fun read(out: FloatArray, frames: Int) {
        synchronized(lock) {
            val avail = (writePos - readPos).toInt()
            val n = minOf(frames, avail)
            var o = 0
            for (i in 0 until n) {
                val idx = ((readPos + i) % ringFrames).toInt() * 2
                out[o++] = ring[idx]; out[o++] = ring[idx + 1]
            }
            for (i in n until frames) { out[o++] = 0f; out[o++] = 0f }
            readPos += n
            lock.notifyAll()
        }
    }

    private fun push(l: Float, r: Float) {
        synchronized(lock) {
            while (running && writePos - readPos >= ringFrames - 1) {
                try { lock.wait(20) } catch (_: InterruptedException) { return }
            }
            if (!running) return
            val idx = (writePos % ringFrames).toInt() * 2
            ring[idx] = l; ring[idx + 1] = r
            writePos++
        }
    }

    private fun decodeLoop() {
        while (running) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
                }
                if (trackIndex < 0 || format == null) { error = "no audio track"; running = false; return }
                format.getString(MediaFormat.KEY_TITLE)?.let { title = it }
                extractor.selectTrack(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val info = MediaCodec.BufferInfo()
                var sawEos = false
                var resampleAcc = 0.0
                var lastL = 0f; var lastR = 0f
                while (running && !sawEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawEos = true
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val fmt = codec.outputFormat
                        inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val isFloat = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) && fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        val frames = if (isFloat) info.size / (4 * channels) else info.size / (2 * channels)
                        val step = inRate.toDouble() / outRate
                        for (f in 0 until frames) {
                            val l: Float; val r: Float
                            if (isFloat) {
                                l = ob.getFloat(info.offset + (f * channels) * 4)
                                r = if (channels > 1) ob.getFloat(info.offset + (f * channels + 1) * 4) else l
                            } else {
                                l = ob.getShort(info.offset + (f * channels) * 2) / 32768f
                                r = if (channels > 1) ob.getShort(info.offset + (f * channels + 1) * 2) / 32768f else l
                            }
                            // naive linear resampling to outRate
                            resampleAcc += 1.0
                            while (resampleAcc >= step) {
                                val t = ((resampleAcc - step) / step).toFloat().coerceIn(0f, 1f)
                                push(lastL + (l - lastL) * (1f - t), lastR + (r - lastR) * (1f - t))
                                resampleAcc -= step
                            }
                            lastL = l; lastR = r
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "decode failed", e)
                error = e.message
                running = false
            } finally {
                try { codec?.stop(); codec?.release() } catch (_: Exception) {}
                extractor.release()
            }
        }
    }

    companion object { private const val TAG = "LocalTrackPlayer" }
}
