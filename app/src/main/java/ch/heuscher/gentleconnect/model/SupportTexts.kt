package ch.heuscher.gentleconnect.model

/**
 * Every sentence the help button sends, in whichever language the phone was set up in.
 *
 * These are the only messages this app writes to somebody other than her, and for a long time
 * they were the only user-visible strings not in `strings.xml`: they are built in `model/`, which
 * may not import `android.*` — that is what lets the tests run here in seconds without the Android
 * toolchain — and without a `Context` there is no `getString`. So the language was a boolean
 * parameter, `isGerman`, defaulted to true and never once passed: the English half had never been
 * sent in its life. Translation that cannot be reached is not translation.
 *
 * So the strings come from the resources after all, resolved by the caller (see
 * `supportTextsOf`) and handed in here. `model/` stays free of Android, `values-de/strings.xml`
 * stays the one place any of these sentences is written, and a new language is a new
 * `values-xx/strings.xml` rather than a new branch in Kotlin.
 *
 * Each field is the raw resource string, placeholders and all; this file's users format them.
 * They are her own voice on purpose — see [SupportMessageText] for why that matters.
 */
data class SupportTexts(
    /** "Ich bitte um Hilfe." */
    val alertHeadline: String,
    /** "Ich habe um %1$s den Hilfe-Knopf gedrückt." */
    val alertPressedAt: String,
    /** "±%1$s m" — the radius the position is good to. */
    val accuracy: String,
    /** "vor %1$s Min." */
    val ageMinutes: String,
    /** "vor 1 Min." */
    val ageOneMinute: String,
    /** "Mein Telefon: %1$s" */
    val phoneLine: String,
    /** "Akku %1$s %%" */
    val battery: String,
    /** "lädt" */
    val charging: String,
    /** "Roaming" */
    val roaming: String,
    /** "Das schickt mein Telefon automatisch an %1$s." */
    val sentToNames: String,
    /** "Das schickt mein Telefon automatisch an alle in „%1$s“." */
    val sentToGroup: String,
    /** "Familie" — only when her settings name no group at all. */
    val groupFallbackName: String,
    /** "und", for a list of names. */
    val and: String,
    /** "Wer sich kümmert, antwortet mit „ok“ oder ruft mich an – dann wissen es die anderen." */
    val closing: String,
    /** "Ich bitte um Hilfe. Ich habe um %1$s den Hilfe-Knopf gedrückt." */
    val smsAlert: String,
    /** "Ort: %1$s%2$s." */
    val smsLocation: String,
    /** "%1$sm" — no ±, which is not in GSM-7 and would halve the SMS. */
    val smsAccuracy: String,
    /** "vor %1$s Min" */
    val smsAgeMinutes: String,
    /** "vor 1 Min" */
    val smsAgeOneMinute: String,
    /** "Akku %1$s%%" */
    val smsBattery: String,
    /** "Mein Telefon schickt das automatisch. Antworte ok oder ruf mich an." */
    val smsClosing: String,
    /** "%1$s kümmert sich um meine Hilfe-Anfrage von %2$s." */
    val claimByReply: String,
    /** "%1$s hat um %2$s mit mir telefoniert und kümmert sich um die Hilfe-Anfrage von %3$s." */
    val claimByCall: String,
    /** "%1$s kümmert sich schon (seit %2$s)." */
    val secondClaim: String,
    /** "Ich bin genauer hier: %1$s%2$s" */
    val locationFollowUp: String
)
