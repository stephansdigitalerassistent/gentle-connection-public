package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsRepository
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.PhotoButtonPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteSettingsTest {

    private lateinit var repository: RemoteSettingsRepository

    @Before
    fun setUp() {
        repository = RemoteSettingsRepository()
    }

    @Test
    fun `pairWithCode succeeds with exact pairing code`() {
        val code = repository.settings.value.pairingCode
        assertTrue(repository.pairWithCode(code))
        assertTrue(repository.settings.value.isPaired)
    }

    @Test
    fun `pairWithCode fails with incorrect code`() {
        assertFalse(repository.pairWithCode("WRONG123"))
    }

    @Test
    fun `setTextSizeMultiplier updates settings state`() {
        repository.setTextSizeMultiplier(1.3f)
        assertEquals(1.3f, repository.settings.value.textSizeMultiplier, 0.001f)
    }

    @Test
    fun `setPhotoSizeMultiplier updates settings state`() {
        repository.setPhotoSizeMultiplier(1.4f)
        assertEquals(1.4f, repository.settings.value.photoSizeMultiplier, 0.001f)
    }

    @Test
    fun `setFavorites updates contacts list`() {
        val newFavorites = listOf(
            Contact(id = "c-1", name = "Anna", phoneNumber = "+41790000000")
        )
        repository.setFavorites(newFavorites)

        assertEquals(1, repository.settings.value.favoriteContacts.size)
        assertEquals("Anna", repository.settings.value.favoriteContacts[0].name)
    }

    @Test
    fun `photo sending and camera switch default to false, placement defaults to card`() {
        val defaultSettings = repository.settings.value
        assertFalse(defaultSettings.enablePhotoSending)
        assertFalse(defaultSettings.enableCameraSwitch)
        assertEquals(PhotoButtonPlacement.CARD, defaultSettings.photoButtonPlacement)
    }
}
