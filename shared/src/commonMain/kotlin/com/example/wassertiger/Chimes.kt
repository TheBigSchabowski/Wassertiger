package com.example.wassertiger

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * When a session sounds its [Chime]. Off by default: the signal is meant to be silent, so music apps
 * keep playing undisturbed. The chime never takes audio focus, it only mixes in.
 */
enum class ToneMode(val label: String) { OFF("Aus"), END("Am Ende"), EVERY("Bei jedem Wechsel") }

/** The short sounds a session can make, synthesized on the spot (no sound files). */
enum class Chime(val label: String) { BEEP("Piep"), BELL("Glocke"), GONG("Gong"), WOOD("Holzblock") }

/** Whether a session with this [ToneMode] sounds at an interval change, or at the end when [finish]. */
fun ToneMode.sounds(finish: Boolean): Boolean = when (this) {
    ToneMode.OFF -> false
    ToneMode.END -> finish
    ToneMode.EVERY -> true
}

/**
 * One strike: partials as (frequency ratio, level, decay factor) over [base] Hz. Each partial dies away
 * with time constant [decay] × its factor, after holding full level for [hold] seconds.
 */
private class Voice(
    val base: Double,
    val partials: List<Triple<Double, Double, Double>>,
    val decay: Double,
    val hold: Double = 0.0,
    val attack: Double = 0.002,
    /** Seconds one strike lasts before it is cut (with a short fade). */
    val length: Double,
    /** Seconds between the strikes of the final triad. */
    val gap: Double,
)

private fun voice(chime: Chime): Voice = when (chime) {
    // A plain sine beep at A5, flat then quickly released.
    Chime.BEEP -> Voice(880.0, listOf(Triple(1.0, 1.0, 1.0)), decay = 0.025, hold = 0.09, attack = 0.004, length = 0.22, gap = 0.17)
    // Church-bell partials (hum, prime, minor third, fifth, nominal …); the high ones fade first.
    Chime.BELL -> Voice(
        660.0,
        listOf(
            Triple(0.5, 0.35, 1.6), Triple(1.0, 1.0, 1.0), Triple(1.2, 0.45, 0.8), Triple(1.5, 0.3, 0.7),
            Triple(2.0, 0.55, 0.55), Triple(2.5, 0.2, 0.4), Triple(3.0, 0.15, 0.3),
        ),
        decay = 0.9, length = 1.6, gap = 0.28,
    )
    // Low and inharmonic, like a small gong.
    Chime.GONG -> Voice(
        196.0,
        listOf(
            Triple(1.0, 1.0, 1.0), Triple(1.52, 0.6, 0.8), Triple(2.27, 0.5, 0.6),
            Triple(2.86, 0.35, 0.5), Triple(3.94, 0.25, 0.35), Triple(5.1, 0.15, 0.25),
        ),
        decay = 1.1, attack = 0.006, length = 2.0, gap = 0.32,
    )
    // A dry knock: a few inharmonic partials that are gone within a tenth of a second.
    Chime.WOOD -> Voice(
        900.0,
        listOf(Triple(1.0, 1.0, 1.0), Triple(2.7, 0.5, 0.6), Triple(4.6, 0.25, 0.4)),
        decay = 0.03, length = 0.15, gap = 0.14,
    )
}

/** Loudest sample of a chime; it mixes into whatever else plays, so it stays well below full scale. */
const val CHIME_PEAK = 0.5f

/** The notes of a chime as frequency ratios: one strike at a change, a rising major triad at the end. */
private fun notes(finish: Boolean) = if (finish) listOf(1.0, 1.25, 1.5) else listOf(1.0)

/** How long [chime] sounds, in ms. */
fun chimeMillis(chime: Chime, finish: Boolean): Long {
    val v = voice(chime)
    return (((notes(finish).size - 1) * v.gap + v.length) * 1000).toLong()
}

/** Mono samples of [chime] at [sampleRate], peaking at [CHIME_PEAK]; see [notes] for [finish]. */
fun chimeSamples(chime: Chime, finish: Boolean, sampleRate: Int): FloatArray {
    val v = voice(chime)
    val out = FloatArray((chimeMillis(chime, finish) * sampleRate / 1000).toInt())
    val strikeFrames = (v.length * sampleRate).toInt()
    val fadeFrames = sampleRate / 100
    notes(finish).forEachIndexed { n, ratio ->
        val start = (n * v.gap * sampleRate).toInt()
        for (i in 0 until minOf(strikeFrames, out.size - start)) {
            val t = i.toDouble() / sampleRate
            val attack = minOf(1.0, t / v.attack)
            val fade = minOf(1.0, (strikeFrames - i).toDouble() / fadeFrames)
            val decayT = max(0.0, t - v.hold)
            var s = 0.0
            for ((r, level, factor) in v.partials) {
                s += level * exp(-decayT / (v.decay * factor)) * sin(2 * PI * v.base * ratio * r * t)
            }
            out[start + i] += (s * attack * fade).toFloat()
        }
    }
    val peak = out.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
    if (peak > 0f) for (i in out.indices) out[i] *= CHIME_PEAK / peak
    return out
}
