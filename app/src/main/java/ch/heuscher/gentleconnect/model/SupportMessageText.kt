package ch.heuscher.gentleconnect.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure text generation for support alert and claim notification messages.
 *
 * **Everything here is written in her own voice.** The alerts go out from her account, so every
 * one of them also lands in her own thread with that person and she reads it there, days later,
 * as something she said. Written about her in the third person — "Mama hat den Hilfe-Knopf
 * gedrückt" — it is a stranger describing her out of her own mouth, in her own conversation.
 * So: "Ich bitte um Hilfe", "meine Hilfe-Anfrage", "ruf mich an".
 *
 * For the same reason the technical detail is not here. Which screen she was on, whether the
 * messengers were connected and how many faults the log holds are the caregiver's questions, and
 * they are answered on the portal, which has the room and the reader for them. In a family chat
 * they are noise, and in her own thread they are somebody inspecting her.
 *
 * The sentences themselves live in `strings.xml` and arrive as [SupportTexts] — see that file for
 * why they had to leave this one. What stays here is the assembly, which is the part with rules:
 *
 * - Messenger: rich text, emoji, Google Maps URL, battery, network.
 * - SMS: <= 2 GSM-7 segments (<= 306 chars), NO emoji, standard ASCII punctuation.
 * - Missing sections left out, not filled with "unbekannt".
 * - Location included only when `supportShareLocation` is true.
 *
 * The emoji are placed here rather than in the resources on purpose: they mark the sections, and
 * one of them wandering into an SMS string would turn the whole message into UCS-2 and halve how
 * much of it arrives.
 */
object SupportMessageText {

    const val MAX_GSM7_TWO_SEGMENT_LENGTH = 306

    /**
     * Builds the alert message for messengers (Threema, WhatsApp).
     */
    fun buildMessengerAlert(
        texts: SupportTexts,
        groupName: String,
        timeString: String,
        location: SupportLocation?,
        shareLocation: Boolean,
        locationAgeText: String?,
        phoneStatus: SupportPhoneStatus?,
        recipientNames: List<String> = emptyList()
    ): String {
        val grp = groupName.ifBlank { texts.groupFallbackName }

        val sb = StringBuilder()
        // "Etwas ist komisch", not "Ich bitte um Hilfe": what is known is that she pressed a
        // button, and she only had to notice that something was off to press it. Two plain
        // question marks, no 🆘 — this lands in her own thread too, and she reads it there.
        sb.append("?? ").append(texts.alertHeadline).append("\n")
        sb.append(format(texts.alertPressedAt, timeString)).append("\n")

        // Location section
        if (shareLocation && location != null) {
            sb.append("\n📍 https://maps.google.com/?q=")
                .append(String.format(Locale.US, "%.5f,%.5f", location.lat, location.lon))
            if (location.accuracyMetres != null || !locationAgeText.isNullOrBlank()) {
                sb.append("  (")
                val parts = mutableListOf<String>()
                if (location.accuracyMetres != null) {
                    parts.add(format(texts.accuracy, location.accuracyMetres.toString()))
                }
                if (!locationAgeText.isNullOrBlank()) {
                    parts.add(locationAgeText)
                }
                sb.append(parts.joinToString(", "))
                sb.append(")")
            }
            sb.append("\n")
        }

        // Phone status section: only what changes what somebody does about it — whether the
        // telephone is about to go flat, and whether she is on the mobile network. Threema and
        // WhatsApp connection state and the day's faults are the caregiver's and live on the
        // portal; see the note at the top of this file.
        if (phoneStatus != null) {
            val statusParts = mutableListOf<String>()
            statusParts.add(format(texts.battery, phoneStatus.battery.toString()))
            if (phoneStatus.charging) {
                statusParts.add(texts.charging)
            }
            if (phoneStatus.network.isNotBlank()) {
                statusParts.add(phoneStatus.network)
            }
            if (phoneStatus.roaming) {
                statusParts.add(texts.roaming)
            }
            sb.append("📱 ").append(format(texts.phoneLine, statusParts.joinToString(" · "))).append("\n")
        }

        sb.append("\n")
        // That the telephone sent this by itself has to be said, or the family reads a message she
        // sat down and wrote. And by name, because "everyone has received this" was not true the
        // day one SMS failed, and a list of names tells each of them who else may be on the way.
        if (recipientNames.isNotEmpty()) {
            sb.append(format(texts.sentToNames, SupportNames.join(recipientNames, texts.and))).append("\n")
        } else {
            sb.append(format(texts.sentToGroup, grp)).append("\n")
        }
        sb.append(texts.closing)

        return sb.toString().trim()
    }

    /**
     * Builds the alert message for SMS. Must stay within 306 GSM-7 characters.
     * No emoji allowed.
     */
    fun buildSmsAlert(
        texts: SupportTexts,
        timeString: String,
        location: SupportLocation?,
        shareLocation: Boolean,
        locationAgeText: String?,
        phoneStatus: SupportPhoneStatus?
    ): String {
        val sb = StringBuilder()
        // The same two question marks as the messenger alert; plain "?" is GSM-7, unlike 🆘.
        sb.append("?? ").append(format(texts.smsAlert, timeString))

        if (shareLocation && location != null) {
            val locStr = String.format(Locale.US, "maps.google.com/?q=%.4f,%.4f", location.lat, location.lon)
            val details = mutableListOf<String>()
            // No "±" here: it is not in the GSM-7 alphabet, and one such character turns the whole
            // SMS into UCS-2 at 70 characters a part.
            if (location.accuracyMetres != null) {
                details.add(format(texts.smsAccuracy, location.accuracyMetres.toString()))
            }
            if (!locationAgeText.isNullOrBlank()) details.add(locationAgeText)
            val detailStr = if (details.isNotEmpty()) " (${details.joinToString(", ")})" else ""
            sb.append(" ").append(format(texts.smsLocation, locStr, detailStr))
        }

        if (phoneStatus != null) {
            val statList = mutableListOf<String>()
            statList.add(format(texts.smsBattery, phoneStatus.battery.toString()))
            if (phoneStatus.charging) statList.add(texts.charging)
            if (phoneStatus.network.isNotBlank()) statList.add(phoneStatus.network)
            if (phoneStatus.roaming) statList.add(texts.roaming)
            sb.append(" ").append(statList.joinToString(", ")).append(".")
        }

        sb.append(" ").append(texts.smsClosing)

        return sb.toString().trim()
    }

    /**
     * Text notifying the rest of the group when a member claimed the request by reply.
     */
    fun buildClaimByReplyNotice(
        texts: SupportTexts,
        claimerName: String,
        requestTimeString: String,
        isMessenger: Boolean
    ): String {
        val prefix = if (isMessenger) "✅ " else ""
        return prefix + format(texts.claimByReply, claimerName, requestTimeString)
    }

    /**
     * Text notifying the rest of the group when a member claimed the request by phone call.
     *
     * "kümmert sich", not "ist erledigt": a call that connected says somebody reached her, not
     * that whatever she needed has been dealt with.
     */
    fun buildClaimByCallNotice(
        texts: SupportTexts,
        claimerName: String,
        callTimeString: String,
        requestTimeString: String,
        isMessenger: Boolean
    ): String {
        val prefix = if (isMessenger) "📞 " else ""
        return prefix + format(texts.claimByCall, claimerName, callTimeString, requestTimeString)
    }

    /**
     * Text sent to a second member who attempts to claim an already claimed request.
     */
    fun buildSecondClaimReply(
        texts: SupportTexts,
        firstClaimerName: String,
        claimTimeString: String
    ): String = format(texts.secondClaim, firstClaimerName, claimTimeString)

    /**
     * Follow-up location message sent on messenger only if a more accurate fix arrives within 5 min.
     */
    fun buildFollowUpLocation(
        texts: SupportTexts,
        location: SupportLocation
    ): String {
        val url = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", location.lat, location.lon)
        val acc = if (location.accuracyMetres != null) {
            " (" + format(texts.accuracy, location.accuracyMetres.toString()) + ")"
        } else {
            ""
        }
        return "📍 " + format(texts.locationFollowUp, url, acc)
    }

    fun stripEmojiForSms(text: String): String {
        // Strip non-ASCII symbol / emoji codepoints for pure GSM-7 compliance
        return text.replace(Regex("[\\p{So}\\p{Cn}]"), "").replace("  ", " ").trim()
    }

    fun secondClaimText(
        texts: SupportTexts,
        firstClaimerName: String,
        claimTimeMs: Long
    ): String = buildSecondClaimReply(texts, firstClaimerName, clockTime(claimTimeMs))

    fun callConnectedNoticeText(
        texts: SupportTexts,
        claimerName: String,
        alertTime: Long,
        callTime: Long,
        isMessenger: Boolean = true
    ): String = buildClaimByCallNotice(
        texts = texts,
        claimerName = claimerName,
        callTimeString = clockTime(callTime),
        requestTimeString = clockTime(alertTime),
        isMessenger = isMessenger
    )

    fun claimNoticeText(
        texts: SupportTexts,
        claimerName: String,
        alertTime: Long,
        isMessenger: Boolean = true
    ): String = buildClaimByReplyNotice(
        texts = texts,
        claimerName = claimerName,
        requestTimeString = clockTime(alertTime),
        isMessenger = isMessenger
    )

    fun formatMessengerAlert(
        texts: SupportTexts,
        groupName: String,
        alertTime: Long,
        location: SupportLocation?,
        phoneStatus: SupportPhoneStatus?,
        shareLocation: Boolean,
        isReAlert: Boolean = false,
        recipientNames: List<String> = emptyList()
    ): String = buildMessengerAlert(
        texts = texts,
        recipientNames = recipientNames,
        groupName = groupName,
        timeString = clockTime(alertTime),
        location = location,
        shareLocation = shareLocation,
        locationAgeText = ageText(texts.ageOneMinute, texts.ageMinutes, alertTime, location),
        phoneStatus = phoneStatus
    )

    fun formatSmsAlert(
        texts: SupportTexts,
        alertTime: Long,
        location: SupportLocation?,
        phoneStatus: SupportPhoneStatus?,
        shareLocation: Boolean,
        isRoaming: Boolean = false,
        isReAlert: Boolean = false
    ): String = buildSmsAlert(
        texts = texts,
        timeString = clockTime(alertTime),
        location = location,
        shareLocation = shareLocation,
        locationAgeText = ageText(texts.smsAgeOneMinute, texts.smsAgeMinutes, alertTime, location),
        phoneStatus = phoneStatus
    )

    /** How old the position was when the alert went out, or null when there is no position. */
    private fun ageText(
        oneMinute: String,
        manyMinutes: String,
        alertTime: Long,
        location: SupportLocation?
    ): String? {
        if (location == null) return null
        val diffMin = TimeUnit.MILLISECONDS.toMinutes(alertTime - location.at)
        return if (diffMin <= 1) oneMinute else format(manyMinutes, diffMin.toString())
    }

    private fun clockTime(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(at))

    /**
     * A resource string with its placeholders filled in.
     *
     * Not `String.format` at every call site, because one of these templates carrying a stray `%`
     * from a translation would throw in the middle of sending a help request. A message with a
     * placeholder still in it is far better than no message at all.
     */
    private fun format(template: String, vararg args: String): String =
        try {
            String.format(Locale.getDefault(), template, *args)
        } catch (e: IllegalArgumentException) {
            template
        }
}
