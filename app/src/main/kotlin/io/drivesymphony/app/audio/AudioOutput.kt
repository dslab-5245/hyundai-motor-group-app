package io.drivesymphony.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Low-latency float PCM output. One render thread pulls stereo blocks from [renderBlock]
 * and writes them blocking into an [AudioTrack] with USAGE_MEDIA (routes to the car's media volume group).
 */
class AudioOutput(
    private val sampleRate: Int = 48000,
    private val blockFrames: Int = 256,
    private val renderBlock: (FloatArray, Int) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var duckGain = 1f
    private var smoothGain = 1f   // audio-thread only; ~20 ms ramp toward duckGain so ducking never clicks
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "DriveSymphonyAudio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(blockFrames * 2)
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufBytes = maxOf(minBuf, blockFrames * 2 * 4 * 6)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            t.play()
            while (running) {
                renderBlock(block, blockFrames)
                smoothGain += (duckGain - smoothGain) * 0.15f
                val g = smoothGain
                if (g < 0.999f) for (i in block.indices) block[i] *= g
                val written = t.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) { Log.w(TAG, "AudioTrack write error $written"); break }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop failed", e)
        } finally {
            track?.let { t -> try { t.stop() } catch (_: Exception) {}; t.release() }
            track = null
        }
    }

    companion object { private const val TAG = "AudioOutput" }
}
