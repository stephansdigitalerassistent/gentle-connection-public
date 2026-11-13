package ch.heuscher.gentleconnect.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need from the phone's voice: reading something aloud, and playing a voice
 * message. The voice itself is the shared [Speech]; this is only the screens' handle on it.
 */
class VoiceViewModel(private val speech: Speech) : ViewModel() {

    /** What the voice is reading at this moment, so the button that started it can show it. */
    val readingAloud: StateFlow<String?> get() = speech.readingAloud

    /** Which voice message is sounding, so its control can show that it is the one playing. */
    val playingVoiceMessage: StateFlow<String?> get() = speech.playingVoiceMessage

    /** A press on a "read aloud" button. See [Speech.speakText]. */
    fun speakText(text: String) = speech.speakText(text)

    fun stopSpeaking() = speech.stopSpeaking()

    fun playVoiceMessage(messageId: String, path: String) = speech.playVoiceMessage(messageId, path)

    fun stopVoiceMessage() = speech.stopVoiceMessage()

    override fun onCleared() {
        super.onCleared()
        // A MediaPlayer holds a real audio track. Left behind, it goes on talking to an empty room.
        speech.stopVoiceMessage()
    }
}
