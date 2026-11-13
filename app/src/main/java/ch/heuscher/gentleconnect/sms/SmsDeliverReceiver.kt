package ch.heuscher.gentleconnect.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ch.heuscher.gentleconnect.data.AppStores
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A text message arriving, now that this app is the messages app.
 *
 * This is the part of the role that carries real weight. `SMS_DELIVER` is sent to exactly one app
 * on the phone, and Android **stops writing messages to the inbox itself** the moment that app is
 * us: whatever this receiver fails to store is not stored anywhere, by anybody, ever. Her bank
 * codes, her appointment reminders and her daughter's texts all come through these few lines.
 *
 * So the order here is deliberate. Store first, tell the app second. If the reload throws, the
 * message is already in the inbox and will be read the next time the launcher comes to the front;
 * if the store throws, nothing else has run and there is nothing to be half-done.
 *
 * The reload is what makes it appear rather than a notice posted from here. Whether a message is
 * worth lighting the phone up for is decided in one place — the arrival loop in `ArrivalPipeline`,
 * which knows about quiet hours and about her being on the telephone. Posting a notice here would
 * be a second opinion that knows neither.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        } ?: return
        if (parts.isEmpty()) return

        // A long text arrives as several parts of one message. Joined in the order the network
        // sent them, because storing them separately would show her the same message twice, cut
        // in half at a word she cannot see the rest of.
        val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val from = parts.first().displayOriginatingAddress.orEmpty()
        val sentAt = parts.first().timestampMillis

        // If this text is a claim from a support group member, it is intercepted here
        // so it does not appear in her ordinary messages or ring arrival chimes.
        if (ch.heuscher.gentleconnect.data.support.SupportManager.interceptIncoming(from, body)) return

        val stored = try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                // When the network says it was sent, kept apart from when this phone got it. The
                // two differ by hours for a message that was queued while she had no signal, and
                // the list is ordered by arrival on purpose — a message that turns up at noon
                // belongs at noon, not buried under this morning.
                put(Telephony.Sms.DATE_SENT, sentAt)
                // Unread and unseen: it has arrived, and nobody has looked at it yet. Marking it
                // read here would leave the message on the phone with nothing anywhere to say it
                // is new — which is the same as it not arriving.
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            // Null back means the provider refused it, which is a lost message and not a success.
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) != null
        } catch (e: Exception) {
            // Nothing to fall back on. The platform will not store it for us and there is no
            // second copy. Swallowed rather than thrown so that one bad message does not take the
            // messages app down and lose the next one too.
            false
        }

        // The store is process-wide on purpose — see AppStores — so this reaches the launcher
        // whether or not any screen is open. When the process has no UI, the message simply waits
        // in the inbox and is read when she next looks, exactly as it did before this app held
        // the role.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!stored) {
                    // Record no content whatsoever: no body, no sender, no number.
                    // This log is published to the caregiver website.
                    try {
                        val repository = CaregiverLogRepository(
                            AppDatabase.getInstance(context.applicationContext).caregiverEventDao()
                        )
                        repository.record(
                            CaregiverEvent(
                                kind = CaregiverEventKind.SMS_NOT_STORED,
                                at = System.currentTimeMillis(),
                                subject = null
                            )
                        )
                    } catch (e: Exception) {
                        // A log that throws must not be the thing that takes the messages app down.
                    }
                    return@launch
                }

                try {
                    AppStores.messages.loadSmsFromPhone(context.applicationContext)
                } catch (e: Exception) {
                    // Already stored. This was only about showing it sooner.
                }
            } finally {
                result.finish()
            }
        }
    }
}
