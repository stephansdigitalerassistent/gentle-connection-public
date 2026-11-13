package ch.heuscher.gentleconnect.speech

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Emoji
import ch.heuscher.gentleconnect.model.ReadAloud
import ch.heuscher.gentleconnect.model.Speech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Text-To-Speech (TTS) manager providing voice speech output ("Sprechen" / Vorlesen)
 * for elderly users with vision or cognitive decline.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    /**
     * The language of the strings Android actually resolved, not the device locale and not a
     * hardcoded one. Those three can disagree — a French phone falls back to the English
     * strings — and reading English text aloud in a French voice produces something she cannot
     * understand at exactly the moment she reached for help.
     *
     * `tts_language_tag` is defined once per values-* directory, so it is resolved by the same
     * rules as the text on screen and cannot drift away from it.
     */
    private val appLocale: Locale =
        Locale.forLanguageTag(context.getString(R.string.tts_language_tag))

    private val emojiWords: Map<String, String> = EmojiNames.resolve(context)

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var isInitialized = false

    /**
     * The speed to read at, kept here rather than only inside the engine.
     *
     * The engine cannot be told anything before it has finished starting up, and the caregiver's
     * setting arrives from Firestore whenever it arrives — often first. Holding the number means
     * the two can happen in either order and the last one still wins: a rate set early is applied
     * in [onInit], and one set later is applied at once.
     */
    @Volatile
    private var speechRate: Float = Speech.DEFAULT_RATE

    /**
     * Text requested before the engine finished starting up. Initialisation takes a moment, and
     * a tap on "read aloud" in that window used to do nothing at all — no sound, no error,
     * nothing to try differently.
     */
    @Volatile
    private var pendingText: String? = null

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * The text being read at this moment, as it was asked for, or null in silence.
     *
     * Set the moment reading is asked for rather than when the engine reports its first sound,
     * so the button she pressed answers her at once, and cleared when the engine says it has
     * finished, failed or been cut off.
     */
    private val _reading = MutableStateFlow<String?>(null)
    val reading: StateFlow<String?> = _reading.asStateFlow()

    /** Which utterance [reading] belongs to: the end of an older one must not clear a newer. */
    @Volatile
    private var readingId: String? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId != null && utteranceId == readingId) silent()
    }

    private fun silent() {
        readingId = null
        _reading.value = null
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w("TtsManager", "TTS initialization failed with status $status")
            // Nothing will ever be read: a button left saying it is reading would be a lie.
            pendingText = null
            silent()
            return
        }
        tts?.setOnUtteranceProgressListener(progress)

        val result = tts?.setLanguage(appLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.getDefault())
        }
        applySpeechRate()
        isInitialized = true
        Log.d("TtsManager", "TTS initialized successfully")

        pendingText?.let { queued ->
            pendingText = null
            speak(queued)
        }
    }

    /**
     * Read at this speed from now on, including whatever is being said at this moment.
     *
     * Applied immediately rather than at the next utterance so that somebody sitting with her,
     * moving the setting on the website while she listens, hears what they changed. A rate that
     * arrives before the engine is ready is remembered and applied in [onInit].
     */
    fun setSpeechRate(rate: Float) {
        val sane = Speech.sane(rate)
        if (sane == speechRate) return
        speechRate = sane
        if (isInitialized) applySpeechRate()
    }

    private fun applySpeechRate() {
        try {
            tts?.setSpeechRate(speechRate)
        } catch (e: Exception) {
            // A speed the engine will not take is not worth losing the voice over: it keeps
            // whatever it was already using and she is still read to.
            Log.w("TtsManager", "Could not set speech rate to $speechRate", e)
        }
    }

    /**
     * Speak out text aloud ("Sprechen" action)
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        val spoken = Emoji.spell(text) { emojiWords[it] }
        if (spoken.isBlank()) return
        if (!isInitialized) {
            // Unspelled: [onInit] hands it back to this function, and [reading] is compared
            // with what the button asked for.
            pendingText = text
            readingId = null
            _reading.value = text
            return
        }
        val id = "gentle_connect_tts_${System.nanoTime()}"
        readingId = id
        _reading.value = text
        raiseQuietVolume()
        try {
            val result = tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) finished(id)
        } catch (e: Exception) {
            Log.e("TtsManager", "Error speaking text", e)
            finished(id)
        }
    }

    /**
     * Turns the phone up to the floor in [ReadAloud.raisedVolume] if it is below it.
     *
     * The media volume is the one the voice comes out on, and it is the one the side keys move
     * when nothing is playing — so it is the one that ends up at nothing without anybody having
     * meant it. Not during a call, where the side keys and this volume belong to the call.
     */
    private fun raiseQuietVolume() {
        val manager = audio ?: return
        try {
            if (manager.isVolumeFixed || manager.mode != AudioManager.MODE_NORMAL) return
            val raised = ReadAloud.raisedVolume(
                current = manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            ) ?: return
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, raised, 0)
        } catch (e: Exception) {
            // Being read to quietly is still being read to.
            Log.w("TtsManager", "Could not raise the volume for reading", e)
        }
    }

    /**
     * Stop ongoing speech playback
     */
    fun stop() {
        pendingText = null
        silent()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shutdown() {
        pendingText = null
        silent()
        isInitialized = false
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
