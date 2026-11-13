package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.Speech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory implementation of SettingsRepository mirroring gentle phone's Firebase backend.
 * Provides caregiver remote configuration sync and pairing state for tests and offline/default usage.
 */
class RemoteSettingsRepository : SettingsRepository {

    private val defaultFavorites = emptyList<Contact>()

    private val _settings = MutableStateFlow(
        RemoteSettings(
            pairingCode = RemoteSettingsMapper.generatePairingCode(),
            isPaired = true,
            textSizeMultiplier = 1.0f,
            photoSizeMultiplier = 1.0f,
            isDarkMode = false,
            enableMessages = true,
            enableMail = true,
            enableNudge = true,
            enableCalendar = true,
            enableWallet = false,
            favoriteContacts = defaultFavorites
        )
    )

    override val settings: StateFlow<RemoteSettings> = _settings.asStateFlow()

    override fun updateSettings(newSettings: RemoteSettings) {
        _settings.value = newSettings
    }

    override fun pairWithCode(inputCode: String): Boolean {
        if (_settings.value.pairingCode.equals(inputCode.trim(), ignoreCase = true)) {
            _settings.update { it.copy(isPaired = true) }
            return true
        }
        return false
    }

    override fun setFavorites(contacts: List<Contact>) {
        _settings.update { it.copy(favoriteContacts = contacts) }
    }

    override fun setTextSizeMultiplier(multiplier: Float) {
        _settings.update { it.copy(textSizeMultiplier = multiplier) }
    }

    override fun setPhotoSizeMultiplier(multiplier: Float) {
        _settings.update { it.copy(photoSizeMultiplier = multiplier) }
    }

    override fun setSpeechRate(rate: Float) {
        _settings.update { it.copy(speechRate = Speech.sane(rate)) }
    }

    override fun setDarkMode(enabled: Boolean) {
        _settings.update { it.copy(isDarkMode = enabled) }
    }

    override suspend fun generateTemporaryPairingCode(): String {
        val code = RemoteSettingsMapper.generatePairingCode()
        _settings.update { it.copy(pairingCode = code) }
        return code
    }
}
