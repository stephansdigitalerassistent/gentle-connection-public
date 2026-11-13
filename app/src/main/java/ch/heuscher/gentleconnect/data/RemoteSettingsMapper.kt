package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.BluetoothAttribution
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.LocatedWith
import ch.heuscher.gentleconnect.model.StatusPosterVisibility
import ch.heuscher.gentleconnect.model.MissedCallNotice
import ch.heuscher.gentleconnect.model.PaymentAuthMethod
import ch.heuscher.gentleconnect.model.PhotoButtonPlacement
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.SenderJudgement
import ch.heuscher.gentleconnect.model.Speech
import ch.heuscher.gentleconnect.model.SupportMember
import java.security.SecureRandom
import java.util.UUID

/**
 * Utility functions for defensive conversion between Firestore document maps and RemoteSettings domain model,
 * as well as pairing code utilities.
 */
object RemoteSettingsMapper {

    private const val ALLOWED_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    const val PAIRING_CODE_VALIDITY_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Where pairing codes come from. A cryptographic source, because the code is the whole of the
     * barrier to her data (see firestore.rules) and kotlin.random is built to be fast, not to be
     * unpredictable.
     */
    private val secureRandom = SecureRandom()

    /**
     * Generates a 6-character uppercase alphanumeric pairing code (A–Z, 0–9).
     */
    fun generatePairingCode(random: java.util.Random = secureRandom): String {
        return (1..6).map { ALLOWED_CODE_CHARS[random.nextInt(ALLOWED_CODE_CHARS.length)] }.joinToString("")
    }

    /**
     * Checks if a pairing code with the given expiry timestamp is expired relative to current time.
     */
    fun isPairingCodeExpired(expiresAtMillis: Long, currentMillis: Long = System.currentTimeMillis()): Boolean {
        return currentMillis >= expiresAtMillis
    }

    /**
     * Defensively converts a Firestore raw data map into a RemoteSettings instance.
     * Guarantees fallback defaults for missing, null, or malformed fields without throwing exceptions.
     */
    fun mapToRemoteSettings(data: Map<String, Any?>?, defaultSettings: RemoteSettings = RemoteSettings()): RemoteSettings {
        if (data == null) return defaultSettings

        val pairingCode = (data["pairingCode"] as? String) ?: defaultSettings.pairingCode
        val isPaired = (data["isPaired"] as? Boolean) ?: defaultSettings.isPaired

        val textSizeMultiplier = parseAsFloat(data["textSizeMultiplier"], defaultSettings.textSizeMultiplier)
        val photoSizeMultiplier = parseAsFloat(data["photoSizeMultiplier"], defaultSettings.photoSizeMultiplier)

        // Through Speech.sane rather than straight out of the map: this one number reaches an
        // engine that treats nonsense as silence, and a website field that has been cleared or
        // typed into badly must not be able to stop her being read to at all.
        val speechRate = Speech.sane(parseAsFloat(data["speechRate"], defaultSettings.speechRate))

        val isDarkMode = (data["isDarkMode"] as? Boolean) ?: defaultSettings.isDarkMode
        val enableMessages = (data["enableMessages"] as? Boolean) ?: defaultSettings.enableMessages
        val enableMail = (data["enableMail"] as? Boolean) ?: defaultSettings.enableMail
        // Absent means every letter, like showCompanyMessages: this switch takes letters off her
        // screen, and a document written before it existed must not read as having asked for that.
        val mailOnlyKnownSenders =
            (data["mailOnlyKnownSenders"] as? Boolean) ?: defaultSettings.mailOnlyKnownSenders
        val enableNudge = (data["enableNudge"] as? Boolean) ?: defaultSettings.enableNudge
        val enableCalendar = (data["enableCalendar"] as? Boolean) ?: defaultSettings.enableCalendar
        val enableWallet = (data["enableWallet"] as? Boolean) ?: defaultSettings.enableWallet
        val showPaymentReadyNotice =
            (data["showPaymentReadyNotice"] as? Boolean) ?: defaultSettings.showPaymentReadyNotice
        val paymentAuthMethod = PaymentAuthMethod.sane(
            (data["paymentAuthMethod"] as? String) ?: defaultSettings.paymentAuthMethod
        )
        val deviceName = (data["deviceName"] as? String)?.trim() ?: defaultSettings.deviceName
        // Through MissedCallNotice.sane rather than straight in, because this one is a number
        // from a website and the two failures it has are both silent: a card that never appears,
        // and a card that never leaves. Number, not Int — Firestore hands back a Long.
        val missedCallHours = MissedCallNotice.sane(
            (data["missedCallHours"] as? Number)?.toInt() ?: defaultSettings.missedCallHours
        )
        // Absent means off. A website that has never heard of this switch must not be read as
        // having asked for calls to be cut off.
        val blockUnknownCallers =
            (data["blockUnknownCallers"] as? Boolean) ?: defaultSettings.blockUnknownCallers
        val showBlockedCallsInCallLog =
            (data["showBlockedCallsInCallLog"] as? Boolean) ?: defaultSettings.showBlockedCallsInCallLog
        val notifyBlockedCalls =
            (data["notifyBlockedCalls"] as? Boolean) ?: defaultSettings.notifyBlockedCalls

        // Read as written, including nonsense. Arrivals.quietAt is where an hour that is not on a
        // clock falls back to the default, so that there is one place to look for what a bad value
        // does rather than two that can disagree.
        val quietFromHour = parseAsInt(data["quietFromHour"], defaultSettings.quietFromHour)
        val quietUntilHour = parseAsInt(data["quietUntilHour"], defaultSettings.quietUntilHour)

        // Absent means 0 (disabled).
        val ringSilenceTimeoutSeconds = parseAsInt(data["ringSilenceTimeoutSeconds"], defaultSettings.ringSilenceTimeoutSeconds)

        // Absent means off, like blockUnknownCallers and unlike the enable* switches above: a
        // document written before this existed must not be read as having asked for a search box.
        val enableSearch = (data["enableSearch"] as? Boolean) ?: defaultSettings.enableSearch

        // Absent means off, for the same reason as enableSearch: a document written before this
        // switch existed must not be read as having asked for a chooser she never had.
        val enableEmojiChoice =
            (data["enableEmojiChoice"] as? Boolean) ?: defaultSettings.enableEmojiChoice

        val enablePhotoSending =
            (data["enablePhotoSending"] as? Boolean) ?: defaultSettings.enablePhotoSending
        val enableCameraSwitch =
            (data["enableCameraSwitch"] as? Boolean) ?: defaultSettings.enableCameraSwitch
        val photoButtonPlacement = PhotoButtonPlacement.sane(
            (data["photoButtonPlacement"] as? String) ?: defaultSettings.photoButtonPlacement
        )

        val contactsRaw = data["favoriteContacts"] as? List<*>
        val favoriteContacts = if (contactsRaw != null) {
            contactsRaw.mapNotNull { item ->
                (item as? Map<*, *>)?.let { map ->
                    val name = (map["name"] as? String) ?: ""
                    val phoneNumber = (map["phoneNumber"] as? String) ?: ""
                    // Stable when the portal wrote none: a claim is remembered by member id, and a
                    // random one would change with every settings update.
                    val id = (map["id"] as? String)?.ifEmpty { null }
                        ?: "phone-" + phoneNumber.filter { it.isDigit() }.ifEmpty { name }
                    val photoUriRaw = map["photoUri"] as? String
                    val photoUri = if (photoUriRaw.isNull_or_Empty()) null else photoUriRaw
                    val order = parseAsInt(map["order"], 0)
                    val isFavorite = (map["isFavorite"] as? Boolean) ?: true
                    Contact(
                        id = id,
                        name = name,
                        phoneNumber = phoneNumber,
                        photoUri = photoUri,
                        order = order,
                        isFavorite = isFavorite
                    )
                }
            }.sortedBy { it.order }
        } else {
            defaultSettings.favoriteContacts
        }

        val caregiverUids = (data["caregiverUids"] as? List<*>)
            ?.mapNotNull { (it as? String)?.takeIf { uid -> uid.isNotBlank() } }
            ?: defaultSettings.caregiverUids

        val favouriteOrder = (data["favouriteOrder"] as? List<*>)
            ?.mapNotNull { (it as? String)?.takeIf { id -> id.isNotBlank() } }
            ?: defaultSettings.favouriteOrder

        // Normalised on the way in rather than at every comparison — see GroupVisibility.hiddenKeys
        // for why a stray capital in this list would be a group that silently stays on the screen
        // after somebody switched it off.
        val hiddenGroups = (data["hiddenGroups"] as? List<*>)
            ?.let { raw -> GroupVisibility.hiddenKeys(raw.map { it as? String }).toList().sorted() }
            ?: defaultSettings.hiddenGroups

        // Normalised on the way in, like hiddenGroups and for the same reason.
        val hiddenStatusPosters = (data["hiddenStatusPosters"] as? List<*>)
            ?.let { raw -> StatusPosterVisibility.hiddenKeys(raw.map { it as? String }).toList().sorted() }
            ?: defaultSettings.hiddenStatusPosters

        // Normalised on the way in, like hiddenGroups and for the same reason — see
        // SenderJudgement.keyOf. A number ticked on the website carries the spacing it was
        // published with, and a key that fails to match is a switch that appears to do nothing.
        val sendersAsPeople = (data["sendersAsPeople"] as? List<*>)
            ?.let { raw -> SenderJudgement.treatedAsPeople(raw.map { it as? String }).toList().sorted() }
            ?: defaultSettings.sendersAsPeople

        // Absent means shown, unlike blockUnknownCallers and enableSearch above and for the
        // opposite reason: this switch takes messages off her phone. A document written before it
        // existed, or a field some future version writes differently, must never be read as
        // somebody having asked for her bank's codes to disappear.
        val showCompanyMessages =
            (data["showCompanyMessages"] as? Boolean) ?: defaultSettings.showCompanyMessages

        // Absent means shown, for the same reason: this switch takes appointments off her screen,
        // and a document written before it existed must never read as somebody having asked for
        // that.
        val showBirthdays = (data["showBirthdays"] as? Boolean) ?: defaultSettings.showBirthdays

        // Absent means shown: this switch hides the step counter at the foot of her home screen,
        // and a document from before it existed must not hide it either.
        val showSteps = (data["showSteps"] as? Boolean) ?: defaultSettings.showSteps

        // Absent means off, like blockUnknownCallers: this switch ends calls, and only somebody
        // who has chosen that may have it.
        val rejectWhatsAppCalls =
            (data["rejectWhatsAppCalls"] as? Boolean) ?: defaultSettings.rejectWhatsAppCalls

        // Absent means on: the default a phone arrives with is the one that cannot run up a
        // roaming bill. Anything but an explicit false keeps it on.
        val threemaOnlyWhenRoaming =
            (data["threemaOnlyWhenRoaming"] as? Boolean) ?: defaultSettings.threemaOnlyWhenRoaming

        // Absent means on: no legitimate stranger reaches her on Threema.
        val blockUnknownThreemaCallers =
            (data["blockUnknownThreemaCallers"] as? Boolean) ?: defaultSettings.blockUnknownThreemaCallers

        // Absent means silent: a voice she did not expect is the thing this switch is careful about.
        val speakCallFailures =
            (data["speakCallFailures"] as? Boolean) ?: defaultSettings.speakCallFailures

        val supportGroupName = (data["supportGroupName"] as? String) ?: defaultSettings.supportGroupName
        val supportPersonName = (data["supportPersonName"] as? String) ?: defaultSettings.supportPersonName
        val supportShareLocation = (data["supportShareLocation"] as? Boolean) ?: defaultSettings.supportShareLocation

        val supportGroupRaw = data["supportGroup"] as? List<*>
        val supportGroup = if (supportGroupRaw != null) {
            supportGroupRaw.mapNotNull { item ->
                (item as? Map<*, *>)?.let { map ->
                    val name = (map["name"] as? String) ?: ""
                    val phoneNumber = (map["phoneNumber"] as? String) ?: ""
                    // Stable when the portal wrote none: a claim is remembered by member id, and a
                    // random one would change with every settings update.
                    val id = (map["id"] as? String)?.ifEmpty { null }
                        ?: "phone-" + phoneNumber.filter { it.isDigit() }.ifEmpty { name }
                    val threemaId = (map["threemaId"] as? String)?.takeIf { it.isNotBlank() }
                    val whatsAppNumber = (map["whatsAppNumber"] as? String)?.takeIf { it.isNotBlank() }
                    val buttonPhotoUri = (map["buttonPhotoUri"] as? String)?.takeIf { it.isNotBlank() }
                    // A favourite picked on the portal carries only its contact id and a name; the
                    // phone fills in the rest from her address book. See SupportGroupResolver.
                    val contactId = (map["contactId"] as? String)?.takeIf { it.isNotBlank() }
                    if (contactId == null && name.isBlank() && phoneNumber.isBlank()) null
                    else SupportMember(
                        id = if (contactId != null) (map["id"] as? String)?.ifEmpty { null } ?: "contact-$contactId" else id,
                        name = name,
                        phoneNumber = phoneNumber,
                        threemaId = threemaId,
                        whatsAppNumber = whatsAppNumber,
                        buttonPhotoUri = buttonPhotoUri,
                        contactId = contactId
                    )
                }
            }
        } else {
            defaultSettings.supportGroup
        }

        val bluetoothAttributionsRaw = data["bluetoothAttributions"] as? List<*>
        val bluetoothAttributions = if (bluetoothAttributionsRaw != null) {
            val parsed = bluetoothAttributionsRaw.mapNotNull { item ->
                (item as? Map<*, *>)?.let { map ->
                    val key = (map["key"] as? String) ?: ""
                    val kind = LocatedWith.parseKind(map["kind"] as? String)
                    val label = (map["label"] as? String) ?: ""
                    if (kind != null) {
                        BluetoothAttribution(
                            key = key,
                            kind = kind,
                            label = label
                        )
                    } else null
                }
            }
            LocatedWith.cleanAttributions(parsed)
        } else {
            defaultSettings.bluetoothAttributions
        }

        return RemoteSettings(
            pairingCode = pairingCode,
            isPaired = isPaired,
            textSizeMultiplier = textSizeMultiplier,
            photoSizeMultiplier = photoSizeMultiplier,
            speechRate = speechRate,
            isDarkMode = isDarkMode,
            enableMessages = enableMessages,
            enableMail = enableMail,
            mailOnlyKnownSenders = mailOnlyKnownSenders,
            enableNudge = enableNudge,
            enableCalendar = enableCalendar,
            enableWallet = enableWallet,
            showPaymentReadyNotice = showPaymentReadyNotice,
            paymentAuthMethod = paymentAuthMethod,
            deviceName = deviceName,
            missedCallHours = missedCallHours,
            blockUnknownCallers = blockUnknownCallers,
            quietFromHour = quietFromHour,
            quietUntilHour = quietUntilHour,
            ringSilenceTimeoutSeconds = ringSilenceTimeoutSeconds,
            enableSearch = enableSearch,
            enableEmojiChoice = enableEmojiChoice,
            enablePhotoSending = enablePhotoSending,
            enableCameraSwitch = enableCameraSwitch,
            photoButtonPlacement = photoButtonPlacement,
            favoriteContacts = favoriteContacts,
            caregiverUids = caregiverUids,
            favouriteOrder = favouriteOrder,
            hiddenGroups = hiddenGroups,
            hiddenStatusPosters = hiddenStatusPosters,
            sendersAsPeople = sendersAsPeople,
            showCompanyMessages = showCompanyMessages,
            showBirthdays = showBirthdays,
            showSteps = showSteps,
            rejectWhatsAppCalls = rejectWhatsAppCalls,
            threemaOnlyWhenRoaming = threemaOnlyWhenRoaming,
            blockUnknownThreemaCallers = blockUnknownThreemaCallers,
            speakCallFailures = speakCallFailures,
            showBlockedCallsInCallLog = showBlockedCallsInCallLog,
            notifyBlockedCalls = notifyBlockedCalls,
            supportGroup = supportGroup,
            supportGroupName = supportGroupName,
            supportPersonName = supportPersonName,
            supportShareLocation = supportShareLocation,
            bluetoothAttributions = bluetoothAttributions
        )
    }

    /**
     * Converts a RemoteSettings domain model into a Firestore map.
     */
    fun remoteSettingsToMap(settings: RemoteSettings): Map<String, Any?> {
        // No supportGroup, supportGroupName, supportPersonName, supportShareLocation here, on purpose.
        // The caregiver portal owns them; a device writing its own copy back would race or overwrite edits
        // made by a caregiver.
        //
        // No caregiverUids here, on purpose. The website owns that array and adds to it with
        // arrayUnion; a device writing its own copy back would delete every caregiver it had not
        // yet heard about — including, in the worst case, all of them.
        //
        // No favouriteOrder either, for the same reason: the caregiver arranges her favourites on
        // the website, and a phone that wrote its own idea of the order back would undo the
        // arrangement every time anything else in this map changed.
        //
        // No hiddenGroups, again for that reason. The phone publishes which groups exist — see
        // SettingsRepository.publishGroups — and reads back which of them are switched off; the
        // switching itself is the caregiver's, and a phone writing the list back would turn a
        // group they had just hidden on again. No hiddenStatusPosters, for the same reason — see
        // SettingsRepository.publishStatusPosters.
        //
        // No sendersAsPeople, for exactly that reason once more. The phone publishes the senders
        // it has filed as companies — SettingsRepository.publishSenders — and reads back the ones
        // corrected; writing its own copy would undo a correction made seconds earlier, and the
        // only sign of it would be her phone going quiet again for the surgery.
        //
        // No bluetoothAttributions here, on purpose. The caregiver portal owns bluetoothAttributions
        // while the phone publishes bluetoothDevices; a device writing its own copy back would race
        // or overwrite edits made by a caregiver.
        return mapOf(
            "pairingCode" to settings.pairingCode,
            "isPaired" to settings.isPaired,
            "textSizeMultiplier" to settings.textSizeMultiplier.toDouble(),
            "photoSizeMultiplier" to settings.photoSizeMultiplier.toDouble(),
            "speechRate" to settings.speechRate.toDouble(),
            "isDarkMode" to settings.isDarkMode,
            "enableMessages" to settings.enableMessages,
            "enableMail" to settings.enableMail,
            "mailOnlyKnownSenders" to settings.mailOnlyKnownSenders,
            "enableNudge" to settings.enableNudge,
            "enableCalendar" to settings.enableCalendar,
            "enableWallet" to settings.enableWallet,
            "showPaymentReadyNotice" to settings.showPaymentReadyNotice,
            "paymentAuthMethod" to settings.paymentAuthMethod,
            "deviceName" to settings.deviceName,
            "missedCallHours" to settings.missedCallHours,
            "blockUnknownCallers" to settings.blockUnknownCallers,
            "showBlockedCallsInCallLog" to settings.showBlockedCallsInCallLog,
            "notifyBlockedCalls" to settings.notifyBlockedCalls,
            "quietFromHour" to settings.quietFromHour,
            "quietUntilHour" to settings.quietUntilHour,
            "ringSilenceTimeoutSeconds" to settings.ringSilenceTimeoutSeconds,
            "enableSearch" to settings.enableSearch,
            "enableEmojiChoice" to settings.enableEmojiChoice,
            "enablePhotoSending" to settings.enablePhotoSending,
            "enableCameraSwitch" to settings.enableCameraSwitch,
            "photoButtonPlacement" to settings.photoButtonPlacement,
            "showCompanyMessages" to settings.showCompanyMessages,
            "showBirthdays" to settings.showBirthdays,
            "showSteps" to settings.showSteps,
            "rejectWhatsAppCalls" to settings.rejectWhatsAppCalls,
            "threemaOnlyWhenRoaming" to settings.threemaOnlyWhenRoaming,
            "blockUnknownThreemaCallers" to settings.blockUnknownThreemaCallers,
            "speakCallFailures" to settings.speakCallFailures,
            "favoriteContacts" to settings.favoriteContacts.map { c ->
                mapOf(
                    "id" to c.id,
                    "name" to c.name,
                    "phoneNumber" to c.phoneNumber,
                    "photoUri" to (c.photoUri ?: ""),
                    "order" to c.order,
                    "isFavorite" to c.isFavorite
                )
            }
        )
    }

    private fun parseAsFloat(value: Any?, default: Float): Float {
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: default
            else -> default
        }
    }

    private fun parseAsInt(value: Any?, default: Int): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun String?.isNull_or_Empty(): Boolean = this == null || this.isEmpty()
}
