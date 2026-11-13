package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsMapper
import ch.heuscher.gentleconnect.model.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the engine is allowed to be told, given what a website can put in a document.
 *
 * The failure this guards against is not a wrong speed, it is silence: `setSpeechRate(0f)` is
 * accepted by the engine and produces no sound at all, which on her phone is indistinguishable
 * from the read-aloud button not working.
 */
class SpeechRateTest {

    @Test
    fun `a sensible rate is kept as it is`() {
        assertEquals(0.65f, Speech.sane(0.65f), 0.0001f)
        assertEquals(1.0f, Speech.sane(1.0f), 0.0001f)
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(Speech.DEFAULT_RATE), 0.0001f)
    }

    @Test
    fun `the default is slower than the engine's own speed`() {
        assertTrue(Speech.DEFAULT_RATE < 1.0f)
    }

    @Test
    fun `too slow and too fast are pulled back to the ends`() {
        assertEquals(Speech.SLOWEST_RATE, Speech.sane(0.1f), 0.0001f)
        assertEquals(Speech.FASTEST_RATE, Speech.sane(4.0f), 0.0001f)
    }

    @Test
    fun `silence is never a speed`() {
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(0f), 0.0001f)
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(-1.5f), 0.0001f)
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(Float.NaN), 0.0001f)
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(Speech.DEFAULT_RATE, Speech.sane(null), 0.0001f)
    }

    @Test
    fun `a document with no speech rate reads as the default`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true))
        assertEquals(Speech.DEFAULT_RATE, settings.speechRate, 0.0001f)
    }

    @Test
    fun `a document with a broken speech rate reads as the default`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("speechRate" to 0))
        assertEquals(Speech.DEFAULT_RATE, settings.speechRate, 0.0001f)

        val text = RemoteSettingsMapper.mapToRemoteSettings(mapOf("speechRate" to "langsam"))
        assertEquals(Speech.DEFAULT_RATE, text.speechRate, 0.0001f)
    }

    @Test
    fun `a rate set on the website survives the round trip`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("speechRate" to 0.65))
        assertEquals(0.65f, settings.speechRate, 0.0001f)

        val written = RemoteSettingsMapper.remoteSettingsToMap(settings)
        assertEquals(0.65, written["speechRate"] as Double, 0.0001)
    }
}
