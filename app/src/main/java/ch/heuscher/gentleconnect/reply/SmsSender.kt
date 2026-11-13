package ch.heuscher.gentleconnect.reply

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Sends an SMS and waits to hear whether it actually went.
 *
 * `SmsManager.sendTextMessage` returns nothing. Called with a null sent-intent — as it was
 * here — the app learns only that the call did not throw, which it does not even when there is
 * no SIM, no service, or no credit. Everything downstream then reported success, so she was
 * told her message was on its way whenever the platform accepted the request, and the one case
 * that matters to her was the one case it could not see.
 *
 * This asks for the result broadcast and waits for it. A message that never leaves the phone
 * is reported as not sent.
 */
class SmsSender(private val context: Context) {

    suspend fun send(address: String, text: String): Boolean {
        if (text.isBlank()) return false

        val destination = normalizeAddress(address)
        if (destination.isBlank()) {
            Log.w(TAG, "No number to send to")
            return false
        }

        val action = "$ACTION_SMS_SENT.${System.nanoTime()}"
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return false

        // What the message has to be cut into to travel. One part is the ordinary case and the
        // limit is not a round number she could have guessed: 160 characters, or 70 as soon as
        // anything in it — one emoji, one unusual dash — forces the wider encoding.
        val parts = smsManager.divideMessage(text) ?: arrayListOf(text)
        if (parts.isEmpty()) return false

        val sent = try {
            withTimeout(SEND_TIMEOUT_MS + EXTRA_PER_PART_MS * (parts.size - 1)) {
                suspendCancellableCoroutine { continuation ->
                    // Every part has to report before the message counts as sent, and any one of
                    // them failing sinks the whole thing: half a message arriving is not something
                    // to call success, and she cannot send the missing half by hand.
                    val outstanding = AtomicInteger(parts.size)
                    val allWent = AtomicBoolean(true)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(receivedContext: Context?, intent: Intent?) {
                            val ok = resultCode == Activity.RESULT_OK
                            if (!ok) {
                                // resultCode carries SmsManager.RESULT_ERROR_* — worth having in
                                // the log, since from her side a failure is completely silent.
                                Log.w(TAG, "SMS not sent, resultCode=$resultCode")
                                allWent.set(false)
                            }
                            // Nothing is gained by waiting for the rest once one has failed.
                            if (!ok || outstanding.decrementAndGet() == 0) {
                                runCatching { context.unregisterReceiver(this) }
                                if (continuation.isActive) continuation.resume(allWent.get())
                            }
                        }
                    }

                    val filter = IntentFilter(action)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(receiver, filter)
                    }

                    continuation.invokeOnCancellation {
                        runCatching { context.unregisterReceiver(receiver) }
                    }

                    // One per part, and distinct: PendingIntents that match are the same
                    // PendingIntent, so a shared request code would collapse the parts into one
                    // and the count above would never reach zero. Extras do not distinguish them;
                    // the request code does.
                    val sentIntents = ArrayList<PendingIntent>(parts.size)
                    for (index in parts.indices) {
                        sentIntents.add(
                            PendingIntent.getBroadcast(
                                context,
                                index,
                                Intent(action).setPackage(context.packageName),
                                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }

                    try {
                        if (parts.size == 1) {
                            smsManager.sendTextMessage(destination, null, parts[0], sentIntents[0], null)
                        } else {
                            // `sendTextMessage` does not split, whatever its name suggests. Handed
                            // 170 characters it built no PDU at all and failed the send outright —
                            // found on the phone, and silent apart from a generic error code.
                            smsManager.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SMS send threw", e)
                        runCatching { context.unregisterReceiver(receiver) }
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // No verdict inside the window. Reporting "sent" here would be a guess, and the
            // guess that costs her most is the optimistic one.
            Log.w(TAG, "SMS send timed out with no result")
            false
        } catch (e: Exception) {
            Log.w(TAG, "SMS send failed", e)
            false
        }

        if (sent) recordAsSent(destination, text)
        return sent
    }

    /**
     * File the message she sent in the telephone's own record of sent messages.
     *
     * Android used to do this for us. It stops the moment this app becomes the messages app — from
     * then on, a message this app does not file has never been sent as far as the phone is
     * concerned. Nothing in *this* app reads the sent box, so the cost is invisible here and lands
     * somewhere else entirely: a caregiver looking at her phone in the ordinary messages app, or
     * a backup, would find her side of every conversation missing.
     *
     * Only after the network has confirmed it went, so the record is of messages that actually
     * left rather than of attempts.
     */
    private fun recordAsSent(destination: String, text: String) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, destination)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (e: Exception) {
            // The message has gone; only the record of it failed. Not worth reporting a send as
            // failed over, which would have her write it again.
            Log.w(TAG, "Sent message could not be filed", e)
        }
    }

    companion object {
        private const val TAG = "SmsSender"
        private const val ACTION_SMS_SENT = "ch.heuscher.gentleconnect.SMS_SENT"

        /** Long enough for a radio that is busy or briefly out of service to answer. */
        private const val SEND_TIMEOUT_MS = 30_000L

        /** Each further part is another PDU on the air, so a long message is given longer. */
        private const val EXTRA_PER_PART_MS = 10_000L

        /**
         * A number as the telephony stack needs it: a leading `+` if it had one, then digits.
         *
         * Her address book stores numbers the way a person writes them down —
         * `+41 79 555 01 14` — and handed that to `sendTextMessage` the stack took the
         * destination as far as the first space and sent to `+41`. The network rejected it,
         * which is what a generic failure had been standing for all along. Dialling never
         * showed this because a `tel:` URI tolerates the spaces.
         *
         * Blank if nothing usable is left, which includes service names like "RoamingInfo" —
         * there is no subscriber to send to and the caller should not try.
         */
        fun normalizeAddress(raw: String): String {
            val trimmed = raw.trim()
            val digits = trimmed.filter { it.isDigit() }
            if (digits.isEmpty()) return ""
            return if (trimmed.startsWith("+")) "+$digits" else digits
        }
    }
}
