package ch.heuscher.gentleconnect.voip

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * The ringing she hears while the person she called is being rung.
 *
 * A Threema call is a self-managed Telecom call, and Telecom plays no ringback for those: without
 * this she hears nothing at all between tapping a face and somebody answering, and silence on a
 * telephone reads as "it isn't working" — on the Fold, 2026-09-16, two calls were hung up after
 * 7 and 22 seconds of it.
 *
 * Starts immediately upon dialling, exactly like an old telephone exchange. An elderly user who
 * taps a person needs instantaneous acoustic confirmation that the call is in progress.
 *
 * [ToneGenerator.TONE_SUP_RINGTONE] is the European ringing cadence (425 Hz, one second on, four
 * off), the one she knows from the telephone. It plays on the voice-call stream, so it follows
 * the call's route to earpiece or loudspeaker.
 */
class RingbackTone {

    private var generator: ToneGenerator? = null

    @Synchronized
    fun start() {
        if (generator != null) return
        generator = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (e: RuntimeException) {
            // ToneGenerator throws when the audio system has no track to give it. A call without
            // ringback is still a call.
            Log.w(TAG, "Could not start ringback tone", e)
            null
        }
    }

    @Synchronized
    fun stop() {
        generator?.let {
            it.stopTone()
            it.release()
        }
        generator = null
    }

    companion object {
        private const val TAG = "RingbackTone"
        private const val VOLUME = 80
    }
}
