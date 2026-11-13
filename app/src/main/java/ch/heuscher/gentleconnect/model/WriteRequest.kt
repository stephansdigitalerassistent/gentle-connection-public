package ch.heuscher.gentleconnect.model

/**
 * Who another app is asking her to write to.
 *
 * The address half of an `smsto:` request, pulled out of the URI and kept pure so it can be tested
 * without a phone. What comes in is genuinely varied — `smsto:0791234567`,
 * `sms:+41 79 123 45 67?body=Hallo`, `smsto:0791234567,0797654321` — because every app on the
 * phone writes these by hand.
 *
 * See MainActivity.showForWriting, which decodes the URI and does something with the answer.
 */
object WriteRequest {

    /** `ACTION_VIEW` of this type is the older form of "write to"; see [EXTRA_ADDRESS]. */
    const val LEGACY_MESSAGES_TYPE = "vnd.android-dir/mms-sms"

    /** Where the older form puts the number, since it has no URI to carry it. */
    const val EXTRA_ADDRESS = "address"

    /** Whether a URI scheme names a text or picture message, and so a request to write. */
    fun isMessageScheme(scheme: String?): Boolean =
        scheme?.lowercase() in setOf("sms", "smsto", "mms", "mmsto")

    /**
     * The first person named, or null when the request names nobody.
     *
     * **The first**, when several are listed. `smsto:` allows a list of recipients and this app has
     * no screen for writing to several people at once — a group conversation is not built out of a
     * list of numbers. Addressing the first is a guess, but the alternative is refusing the request
     * outright, and the overwhelmingly common list of several is a list of one.
     *
     * The `?body=` half is dropped along with everything after it. It carries text somebody else
     * wrote, and putting words into her message that she did not write is not something this app
     * does — not even helpfully, and not even when the other app meant well.
     *
     * Null rather than an empty string for "nobody named", because a bare `smsto:` is a legitimate
     * request meaning "open the messages", and the caller answers it differently.
     */
    fun firstAddressIn(schemeSpecificPart: String?): String? =
        schemeSpecificPart
            ?.substringBefore('?')
            ?.split(',', ';')
            ?.firstOrNull()
            ?.trim()
            ?.ifEmpty { null }
}
