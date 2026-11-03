package com.example.wassertiger

import kotlin.concurrent.thread

/**
 * Plays a [Chime] once on its own [PcmSink], next to whatever else sounds (the drone, Spotify):
 * like the music, it never asks for audio focus, so nothing pauses or ducks.
 */
class ChimePlayer(private val newSink: () -> PcmSink) {

    /** Plays the chime of [session] if its [Session.tone] wants one now. */
    fun signal(session: Session, finish: Boolean) {
        if (session.tone.sounds(finish)) play(session.chime, finish)
    }

    fun play(chime: Chime, finish: Boolean) {
        thread(name = "wassertiger-chime", isDaemon = true) {
            val sink = newSink()
            val rate = sink.open(MusicEngine.SAMPLE_RATE, 1) ?: return@thread
            val samples = chimeSamples(chime, finish, rate)
            val pcm = ShortArray(samples.size) { (samples[it] * 32767).toInt().toShort() }
            try {
                sink.write(pcm, pcm.size)
                sink.drainAndClose()
            } catch (e: Exception) {
                runCatching { sink.abort() }
            }
        }
    }
}
