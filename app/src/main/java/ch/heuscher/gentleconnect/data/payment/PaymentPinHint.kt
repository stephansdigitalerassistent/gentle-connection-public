package ch.heuscher.gentleconnect.data.payment

import android.content.Context

/**
 * A line of help she is shown before the PIN screen that unlocks the card — "the PIN is the same
 * as for your bank card", say — set on this phone by whoever looks after her.
 *
 * On this phone and nowhere else. It is not in [ch.heuscher.gentleconnect.model.RemoteSettings]
 * and never goes to the caregiver website: it is a remark about her PIN, and the fewer places
 * that has been written down, the better.
 *
 * Empty means no hint, and then nothing changes at all: pressing the card goes straight to the
 * PIN, the way it always has. See MainActivity.launchPaymentPinAuth.
 */
class PaymentPinHint(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The hint, or null when none is set. */
    fun get(): String? = prefs.getString(KEY_HINT, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Stores [text]; a blank one removes the hint. */
    fun set(text: String) {
        val trimmed = text.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_HINT) else putString(KEY_HINT, trimmed)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "payment_pin_hint"
        const val KEY_HINT = "hint"
    }
}
