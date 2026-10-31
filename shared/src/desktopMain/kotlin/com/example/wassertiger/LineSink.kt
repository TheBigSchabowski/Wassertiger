package com.example.wassertiger

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** javax.sound output on the default device; falls back to 44.1 / 48 kHz if it refuses the rate. */
class LineSink : PcmSink {
    private var line: SourceDataLine? = null
    private var bytes = ByteArray(0)
    @Volatile private var closed = false

    override fun open(sampleRate: Int, channels: Int): Int? {
        for (rate in listOf(sampleRate, 44100, 48000).distinct()) {
            try {
                val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                // ~100 ms of buffer: short enough that a step change is heard at once.
                l.open(format, (rate / 10) * channels * 2)
                l.start()
                line = l
                return rate
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    override fun write(samples: ShortArray, count: Int) {
        val l = line ?: return
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        if (!closed) l.write(bytes, 0, count * 2)
    }

    override fun drainAndClose() {
        val l = line ?: return
        if (!closed) l.drain()
        abort()
    }

    @Synchronized
    override fun abort() {
        if (closed) return
        closed = true
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
    }
}
