package io.drivesymphony.engine.theory

import io.drivesymphony.engine.dsp.Rng

/** Pitch-class helpers. MIDI 60 = C4. */
object Pitch {
    val NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    fun name(midi: Int): String = NAMES[((midi % 12) + 12) % 12]
}

enum class ChordQuality(val label: String, val intervals: IntArray, val extensions: IntArray) {
    MAJ7("maj7", intArrayOf(0, 4, 7, 11), intArrayOf(14, 21)),      // 9, 13
    MIN7("m7", intArrayOf(0, 3, 7, 10), intArrayOf(14, 17)),        // 9, 11
    DOM7("7", intArrayOf(0, 4, 7, 10), intArrayOf(14, 21)),         // 9, 13
    DOM7_SUS("7sus4", intArrayOf(0, 5, 7, 10), intArrayOf(14)),
    MIN7_B5("m7b5", intArrayOf(0, 3, 6, 10), intArrayOf(13)),
    MIN_MAJ7("mMaj7", intArrayOf(0, 3, 7, 11), intArrayOf(14)),
    MAJ6("6", intArrayOf(0, 4, 7, 9), intArrayOf(14)),
    MIN6("m6", intArrayOf(0, 3, 7, 9), intArrayOf(14)),
    MAJ_ADD9("add9", intArrayOf(0, 4, 7, 14), intArrayOf(11)),
    SUS2("sus2", intArrayOf(0, 2, 7), intArrayOf(11, 14));
}

/** A chord with an absolute root (MIDI pitch class 0..11 + octave handled by voicing). */
data class Chord(val rootPc: Int, val quality: ChordQuality, val beats: Int = 4) {
    val label: String get() = Pitch.NAMES[((rootPc % 12) + 12) % 12] + quality.label

    /** Scale (pitch-class offsets from root) that fits the chord for melody generation. */
    fun scale(): IntArray = when (quality) {
        ChordQuality.MAJ7, ChordQuality.MAJ6, ChordQuality.MAJ_ADD9 -> intArrayOf(0, 2, 4, 6, 7, 9, 11) // lydian-ish: avoids the 4th rub
        ChordQuality.MIN7, ChordQuality.MIN6 -> intArrayOf(0, 2, 3, 5, 7, 9, 10)                    // dorian
        ChordQuality.DOM7, ChordQuality.DOM7_SUS -> intArrayOf(0, 2, 4, 5, 7, 9, 10)                // mixolydian
        ChordQuality.MIN7_B5 -> intArrayOf(0, 1, 3, 5, 6, 8, 10)                                     // locrian
        ChordQuality.MIN_MAJ7 -> intArrayOf(0, 2, 3, 5, 7, 9, 11)                                    // melodic minor
        ChordQuality.SUS2 -> intArrayOf(0, 2, 4, 7, 9)                                               // pentatonic
    }

    /** Pentatonic-ish "safe" note set used for sparse melodies (always consonant). */
    fun safeTones(): IntArray = when (quality) {
        ChordQuality.MAJ7, ChordQuality.MAJ6, ChordQuality.MAJ_ADD9, ChordQuality.SUS2 -> intArrayOf(0, 2, 4, 7, 9)
        ChordQuality.MIN7, ChordQuality.MIN6, ChordQuality.MIN_MAJ7 -> intArrayOf(0, 3, 5, 7, 10)
        ChordQuality.DOM7, ChordQuality.DOM7_SUS -> intArrayOf(0, 2, 4, 7, 10)
        ChordQuality.MIN7_B5 -> intArrayOf(0, 3, 6, 10)
    }
}

/**
 * Voicing: produces MIDI notes for the comping instrument.
 * richness 0..1 adds extensions (9ths/13ths) and widens the spread as the driver "earns" them.
 */
object Voicing {
    /** Rootless-ish mid register voicing around C4..C5. */
    fun comp(chord: Chord, richness: Float, rng: Rng): IntArray {
        val base = 60 + chord.rootPc
        val core = chord.quality.intervals.drop(1) // drop root: bass has it
        val notes = ArrayList<Int>(5)
        for (iv in core) notes.add(base + iv)
        if (richness > 0.35f && chord.quality.extensions.isNotEmpty()) {
            notes.add(base + chord.quality.extensions[0])
        }
        if (richness > 0.75f && chord.quality.extensions.size > 1) {
            notes.add(base + chord.quality.extensions[1])
        }
        // keep inside a comfortable register: fold notes above G5 down an octave
        val folded = notes.map { if (it > 79) it - 12 else it }.distinct().sorted()
        // light randomization of inversion for natural comping
        return if (folded.size > 3 && rng.chance(0.4f)) {
            (folded.drop(1) + (folded[0] + 12)).toIntArray()
        } else folded.toIntArray()
    }

    /** Wide, slow pad voicing: root, fifth, seventh, ninth across two octaves. */
    fun pad(chord: Chord, richness: Float): IntArray {
        val base = 48 + chord.rootPc
        val q = chord.quality
        val notes = ArrayList<Int>(5)
        notes.add(base)
        notes.add(base + 7)
        notes.add(base + 12 + q.intervals[1])
        notes.add(base + 12 + q.intervals.getOrElse(3) { q.extensions.firstOrNull() ?: 7 })
        if (richness > 0.5f && q.extensions.isNotEmpty()) notes.add(base + 12 + q.extensions[0])
        return notes.toIntArray()
    }
}

enum class Genre(val label: String, val bpmMin: Float, val bpmMax: Float) {
    LOFI("Lo-fi", 72f, 86f),
    JAZZ("Jazz", 108f, 128f),
    AMBIENT("Ambient", 56f, 64f);
}

/** Named progressions. Degrees are semitone offsets from the key root. */
object Progressions {
    data class Step(val degree: Int, val quality: ChordQuality, val beats: Int = 4)

    private val lofi = listOf(
        listOf(Step(0, ChordQuality.MAJ7), Step(9, ChordQuality.MIN7), Step(2, ChordQuality.MIN7), Step(7, ChordQuality.DOM7)),          // I vi ii V
        listOf(Step(2, ChordQuality.MIN7), Step(7, ChordQuality.DOM7), Step(0, ChordQuality.MAJ7), Step(9, ChordQuality.MIN7)),          // ii V I vi
        listOf(Step(5, ChordQuality.MAJ7), Step(4, ChordQuality.MIN7), Step(9, ChordQuality.MIN7), Step(7, ChordQuality.DOM7_SUS)),      // IV iii vi Vsus
        listOf(Step(0, ChordQuality.MAJ_ADD9), Step(5, ChordQuality.MAJ7), Step(9, ChordQuality.MIN7), Step(5, ChordQuality.MAJ6)),      // I IV vi IV6
    )
    private val jazz = listOf(
        listOf(Step(2, ChordQuality.MIN7), Step(7, ChordQuality.DOM7), Step(0, ChordQuality.MAJ7), Step(9, ChordQuality.DOM7)),          // ii V I VI7 turnaround
        listOf(Step(0, ChordQuality.MAJ7), Step(9, ChordQuality.MIN7), Step(2, ChordQuality.MIN7), Step(7, ChordQuality.DOM7)),          // rhythm changes A
        listOf(Step(5, ChordQuality.MIN7), Step(10, ChordQuality.DOM7), Step(3, ChordQuality.MAJ7), Step(8, ChordQuality.MAJ7),
            Step(2, ChordQuality.MIN7_B5), Step(7, ChordQuality.DOM7), Step(0, ChordQuality.MIN7), Step(0, ChordQuality.MIN7)),            // autumn-leaves cycle (relative minor)
        listOf(Step(0, ChordQuality.MIN7, 8), Step(5, ChordQuality.MIN7, 8)),                                                            // modal vamp
    )
    private val ambient = listOf(
        listOf(Step(0, ChordQuality.MAJ_ADD9, 8), Step(5, ChordQuality.MAJ7, 8)),                                                        // I IV slow
        listOf(Step(0, ChordQuality.SUS2, 8), Step(2, ChordQuality.SUS2, 8), Step(0, ChordQuality.MAJ7, 8)),                             // lydian drift
        listOf(Step(9, ChordQuality.MIN7, 8), Step(5, ChordQuality.MAJ7, 8), Step(7, ChordQuality.DOM7_SUS, 8)),                         // vi IV Vsus
        listOf(Step(0, ChordQuality.MIN_MAJ7, 8), Step(8, ChordQuality.MAJ7, 8), Step(10, ChordQuality.DOM7_SUS, 8)),                    // minor cinematic
    )

    fun pick(genre: Genre, keyRoot: Int, rng: Rng): List<Chord> {
        val pool = when (genre) { Genre.LOFI -> lofi; Genre.JAZZ -> jazz; Genre.AMBIENT -> ambient }
        val prog = pool[rng.nextInt(pool.size)]
        return prog.map { Chord((keyRoot + it.degree) % 12, it.quality, it.beats) }
    }

    /** Keys that sit well for EP/bass registers; night leans to flat keys (darker), day to sharps. */
    fun pickKey(hourOfDay: Int, rng: Rng): Int {
        val night = hourOfDay < 6 || hourOfDay >= 19
        val pool = if (night) intArrayOf(10, 3, 8, 1, 5) else intArrayOf(0, 7, 2, 5, 9)
        return pool[rng.nextInt(pool.size)]
    }
}
