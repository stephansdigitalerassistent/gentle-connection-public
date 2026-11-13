package ch.heuscher.gentleconnect.voip

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The busy tone (Besetztzeichen) she hears when a call ends or cannot be answered.
 *
 * For a cognitively impaired user who has used ordinary landline telephones all her life,
 * silence or abrupt screen closure does not signal that the other party hung up. It feels
 * like a technical glitch or a broken connection. Hearing the familiar European busy cadence
 * ([ToneGenerator.TONE_SUP_BUSY]) gives immediate, instinctive acoustic feedback that the
 * line has been disconnected.
 *
 * Plays on [AudioManager.STREAM_VOICE_CALL] at volume 80 so it routes properly to earpiece
 * or loudspeaker. The tone plays for [DURATION_MS] (3.5 seconds) unless cancelled early by
 * a local hangup.
 */
class BusyTone {

    private var generator: ToneGenerator? = null
    private var playJob: Job? = null

    /**
     * Starts playing the busy tone asynchronously within [scope].
     *
     * @param durationMs how long to play the tone before invoking [onFinished]. Defaults to 3.5 seconds.
     * @param onFinished callback invoked when the tone finishes naturally after [durationMs].
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        durationMs: Long = DURATION_MS,
        onFinished: () -> Unit = {}
    ) {
        stop()

        generator = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_BUSY, durationMs.toInt())
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start busy tone generator", e)
            null
        }

        if (generator == null) {
            onFinished()
            return
        }

        playJob = scope.launch {
            try {
                delay(durationMs)
                synchronized(this@BusyTone) {
                    stop()
                }
                onFinished()
            } catch (e: CancellationException) {
                // If cancelled (e.g. user pressed hangup), stop immediately.
                synchronized(this@BusyTone) {
                    stop()
                }
            }
        }
    }

    /**
     * Immediately stops the busy tone and releases resources.
     */
    @Synchronized
    fun stop() {
        playJob?.cancel()
        playJob = null

        generator?.let {
            try {
                it.stopTone()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing busy tone generator", e)
            }
        }
        generator = null
    }

    companion object {
        private const val TAG = "BusyTone"
        private const val VOLUME = 80
        const val DURATION_MS = 3_500L

        /**
         * Plays a standalone busy tone for [durationMs] in a background coroutine.
         * Useful for telephone calls ending in [ch.heuscher.gentleconnect.telecom.GentlePhoneCallService].
         */
        fun playStandalone(scope: CoroutineScope, durationMs: Long = DURATION_MS) {
            val tone = BusyTone()
            tone.start(scope, durationMs) {
                tone.stop()
            }
        }
    }
}
