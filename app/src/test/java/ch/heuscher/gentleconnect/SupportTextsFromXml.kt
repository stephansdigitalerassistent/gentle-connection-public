package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SupportTexts
import java.io.File

/**
 * The real German strings, read out of `values-de/strings.xml`.
 *
 * The alternative was a fixture with the sentences typed out again here, which tests the assembly
 * against a copy of the wording rather than against the wording that is shipped — and the day
 * somebody improves a sentence in the resources, the copy keeps the test green while the phone
 * sends something nobody has read. These are the only strings in this app that go to other
 * people, so they are worth reading from the file that holds them.
 *
 * No XML parser: the file is ours, the shape is one `<string>` per line, and a regex keeps this
 * test from depending on anything.
 */
object SupportTextsFromXml {

    fun german(): SupportTexts = from(read("values-de"))

    fun english(): SupportTexts = from(read("values"))

    private fun from(s: Map<String, String>) = SupportTexts(
        alertHeadline = s.getValue("support_msg_alert_headline"),
        alertPressedAt = s.getValue("support_msg_alert_pressed"),
        accuracy = s.getValue("support_msg_accuracy"),
        ageMinutes = s.getValue("support_msg_age_minutes"),
        ageOneMinute = s.getValue("support_msg_age_one_minute"),
        phoneLine = s.getValue("support_msg_phone_line"),
        battery = s.getValue("support_msg_battery"),
        charging = s.getValue("support_msg_charging"),
        roaming = s.getValue("support_msg_roaming"),
        sentToNames = s.getValue("support_msg_sent_to_names"),
        sentToGroup = s.getValue("support_msg_sent_to_group"),
        groupFallbackName = s.getValue("support_msg_group_fallback"),
        and = s.getValue("support_names_and"),
        closing = s.getValue("support_msg_closing"),
        smsAlert = s.getValue("support_sms_alert"),
        smsLocation = s.getValue("support_sms_location"),
        smsAccuracy = s.getValue("support_sms_accuracy"),
        smsAgeMinutes = s.getValue("support_sms_age_minutes"),
        smsAgeOneMinute = s.getValue("support_sms_age_one_minute"),
        smsBattery = s.getValue("support_sms_battery"),
        smsClosing = s.getValue("support_sms_closing"),
        claimByReply = s.getValue("support_msg_claim_reply"),
        claimByCall = s.getValue("support_msg_claim_call"),
        secondClaim = s.getValue("support_msg_second_claim"),
        locationFollowUp = s.getValue("support_msg_location_follow_up")
    )

    /** Run by Gradle from the module directory and by hand from the repository root. */
    private fun read(qualifier: String): Map<String, String> {
        val relative = "src/main/res/$qualifier/strings.xml"
        val file = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("strings.xml for '$qualifier' not found from ${File(".").absolutePath}")

        val pattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(file.readText())
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    /** What `getString` would hand back: the Android escapes resolved, the placeholders left. */
    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#160;", " ")
}
