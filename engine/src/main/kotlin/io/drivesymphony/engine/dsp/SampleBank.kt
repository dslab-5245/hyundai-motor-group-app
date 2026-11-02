package io.drivesymphony.engine.dsp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drive Symphony sample bank (".dsb"): a flat, little-endian container of 16-bit mono PCM zones grouped into
 * instruments, extracted from the MIT-licensed FluidR3_GM SoundFont by `scripts/sf2_extract.py`.
 *
 * Format, version 1 (all integers little-endian, no alignment padding):
 * ```
 * header
 *   4   magic            "DSBK"
 *   1   version          1
 *   1   instrumentCount  u8
 *   2   reserved         0
 * instrument (repeated instrumentCount times)
 *   1   nameLen          u8
 *   n   name             UTF-8
 *   4   attackSec        f32   linear-amplitude attack time
 *   4   holdSec          f32   time at full level before the decay
 *   4   decaySec         f32   SF2 convention: time to fall 96 dB (linear in dB), stopped at sustain
 *   4   sustain          f32   linear amplitude 0..1
 *   4   releaseSec       f32   SF2 convention: time to fall 96 dB after note-off
 *   2   zoneCount        u16
 *   zone (repeated zoneCount times)
 *     1  lowKey, 1 highKey, 1 lowVel, 1 highVel, 1 rootKey   u8 each
 *     1  pad              0
 *     2  fineCents        i16  added to the pitch (coarse + fine tune + sample pitch correction)
 *     4  sampleRate       i32  native rate of the PCM
 *     4  loopStart        i32  first sample of the loop
 *     4  loopEnd          i32  first sample AFTER the loop (SF2 convention); -1 = one-shot, no loop
 *     4  gainDb           f32  zone gain (negative = SF2 attenuation)
 *     4  frameCount       i32
 *     2* pcm              i16  frameCount mono samples
 * ```
 * Velocity ranges are MIDI 0..127; the sampler maps its 0..1 velocity onto that scale.
 */
class SampleZone(
    val lowKey: Int,
    val highKey: Int,
    val lowVel: Int,
    val highVel: Int,
    val rootKey: Int,
    val fineCents: Int,
    val sampleRate: Int,
    val loopStart: Int,
    /** First sample after the loop; -1 when the zone is a one-shot. */
    val loopEnd: Int,
    val gainDb: Float,
    val pcm: ShortArray,
) {
    val looped: Boolean get() = loopEnd > loopStart && loopStart >= 0 && loopEnd <= pcm.size
    val frames: Int get() = pcm.size
    fun matches(key: Int, vel: Int): Boolean = key in lowKey..highKey && vel in lowVel..highVel
}

class SampleInstrument(
    val name: String,
    val zones: List<SampleZone>,
    val attackSec: Float,
    val decaySec: Float,
    val sustain: Float,
    val releaseSec: Float,
    val holdSec: Float = 0f,
) {
    val lowKey: Int get() = zones.minOfOrNull { it.lowKey } ?: 0
    val highKey: Int get() = zones.maxOfOrNull { it.highKey } ?: 127
    val totalFrames: Long get() = zones.sumOf { it.pcm.size.toLong() }

    /** Best zone for a key/velocity (vel 0..127); nearest zone by key distance when nothing covers it exactly. */
    fun zoneFor(key: Int, vel: Int): SampleZone? {
        var best: SampleZone? = null
        var bestDist = Int.MAX_VALUE
        for (z in zones) {
            val kd = if (key < z.lowKey) z.lowKey - key else if (key > z.highKey) key - z.highKey else 0
            val vd = if (vel < z.lowVel) z.lowVel - vel else if (vel > z.highVel) vel - z.highVel else 0
            val d = kd * 4 + vd
            if (d < bestDist) { bestDist = d; best = z; if (d == 0) break }
        }
        return best
    }

    fun hasKey(key: Int): Boolean = zones.any { key in it.lowKey..it.highKey }
}

class SampleBank(val instruments: Map<String, SampleInstrument>) {
    operator fun get(name: String): SampleInstrument? = instruments[name]
    val totalBytes: Long get() = instruments.values.sumOf { it.totalFrames } * 2

    /** Serialises the bank in the version-1 format (used by the round-trip test and available for tooling). */
    fun toBytes(): ByteArray {
        require(instruments.size < 256) { "too many instruments" }
        val out = ByteArrayOutputStream()
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        out.write(buf(8).put(MAGIC).put(VERSION.toByte()).put(instruments.size.toByte()).putShort(0).array())
        for (inst in instruments.values) {
            val name = inst.name.toByteArray(Charsets.UTF_8)
            require(name.size < 256 && inst.zones.size < 65536)
            out.write(buf(1 + name.size + 22).put(name.size.toByte()).put(name)
                .putFloat(inst.attackSec).putFloat(inst.holdSec).putFloat(inst.decaySec).putFloat(inst.sustain).putFloat(inst.releaseSec)
                .putShort(inst.zones.size.toShort()).array())
            for (z in inst.zones) {
                val b = buf(ZONE_HEADER + z.pcm.size * 2)
                b.put(z.lowKey.toByte()).put(z.highKey.toByte()).put(z.lowVel.toByte()).put(z.highVel.toByte()).put(z.rootKey.toByte()).put(0)
                b.putShort(z.fineCents.toShort()).putInt(z.sampleRate).putInt(z.loopStart).putInt(z.loopEnd).putFloat(z.gainDb).putInt(z.pcm.size)
                b.asShortBuffer().put(z.pcm)
                out.write(b.array())
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_RESOURCE = "/samples/fluid_lofi.dsb"
        private val MAGIC = "DSBK".toByteArray(Charsets.US_ASCII)
        private const val ZONE_HEADER = 28

        /** Reads the bundled FluidR3 bank from the class path; null when the resource is missing or unreadable. Never throws. */
        fun loadDefault(): SampleBank? = try {
            val stream = SampleBank::class.java.getResourceAsStream(DEFAULT_RESOURCE) ?: return null
            stream.use { read(it) }
        } catch (t: Throwable) {
            null
        }

        fun read(stream: InputStream): SampleBank = read(stream.readBytes())

        fun read(bytes: ByteArray): SampleBank {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals(MAGIC)) { "not a .dsb bank" }
            val version = b.get().toInt() and 0xFF
            require(version == VERSION) { "unsupported .dsb version $version" }
            val count = b.get().toInt() and 0xFF
            b.getShort()
            val map = LinkedHashMap<String, SampleInstrument>(count * 2)
            repeat(count) {
                val nameLen = b.get().toInt() and 0xFF
                val name = ByteArray(nameLen).also { b.get(it) }.toString(Charsets.UTF_8)
                val attack = b.getFloat(); val hold = b.getFloat(); val decay = b.getFloat(); val sustain = b.getFloat(); val release = b.getFloat()
                val zoneCount = b.getShort().toInt() and 0xFFFF
                val zones = ArrayList<SampleZone>(zoneCount)
                repeat(zoneCount) {
                    val lowKey = b.get().toInt() and 0xFF; val highKey = b.get().toInt() and 0xFF
                    val lowVel = b.get().toInt() and 0xFF; val highVel = b.get().toInt() and 0xFF
                    val rootKey = b.get().toInt() and 0xFF; b.get()
                    val fine = b.getShort().toInt()
                    val rate = b.getInt(); val loopStart = b.getInt(); val loopEnd = b.getInt(); val gainDb = b.getFloat()
                    val frames = b.getInt()
                    require(frames >= 0 && frames * 2 <= b.remaining()) { "corrupt zone in $name" }
                    val pcm = ShortArray(frames)
                    b.asShortBuffer().get(pcm)
                    b.position(b.position() + frames * 2)
                    zones.add(SampleZone(lowKey, highKey, lowVel, highVel, rootKey, fine, rate, loopStart, loopEnd, gainDb, pcm))
                }
                map[name] = SampleInstrument(name, zones, attack, decay, sustain, release, hold)
            }
            return SampleBank(map)
        }
    }
}
