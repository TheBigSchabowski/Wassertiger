package com.example.wassertiger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** AudioTrack output for the drone and drums; no audio focus, so other apps keep playing too. */
class TrackSink : PcmSink {
    private var track: AudioTrack? = null
    @Volatile private var closed = false
    private var written = 0L
    private var channels = 1

    override fun open(sampleRate: Int, channels: Int): Int? = try {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate / 10 * channels * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        this.channels = channels
        sampleRate
    } catch (e: Exception) {
        null
    }

    override fun write(samples: ShortArray, count: Int) {
        val t = track ?: return
        var offset = 0
        while (offset < count && !closed) {
            val n = t.write(samples, offset, count - offset)
            if (n <= 0) break
            offset += n
        }
        written += count / channels
    }

    override fun drainAndClose() {
        val t = track ?: return
        // AudioTrack has no drain: wait until the play head has reached what was written.
        val deadline = System.currentTimeMillis() + 3000
        while (!closed && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        abort()
    }

    @Synchronized
    override fun abort() {
        if (closed) return
        closed = true
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
    }
}
