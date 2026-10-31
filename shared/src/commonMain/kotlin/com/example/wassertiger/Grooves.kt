package com.example.wassertiger

/** Time signature of a groove; BPM counts quarters, in 6/8 the eighths. */
enum class Meter(val label: String, val beats: Int) { FOUR_FOUR("4/4", 4), THREE_FOUR("3/4", 3), TWO_FOUR("2/4", 2), SIX_EIGHT("6/8", 6) }

/**
 * One drum pattern for one bar, taken over from Widder (NicheLooper's RhythmSection). Bit i of each mask is
 * step i of the bar; the accent masks pick the steps played at full strength, everything else in
 * the pattern plays soft (ghost notes, unaccented hats), which keeps a one-bar loop from sounding
 * mechanical.
 */
class Groove(
    val name: String,
    val meter: Meter,
    /** Sequencer resolution of this groove. */
    val steps: Int,
    /** steps / beats; 3 = triplet feel. */
    val stepsPerBeat: Int,
    val kick: Int,
    val kickAccent: Int,
    val snare: Int,
    val snareAccent: Int,
    val hat: Int,
    val hatAccent: Int,
    /** Steps with a secondary metronome accent (beat 1 always gets the primary one). */
    val midAccent: Int = 0,
)

object Grooves {
    /**
     * Grouped by meter, in the order the editor lists them. Step bit i (1 shl i) is step i
     * at the groove's own resolution: 4 steps per beat = 16ths, 3 = 8th triplets (shuffle), 2 = the
     * eighths of compound meter.
     */
    val all: List<Groove> = listOf(
        // ---- 4/4, 16 steps: beats on 0, 4, 8, 12 ----
        // Kick 1 & 3, snare 2 & 4, hats in 8ths accented on the quarters.
        Groove("Rock", Meter.FOUR_FOUR, 16, 4, 0x0101, 0x0101, 0x1010, 0x1010, 0x5555, 0x1111),
        // Adds the kick on the "and" of 3 - the everyday pop push.
        Groove("Pop", Meter.FOUR_FOUR, 16, 4, 0x0501, 0x0501, 0x1010, 0x1010, 0x5555, 0x1111),
        // Same backbeat, hats in 16ths: drives without changing the feel.
        Groove("Straight 16", Meter.FOUR_FOUR, 16, 4, 0x0101, 0x0101, 0x1010, 0x1010, 0xFFFF, 0x5555),
        // Syncopated kick plus ghost snares; only the downbeat hats are accented.
        Groove("Funk", Meter.FOUR_FOUR, 16, 4, 0x0409, 0x0409, 0x5090, 0x1010, 0xFFFF, 0x1111),
        // Half-time: kick 1, snare 3. Room to breathe.
        Groove("Ballad", Meter.FOUR_FOUR, 16, 4, 0x0001, 0x0001, 0x0100, 0x0100, 0x5555, 0x1111),
        // Kick on every quarter, hats accented on the offbeats.
        Groove("Four on the Floor", Meter.FOUR_FOUR, 16, 4, 0x1111, 0x1111, 0x1010, 0x1010, 0x5555, 0x4444),
        // Triplet grid: 12 steps = 4 beats x 3. Hats long-short per beat.
        Groove("Shuffle", Meter.FOUR_FOUR, 12, 3, 0x041, 0x041, 0x208, 0x208, 0xB6D, 0x249),
        // Surdo-ish kick, clave on the snare kept ghosted so it reads as a rim click.
        Groove("Bossa", Meter.FOUR_FOUR, 16, 4, 0x4141, 0x4141, 0x1448, 0x0000, 0x5555, 0x1111),

        // ---- 3/4, 12 steps: beats on 0, 4, 8 ----
        Groove("Waltz", Meter.THREE_FOUR, 12, 4, 0x001, 0x001, 0x110, 0x110, 0x111, 0x001),
        Groove("Jazz Waltz", Meter.THREE_FOUR, 12, 4, 0x001, 0x001, 0x110, 0x100, 0x555, 0x111),
        Groove("Waltz Ballad", Meter.THREE_FOUR, 12, 4, 0x001, 0x001, 0x100, 0x100, 0x555, 0x111),

        // ---- 2/4, 8 steps: beats on 0, 4 ----
        Groove("March", Meter.TWO_FOUR, 8, 4, 0x01, 0x01, 0x10, 0x10, 0x55, 0x11),
        Groove("Polka", Meter.TWO_FOUR, 8, 4, 0x11, 0x11, 0x44, 0x44, 0x55, 0x11),
        Groove("2/4 Rock", Meter.TWO_FOUR, 8, 4, 0x01, 0x01, 0x10, 0x10, 0xFF, 0x55),

        // ---- 6/8, 12 steps: the six eighths on 0, 2, 4, 6, 8, 10 ----
        Groove("6/8 Basic", Meter.SIX_EIGHT, 12, 2, 0x001, 0x001, 0x040, 0x040, 0x555, 0x041, midAccent = 0x040),
        Groove("6/8 Blues", Meter.SIX_EIGHT, 12, 2, 0x021, 0x021, 0x040, 0x040, 0xFFF, 0x041, midAccent = 0x040),
        Groove("6/8 March", Meter.SIX_EIGHT, 12, 2, 0x041, 0x041, 0x514, 0x104, 0x555, 0x041, midAccent = 0x040),
    )

    /** The groove called [name], Rock for an unknown one. */
    fun named(name: String?): Groove = all.firstOrNull { it.name == name } ?: all.first()
}
