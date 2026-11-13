package ch.heuscher.gentleconnect.model

/**
 * How the phone asks her to prove it is her before the payment card lights up.
 *
 * Set per phone on the caregiver website, because which way in works is a fact about one
 * particular person and one particular phone. On a phone where the fingerprint reader rejects
 * half of her touches, the screen that opens at the till must be the PIN keypad; on a phone
 * where the fingerprint works, making her type the PIN every time is a cost with nothing bought.
 *
 * Either way the card itself behaves the same: the keystore takes a confirmed PIN and a strong
 * fingerprint alike — see [ch.heuscher.gentleconnect.data.payment.PaymentAuthGate].
 */
object PaymentAuthMethod {
    /** The phone's own confirm screen, which leads with whatever the system prefers. */
    const val SYSTEM = ""

    /** The PIN keypad and nothing else, with no fingerprint sheet in front of it. */
    const val PIN = "pin"

    /** Anything this version does not know reads as [SYSTEM], which is what every phone had first. */
    fun sane(value: String?): String = when (value?.trim()?.lowercase()) {
        PIN -> PIN
        else -> SYSTEM
    }
}
