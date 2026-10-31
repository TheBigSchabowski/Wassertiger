package com.example.wassertiger

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

enum class DrumKit(val label: String) {
    /** The sampled kit from NicheLooper ("The Black Pearl"). */
    ACOUSTIC("Akustik"),
    /** NicheLooper's synthesized fallback kit. */
    SYNTH("Synth"),
    ELECTRO("808"),
    BRUSHES("Besen"),
    CAJON("Cajón"),
    /** No drums, only the metronome. */
    CLICK("Nur Klick"),
}

/** Mono samples at [rate]. */
class Mono(val rate: Int, val samples: FloatArray) {
    /** Linear interpolation, enough for one-shots. */
    fun resample(to: Int): FloatArray {
        if (to == rate) return samples.copyOf()
        val step = rate.toDouble() / to
        val n = samples.size
        return FloatArray((n.toLong() * to / rate).toInt()) { i ->
            val pos = i * step
            val j = pos.toInt()
            val a = samples[minOf(j, n - 1)]
            a + (samples[minOf(j + 1, n - 1)] - a) * (pos - j).toFloat()
        }
    }

    companion object {
        /** RIFF WAVE with 16-bit PCM; further channels are dropped. */
        fun wav(bytes: ByteArray): Mono {
            fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            fun id(at: Int) = (at until at + 4).map { bytes[it].toInt().toChar() }.joinToString("")
            require(bytes.size > 12 && id(0) == "RIFF" && id(8) == "WAVE") { "Keine WAV-Datei" }
            var pos = 12
            var channels = 0
            var rate = 0
            while (pos + 8 <= bytes.size) {
                val len = u32(pos + 4)
                when (id(pos)) {
                    "fmt " -> {
                        require(u16(pos + 8) == 1 && u16(pos + 22) == 16) { "Nur 16-Bit-PCM" }
                        channels = u16(pos + 10)
                        rate = u32(pos + 12)
                    }
                    "data" -> {
                        require(channels > 0) { "WAV ohne Format" }
                        val frames = minOf(len, bytes.size - pos - 8) / (2 * channels)
                        return Mono(rate, FloatArray(frames) { u16(pos + 8 + it * 2 * channels).toShort() / 32768f })
                    }
                }
                pos += 8 + len + (len and 1)
            }
            error("WAV ohne Audiodaten")
        }
    }
}

/** The sampled acoustic kit (see resources/drums/ATTRIBUTION.txt), as bundled with the app. */
class DrumSamples(val kick: Mono, val snare: Mono, val hat: Mono) {
    companion object {
        /** Null when the files are missing; the acoustic kit then falls back to the synthesized one. */
        fun load(bundled: (String) -> ByteArray?): DrumSamples? = runCatching {
            fun read(name: String) = Mono.wav(bundled("drums/$name.wav") ?: error("drums/$name.wav fehlt"))
            DrumSamples(read("kick"), read("snare"), read("hat"))
        }.getOrNull()
    }
}

/** One kit at the engine's rate: the three one-shots the grooves play. */
class KitSounds(
    val kick: FloatArray,
    val snare: FloatArray,
    val hat: FloatArray,
    /** A new hat cuts the ringing one, like a closed hi-hat; a ride rings on. */
    val hatChokes: Boolean = true,
)

/**
 * The drum kits, taken over from Widder. "Akustik" and "Synth" are NicheLooper's kit and its synthesized
 * fallback; the others are synthesized here. Every sound is scaled to the same peaks NicheLooper
 * uses, so switching kits keeps the balance with the drone.
 */
object DrumKits {
    private const val TWO_PI = 2 * PI

    fun build(kit: DrumKit, rate: Int, samples: DrumSamples?): KitSounds = when (kit) {
        DrumKit.ACOUSTIC -> samples?.let {
            KitSounds(sampled(it.kick, rate, 0.40f), sampled(it.snare, rate, 0.35f), sampled(it.hat, rate, 0.20f))
        } ?: build(DrumKit.SYNTH, rate, null)
        DrumKit.SYNTH, DrumKit.CLICK -> KitSounds(
            scaled(synthKick(rate), 0.40f),
            scaled(synthSnare(rate), 0.35f),
            scaled(synthHat(rate), 0.20f),
        )
        DrumKit.ELECTRO -> KitSounds(scaled(kick808(rate), 0.45f), scaled(clap(rate), 0.33f), scaled(hat808(rate), 0.17f))
        DrumKit.BRUSHES -> KitSounds(scaled(feltKick(rate), 0.34f), scaled(brush(rate), 0.30f), scaled(ride(rate), 0.13f), hatChokes = false)
        DrumKit.CAJON -> KitSounds(scaled(cajonBass(rate), 0.42f), scaled(cajonSlap(rate), 0.34f), scaled(shaker(rate), 0.14f))
    }

    /** Metronome clicks: primary accent (G6), secondary (E6), plain (C6) - as in NicheLooper. */
    fun clicks(rate: Int): List<FloatArray> = listOf(click(rate, 1568.0, 0.55f), click(rate, 1318.5, 0.45f), click(rate, 1046.5, 0.35f))

    private fun click(sr: Int, freq: Double, amp: Float) = render(sr, 0.03) { t, _ -> amp * sin(TWO_PI * freq * t) * exp(-t / 0.005) }

    private fun sampled(mono: Mono, rate: Int, peak: Float): FloatArray = scaled(mono.resample(rate), peak)

    private fun scaled(buf: FloatArray, peak: Float): FloatArray {
        var p = 0f
        for (s in buf) p = max(p, abs(s))
        if (p > 0f) for (i in buf.indices) buf[i] *= peak / p
        return buf
    }

    /** [seconds] of sound from [f] (time in seconds, sample index). */
    private inline fun render(sr: Int, seconds: Double, f: (t: Double, i: Int) -> Double): FloatArray =
        FloatArray((sr * seconds).toInt()) { f(it.toDouble() / sr, it).toFloat() }

    // ---- NicheLooper's synthesized kit ----

    private fun synthKick(sr: Int): FloatArray {
        var phase = 0.0
        // Bottoms out at 55 Hz: deep enough to read as a kick, shallow enough for small speakers.
        return render(sr, 0.2) { t, _ ->
            phase += TWO_PI * (55 + 110 * exp(-t / 0.045)) / sr
            0.5 * sin(phase) * exp(-t / 0.11)
        }
    }

    private fun synthSnare(sr: Int): FloatArray {
        val noise = Noise()
        return render(sr, 0.16) { t, _ ->
            0.22 * sin(TWO_PI * 185 * t) * exp(-t / 0.05) + 0.32 * noise.next() * exp(-t / 0.055)
        }
    }

    private fun synthHat(sr: Int): FloatArray {
        val noise = Noise()
        val hp = Biquad.highPass(sr, 6000.0)
        return render(sr, 0.06) { t, _ -> 0.25 * hp.process(noise.next()) * exp(-t / 0.015) }
    }

    // ---- 808 ----

    private fun kick808(sr: Int): FloatArray {
        var phase = 0.0
        return render(sr, 0.6) { t, _ ->
            phase += TWO_PI * (48 + 90 * exp(-t / 0.03)) / sr
            tanh(1.6 * sin(phase) * exp(-t / 0.32))
        }
    }

    /** Three quick bursts of band-passed noise and a tail: the hand clap. */
    private fun clap(sr: Int): FloatArray {
        val noise = Noise()
        val bp = Biquad.bandPass(sr, 1100.0, 1.2)
        return render(sr, 0.35) { t, _ ->
            val burst = (0..2).sumOf { k -> val d = t - k * 0.011; if (d >= 0) exp(-d / 0.004) else 0.0 }
            val tail = if (t > 0.025) 0.7 * exp(-(t - 0.025) / 0.1) else 0.0
            bp.process(noise.next()) * (burst + tail)
        }
    }

    /** Six detuned square waves, high-passed: the 808's metallic hat. */
    private fun hat808(sr: Int): FloatArray {
        val freqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * 1.6 }
        val hp = Biquad.highPass(sr, 7000.0)
        return render(sr, 0.09) { t, _ ->
            val metal = freqs.sumOf { if (sin(TWO_PI * it * t) >= 0) 1.0 else -1.0 }
            hp.process(metal / 6) * exp(-t / 0.025)
        }
    }

    // ---- Brushes ----

    private fun feltKick(sr: Int): FloatArray {
        var phase = 0.0
        return render(sr, 0.25) { t, _ ->
            phase += TWO_PI * (62 + 30 * exp(-t / 0.02)) / sr
            sin(phase) * exp(-t / 0.08) * minOf(1.0, t / 0.004)
        }
    }

    /** A brush slapping the snare: dark noise with a soft attack and a longer swish. */
    private fun brush(sr: Int): FloatArray {
        val noise = Noise()
        val bp = Biquad.bandPass(sr, 2600.0, 0.6)
        return render(sr, 0.28) { t, _ ->
            bp.process(noise.next()) * minOf(1.0, t / 0.006) * (0.8 * exp(-t / 0.05) + 0.25 * exp(-t / 0.18))
        }
    }

    /** The ride cymbal's tip: a few inharmonic partials over hissing noise, ringing on. */
    private fun ride(sr: Int): FloatArray {
        val noise = Noise()
        val hp = Biquad.highPass(sr, 5000.0)
        val partials = doubleArrayOf(3170.0, 4120.0, 5340.0, 6610.0)
        return render(sr, 0.7) { t, _ ->
            val ring = partials.withIndex().sumOf { (k, f) -> sin(TWO_PI * f * t + k) } / partials.size
            (0.6 * hp.process(noise.next()) * exp(-t / 0.05) + 0.5 * ring * exp(-t / 0.3)) * minOf(1.0, t / 0.001)
        }
    }

    // ---- Cajón ----

    private fun cajonBass(sr: Int): FloatArray {
        val noise = Noise()
        val lp = Biquad.lowPass(sr, 400.0)
        var phase = 0.0
        return render(sr, 0.3) { t, _ ->
            phase += TWO_PI * (82 + 45 * exp(-t / 0.012)) / sr
            sin(phase) * exp(-t / 0.1) + 0.6 * lp.process(noise.next()) * exp(-t / 0.01)
        }
    }

    /** The slap at the top edge: a short wooden body and the rattle of the snare wires inside. */
    private fun cajonSlap(sr: Int): FloatArray {
        val noise = Noise()
        val bp = Biquad.bandPass(sr, 2500.0, 0.9)
        return render(sr, 0.22) { t, _ ->
            0.5 * sin(TWO_PI * 240 * t) * exp(-t / 0.03) + bp.process(noise.next()) * exp(-t / 0.06)
        }
    }

    private fun shaker(sr: Int): FloatArray {
        val noise = Noise()
        val bp = Biquad.bandPass(sr, 6500.0, 1.0)
        return render(sr, 0.1) { t, _ ->
            val env = if (t < 0.014) t / 0.014 else exp(-(t - 0.014) / 0.025)
            bp.process(noise.next()) * env
        }
    }

    /** Deterministic white noise, so a kit sounds the same every time it is built. */
    private class Noise {
        private var seed = 0x1D872B41

        fun next(): Double {
            seed = seed * 1664525 + 1013904223
            return seed / 2147483648.0
        }
    }

    /** RBJ cookbook filters, enough to shape noise. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            private fun make(sr: Int, freq: Double, q: Double, b: (cosW: Double, alpha: Double) -> Triple<Double, Double, Double>): Biquad {
                val w = TWO_PI * freq.coerceAtMost(sr * 0.45) / sr
                val alpha = sin(w) / (2 * q)
                val a0 = 1 + alpha
                val (b0, b1, b2) = b(cos(w), alpha)
                return Biquad(b0 / a0, b1 / a0, b2 / a0, -2 * cos(w) / a0, (1 - alpha) / a0)
            }

            fun lowPass(sr: Int, freq: Double, q: Double = 0.707) = make(sr, freq, q) { c, _ -> Triple((1 - c) / 2, 1 - c, (1 - c) / 2) }
            fun highPass(sr: Int, freq: Double, q: Double = 0.707) = make(sr, freq, q) { c, _ -> Triple((1 + c) / 2, -(1 + c), (1 + c) / 2) }
            fun bandPass(sr: Int, freq: Double, q: Double) = make(sr, freq, q) { _, alpha -> Triple(alpha, 0.0, -alpha) }
        }
    }
}
