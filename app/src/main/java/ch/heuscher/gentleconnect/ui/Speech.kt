package ch.heuscher.gentleconnect.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.model.ReadAloud
import ch.heuscher.gentleconnect.speech.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * The phone's one voice: reading aloud, saying who she is calling, and playing the voice messages
 * she is sent.
 *
 * The two are one thing on purpose. Two voices at once is unusable for anybody, and for her worse
 * than unusable: she cannot tell which of the two she started, so she cannot work out what to press
 * to stop it. So reading aloud stops a voice message and a voice message stops reading aloud — which
 * only works if both live in the same place.
 *
 * Shared across the process, because more than one screen speaks: the home screen announces a
 * call, the conversation reads a message aloud and plays a voice message. See [shared].
 */
class Speech(
    /** The engine. Null where there is none — in tests: then nothing is said. */
    private val ttsManager: TtsManager?,
    scope: CoroutineScope,
    /** The caregiver's speech rate, followed for as long as the voice exists. */
    speechRate: Flow<Float>
) {

    init {
        // Collected rather than read once: the caregiver changes this from the website while the
        // phone is sitting on a table, and the whole point of the setting is that it takes effect
        // there and then, without anybody visiting her to restart anything.
        scope.launch {
            speechRate.distinctUntilChanged().collect { rate -> ttsManager?.setSpeechRate(rate) }
        }
    }

    /** What the voice is reading at this moment, so the button that started it can show it. */
    val readingAloud: StateFlow<String?> = ttsManager?.reading ?: MutableStateFlow(null)

    /** When the reading began, on a clock that only runs forwards. */
    private var readingSinceMs = 0L

    /**
     * A press on a "read aloud" button.
     *
     * Reads [text], unless that is what is already being read: then the press stops it, or —
     * straight after it started — does nothing at all. See [ReadAloud.onTap].
     */
    fun speakText(text: String) {
        val now = System.nanoTime() / 1_000_000
        // Asked of the engine's own state rather than of [readingAloud], which is a collector
        // behind it.
        when (ReadAloud.onTap(ttsManager?.reading?.value, text, now - readingSinceMs)) {
            ReadAloud.Tap.IGNORE -> return
            ReadAloud.Tap.STOP -> {
                stopSpeaking()
                return
            }
            ReadAloud.Tap.START -> Unit
        }
        readingSinceMs = now
        say(text)
    }

    /** Says [text] whatever is being said now. For what the phone announces unasked. */
    fun say(text: String) {
        // One voice at a time. If a voice message is playing she is listening to her daughter,
        // and the reading-aloud voice starting over the top of it is two people talking at once
        // with no way to tell which control silenced what.
        stopVoiceMessage()
        ttsManager?.speak(text)
    }

    /**
     * Stop reading aloud. Called when she closes whatever was being read, so the voice does not
     * carry on talking about a screen that is no longer in front of her.
     */
    fun stopSpeaking() {
        ttsManager?.stop()
    }

    /** The one player. See [playVoiceMessage] for why there is exactly one. */
    private var voicePlayer: MediaPlayer? = null

    /** Which message is sounding, so its control can show that it is the one playing. */
    private val _playingVoiceMessage = MutableStateFlow<String?>(null)
    val playingVoiceMessage: StateFlow<String?> = _playingVoiceMessage.asStateFlow()

    /**
     * Plays a voice message somebody sent her.
     *
     * One player for the whole app, and reading-aloud is stopped first. Two voices at once is
     * unusable for anybody, and for her it is worse than unusable: she cannot tell which of the
     * two she started, so she cannot work out what to press to stop it.
     *
     * Pressing the control of whatever is already playing stops it. That is the whole of the
     * interaction — no pause and resume, no scrubbing, no speed. A control that means "stop" one
     * moment and "carry on from where you were" the next is two controls wearing one face.
     */
    fun playVoiceMessage(messageId: String, path: String) {
        if (_playingVoiceMessage.value == messageId) {
            stopVoiceMessage()
            return
        }

        stopVoiceMessage()
        ttsManager?.stop()

        val file = File(path)
        if (!file.isFile) {
            // Pruned, or never arrived. Nothing on her screen says so — see the note about error
            // copy in the caregiver log — and the control simply does nothing.
            return
        }

        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener { stopVoiceMessage() }
                setOnErrorListener { _, _, _ ->
                    stopVoiceMessage()
                    true
                }
                prepare()
                start()
            }
        }.onSuccess { player ->
            voicePlayer = player
            _playingVoiceMessage.value = messageId
        }
    }

    /** Silence. Safe to call when nothing is playing, which is most of the time. */
    fun stopVoiceMessage() {
        voicePlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        voicePlayer = null
        _playingVoiceMessage.value = null
    }

    companion object {
        @Volatile
        private var instance: Speech? = null

        /** The process's one voice. See the class comment for why there is exactly one. */
        fun shared(context: Context): Speech = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                Speech(
                    ttsManager = TtsManager(app),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    speechRate = Messengers.settings(app).settings.map { it.speechRate }
                ).also { instance = it }
            }
        }
    }
}
