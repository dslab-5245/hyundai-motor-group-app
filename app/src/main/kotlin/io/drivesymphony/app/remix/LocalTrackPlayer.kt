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
 *
 * The ring is a lock-free single-producer (decoder) / single-consumer (audio thread) queue: each side owns one
 * position and only reads the other's. Samples are stored before the owning position is published (volatile
 * write), so the reader never sees a frame that is not fully written and the audio thread never blocks or waits.
 */
class LocalTrackPlayer(private val context: Context, private val uri: Uri, private val outRate: Int = 48000) {
    private val ringFrames = outRate * 6 // 6 s
    private val ring = FloatArray(ringFrames * 2)
    @Volatile private var writePos = 0L   // written by the decoder thread only
    @Volatile private var readPos = 0L    // written by the audio thread only
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
        thread?.join(300)
        thread = null
    }

    /** Audio thread: fills [out] with [frames] stereo frames (zeros on underrun). Never blocks. */
    fun read(out: FloatArray, frames: Int) {
        val rp = readPos
        val n = minOf(frames, (writePos - rp).toInt())
        var idx = (rp % ringFrames).toInt() * 2
        var o = 0
        for (i in 0 until n) {
            out[o++] = ring[idx]; out[o++] = ring[idx + 1]
            idx += 2; if (idx >= ring.size) idx = 0
        }
        for (i in n until frames) { out[o++] = 0f; out[o++] = 0f }
        readPos = rp + n   // published after the copy: the decoder may now overwrite what was read
    }

    // Decoder thread collects resampled frames in a local batch and pushes them into the ring in bulk.
    private val batch = FloatArray(BATCH_FRAMES * 2)
    private var batchFrames = 0

    private fun enqueue(l: Float, r: Float) {
        batch[batchFrames * 2] = l; batch[batchFrames * 2 + 1] = r
        batchFrames++
        if (batchFrames == BATCH_FRAMES) flushBatch()
    }

    private fun flushBatch() {
        var off = 0
        while (off < batchFrames && running) {
            val wp = writePos
            val free = (ringFrames - 1 - (wp - readPos)).toInt()
            if (free <= 0) {
                // ring full (6 s ahead of playback): sleep a little instead of waiting on a monitor the audio thread would have to signal
                try { Thread.sleep(5) } catch (_: InterruptedException) { running = false }
                continue
            }
            val n = minOf(free, batchFrames - off)
            var idx = (wp % ringFrames).toInt() * 2
            for (i in 0 until n) {
                ring[idx] = batch[(off + i) * 2]; ring[idx + 1] = batch[(off + i) * 2 + 1]
                idx += 2; if (idx >= ring.size) idx = 0
            }
            writePos = wp + n   // published after the copy: the audio thread may now read these frames
            off += n
        }
        batchFrames = 0
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
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(context, uri); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it } }
                    finally { r.release() }   // not AutoCloseable on API 28, so no use {}
                }
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
                                enqueue(lastL + (l - lastL) * (1f - t), lastR + (r - lastR) * (1f - t))
                                resampleAcc -= step
                            }
                            lastL = l; lastR = r
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        flushBatch()
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

    companion object { private const val TAG = "LocalTrackPlayer"; private const val BATCH_FRAMES = 4096 }
}
