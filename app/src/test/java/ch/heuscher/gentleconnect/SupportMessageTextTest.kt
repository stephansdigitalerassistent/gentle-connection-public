package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportLocation
import ch.heuscher.gentleconnect.model.SupportMessageText
import ch.heuscher.gentleconnect.model.SupportPhoneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportMessageTextTest {

    /** The sentences as they are actually shipped — see [SupportTextsFromXml]. */
    private val german = SupportTextsFromXml.german()

    private val sampleLocation = SupportLocation(
        lat = 47.37690,
        lon = 8.54170,
        accuracyMetres = 30,
        at = 1_700_000_000_000L
    )

    private val samplePhoneStatus = SupportPhoneStatus(
        battery = 23,
        charging = false,
        network = "WLAN",
        roaming = false,
        threema = "verbunden",
        whatsapp = "getrennt",
        faults = listOf("2x Senden fehlgeschlagen"),
        appVersion = 42
    )

    @Test
    fun `buildMessengerAlert contains all sections when available`() {
        val text = SupportMessageText.buildMessengerAlert(
            texts = german,
            groupName = "Familie",
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = true,
            locationAgeText = "vor 1 Min.",
            phoneStatus = samplePhoneStatus,
            recipientNames = listOf("Anna", "Beat", "Carla")
        )

        assertTrue(text.startsWith("?? Etwas ist komisch. Schauen wir es zusammen an?"))
        assertTrue(text.contains("Ich habe um 14:32 auf den Knopf «Etwas ist komisch» gedrückt."))
        assertTrue(text.contains("📍 https://maps.google.com/?q=47.37690,8.54170"))
        assertTrue(text.contains("±30 m, vor 1 Min."))
        assertTrue(text.contains("📱 Mein Telefon: Akku 23 % · WLAN"))
        assertTrue(text.contains("Das schickt mein Telefon automatisch an Anna, Beat und Carla."))
        assertTrue(text.endsWith("Wer sich kümmert, antwortet mit „ok“ oder 👍 oder ruft mich an – dann wissen es die anderen."))
    }

    @Test
    fun `the alert says nothing about her a family chat has no use for`() {
        val text = SupportMessageText.buildMessengerAlert(
            texts = german,
            groupName = "Familie",
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = true,
            locationAgeText = "vor 1 Min.",
            phoneStatus = samplePhoneStatus,
            recipientNames = listOf("Anna")
        )

        // She reads this in her own thread with Anna. Which screen she was on, whether her
        // messengers were connected and how many faults today held are the caregiver's business
        // and are on the portal — here they would be somebody inspecting her.
        assertFalse(text.contains("Bildschirm"))
        assertFalse(text.contains("Threema"))
        assertFalse(text.contains("WhatsApp"))
        assertFalse(text.contains("Störung"))
        // And never about her in the third person, out of her own mouth.
        assertFalse(text.contains("Mama"))
    }

    @Test
    fun `buildMessengerAlert without names still says who it went to`() {
        val text = SupportMessageText.buildMessengerAlert(
            texts = german,
            groupName = "Familie",
            timeString = "14:32",
            location = null,
            shareLocation = false,
            locationAgeText = null,
            phoneStatus = null
        )
        assertTrue(text.contains("Das schickt mein Telefon automatisch an alle in „Familie“."))
        assertFalse("never claims everybody received it", text.contains("bekommen"))
    }

    @Test
    fun `buildMessengerAlert leaves location out when shareLocation is false`() {
        val text = SupportMessageText.buildMessengerAlert(
            texts = german,
            groupName = "Familie",
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = false,
            locationAgeText = null,
            phoneStatus = samplePhoneStatus
        )

        assertFalse(text.contains("📍"))
        assertFalse(text.contains("maps.google.com"))
    }

    @Test
    fun `buildMessengerAlert leaves phone status out when null without placeholder`() {
        val text = SupportMessageText.buildMessengerAlert(
            texts = german,
            groupName = "Familie",
            timeString = "14:32",
            location = null,
            shareLocation = true,
            locationAgeText = null,
            phoneStatus = null
        )

        assertFalse(text.contains("📱"))
        assertFalse(text.contains("unbekannt"))
    }

    @Test
    fun `buildSmsAlert has no emoji and stays within 306 GSM-7 characters with longest realistic values`() {
        val longPhoneStatus = SupportPhoneStatus(
            battery = 100,
            charging = true,
            network = "kein Internet",
            roaming = true,
            threema = "getrennt",
            whatsapp = "getrennt",
            faults = listOf("Verbindung fehlgeschlagen"),
            appVersion = 99
        )

        val text = SupportMessageText.buildSmsAlert(
            texts = german,
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = true,
            locationAgeText = "vor 1 Min",
            phoneStatus = longPhoneStatus
        )

        // No emoji in SMS alert
        assertFalse(text.contains("🆘"))
        assertFalse(text.contains("📍"))
        assertFalse(text.contains("📱"))
        assertFalse(text.contains("⚠️"))

        // Must stay <= 306 chars (2 GSM-7 segments)
        assertTrue(
            "SMS length was ${text.length}, expected <= ${SupportMessageText.MAX_GSM7_TWO_SEGMENT_LENGTH}",
            text.length <= SupportMessageText.MAX_GSM7_TWO_SEGMENT_LENGTH
        )

        assertTrue(text.startsWith("?? Etwas ist komisch. Schauen wir es zusammen an? Ich habe um 14:32 auf den Knopf gedrückt."))
        assertTrue(text.contains("Ort: maps.google.com/?q=47.3769,8.5417 (30m, vor 1 Min)."))
        assertFalse("± is not GSM-7 and would turn the SMS into UCS-2", text.contains("±"))
        assertTrue(text.contains("Akku 100%, lädt, kein Internet, Roaming."))
        assertTrue(text.endsWith("Mein Telefon schickt das automatisch. Antworte ok oder ruf mich an."))
    }

    @Test
    fun `buildClaimByReplyNotice respects messenger vs SMS formatting`() {
        val messengerText = SupportMessageText.buildClaimByReplyNotice(
            texts = german,
            claimerName = "Anna",
            requestTimeString = "14:32",
            isMessenger = true
        )
        assertTrue(messengerText.startsWith("✅ Anna schaut mit mir zusammen an, was um 14:32 komisch war."))

        val smsText = SupportMessageText.buildClaimByReplyNotice(
            texts = german,
            claimerName = "Anna",
            requestTimeString = "14:32",
            isMessenger = false
        )
        assertFalse(smsText.contains("✅"))
        assertEquals("Anna schaut mit mir zusammen an, was um 14:32 komisch war.", smsText)
    }

    @Test
    fun `buildClaimByCallNotice respects messenger vs SMS formatting`() {
        val messengerText = SupportMessageText.buildClaimByCallNotice(
            texts = german,
            claimerName = "Anna",
            callTimeString = "14:36",
            requestTimeString = "14:32",
            isMessenger = true
        )
        assertTrue(messengerText.startsWith("📞 Anna hat um 14:36 mit mir telefoniert und schaut mit mir zusammen an, was um 14:32 komisch war."))

        val smsText = SupportMessageText.buildClaimByCallNotice(
            texts = german,
            claimerName = "Anna",
            callTimeString = "14:36",
            requestTimeString = "14:32",
            isMessenger = false
        )
        assertFalse(smsText.contains("📞"))
        assertEquals("Anna hat um 14:36 mit mir telefoniert und schaut mit mir zusammen an, was um 14:32 komisch war.", smsText)
    }

    @Test
    fun `buildSecondClaimReply formats expected notice`() {
        val text = SupportMessageText.buildSecondClaimReply(
            texts = german,
            firstClaimerName = "Anna",
            claimTimeString = "14:36"
        )
        assertEquals("Anna kümmert sich schon (seit 14:36).", text)
    }

    @Test
    fun `the English strings are a message and not a branch nobody reaches`() {
        val english = SupportTextsFromXml.english()
        val alert = SupportMessageText.buildMessengerAlert(
            texts = english,
            groupName = "Family",
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = true,
            locationAgeText = "1 min ago",
            phoneStatus = samplePhoneStatus,
            recipientNames = listOf("Anna", "Beat")
        )
        assertTrue(alert.startsWith("?? Something is strange. Shall we look at it together?"))
        assertTrue(alert.contains("I pressed the \"something is strange\" button at 14:32."))
        assertTrue(alert.contains("📱 My phone: Battery 23 % · WLAN"))
        assertTrue(alert.contains("My phone sends this automatically to Anna and Beat."))

        // The length rule is a property of the message, not of German.
        val sms = SupportMessageText.buildSmsAlert(
            texts = english,
            timeString = "14:32",
            location = sampleLocation,
            shareLocation = true,
            locationAgeText = "1 min",
            phoneStatus = samplePhoneStatus
        )
        assertTrue(
            "English SMS was ${sms.length} characters",
            sms.length <= SupportMessageText.MAX_GSM7_TWO_SEGMENT_LENGTH
        )
        assertFalse(sms.contains("±"))
    }

    @Test
    fun `buildFollowUpLocation formats accurate fix`() {
        val accurateLoc = SupportLocation(
            lat = 47.37691,
            lon = 8.54172,
            accuracyMetres = 12,
            at = 1_700_000_100_000L
        )
        val text = SupportMessageText.buildFollowUpLocation(german, accurateLoc)
        assertTrue(text.contains("📍 Ich bin genauer hier: https://maps.google.com/?q=47.37691,8.54172 (±12 m)"))
    }
}
