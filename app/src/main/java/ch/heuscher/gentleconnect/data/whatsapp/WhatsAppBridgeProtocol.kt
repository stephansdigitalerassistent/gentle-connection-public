package ch.heuscher.gentleconnect.data.whatsapp

import org.json.JSONArray
import org.json.JSONObject

/** Something the bridge said. */
sealed interface WhatsAppEvent {

    /** Whether this phone has been linked before. The first line the bridge ever writes. */
    data class State(val linked: Boolean) : WhatsAppEvent

    /** The code to type into WhatsApp → Linked Devices. Short-lived, and never logged. */
    data class PairingCode(val code: String) : WhatsAppEvent

    /** Linking succeeded. */
    data class Paired(val jid: String) : WhatsAppEvent

    /**
     * The code went stale before anybody used it, and a new one is needed.
     *
     * A pairing code is bound to the socket it was issued on. Linking takes minutes — a caregiver
     * has to read the code, open WhatsApp, get past a fingerprint prompt and type eight
     * characters — and an idle unauthenticated socket does not last that long. Without this the
     * caregiver types a code that cannot possibly work and concludes the app is broken.
     */
    data object PairingExpired : WhatsAppEvent

    /** The socket is up and messages can flow. */
    data object Connected : WhatsAppEvent

    /** Ordinary and constant. Not a fault, and must never be recorded as one. */
    data object Disconnected : WhatsAppEvent

    /**
     * The session is gone and will not come back on its own.
     *
     * This is the fault, and the one this whole integration has to be able to report: somebody
     * has to be at her phone — in person or over remote control — before anything works again.
     */
    data class LoggedOut(val reason: String) : WhatsAppEvent

    /** A message. Groups and her own echoed messages included; the caller decides what to keep. */
    data class Incoming(
        val messageId: String,
        val senderJid: String,
        val chatJid: String,
        val isGroup: Boolean,
        val pushName: String,
        val text: String,
        val timestamp: Long,
        val outgoing: Boolean,
        /**
         * Whether this came out of the history WhatsApp sends a newly linked device, rather than
         * arriving now.
         *
         * The difference is not decoration: a fortnight of chats arriving at once on the day her
         * phone is set up would be a fortnight of unread badges, which teaches her within a day
         * that the badge means nothing.
         */
        val historical: Boolean = false,
        /** The stanza id of the message being replied to, as reported by the bridge. */
        val quotedId: String? = null,
        /** The sender of the quoted message, resolved to a telephone number or contact name by the bridge. */
        val quotedSender: String? = null,
        /** A copy of the quoted message's text (or media placeholder), capped in length. */
        val quotedText: String? = null
    ) : WhatsAppEvent

    /**
     * An attachment belonging to a message that has already arrived: a photograph, or a voice
     * message.
     *
     * Separate from [Incoming] because the two travel at different speeds. Words are in the
     * message itself and reach her instantly; an attachment has to be fetched from WhatsApp, and
     * during a history import there may be hundreds of them. Making the message wait for its
     * picture would put a queue of downloads between her and everything anybody has written.
     *
     * So the message appears with its text — "[Foto]", "[Sprachnachricht]", or the caption
     * somebody wrote — and the picture or the play control fills in when it lands. One that never
     * arrives, which is most of an old history because WhatsApp expires media, simply leaves the
     * message as it was.
     */
    data class Media(val messageId: String, val path: String, val kind: String) : WhatsAppEvent

    /**
     * Somebody reacted to a message.
     *
     * Named against the message it refers to, never a message of its own. A reaction shown as its
     * own line is a bare heart from nobody, sitting under whatever arrived next and pointing at
     * nothing — which is what these became before this event existed.
     */
    data class Reaction(
        val targetMessageId: String,
        val senderJid: String,
        val emoji: String,
        val withdrawn: Boolean,
        val fromMe: Boolean
    ) : WhatsAppEvent

    /**
     * A message of hers left. [ref] is the app's name for the request this answers — see
     * [WhatsAppBridgeProtocol.answers].
     */
    data class Sent(
        val to: String,
        val messageId: String,
        val timestamp: Long,
        val ref: String? = null
    ) : WhatsAppEvent

    /** A message of hers did not leave. She is told it did not go; the reason comes here. */
    data class SendFailed(val to: String, val message: String, val ref: String? = null) : WhatsAppEvent

    /**
     * One of the groups she is in: what it is called, how many people are in it, and who.
     *
     * The membership used to be withheld, on the reasoning that Threema needs one because a group
     * message there is encrypted separately to every member, while a WhatsApp group is a real
     * address and a reply is one message to it — so the list was a copy of her social graph kept
     * for nothing.
     *
     * Kept for something, as it turned out: the phone draws a group as the people in it, and with
     * no list the faces fell back to everybody who had ever written in the chat. That keeps
     * whoever has left and misses whoever has not spoken, which she noticed from the other end —
     * names in a group belonging to people who are not in it. Sent since 2026-09-20, on Simon's
     * decision; [members] is empty for a group reported by an older bridge, and then the faces
     * fall back as before.
     *
     * @param members each person by the address the phone can place them under — a telephone-
     *   number JID wherever WhatsApp offers one, a LID where it does not.
     */
    data class Group(
        val jid: String,
        val name: String,
        val participants: Int,
        val members: List<String> = emptyList()
    ) : WhatsAppEvent

    /**
     * A profile picture downloaded for a contact or group.
     *
     * Used as a fallback when her address book has no photograph for that person.
     */
    data class Avatar(val jid: String, val path: String) : WhatsAppEvent

    /**
     * A moment of a WhatsApp call: it is ringing, somebody answered it, or it is over.
     *
     * The bridge can hear calls and cannot carry one. WhatsApp's companion protocol delivers the
     * signalling to every linked device — which is why a call rings on WhatsApp Web — but the
     * audio runs over Meta's own relay protocol, and no client outside their own implements it.
     * So this is not the beginning of a call she can answer here; it is the only evidence this
     * app will ever have that somebody tried to reach her that way.
     *
     * What is done with it is in [ch.heuscher.gentleconnect.model.CallCourtesy].
     */
    data class Call(
        val callId: String,
        /** Whoever rang, resolved to a telephone number by the bridge wherever it could be. */
        val callerJid: String,
        val state: CallState,
        /** WhatsApp's own word for why a call ended. Empty for anything but an ending. */
        val reason: String,
        val timestamp: Long,
        /** A call she placed herself, in the native WhatsApp app on this phone. */
        val fromMe: Boolean,
        /** A call to several people at once. */
        val group: Boolean
    ) : WhatsAppEvent

    /** Anything else that went wrong, named by the stage it went wrong in. */
    data class Failure(val stage: String, val message: String) : WhatsAppEvent
}

/** The three things that can be said about a call from outside it. See [WhatsAppEvent.Call]. */
enum class CallState { RINGING, ANSWERED, ENDED }

/**
 * The line protocol between the app and the WhatsApp bridge.
 *
 * The bridge is a subprocess — see `native/whatsapp-bridge` — and everything crossing the pipe is
 * one JSON object per line, prefixed so that a stray library print can never be mistaken for an
 * event. All of that parsing is here, and it is pure, because CI runs JVM tests only and this is
 * the layer where a wrong assumption turns into her messages quietly going nowhere.
 *
 * Everything is defensive in the same direction: a line this build does not understand produces
 * null rather than an exception. A bridge from a later version must degrade to silence, not to a
 * crash loop on the phone somebody's mother depends on.
 */
object WhatsAppBridgeProtocol {

    /** Every event line carries this. Anything else on stdout is the bridge's own logging. */
    const val PREFIX = "BRIDGE-JSON: "

    /**
     * Reads one line from the bridge, or nothing.
     *
     * Null covers three cases on purpose — not our line, not JSON, not an event kind this build
     * knows — because the caller does the same thing with all three: ignores it.
     */
    fun parse(line: String?): WhatsAppEvent? {
        val trimmed = line?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val payload = when {
            trimmed.startsWith(PREFIX) -> trimmed.removePrefix(PREFIX).trim()
            // A bare object is accepted for the same reason the Threema client accepts one: the
            // prefix is a convenience, not a security boundary, and a bridge that forgot it is
            // still telling the truth.
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }

        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null

        return when (json.optString("type")) {
            "state" -> WhatsAppEvent.State(linked = json.optBoolean("linked", false))

            "pairing_code" -> json.optString("code")
                .takeIf { it.isNotBlank() }
                ?.let { WhatsAppEvent.PairingCode(it) }

            "paired" -> WhatsAppEvent.Paired(json.optString("jid"))
            "pairing_expired" -> WhatsAppEvent.PairingExpired
            "connected" -> WhatsAppEvent.Connected
            "disconnected" -> WhatsAppEvent.Disconnected
            "logged_out" -> WhatsAppEvent.LoggedOut(json.optString("reason").ifBlank { "unknown" })

            "message" -> parseMessage(json)

            "reaction" -> {
                val target = json.optString("targetMessageId")
                val emoji = json.optString("emoji")
                val withdrawn = json.optBoolean("withdrawn", emoji.isBlank())
                // A reaction naming no message has nothing to attach to. One with no emoji and
                // no withdrawal flag says nothing at all.
                if (target.isBlank() || (emoji.isBlank() && !withdrawn)) null
                else WhatsAppEvent.Reaction(
                    targetMessageId = target,
                    senderJid = json.optString("sender"),
                    emoji = emoji,
                    withdrawn = withdrawn,
                    fromMe = json.optBoolean("fromMe", false)
                )
            }

            // All three are required. A line naming no message has nothing to attach to; one
            // naming no file would blank something already on her screen; and one naming a kind
            // this build does not know would be stored as neither a picture nor a sound, and
            // then drawn as nothing.
            "media" -> {
                val messageId = json.optString("messageId")
                val path = json.optString("path")
                val kind = json.optString("kind").ifBlank { KIND_PHOTO }
                if (messageId.isBlank() || path.isBlank() || kind !in KNOWN_MEDIA_KINDS) null
                else WhatsAppEvent.Media(messageId, path, kind)
            }

            "group" -> json.optString("jid")
                .takeIf { WhatsAppNumber.isGroup(it) }
                ?.let {
                    WhatsAppEvent.Group(
                        jid = it,
                        name = json.optString("name"),
                        participants = json.optInt("participants", 0),
                        members = stringsOf(json.optJSONArray("members"))
                    )
                }

            "avatar", "profile_picture" -> {
                val jid = json.optString("jid")
                val path = json.optString("path")
                if (jid.isNotBlank() && path.isNotBlank()) {
                    WhatsAppEvent.Avatar(jid = jid, path = path)
                } else null
            }

            "sent" -> WhatsAppEvent.Sent(
                to = json.optString("to"),
                messageId = json.optString("messageId"),
                timestamp = json.optLong("timestamp"),
                ref = json.optString("ref").ifBlank { null }
            )

            "send_failed" -> WhatsAppEvent.SendFailed(
                to = json.optString("to"),
                message = json.optString("message"),
                ref = json.optString("ref").ifBlank { null }
            )

            "call" -> parseCall(json)

            "error" -> WhatsAppEvent.Failure(
                stage = json.optString("stage").ifBlank { "unknown" },
                message = json.optString("message")
            )

            else -> null
        }
    }

    /**
     * A message, or nothing.
     *
     * Two fields are load-bearing and a message without either is dropped rather than shown: an
     * id, because without it a re-delivery duplicates on her screen, and text, because an empty
     * card is worse than no card. This mirrors [ch.heuscher.gentleconnect.data.OnDeviceMessageMapper].
     */
    private fun parseMessage(json: JSONObject): WhatsAppEvent? {
        val messageId = json.optString("messageId").takeIf { it.isNotBlank() } ?: return null
        val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
        val sender = json.optString("sender").takeIf { it.isNotBlank() } ?: return null

        return WhatsAppEvent.Incoming(
            messageId = messageId,
            senderJid = sender,
            chatJid = json.optString("chat").ifBlank { sender },
            isGroup = json.optBoolean("isGroup", false),
            pushName = json.optString("pushName"),
            text = text,
            timestamp = json.optLong("timestamp").takeIf { it > 0 } ?: System.currentTimeMillis(),
            outgoing = json.optString("direction") == "out",
            historical = json.optBoolean("historical", false),
            quotedId = json.optString("quotedId").takeIf { it.isNotBlank() },
            quotedSender = json.optString("quotedSender").takeIf { it.isNotBlank() },
            quotedText = json.optString("quotedText").takeIf { it.isNotBlank() }
        )
    }

    /**
     * The non-empty strings of a JSON array, or nothing at all.
     *
     * Missing, malformed or empty all read the same on purpose: a bridge that does not send group
     * members — an older one, or a newer one that has not been told who is in a group yet — must
     * leave the phone doing what it did before rather than reporting a group of nobody.
     */
    private fun stringsOf(array: JSONArray?): List<String> {
        val values = array ?: return emptyList()
        return (0 until values.length())
            .mapNotNull { index -> values.optString(index).trim().takeIf { it.isNotEmpty() } }
    }

    /**
     * One moment of a call, or nothing.
     *
     * An id and a caller are both required, and for the same reason: without the id the ringing
     * and the ending cannot be recognised as one call, and without the caller there is nobody to
     * tell her about and nobody to write back to. A state this build does not know is nothing —
     * a later bridge may report parts of a call this one has no use for.
     */
    private fun parseCall(json: JSONObject): WhatsAppEvent? {
        val callId = json.optString("callId").takeIf { it.isNotBlank() } ?: return null
        val caller = json.optString("from").takeIf { it.isNotBlank() } ?: return null
        val state = when (json.optString("state")) {
            "ringing" -> CallState.RINGING
            "answered" -> CallState.ANSWERED
            "ended" -> CallState.ENDED
            else -> return null
        }

        return WhatsAppEvent.Call(
            callId = callId,
            callerJid = caller,
            state = state,
            reason = json.optString("reason"),
            timestamp = json.optLong("timestamp").takeIf { it > 0 } ?: System.currentTimeMillis(),
            fromMe = json.optBoolean("fromMe", false),
            group = json.optBoolean("group", false)
        )
    }

    /**
     * Ask for a pairing code for a telephone number.
     *
     * The number is hers — the account being linked — and it goes over the pipe, never in argv.
     */
    fun pairCommand(phoneNumber: String): String =
        JSONObject()
            .put("cmd", "pair")
            .put("phone", WhatsAppNumber.digitsOf(phoneNumber).orEmpty())
            .toString()

    /** Send a message as her. */
    /**
     * [messageId] is the name the message will go by on WhatsApp, chosen here rather than by the
     * bridge so that the app knows afterwards what its own message is called. Without it a
     * reaction to something she wrote names a message the app has no record of.
     */
    fun sendCommand(toJid: String, text: String, messageId: String? = null, ref: String? = null): String =
        JSONObject()
            .put("cmd", "send")
            .put("to", toJid)
            .put("text", text)
            .apply { if (messageId != null) put("messageId", messageId) }
            .apply { if (ref != null) put("ref", ref) }
            .toString()

    /**
     * Send an attachment that is already on this phone.
     *
     * The path never leaves the device — the bridge reads the file and puts its bytes on
     * WhatsApp's media servers. [mime] is what decides how it is drawn at the other end: a
     * photograph must arrive as a photograph rather than as a file to be tapped twice.
     */
    fun sendMediaCommand(
        toJid: String,
        path: String,
        mime: String,
        messageId: String? = null,
        ref: String? = null
    ): String =
        JSONObject()
            .put("cmd", "send_media")
            .put("to", toJid)
            .put("path", path)
            .put("mime", mime)
            .apply { if (messageId != null) put("messageId", messageId) }
            .apply { if (ref != null) put("ref", ref) }
            .toString()

    /**
     * Send a real reaction rather than an emoji as a message.
     *
     * [fromMe] is not decoration: WhatsApp's message key includes whether the message being
     * reacted to was hers, and a key that gets it wrong addresses a message that does not exist,
     * so the reaction is accepted and lands nowhere.
     */
    fun reactCommand(
        toJid: String,
        emoji: String,
        messageId: String,
        fromMe: Boolean,
        participant: String? = null,
        ref: String? = null
    ): String =
        JSONObject()
            .put("cmd", "react")
            .put("to", toJid)
            .put("emoji", emoji)
            .put("messageId", messageId)
            .put("fromMe", fromMe)
            .apply { if (!participant.isNullOrBlank()) put("participant", participant) }
            .apply { if (ref != null) put("ref", ref) }
            .toString()

    /**
     * Turn a ringing call away, by the id the bridge reported it under.
     *
     * Only the id: rejecting has to name the call's creator exactly as WhatsApp's offer did, and
     * the bridge keeps that itself — the caller this app was told about is the resolved number,
     * which is not always the address the offer came from.
     */
    /**
     * Whether [event] answers the send request named [ref]: true for sent, false for not sent,
     * null for an event about something else.
     *
     * By the request's own name and nothing else. Matching by recipient let the "sent" for a
     * photo or a reaction to the same person be taken as the answer to a text queued behind it,
     * so a text that went on to fail had already been reported to her as gone.
     */
    fun answers(event: WhatsAppEvent, ref: String): Boolean? = when {
        event is WhatsAppEvent.Sent && event.ref == ref -> true
        event is WhatsAppEvent.SendFailed && event.ref == ref -> false
        else -> null
    }

    fun rejectCallCommand(callId: String): String =
        JSONObject().put("cmd", "reject_call").put("callId", callId).toString()

    /** Unlink this phone. Deliberate, and only ever from the caregiver dialog. */
    fun logoutCommand(): String = JSONObject().put("cmd", "logout").toString()

    /**
     * The phone has moved to a network with other DNS servers — from the flat's router to the
     * mobile operator, say. The bridge cannot find this out for itself, and without it every
     * reconnect goes on asking a server that is no longer reachable.
     */
    fun dnsCommand(servers: List<String>): String =
        JSONObject()
            .put("cmd", "dns")
            .put("servers", servers.joinToString(","))
            .toString()

    /** Ask the bridge to fetch a profile picture for a JID or phone number. */
    fun avatarCommand(jid: String): String =
        JSONObject()
            .put("cmd", "avatar")
            .put("jid", jid)
            .toString()

    /**
     * The reason the bridge gives for a logout that this app asked for.
     *
     * Every other reason comes from WhatsApp and means the session was taken away. This one means
     * a caregiver pressed Unlink, which is why it is the one logout that raises no alert.
     */
    const val REASON_UNLINKED = "unlinked"

    /** A picture to draw. */
    const val KIND_PHOTO = "photo"

    /** Somebody talking to her, which gets a play control rather than a picture. */
    const val KIND_VOICE = "voice"

    /**
     * A film, which arrives *beside* the still rather than instead of it.
     *
     * The bridge sends two media lines for one video message: the thumbnail as [KIND_PHOTO], which
     * costs no network at all and is on her screen at once, and the film itself as this, minutes
     * later or never. That ordering is the whole design — the picture of who is in it is what she
     * usually wanted, and it must not wait behind a download that may never finish.
     */
    const val KIND_VIDEO = "video"

    /**
     * The kinds this build can put on her screen.
     *
     * A kind from a later bridge is dropped rather than stored: a file recorded as an attachment
     * that nothing knows how to draw is a message with an invisible hole in it.
     */
    val KNOWN_MEDIA_KINDS = setOf(KIND_PHOTO, KIND_VOICE, KIND_VIDEO)
}
