package io.drivesymphony.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Debug
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
    /**
     * Render CPU time / block time as a fraction of one core (~2 s EMA); the service's CPU-budget guard reads it.
     * Thread CPU time, not wall time: the Pleos budget is a CPU share, and a thread preempted by the UI must not look busy.
     */
    @Volatile var load = 0f
        private set
    /** The render thread is alive (also while it is still finishing a blocking write after stop()). */
    val isRunning: Boolean get() = thread?.isAlive == true
    private var smoothGain = 1f   // audio-thread only; ~20 ms ramp toward duckGain so ducking never clicks
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running || thread?.isAlive == true) return
        running = true
        thread = Thread({ loop() }, "DriveSymphonyAudio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        // keep the reference while the thread is still finishing so start() cannot spawn a second one
        if (thread?.isAlive == true) Log.w(TAG, "audio thread still running after stop()") else thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(blockFrames * 2)
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val bufBytes = maxOf(minBuf, blockFrames * 2 * 4 * 4)   // 4 blocks (~43 ms at 512 frames): reaction stays well under 0.2 s
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
            // the real output latency budget: the buffer the platform actually granted (it may exceed the request)
            Log.i(TAG, "AudioTrack buffer ${t.bufferSizeInFrames} frames = ${t.bufferSizeInFrames * 1000 / sampleRate} ms " +
                "(requested ${bufBytes / 8} frames, min ${minBuf / 8}), block $blockFrames frames = ${blockFrames * 1000 / sampleRate} ms")
            t.play()
            // click-free start: ~200 ms of pre-roll silence (same technique Pleos recommends for voice prompts),
            // then the block gain ramps up from zero (~40 ms) instead of starting at full level.
            java.util.Arrays.fill(block, 0f)
            var preRoll = sampleRate / 5
            while (running && preRoll > 0) { t.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING); preRoll -= blockFrames }
            smoothGain = 0f
            load = 0f
            val blockNs = blockFrames * 1_000_000_000.0 / sampleRate
            val loadAlpha = (blockFrames / (sampleRate * 2.0)).toFloat()   // ~2 s window
            while (running) {
                val t0 = Debug.threadCpuTimeNanos()
                renderBlock(block, blockFrames)
                val t1 = Debug.threadCpuTimeNanos()
                if (t0 >= 0L && t1 >= t0) load += (((t1 - t0) / blockNs).toFloat() - load) * loadAlpha
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
            running = false   // a dead loop must not look 'running' to the next start()
            load = 0f
        }
    }

    companion object { private const val TAG = "AudioOutput" }
}
