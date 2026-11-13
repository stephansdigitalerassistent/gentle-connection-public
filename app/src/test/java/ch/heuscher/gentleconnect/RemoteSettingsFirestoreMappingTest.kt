package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.RemoteSettingsMapper
import ch.heuscher.gentleconnect.model.Arrivals
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.MissedCallNotice
import ch.heuscher.gentleconnect.model.PaymentAuthMethod
import ch.heuscher.gentleconnect.model.PhotoButtonPlacement
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.SupportMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSettingsFirestoreMappingTest {

    @Test
    fun `mapToRemoteSettings handles valid map cleanly`() {
        val rawData = mapOf<String, Any?>(
            "pairingCode" to "XYZ789",
            "isPaired" to true,
            "textSizeMultiplier" to 1.4,
            "photoSizeMultiplier" to 1.2,
            "isDarkMode" to true,
            "enableMessages" to false,
            "enableMail" to true,
            "enableNudge" to false,
            "enableCalendar" to false,
            // A Long, the way Firestore hands numbers back — not an Int.
            "missedCallHours" to 2L,
            "favoriteContacts" to listOf(
                mapOf(
                    "id" to "c-1",
                    "name" to "Oma",
                    "phoneNumber" to "+41790000000",
                    "photoUri" to "https://example.com/oma.jpg",
                    "order" to 2,
                    "isFavorite" to true
                ),
                mapOf(
                    "id" to "c-2",
                    "name" to "Opa",
                    "phoneNumber" to "+41791111111",
                    "photoUri" to "",
                    "order" to 1,
                    "isFavorite" to true
                )
            )
        )

        val settings = RemoteSettingsMapper.mapToRemoteSettings(rawData)

        assertEquals("XYZ789", settings.pairingCode)
        assertTrue(settings.isPaired)
        assertEquals(1.4f, settings.textSizeMultiplier, 0.001f)
        assertEquals(1.2f, settings.photoSizeMultiplier, 0.001f)
        assertTrue(settings.isDarkMode)
        assertFalse(settings.enableMessages)
        assertTrue(settings.enableMail)
        assertFalse(settings.enableNudge)
        assertFalse(settings.enableCalendar)
        assertEquals(2, settings.missedCallHours)

        assertEquals(2, settings.favoriteContacts.size)
        assertEquals("Opa", settings.favoriteContacts[0].name) // Order 1 comes first
        assertEquals("Oma", settings.favoriteContacts[1].name) // Order 2 comes second
        assertNull(settings.favoriteContacts[0].photoUri) // Empty string converted to null
        assertEquals("https://example.com/oma.jpg", settings.favoriteContacts[1].photoUri)
    }

    @Test
    fun `mapToRemoteSettings handles null input by returning default settings`() {
        val defaults = RemoteSettings(pairingCode = "DEF123")
        val result = RemoteSettingsMapper.mapToRemoteSettings(null, defaults)
        assertEquals("DEF123", result.pairingCode)
    }

    @Test
    fun `mapToRemoteSettings handles missing and malformed fields gracefully`() {
        val malformedData = mapOf<String, Any?>(
            "pairingCode" to 12345, // Wrong type (Int instead of String)
            "textSizeMultiplier" to "not-a-number", // Malformed String
            "photoSizeMultiplier" to null,
            "isDarkMode" to "yes", // Wrong type
            "enableMessages" to null,
            "favoriteContacts" to "invalid-contacts-format"
        )

        val settings = RemoteSettingsMapper.mapToRemoteSettings(malformedData)

        assertEquals("", settings.pairingCode) // Fallback to default
        assertEquals(1.0f, settings.textSizeMultiplier, 0.001f)
        assertEquals(1.0f, settings.photoSizeMultiplier, 0.001f)
        assertFalse(settings.isDarkMode)
        assertTrue(settings.enableMessages)
        // A document written before the calendar switch existed has no field here, and absent
        // must read as "show them" — the same way the post and the messages read.
        assertTrue(settings.enableCalendar)
        // Absent is the notice shown and the phone's own confirm screen, as before either existed.
        assertTrue(settings.showPaymentReadyNotice)
        assertEquals(PaymentAuthMethod.SYSTEM, settings.paymentAuthMethod)
        assertEquals(PhotoButtonPlacement.CARD, settings.photoButtonPlacement)
        // Absent is the day the phone assumed before this was a choice anybody could make.
        assertEquals(MissedCallNotice.DEFAULT_HOURS, settings.missedCallHours)
        assertTrue(settings.favoriteContacts.isEmpty())
    }

    @Test
    fun `remoteSettingsToMap converts model back to Firestore map`() {
        val settings = RemoteSettings(
            pairingCode = "ABC123",
            isPaired = true,
            textSizeMultiplier = 1.2f,
            photoSizeMultiplier = 1.4f,
            isDarkMode = true,
            enableMessages = true,
            enableMail = false,
            enableNudge = true,
            enableCalendar = false,
            missedCallHours = MissedCallNotice.FOREVER,
            favoriteContacts = listOf(
                Contact(id = "c-1", name = "Lisa", phoneNumber = "+41791234567", order = 1)
            )
        )

        val map = RemoteSettingsMapper.remoteSettingsToMap(settings)

        assertEquals("ABC123", map["pairingCode"])
        assertEquals(true, map["isPaired"])
        // Float -> Double widening is not exact (1.2f.toDouble() == 1.2000000476837158),
        // so compare with a delta rather than by boxed equality.
        assertEquals(1.2, map["textSizeMultiplier"] as Double, 0.0001)
        assertEquals(1.4, map["photoSizeMultiplier"] as Double, 0.0001)
        assertEquals(true, map["isDarkMode"])
        assertEquals(true, map["enableMessages"])
        assertEquals(false, map["enableMail"])
        assertEquals(false, map["enableCalendar"])
        assertEquals(MissedCallNotice.FOREVER, map["missedCallHours"])

        val contactsList = map["favoriteContacts"] as? List<*>
        assertNotNull(contactsList)
        assertEquals(1, contactsList!!.size)
    }

    @Test
    fun `a length the phone does not recognise is read as a day`() {
        // The website offers seven choices. A document holding anything else — an older or newer
        // site, a hand-edited field — must not be able to make the card permanent or invisible,
        // because both of those look exactly like the app being broken.
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("missedCallHours" to 7L))

        assertEquals(MissedCallNotice.DEFAULT_HOURS, settings.missedCallHours)
    }

    @Test
    fun `never is a real choice and survives the round trip`() {
        // Zero is the one value that is both meaningful and falsy, which is the shape of thing
        // that gets quietly turned into a default somewhere along the way.
        val off = RemoteSettingsMapper.mapToRemoteSettings(mapOf("missedCallHours" to 0L))
        assertEquals(MissedCallNotice.NEVER, off.missedCallHours)

        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(missedCallHours = MissedCallNotice.NEVER)
        )
        assertEquals(MissedCallNotice.NEVER, map["missedCallHours"])
    }

    @Test
    fun `favouriteOrder is read from the document`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("favouriteOrder" to listOf("c3", "c1", "c2"))
        )
        assertEquals(listOf("c3", "c1", "c2"), settings.favouriteOrder)
    }

    @Test
    fun `a malformed favouriteOrder does not throw or smuggle blanks through`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("favouriteOrder" to listOf("c1", "", 7, null, "c2"))
        )
        assertEquals(listOf("c1", "c2"), settings.favouriteOrder)
    }

    @Test
    fun `an absent favouriteOrder keeps what is already known`() {
        val known = RemoteSettings(favouriteOrder = listOf("c1"))
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true), known)
        assertEquals(listOf("c1"), settings.favouriteOrder)
    }

    @Test
    fun `the phone never writes favouriteOrder back`() {
        // The caregiver arranges the favourites on the website. A phone that wrote its own idea
        // of the order back would undo the arrangement every time anything else in this map
        // changed — the same trap caregiverUids is kept out of the map for.
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(favouriteOrder = listOf("c1", "c2"))
        )
        assertFalse(map.containsKey("favouriteOrder"))
        assertFalse(map.containsKey("caregiverUids"))
    }

    @Test
    fun `hiddenGroups is read from the document, normalised`() {
        // The website stores the phone's own key for a group. A stray capital coming back would be
        // a switch that appears to do nothing — see GroupVisibility.hiddenKeys.
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("hiddenGroups" to listOf("TSTMBR06:0A1B", " echo1001:99ff ", "", 7, null))
        )
        assertEquals(listOf("echo1001:99ff", "tstmbr06:0a1b"), settings.hiddenGroups)
    }

    @Test
    fun `an absent hiddenGroups keeps what is already known`() {
        val known = RemoteSettings(hiddenGroups = listOf("tstmbr06:0a1b"))
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true), known)
        assertEquals(listOf("tstmbr06:0a1b"), settings.hiddenGroups)
    }

    @Test
    fun `nothing is hidden until the caregiver hides something`() {
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).hiddenGroups.isEmpty())
    }

    @Test
    fun `the phone never writes hiddenGroups back`() {
        // The same trap favouriteOrder is kept out of the map for: the switching is the
        // caregiver's, and a phone writing the list back would turn a just-hidden group on again.
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(hiddenGroups = listOf("tstmbr06:0a1b"))
        )
        assertFalse(map.containsKey("hiddenGroups"))
    }

    @Test
    fun `hiddenStatusPosters is read normalised and never written back`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("hiddenStatusPosters" to listOf(" 41791234567 ", "", 7, null))
        )
        assertEquals(listOf("41791234567"), settings.hiddenStatusPosters)
        assertFalse(RemoteSettingsMapper.remoteSettingsToMap(settings).containsKey("hiddenStatusPosters"))
    }

    @Test
    fun `quiet hours are read from the document`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("quietFromHour" to 23L, "quietUntilHour" to 5L)
        )
        assertEquals(23, settings.quietFromHour)
        assertEquals(5, settings.quietUntilHour)
    }

    @Test
    fun `a document written before quiet hours existed keeps the default night`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true))
        assertEquals(Arrivals.QUIET_FROM_HOUR, settings.quietFromHour)
        assertEquals(Arrivals.QUIET_UNTIL_HOUR, settings.quietUntilHour)
    }

    @Test
    fun `quiet hours are written back to the document`() {
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(quietFromHour = 22, quietUntilHour = 6)
        )
        assertEquals(22, map["quietFromHour"])
        assertEquals(6, map["quietUntilHour"])
    }

    @Test
    fun `every letter is shown unless the website asked for only people she knows`() {
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).mailOnlyKnownSenders)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("mailOnlyKnownSenders" to true)).mailOnlyKnownSenders)
        assertEquals(
            true,
            RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(mailOnlyKnownSenders = true))["mailOnlyKnownSenders"]
        )
    }

    @Test
    fun `search is off unless the website switched it on`() {
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).enableSearch)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("enableSearch" to false)).enableSearch)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("enableSearch" to true)).enableSearch)
    }

    @Test
    fun `corrected senders arrive in the form the phone compares`() {
        // Ticked on the website against the number as it was published, and matched on the phone
        // against the next message. A key that kept its spacing would be a switch doing nothing.
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("sendersAsPeople" to listOf("+41 44 123 45 67", "Praxis", null, "  ", 7L))
        )
        assertEquals(listOf("+41441234567", "praxis"), settings.sendersAsPeople)
    }

    @Test
    fun `a document written before this existed corrects nobody`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true))
        assertTrue(settings.sendersAsPeople.isEmpty())
    }

    @Test
    fun `the companies are shown unless the website said otherwise`() {
        // Absent must read as shown. The other way round, a settings document written before this
        // switch existed would take her bank's codes off the phone the next time it synced.
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).showCompanyMessages)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showCompanyMessages" to "ja")).showCompanyMessages)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showCompanyMessages" to true)).showCompanyMessages)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showCompanyMessages" to false)).showCompanyMessages)
    }

    @Test
    fun `birthdays are shown unless somebody switched them off`() {
        // Absent must read as shown: a settings document written before this switch existed must
        // not take the birthdays out of her appointments the next time it syncs.
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).showBirthdays)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showBirthdays" to "nein")).showBirthdays)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showBirthdays" to false)).showBirthdays)
        assertEquals(false, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(showBirthdays = false))["showBirthdays"])
    }

    @Test
    fun `steps are shown unless somebody switched them off`() {
        // Absent must read as shown: a settings document written before this switch existed must
        // not hide the step counter on her screen the next time it syncs.
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).showSteps)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showSteps" to "nein")).showSteps)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("showSteps" to false)).showSteps)
        assertEquals(false, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(showSteps = false))["showSteps"])
    }

    @Test
    fun `WhatsApp calls are turned away only when somebody chose that`() {
        // Absent must read as off: this ends calls, and a document from before the switch existed
        // must not start ending them.
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).rejectWhatsAppCalls)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("rejectWhatsAppCalls" to "ja")).rejectWhatsAppCalls)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("rejectWhatsAppCalls" to true)).rejectWhatsAppCalls)
        assertEquals(true, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(rejectWhatsAppCalls = true))["rejectWhatsAppCalls"])
    }

    @Test
    fun `calls go only over Threema while roaming unless somebody turned it off`() {
        // Absent must read as on: the default is the one that cannot run up a roaming bill.
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).threemaOnlyWhenRoaming)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("threemaOnlyWhenRoaming" to "nein")).threemaOnlyWhenRoaming)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("threemaOnlyWhenRoaming" to false)).threemaOnlyWhenRoaming)
        assertEquals(false, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(threemaOnlyWhenRoaming = false))["threemaOnlyWhenRoaming"])
    }

    @Test
    fun `unknown Threema callers are blocked unless somebody turned it off`() {
        // Absent must read as on: no legitimate stranger reaches her on Threema.
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).blockUnknownThreemaCallers)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("blockUnknownThreemaCallers" to "nein")).blockUnknownThreemaCallers)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("blockUnknownThreemaCallers" to false)).blockUnknownThreemaCallers)
        assertEquals(false, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(blockUnknownThreemaCallers = false))["blockUnknownThreemaCallers"])
    }

    @Test
    fun `a failed call is read aloud only when somebody turned it on`() {
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("isDarkMode" to true)).speakCallFailures)
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapOf("speakCallFailures" to "ja")).speakCallFailures)
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapOf("speakCallFailures" to true)).speakCallFailures)
        assertEquals(true, RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings(speakCallFailures = true))["speakCallFailures"])
    }

    @Test
    fun `the phone never writes the corrections back`() {
        // The website owns this list, like hiddenGroups and favouriteOrder. A phone that wrote its
        // own copy would undo a correction made seconds earlier on a screen it cannot see.
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(sendersAsPeople = listOf("4144"))
        )
        assertNull(map["sendersAsPeople"])
    }

    /**
     * A settings document written before the chooser existed must not be read as having asked
     * for one — the same way round as enableSearch and blockUnknownCallers.
     */
    @Test
    fun `an absent emoji chooser reads as off`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertFalse(settings.enableEmojiChoice)
    }

    @Test
    fun `absent photo sending and camera switch read as off`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertFalse(settings.enablePhotoSending)
        assertFalse(settings.enableCameraSwitch)
    }

    @Test
    fun `the emoji chooser survives the trip to Firestore and back`() {
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(enableEmojiChoice = true)
        )
        assertEquals(true, map["enableEmojiChoice"])
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(map).enableEmojiChoice)
    }

    @Test
    fun `photo sending and camera switch survive the trip to Firestore and back`() {
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(enablePhotoSending = true, enableCameraSwitch = true)
        )
        assertEquals(true, map["enablePhotoSending"])
        assertEquals(true, map["enableCameraSwitch"])
        val restored = RemoteSettingsMapper.mapToRemoteSettings(map)
        assertTrue(restored.enablePhotoSending)
        assertTrue(restored.enableCameraSwitch)
    }

    @Test
    fun `blocked call display and notification settings default to off when absent`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertFalse(settings.showBlockedCallsInCallLog)
        assertFalse(settings.notifyBlockedCalls)
    }

    @Test
    fun `blocked call display and notification settings survive the trip to Firestore and back`() {
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(
                showBlockedCallsInCallLog = true,
                notifyBlockedCalls = true
            )
        )
        assertEquals(true, map["showBlockedCallsInCallLog"])
        assertEquals(true, map["notifyBlockedCalls"])

        val mapped = RemoteSettingsMapper.mapToRemoteSettings(map)
        assertTrue(mapped.showBlockedCallsInCallLog)
        assertTrue(mapped.notifyBlockedCalls)
    }

    @Test
    fun `support group settings default correctly when absent`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertTrue(settings.supportGroup.isEmpty())
        assertEquals("", settings.supportGroupName)
        assertEquals("", settings.supportPersonName)
        assertTrue(settings.supportShareLocation)
    }

    @Test
    fun `support group settings survive the trip to Firestore and back`() {
        val member1 = SupportMember(
            id = "m-1",
            name = "Anna",
            phoneNumber = "+41791234567",
            threemaId = "ANNA1234",
            whatsAppNumber = "+41791234567",
            buttonPhotoUri = "https://example.com/anna.jpg"
        )
        val member2 = SupportMember(
            id = "m-2",
            name = "Ben",
            phoneNumber = "+41792345678",
            threemaId = null,
            whatsAppNumber = null,
            buttonPhotoUri = null
        )

        // The portal owns these, so the phone never writes them back — see remoteSettingsToMap.
        // The map is therefore what the portal stores, written out by hand.
        fun asMap(m: SupportMember) = mapOf(
            "id" to m.id,
            "name" to m.name,
            "phoneNumber" to m.phoneNumber,
            "threemaId" to m.threemaId,
            "whatsAppNumber" to m.whatsAppNumber,
            "buttonPhotoUri" to m.buttonPhotoUri
        )
        val map = mapOf(
            "supportGroupName" to "Notfallteam",
            "supportPersonName" to "Grossmutter",
            "supportShareLocation" to false,
            "supportGroup" to listOf(asMap(member1), asMap(member2))
        )
        assertFalse(RemoteSettingsMapper.remoteSettingsToMap(RemoteSettings()).containsKey("supportGroup"))

        val mapped = RemoteSettingsMapper.mapToRemoteSettings(map)
        assertEquals("Notfallteam", mapped.supportGroupName)
        assertEquals("Grossmutter", mapped.supportPersonName)
        assertFalse(mapped.supportShareLocation)
        assertEquals(2, mapped.supportGroup.size)

        val m1 = mapped.supportGroup[0]
        assertEquals("m-1", m1.id)
        assertEquals("Anna", m1.name)
        assertEquals("+41791234567", m1.phoneNumber)
        assertEquals("ANNA1234", m1.threemaId)
        assertEquals("+41791234567", m1.whatsAppNumber)
        assertEquals("https://example.com/anna.jpg", m1.buttonPhotoUri)

        val m2 = mapped.supportGroup[1]
        assertEquals("m-2", m2.id)
        assertEquals("Ben", m2.name)
        assertEquals("+41792345678", m2.phoneNumber)
        assertNull(m2.threemaId)
        assertNull(m2.whatsAppNumber)
        assertNull(m2.buttonPhotoUri)
    }

    @Test
    fun `a support member picked from favourites needs only its contact id`() {
        val mapped = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("supportGroup" to listOf(mapOf("id" to "contact-42", "contactId" to "42", "name" to "Anna")))
        )
        val m = mapped.supportGroup.single()
        assertEquals("contact-42", m.id)
        assertEquals("42", m.contactId)
        assertEquals("Anna", m.name)
        assertEquals("", m.phoneNumber)
    }

    @Test
    fun `an absent enableWallet reads as off`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertFalse(settings.enableWallet)
    }

    @Test
    fun `enableWallet survives the trip to Firestore and back`() {
        val mapTrue = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(enableWallet = true)
        )
        assertEquals(true, mapTrue["enableWallet"])
        assertTrue(RemoteSettingsMapper.mapToRemoteSettings(mapTrue).enableWallet)

        val mapFalse = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(enableWallet = false)
        )
        assertEquals(false, mapFalse["enableWallet"])
        assertFalse(RemoteSettingsMapper.mapToRemoteSettings(mapFalse).enableWallet)
    }

    @Test
    fun `an unnamed phone reads as having no name rather than null`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("isPaired" to true))
        assertEquals("", settings.deviceName)
    }

    @Test
    fun `the name a caregiver typed survives the trip to Firestore and back`() {
        val map = RemoteSettingsMapper.remoteSettingsToMap(
            RemoteSettings(deviceName = "Mamas Telefon")
        )
        assertEquals("Mamas Telefon", map["deviceName"])
        assertEquals("Mamas Telefon", RemoteSettingsMapper.mapToRemoteSettings(map).deviceName)
    }

    @Test
    fun `a name typed with stray spaces is stored without them`() {
        // The portal trims before saving, but a document written by hand, or by an older
        // portal, does not — and "  " as a name would otherwise beat the model in the list
        // while showing as nothing at all.
        val settings = RemoteSettingsMapper.mapToRemoteSettings(
            mapOf("deviceName" to "  Mamas Telefon  ")
        )
        assertEquals("Mamas Telefon", settings.deviceName)
    }

    @Test
    fun `payment unlock reads pin and nothing else it does not know`() {
        fun read(value: Any?) =
            RemoteSettingsMapper.mapToRemoteSettings(mapOf("paymentAuthMethod" to value)).paymentAuthMethod

        assertEquals(PaymentAuthMethod.PIN, read("pin"))
        assertEquals(PaymentAuthMethod.PIN, read(" PIN "))
        assertEquals(PaymentAuthMethod.SYSTEM, read(""))
        assertEquals(PaymentAuthMethod.SYSTEM, read("face"))
        assertEquals(PaymentAuthMethod.SYSTEM, read(42))
    }

    @Test
    fun `ready notice can be switched off`() {
        val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("showPaymentReadyNotice" to false))

        assertFalse(settings.showPaymentReadyNotice)
        assertEquals(false, RemoteSettingsMapper.remoteSettingsToMap(settings)["showPaymentReadyNotice"])
    }

    @Test
    fun `photo button placement defaults to card, maps every value and rejects garbage`() {
        fun read(value: Any?) =
            RemoteSettingsMapper.mapToRemoteSettings(mapOf("photoButtonPlacement" to value)).photoButtonPlacement

        assertEquals(PhotoButtonPlacement.CARD, read(null))
        assertEquals(PhotoButtonPlacement.CARD, read(""))
        assertEquals(PhotoButtonPlacement.CARD, read("garbage"))
        assertEquals(PhotoButtonPlacement.CARD, read(123))

        assertEquals(PhotoButtonPlacement.NONE, read("none"))
        assertEquals(PhotoButtonPlacement.NONE, read(" NONE "))
        assertEquals(PhotoButtonPlacement.CLOCK, read("clock"))
        assertEquals(PhotoButtonPlacement.CLOCK, read(" Clock "))
        assertEquals(PhotoButtonPlacement.CARD, read("card"))
        assertEquals(PhotoButtonPlacement.FACES, read("faces"))
        assertEquals(PhotoButtonPlacement.BOTTOM, read("bottom"))

        listOf(
            PhotoButtonPlacement.NONE,
            PhotoButtonPlacement.CLOCK,
            PhotoButtonPlacement.CARD,
            PhotoButtonPlacement.FACES,
            PhotoButtonPlacement.BOTTOM
        ).forEach { placement ->
            val settings = RemoteSettingsMapper.mapToRemoteSettings(mapOf("photoButtonPlacement" to placement))
            assertEquals(placement, settings.photoButtonPlacement)
            val map = RemoteSettingsMapper.remoteSettingsToMap(settings)
            assertEquals(placement, map["photoButtonPlacement"])
        }
    }
}
