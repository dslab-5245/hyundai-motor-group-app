package io.drivesymphony.engine.wav

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM WAV writer for interleaved stereo float audio. */
object WavWriter {
    fun write(file: File, interleaved: FloatArray, frames: Int, sampleRate: Int, channels: Int = 2) {
        file.parentFile?.mkdirs()
        val dataBytes = frames * channels * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + dataBytes); header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(channels.toShort())
            header.putInt(sampleRate); header.putInt(sampleRate * channels * 2); header.putShort((channels * 2).toShort()); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(dataBytes)
            out.write(header.array())
            val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * channels) {
                val v = interleaved[i].coerceIn(-1f, 1f)
                buf.putShort((v * 32767f).toInt().toShort())
            }
            out.write(buf.array())
        }
    }
}
