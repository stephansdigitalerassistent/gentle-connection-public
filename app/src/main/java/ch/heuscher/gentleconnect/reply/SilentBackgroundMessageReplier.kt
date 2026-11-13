package ch.heuscher.gentleconnect.reply

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel

/**
 * Sends her replies (emoji reactions and text) strictly in the background.
 * GUARANTEE: NEVER opens third-party apps, browsers, or dialers so elderly users stay inside gentle connection.
 *
 * This used to answer any app by firing the RemoteInput action attached to its notification. That
 * path went with the notification listener. Threema replies travel through the bridge outbox
 * instead; what is left here is her SMS inbox, which is read directly and answered through
 * [SmsSender] — which, unlike a bare `sendTextMessage`, waits to hear whether the message went.
 */
class SilentBackgroundMessageReplier(
    private val context: Context
) : MessageReplier {

    private val smsSender = SmsSender(context)

    override suspend fun replyWithEmoji(message: Message, emoji: String): Boolean {
        return sendBackgroundReply(message, emoji)
    }

    override suspend fun replyWithText(message: Message, text: String): Boolean {
        return sendBackgroundReply(message, text)
    }

    private suspend fun sendBackgroundReply(message: Message, replyContent: String): Boolean {
        val recipientAddress = message.senderAddress
        // [MessageChannel.DEVICE] *is* the SMS inbox, so it settles this on its own. The package
        // names below settle nothing any more — they date from reading other apps' notifications,
        // and a message this app recorded itself carries no source package at all. Asking them
        // alone turned writing again to somebody who had not written back into a silent no-op:
        // the only message naming her correspondent was one of hers, and it failed this test.
        val isSms = message.channel == MessageChannel.DEVICE ||
                message.sourcePackage == "com.google.android.apps.messaging" ||
                message.sourcePackage.contains("sms", ignoreCase = true) ||
                message.sourcePackage.contains("messaging", ignoreCase = true)

        if (!recipientAddress.isNullOrEmpty() && isSms && hasSendSmsPermission()) {
            return smsSender.send(recipientAddress, replyContent)
        }

        // Her reaction is stored locally either way, but nothing left the phone — say so, so the
        // caller can tell her the message did not go out rather than letting her believe it did.
        return false
    }

    override suspend fun sendDirectText(address: String, text: String): Boolean {
        if (address.isBlank() || !hasSendSmsPermission()) return false
        return smsSender.send(address, text)
    }


    private fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
