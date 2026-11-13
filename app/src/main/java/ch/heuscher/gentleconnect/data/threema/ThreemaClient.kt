package ch.heuscher.gentleconnect.data.threema

import ch.heuscher.gentleconnect.voip.signalling.VoipCallAnswerData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallHangupData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallOfferData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallRingingData
import ch.heuscher.gentleconnect.voip.signalling.VoipICECandidatesData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppBridgeProtocol
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.math.min

/** Connection state of the embedded Threema client subprocess. */
sealed class ThreemaConnectionState {
    object Disconnected : ThreemaConnectionState()
    object Connecting : ThreemaConnectionState()
    object Connected : ThreemaConnectionState()
    data class Failed(val reason: String) : ThreemaConnectionState()
}

/** Incoming 1:1 or group text message received from the Threema client. */
data class ThreemaIncomingMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
    val messageId: String,
    val groupCreator: String? = null,
    val groupId: String? = null
)


/** Incoming unknown/raw CSP E2E message (e.g., file 0x17). */
/**
 * A photograph or a voice message that has finished downloading.
 *
 * Arrives after the message it belongs to, sometimes seconds after: the placeholder is on her
 * screen the moment the message does, and the blob is fetched and unsealed behind it.
 */
/**
 * Somebody reacted to a message she has.
 *
 * Carries the message it refers to, not a message of its own: an emoji shown as its own line is a
 * heart from nobody, pointing at nothing.
 */
data class ThreemaReactionMessage(
    val targetMessageId: String,
    val sender: String,
    val emoji: String,
    val withdrawn: Boolean,
    /**
     * Whether this came from an older client's acknowledge/decline rather than a modern reaction.
     *
     * The two behave differently and the emoji cannot tell them apart: a modern reaction is one of
     * a set a person adds to and takes back one at a time, while acknowledge and decline share a
     * single slot that the next one overwrites. Kept apart so that somebody on an old client who
     * acknowledges and then declines does not sit on her screen as both at once.
     */
    val legacy: Boolean = false
)

data class ThreemaMediaMessage(
    val messageId: String,
    val path: String,
    /** `photo` or `voice`, the same two words the WhatsApp bridge uses. */
    val kind: String
)

/**
 * What became of an attachment of hers that was put back on the blob servers.
 *
 * The answer to one [ThreemaClient.uploadMedia], named by the same `requestId`, and the three
 * things a file message is made of. [blobId] absent means it did not go up — which somebody is
 * waiting on, unlike a download nobody asked for.
 */
data class ThreemaMediaUpload(
    val requestId: String,
    val blobId: String?,
    val thumbnailBlobId: String?,
    /** Hex, and the whole of the ability to read either blob. */
    val key: String?,
    /** Of the file as it is on this phone, which is the number the message declares. */
    val size: Long
) {
    val succeeded: Boolean get() = blobId != null && key != null
}

data class ThreemaUnknownMessage(
    val sender: String,
    val messageType: Int,
    val dataHex: String,
    val timestamp: Long,
    val messageId: String,
    val groupCreator: String? = null,
    val groupId: String? = null
)

/** Incoming VoIP call signalling message (type code 0x60..0x64). */
data class ThreemaCallMessage(
    val sender: String,
    val messageType: Int,
    val dataHex: String,
    val timestamp: Long,
    val messageId: String
) {
    /** Decodes the hex payload as UTF-8 JSON and parses it into the appropriate [VoipCallData] signalling class. */
    fun parseSignallingData(): VoipCallData<*> {
        val bytes = hexToBytes(dataHex)
        val jsonStr = String(bytes, Charsets.UTF_8)
        return when (messageType) {
            0x60 -> VoipCallOfferData.parse(jsonStr)
            0x61 -> VoipCallAnswerData.parse(jsonStr)
            0x62 -> VoipICECandidatesData.parse(jsonStr)
            0x63 -> VoipCallHangupData.parse(jsonStr)
            0x64 -> VoipCallRingingData.parse(jsonStr)
            else -> throw IllegalArgumentException("Unknown call message type: 0x${messageType.toString(16)}")
        }
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }
}

/** Incoming TURN credentials response from the Threema client. */
/** One address from her address book that turned out to belong to a Threema identity. */
data class ThreemaContactMatch(
    val identity: String,
    val phoneNumber: String? = null,
    val email: String? = null
)

/**
 * The outcome of a contact match.
 *
 * [error] separates "nobody in her address book uses Threema" from "the lookup never completed".
 * Both produce an empty [matches], and treating the second as the first would quietly tell her
 * that people she can in fact reach are unreachable.
 */
data class ThreemaContactMatchResult(
    val matches: List<ThreemaContactMatch>,
    val error: String? = null
)

/**
 * How one message to several people went, member by member.
 *
 * There is no group address in Threema: the message is encrypted and sent to every member
 * separately, so "sent" is not one answer but as many answers as there are members. [delivered]
 * means the bridge accepted that copy for sending, which is not delivery and must never be drawn
 * as delivery.
 *
 * The reason this is per-member rather than a boolean is the failure it exists to catch: a message
 * that reached three of five people looks exactly like one that reached everybody, and she would
 * have no way to find out.
 */
data class ThreemaGroupSendResult(
    val creatorIdentity: String,
    val groupId: String,
    val delivered: List<String>,
    val failed: List<String>,
    val messageId: String? = null
)

/**
 * The outcome of sending a reaction, one-to-one or into a group.
 *
 * Counts only, never identities: for a reaction, who could not be reached is not something worth
 * writing down. [delivered] > 0 counts as success.
 */
data class ThreemaReactionResult(
    /** The message this answers about, so two reactions in flight are not confused for each other. */
    val targetMessageId: String,
    val delivered: Int,
    val failed: Int
)

data class ThreemaTurnCredentialsMessage(
    val turnUrls: List<String>,
    val turnUrlsDualStack: List<String>,
    val turnUsername: String,
    val turnPassword: String,
    val expirationSeconds: Long,
    val error: String? = null
)

/**
 * Manages the native `libthreema_bridge.so` subprocess running in the Android application.
 *
 * Communicates over standard I/O using newline-delimited JSON framing matching the specification
 * in `docs/in-app-bridges.md` Stage 3 and `rust-bridge-client.ts`.
 *
 * NO FOREGROUND SERVICE: Runs in background / process lifecycle per Stage 2/3 staying alive decision.
 */
class ThreemaClient(
    private val context: Context,
    private val identityRepository: ThreemaIdentityRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val registrar = ThreemaRegistrar(context, identityRepository)

    private val _connectionState = MutableStateFlow<ThreemaConnectionState>(ThreemaConnectionState.Disconnected)
    val connectionState: StateFlow<ThreemaConnectionState> = _connectionState.asStateFlow()

    // Sized for a reconnect backlog after hours offline, not for steady-state traffic.
    // When reconnecting, the bridge delivers a burst of queued messages, reactions,
    // and attachments while collectors persist each row to SQLite.
    private val _incomingMessages = MutableSharedFlow<ThreemaIncomingMessage>(extraBufferCapacity = 256)
    val incomingMessages: SharedFlow<ThreemaIncomingMessage> = _incomingMessages.asSharedFlow()

    private val _unknownMessages = MutableSharedFlow<ThreemaUnknownMessage>(extraBufferCapacity = 256)
    val unknownMessages: SharedFlow<ThreemaUnknownMessage> = _unknownMessages.asSharedFlow()

    /**
     * Photographs and voice messages, each naming the message it belongs to.
     *
     * Sized for a reconnect backlog after hours offline, not for steady-state traffic.
     * A bigger buffer than the message flows have, because attachments arrive in bursts — several
     * people sending holiday photographs to a group is one message and a dozen of these.
     */
    private val _mediaMessages = MutableSharedFlow<ThreemaMediaMessage>(extraBufferCapacity = 512)
    val mediaMessages: SharedFlow<ThreemaMediaMessage> = _mediaMessages.asSharedFlow()

    /**
     * Attachments of hers that have been put back on the blob servers, ready to be sent on.
     *
     * A small buffer on purpose: these exist only while she is forwarding something, which is one
     * at a time and only ever because she asked.
     */
    private val _mediaUploads = MutableSharedFlow<ThreemaMediaUpload>(extraBufferCapacity = 16)
    val mediaUploads: SharedFlow<ThreemaMediaUpload> = _mediaUploads.asSharedFlow()

    /** Somebody reacting to a message, against the message they reacted to. */
    private val _reactionMessages = MutableSharedFlow<ThreemaReactionMessage>(extraBufferCapacity = 256)
    val reactionMessages: SharedFlow<ThreemaReactionMessage> = _reactionMessages.asSharedFlow()

    private val _callMessages = MutableSharedFlow<ThreemaCallMessage>(extraBufferCapacity = 256)
    val callMessages: SharedFlow<ThreemaCallMessage> = _callMessages.asSharedFlow()

    private val _droppedPayloads = MutableStateFlow(0)
    /** How many payloads the bridge handed over that no buffer had room for. Only ever rises. */
    val droppedPayloads: StateFlow<Int> = _droppedPayloads.asStateFlow()

    private val _turnCredentialsMessages = MutableSharedFlow<ThreemaTurnCredentialsMessage>(extraBufferCapacity = 16)
    val turnCredentialsMessages: SharedFlow<ThreemaTurnCredentialsMessage> = _turnCredentialsMessages.asSharedFlow()

    private val _contactMatches = MutableSharedFlow<ThreemaContactMatchResult>(extraBufferCapacity = 8)
    val contactMatches: SharedFlow<ThreemaContactMatchResult> = _contactMatches.asSharedFlow()

    private val _groupSendResults = MutableSharedFlow<ThreemaGroupSendResult>(extraBufferCapacity = 16)
    val groupSendResults: SharedFlow<ThreemaGroupSendResult> = _groupSendResults.asSharedFlow()

    private val _reactionResults = MutableSharedFlow<ThreemaReactionResult>(extraBufferCapacity = 16)
    val reactionResults: SharedFlow<ThreemaReactionResult> = _reactionResults.asSharedFlow()

    private fun onPayloadDropped(type: String) {
        _droppedPayloads.value += 1
        Log.w(TAG, "Dropped $type payload: buffer full")
    }

    /** Requests TURN server credentials from the bridge. */
    fun requestTurnCredentials(turnType: String = "voip"): Boolean {
        val payload = JSONObject().apply {
            put("type", "FetchTurnCredentials")
            put("turn_type", turnType)
        }
        return writeToSubprocess(payload.toString())
    }

    private var process: Process? = null
    private var processWriter: PrintWriter? = null
    private var supervisorJob: Job? = null
    private var isStoppedExplicitly = false

    /** Set when the running bridge logs in, so the next reconnect starts from a short wait again. */
    @Volatile
    private var loggedInThisRun = false

    fun start() {
        if (supervisorJob?.isActive == true) return
        isStoppedExplicitly = false
        supervisorJob = scope.launch {
            runConnectionLoop()
        }
    }

    fun stop() {
        isStoppedExplicitly = true
        supervisorJob?.cancel()
        supervisorJob = null
        killSubprocess()
        _connectionState.value = ThreemaConnectionState.Disconnected
    }

    /**
     * Sends a text message to a recipient Threema ID.
     *
     * [messageId] is the name the message will go by on the wire. The app chooses it so that it
     * knows afterwards what its own message is called: the bridge used to invent one and never
     * report it, so a reaction to something she had written named a message the app had no record
     * of and was dropped. See `ThreemaWireId`.
     */
    fun sendTextMessage(recipient: String, text: String, messageId: String? = null): Boolean {
        val payload = JSONObject().apply {
            put("type", "SendMessage")
            put("recipient", recipient)
            put("text", text)
            if (messageId != null) put("message_id", messageId)
        }
        return writeToSubprocess(payload.toString())
    }

    /**
     * Sends one text message to several people.
     *
     * Not a loop over [sendTextMessage]: every copy has to carry the group it belongs to, or each
     * member receives it as a private message from her and answers her alone. The fan-out happens
     * in the bridge because only the bridge can say which members it actually managed — writing a
     * line to a subprocess says nothing about the five separate lookups and encryptions behind it.
     *
     * @param members exactly who to send to, her own identity excluded — see [GroupReplyPolicy],
     *   which is what decides that and whether the group may be answered at all.
     * @return false if the request never reached the bridge. True means it was asked, nothing
     *   more: the answer arrives on [groupSendResults].
     */
    fun sendGroupTextMessage(
        creatorIdentity: String,
        groupId: String,
        members: List<String>,
        text: String,
        messageId: String? = null
    ): Boolean {
        if (members.isEmpty() || text.isBlank()) return false
        val payload = JSONObject().apply {
            put("type", "SendGroupMessage")
            put("creator", creatorIdentity)
            put("group_id", groupId)
            put("members", JSONArray().apply { members.forEach { put(it) } })
            put("text", text)
            if (messageId != null) put("message_id", messageId)
        }
        return writeToSubprocess(payload.toString())
    }

    /**
     * Sends one photograph to several people: [sendGroupTextMessage], with a file message where
     * the words would be.
     *
     * The blobs must already be up — [prepareFileMessage] — because the upload is once for the
     * whole group and the fan-out is per member. The answer arrives on [groupSendResults], as for
     * words.
     *
     * @param fileHex what [prepareFileMessage] returned.
     */
    fun sendGroupFileMessage(
        creatorIdentity: String,
        groupId: String,
        members: List<String>,
        fileHex: String,
        messageId: String? = null
    ): Boolean {
        if (members.isEmpty() || fileHex.isBlank()) return false
        val payload = JSONObject().apply {
            put("type", "SendGroupMessage")
            put("creator", creatorIdentity)
            put("group_id", groupId)
            put("members", JSONArray().apply { members.forEach { put(it) } })
            put("file_hex", fileHex)
            if (messageId != null) put("message_id", messageId)
        }
        return writeToSubprocess(payload.toString())
    }

    /**
     * Sends an emoji reaction to a 1:1 message, or withdraws an existing one.
     *
     * Awaits the bridge's [ThreemaReactionResult] and returns true if delivered > 0.
     */
    suspend fun sendReaction(
        recipient: String,
        targetMessageId: String,
        emoji: String,
        withdraw: Boolean = false
    ): Boolean {
        if (recipient.isBlank() || targetMessageId.isBlank()) return false
        return withTimeoutOrNull(REACTION_SEND_TIMEOUT_MS) {
            coroutineScope {
                // Matched to the message it is about, the way a group send is matched to its
                // group: two hearts on their way at once are two collectors on one event, and
                // without this each takes whichever answer arrives first.
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    _reactionResults.first { it.targetMessageId == targetMessageId }
                }
                val payload = JSONObject().apply {
                    put("type", "SendReaction")
                    put("recipient", recipient)
                    put("target_message_id", targetMessageId)
                    put("emoji", emoji)
                    put("withdraw", withdraw)
                }
                val asked = writeToSubprocess(payload.toString())
                if (!asked) {
                    outcome.cancel()
                    return@coroutineScope false
                }
                val result = outcome.await()
                result.delivered > 0
            }
        } ?: false
    }

    /**
     * Sends an emoji reaction to a group message, or withdraws an existing one.
     *
     * Awaits the bridge's [ThreemaReactionResult] and returns true if delivered > 0.
     */
    suspend fun sendGroupReaction(
        creator: String,
        groupId: String,
        members: List<String>,
        targetMessageId: String,
        emoji: String,
        withdraw: Boolean = false
    ): Boolean {
        if (members.isEmpty() || creator.isBlank() || groupId.isBlank() || targetMessageId.isBlank()) return false
        return withTimeoutOrNull(REACTION_SEND_TIMEOUT_MS) {
            coroutineScope {
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    _reactionResults.first { it.targetMessageId == targetMessageId }
                }
                val payload = JSONObject().apply {
                    put("type", "SendGroupReaction")
                    put("creator", creator)
                    put("group_id", groupId)
                    put("members", JSONArray().apply { members.forEach { put(it) } })
                    put("target_message_id", targetMessageId)
                    put("emoji", emoji)
                    put("withdraw", withdraw)
                }
                val asked = writeToSubprocess(payload.toString())
                if (!asked) {
                    outcome.cancel()
                    return@coroutineScope false
                }
                val result = outcome.await()
                result.delivered > 0
            }
        } ?: false
    }

    /**
     * Puts an attachment already on this phone back onto Threema's blob servers.
     *
     * Half of forwarding: what comes back on [mediaUploads] are the two blob names and the key
     * that a file message names, and the message itself then goes out through [sendRawMessage]
     * like any other. Split that way because a blob upload takes seconds to minutes and a message
     * takes none — and because a file message, once its blobs exist, is nothing special.
     *
     * @param requestId names the answer. Anything unique; the id of the message being forwarded
     *   is the obvious choice, and is what the caller uses.
     */
    fun uploadMedia(requestId: String, path: String, thumbnailPath: String? = null): Boolean {
        val payload = JSONObject().apply {
            put("type", "UploadMedia")
            put("request_id", requestId)
            put("path", path)
            if (thumbnailPath != null) put("thumbnail_path", thumbnailPath)
        }
        return writeToSubprocess(payload.toString())
    }

    /**
     * Sends somebody a photograph, a film or a voice message that is already on this phone.
     *
     * Two steps behind one call, because they are one thing as far as anybody waiting is
     * concerned: the blobs go up, and then the message naming them goes out. Nothing is sent if
     * the upload fails — a file message naming a blob that does not exist arrives as an
     * attachment the recipient can never open, which is worse than nothing arriving.
     *
     * @param thumbnailPath the still, for a film. Threema shows it while the film downloads, the
     *   same way this app does.
     * @return false if it did not go, so that she can be told it did not go.
     */
    suspend fun sendFile(
        recipient: String,
        path: String,
        thumbnailPath: String? = null,
        caption: String = ""
    ): Boolean {
        if (recipient.isBlank()) return false
        val fileHex = prepareFileMessage(path, thumbnailPath, caption) ?: return false
        return sendRawMessage(recipient, ThreemaFileMessage.MESSAGE_TYPE, fileHex)
    }

    /**
     * The first of [sendFile]'s two steps on its own: the blobs go up, and what comes back is the
     * file message naming them, as hex — or null when the upload did not finish, in which case
     * nothing may be sent at all.
     *
     * Separate because a group needs the upload once and the message once per member; see
     * [sendGroupFileMessage].
     */
    suspend fun prepareFileMessage(
        path: String,
        thumbnailPath: String? = null,
        caption: String = ""
    ): String? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null

        val requestId = "upload_${System.nanoTime()}"
        val uploaded = withTimeoutOrNull(MEDIA_UPLOAD_TIMEOUT_MS) {
            coroutineScope {
                // Listening before asking, like the reactions above: the answer to a small
                // photograph on a good connection can be back before the next line runs.
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    _mediaUploads.first { it.requestId == requestId }
                }
                if (!uploadMedia(requestId, path, thumbnailPath)) {
                    outcome.cancel()
                    return@coroutineScope null
                }
                outcome.await()
            }
        }
        if (uploaded == null || !uploaded.succeeded) {
            Log.w(TAG, "Forward: upload did not finish for $requestId")
            return null
        }

        val mime = ThreemaFileMessage.mimeOf(path)
        val body = ThreemaFileMessage.body(
            blobId = uploaded.blobId ?: return null,
            thumbnailBlobId = uploaded.thumbnailBlobId,
            key = uploaded.key ?: return null,
            mime = mime,
            name = ThreemaFileMessage.nameFor(path, mime),
            // The bridge counted the bytes it actually sealed; this side only knows what the file
            // system says. They are the same number, and the bridge's is the one that was sent.
            size = if (uploaded.size > 0L) uploaded.size else file.length(),
            caption = caption
        )
        return ThreemaFileMessage.hex(body)
    }

    /** Sends a raw CSP E2E message (e.g. VoIP signalling payload) to a recipient. */
    fun sendRawMessage(recipient: String, messageType: Int, dataHex: String): Boolean {
        val payload = JSONObject().apply {
            put("type", "SendRaw")
            put("recipient", recipient)
            put("message_type", messageType)
            put("data_hex", dataHex)
        }
        return writeToSubprocess(payload.toString())
    }

    /** Sends a call signalling message (type code 0x60..0x64) to a recipient. */
    fun sendCallMessage(recipient: String, messageType: Int, dataHex: String): Boolean {
        require(messageType in 0x60..0x64) { "Invalid call message type: 0x${messageType.toString(16)}" }
        val payload = JSONObject().apply {
            put("type", "SendCall")
            put("recipient", recipient)
            put("message_type", messageType)
            put("data_hex", dataHex)
        }
        return writeToSubprocess(payload.toString())
    }

    /** Sends a typed [VoipCallData] signalling payload to a recipient. */
    fun sendCallData(recipient: String, messageType: Int, callData: VoipCallData<*>): Boolean {
        val jsonStr = when (callData) {
            is VoipCallOfferData -> callData.generateString()
            is VoipCallAnswerData -> callData.generateString()
            is VoipICECandidatesData -> callData.generateString()
            is VoipCallHangupData -> callData.generateString()
            is VoipCallRingingData -> callData.generateString()
            else -> throw IllegalArgumentException("Unsupported VoipCallData type")
        }
        val hex = bytesToHex(jsonStr.toByteArray(Charsets.UTF_8))
        return sendCallMessage(recipient, messageType, hex)
    }

    /**
     * Asks the directory server which of these addresses belong to a Threema identity.
     *
     * This is the only mechanism that gives an ordinary address-book contact a Threema ID:
     * `ContactsContract` has no such column. Numbers must already be normalised to E.164 without
     * the leading `+` — see `ThreemaContactSync`.
     *
     * The addresses are hashed in the bridge before they leave the device, but be clear-eyed
     * about what that buys: the number space is small enough to brute-force, so this discloses
     * her address book to Threema in practice. It is the same trade the Threema app makes, and
     * the same reason that app puts contact sync behind an explicit opt-in.
     *
     * Results arrive on [contactMatches].
     */
    fun matchContacts(phoneNumbers: List<String>, emails: List<String> = emptyList()): Boolean {
        if (phoneNumbers.isEmpty() && emails.isEmpty()) return true
        val payload = JSONObject().apply {
            put("type", "MatchContacts")
            put("phone_numbers", JSONArray().apply { phoneNumbers.forEach { put(it) } })
            put("emails", JSONArray().apply { emails.forEach { put(it) } })
        }
        return writeToSubprocess(payload.toString())
    }

    /** Seeds the in-memory contact store with known identities. */
    fun addContacts(identities: List<String>): Boolean {
        if (identities.isEmpty()) return true
        val array = JSONArray()
        identities.forEach { array.put(it) }
        val payload = JSONObject().apply {
            put("type", "AddContacts")
            put("identities", array)
        }
        return writeToSubprocess(payload.toString())
    }

    @Synchronized
    private fun writeToSubprocess(jsonLine: String): Boolean {
        val writer = processWriter ?: return false
        return try {
            writer.println(jsonLine)
            writer.flush()
            !writer.checkError()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to subprocess stdin", e)
            false
        }
    }

    private suspend fun runConnectionLoop() {
        var backoffMs = INITIAL_BACKOFF_MS

        while (scope.isActive && !isStoppedExplicitly) {
            var identity = identityRepository.getIdentity()
            var missingReason = "No Threema identity configured"
            if (identity == null) {
                // Nothing stored yet: register once against the serial license, then re-read.
                // ensureIdentity() is a no-op once an identity exists, so on every later start
                // this costs a SharedPreferences lookup and nothing else — which matters,
                // because a second identity would be a second address her contacts don't have.
                when (val registration = registrar.ensureIdentity()) {
                    is ThreemaRegistrar.Result.Registered,
                    ThreemaRegistrar.Result.AlreadyRegistered -> {
                        identity = identityRepository.getIdentity()
                    }
                    is ThreemaRegistrar.Result.Failed -> {
                        Log.w(TAG, "Threema registration failed: ${registration.reason}")
                        // Shown on the caregiver screen. "Cannot be read" is what tells them to
                        // restore her account, rather than to wonder why nothing arrives.
                        missingReason = registration.reason
                    }
                }
            }
            if (identity == null) {
                _connectionState.value = ThreemaConnectionState.Failed(missingReason)
                delay(MISSING_IDENTITY_RETRY_MS)
                continue
            }

            _connectionState.value = ThreemaConnectionState.Connecting
            val binaryFile = getSubprocessExecutable()
            if (binaryFile == null || !binaryFile.exists()) {
                _connectionState.value = ThreemaConnectionState.Failed("Executable libthreema_bridge.so not found")
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
                continue
            }

            try {
                // The client key goes in the environment, never in argv. A process's command
                // line is readable from /proc, its environment is not — and the client key is
                // the whole Threema identity, so anything holding it can impersonate her.
                // The bridge reads THREEMA_CLIENT_KEY; --client-key still works but warns.
                val command = listOf(
                    binaryFile.absolutePath,
                    "--threema-id", identity.threemaId,
                    "--csp-server-group", identity.serverGroup,
                    "--consumer", "production",
                    "--nickname", identity.nickname,
                    // App-private, beside everything else the bridge owns. A photograph her
                    // family sent must not be one directory move away from the shared card.
                    "--media-dir", File(context.filesDir, "threema-media").absolutePath
                )

                loggedInThisRun = false
                val pb = ProcessBuilder(command)
                pb.environment()["THREEMA_CLIENT_KEY"] = identity.clientKey
                val proc = pb.start()
                process = proc
                processWriter = PrintWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8), true)

                // Read stdout and stderr
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))

                scope.launch(Dispatchers.IO) {
                    try {
                        var errLine: String?
                        while (errReader.readLine().also { errLine = it } != null) {
                            // The bridge logs at TRACE and its stderr carries protocol detail,
                            // so this is off unless someone is actively debugging. Drain the
                            // stream regardless — a full stderr pipe blocks the subprocess.
                            if (VERBOSE_BRIDGE_LOGGING) Log.d(TAG, "[RustStderr] ${errLine}")
                        }
                    } catch (_: Exception) {}
                }

                val exitCode = withContext(Dispatchers.IO) {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        if (trimmed.isEmpty()) continue

                        val jsonStr = if (trimmed.startsWith("BRIDGE-JSON: ")) {
                            trimmed.substring("BRIDGE-JSON: ".length)
                        } else if (trimmed.startsWith("{")) {
                            trimmed
                        } else {
                            // The Rust bridge outputs tracing logs to stdout. TRACE lines flood
                            // the rotating app.log (accounting for ~41% of a 5 MB log file) and
                            // contain protocol detail about her conversations. Unless
                            // VERBOSE_BRIDGE_LOGGING is enabled for debugging, drop TRACE lines.
                            val stripped = trimmed.replace(ANSI_ESCAPE_REGEX, "")
                            if (VERBOSE_BRIDGE_LOGGING || (!stripped.contains(" TRACE ") && !stripped.startsWith("TRACE "))) {
                                Log.d(TAG, "[RustStdout] $stripped")
                            }
                            null
                        }

                        if (jsonStr != null) {
                            handleBridgeJson(jsonStr)
                        }
                    }
                    proc.waitFor()
                }

                Log.w(TAG, "Subprocess exited with code $exitCode")
                _connectionState.value = ThreemaConnectionState.Disconnected
            } catch (e: Exception) {
                Log.e(TAG, "Exception during subprocess execution", e)
                _connectionState.value = ThreemaConnectionState.Failed(e.message ?: "Subprocess exception")
            } finally {
                killSubprocess()
            }

            if (!isStoppedExplicitly && scope.isActive) {
                // A bridge that logged in and later lost the server is a fresh outage, not the
                // tenth failure in a row: without this every drop after a few days waits the full
                // minute before trying again.
                if (loggedInThisRun) backoffMs = INITIAL_BACKOFF_MS
                Log.i(TAG, "Reconnecting in ${backoffMs}ms...")
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private fun handleBridgeJson(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            when (json.optString("type")) {
                "HandshakeComplete" -> {
                    Log.i(TAG, "Threema CSP Handshake Complete!")
                    loggedInThisRun = true
                    _connectionState.value = ThreemaConnectionState.Connected
                }
                "Message" -> {
                    val msg = ThreemaIncomingMessage(
                        sender = json.getString("sender"),
                        text = json.getString("text"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        messageId = json.optString("message_id", ""),
                        groupCreator = json.optString("group_creator").takeIf { it.isNotBlank() },
                        groupId = json.optString("group_id").takeIf { it.isNotBlank() }
                    )
                    if (!_incomingMessages.tryEmit(msg)) {
                        onPayloadDropped("Message")
                    }
                }
                "Call" -> {
                    val msg = ThreemaCallMessage(
                        sender = json.getString("sender"),
                        messageType = json.getInt("message_type"),
                        dataHex = json.getString("data_hex"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        messageId = json.optString("message_id", "")
                    )
                    if (!_callMessages.tryEmit(msg)) {
                        onPayloadDropped("Call")
                    }
                }
                "Reaction" -> {
                    val target = json.optString("target_message_id")
                    val emoji = json.optString("emoji")
                    val withdrawn = json.optBoolean("withdrawn", false)
                    if (target.isNotBlank() && (emoji.isNotBlank() || withdrawn)) {
                        val emitted = _reactionMessages.tryEmit(
                            ThreemaReactionMessage(
                                targetMessageId = target,
                                sender = json.optString("sender"),
                                emoji = emoji,
                                withdrawn = withdrawn,
                                legacy = json.optBoolean("legacy", false)
                            )
                        )
                        if (!emitted) {
                            onPayloadDropped("Reaction")
                        }
                    }
                }
                "Media" -> {
                    // All three are required, for the same reasons as on the WhatsApp side: a
                    // line naming no message has nothing to attach to, one naming no file would
                    // blank something already on her screen, and a kind this build cannot draw
                    // would be stored as neither picture nor sound and then drawn as nothing.
                    val messageId = json.optString("message_id")
                    val path = json.optString("path")
                    val kind = json.optString("kind")
                    if (messageId.isNotBlank() && path.isNotBlank() && kind in KNOWN_MEDIA_KINDS) {
                        if (!_mediaMessages.tryEmit(ThreemaMediaMessage(messageId, path, kind))) {
                            onPayloadDropped("Media")
                        }
                    }
                }
                "MediaUploaded" -> {
                    val requestId = json.optString("request_id")
                    val blobId = json.optString("blob_id").takeIf { it.isNotBlank() }
                    val key = json.optString("key").takeIf { it.isNotBlank() }
                    if (requestId.isNotBlank()) {
                        val emitted = _mediaUploads.tryEmit(
                            ThreemaMediaUpload(
                                requestId = requestId,
                                blobId = blobId,
                                thumbnailBlobId = json.optString("thumbnail_blob_id")
                                    .takeIf { it.isNotBlank() },
                                key = key,
                                size = json.optLong("size", 0L)
                            )
                        )
                        if (!emitted) onPayloadDropped("MediaUploaded")
                    }
                }
                "MediaUploadFailed" -> {
                    val requestId = json.optString("request_id")
                    if (requestId.isNotBlank()) {
                        // Reported as the same thing with nothing in it, so that whoever is
                        // waiting hears an answer rather than waiting out a timeout.
                        val emitted = _mediaUploads.tryEmit(
                            ThreemaMediaUpload(requestId, null, null, null, 0L)
                        )
                        if (!emitted) onPayloadDropped("MediaUploadFailed")
                    }
                }
                "Unknown" -> {
                    val msg = ThreemaUnknownMessage(
                        sender = json.getString("sender"),
                        messageType = json.getInt("message_type"),
                        dataHex = json.getString("data_hex"),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        messageId = json.optString("message_id", ""),
                        groupCreator = json.optString("group_creator").takeIf { it.isNotBlank() },
                        groupId = json.optString("group_id").takeIf { it.isNotBlank() }
                    )
                    if (!_unknownMessages.tryEmit(msg)) {
                        onPayloadDropped("Unknown")
                    }
                }
                "ContactMatches" -> {
                    val matchesArr = json.optJSONArray("matches") ?: JSONArray()
                    val matches = (0 until matchesArr.length()).mapNotNull { index ->
                        val entry = matchesArr.optJSONObject(index) ?: return@mapNotNull null
                        val identity = entry.optString("identity").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        ThreemaContactMatch(
                            identity = identity.uppercase(),
                            phoneNumber = entry.optString("phone_number").takeIf { it.isNotBlank() },
                            email = entry.optString("email").takeIf { it.isNotBlank() }
                        )
                    }
                    val result = ThreemaContactMatchResult(
                        matches = matches,
                        error = json.optString("error").takeIf { it.isNotBlank() }
                    )
                    // Counts only — never the numbers themselves, which are her address book.
                    val delivered = _contactMatches.tryEmit(result)
                    if (!delivered) {
                        onPayloadDropped("ContactMatches")
                    }
                    Log.i(
                        TAG,
                        "ContactMatches: ${result.matches.size} parsed, " +
                            "${result.matches.count { it.phoneNumber != null }} with a number, " +
                            "error=${result.error != null}, delivered=$delivered"
                    )
                }
                "TurnCredentials" -> {
                    val turnUrlsArr = json.optJSONArray("turn_urls") ?: JSONArray()
                    val turnUrls = (0 until turnUrlsArr.length()).map { turnUrlsArr.getString(it) }
                    val turnUrlsDualArr = json.optJSONArray("turn_urls_dual_stack") ?: JSONArray()
                    val turnUrlsDualStack = (0 until turnUrlsDualArr.length()).map { turnUrlsDualArr.getString(it) }
                    val msg = ThreemaTurnCredentialsMessage(
                        turnUrls = turnUrls,
                        turnUrlsDualStack = turnUrlsDualStack,
                        turnUsername = json.optString("turn_username", ""),
                        turnPassword = json.optString("turn_password", ""),
                        expirationSeconds = json.optLong("expiration", 0L),
                        error = json.optString("error").takeIf { it.isNotBlank() }
                    )
                    if (!_turnCredentialsMessages.tryEmit(msg)) {
                        onPayloadDropped("TurnCredentials")
                    }
                }
                "GroupSendResult" -> {
                    val deliveredArr = json.optJSONArray("delivered") ?: JSONArray()
                    val failedArr = json.optJSONArray("failed") ?: JSONArray()
                    val result = ThreemaGroupSendResult(
                        creatorIdentity = json.optString("creator").uppercase(),
                        groupId = json.optString("group_id").lowercase(),
                        delivered = (0 until deliveredArr.length()).map { deliveredArr.getString(it) },
                        failed = (0 until failedArr.length()).map { failedArr.getString(it) },
                        messageId = json.optString("message_id").ifEmpty { null }
                    )
                    // Counts only. Which people are in which group is her social graph, and
                    // logcat is readable by anything that has ever held the phone.
                    Log.i(
                        TAG,
                        "GroupSendResult: ${result.delivered.size} accepted, ${result.failed.size} not"
                    )
                    if (!_groupSendResults.tryEmit(result)) {
                        onPayloadDropped("GroupSendResult")
                    }
                }
                "ReactionResult" -> {
                    val target = json.optString("target_message_id")
                    val delivered = json.optInt("delivered", 0)
                    val failed = json.optInt("failed", 0)
                    val result = ThreemaReactionResult(target, delivered, failed)
                    Log.i(
                        TAG,
                        "ReactionResult: ${result.delivered} accepted, ${result.failed} not"
                    )
                    if (!_reactionResults.tryEmit(result)) {
                        onPayloadDropped("ReactionResult")
                    }
                }
                "Log" -> {
                    val level = json.optString("level", "info")
                    val message = json.optString("message", "")
                    Log.d(TAG, "[RustLog][$level] $message")
                }
            }
        } catch (e: Exception) {
            // Deliberately not logging jsonStr: on a parse failure it still holds whatever the
            // bridge emitted, which for a message envelope is the plaintext of what someone
            // wrote to her. Length and the exception are enough to debug a framing problem.
            Log.e(TAG, "Error parsing bridge JSON (${jsonStr.length} chars)", e)
        }
    }

    /**
     * There is deliberately no fallback path here any more. There used to be one pointing at a
     * build directory on the developer's workstation, which can never exist on a phone — so the
     * only thing it achieved was to keep the bridge appearing to work while it was only ever
     * being exercised on the host. It is why nobody noticed for weeks that AGP was not
     * extracting the .so at all and the nativeLibraryDir was empty on every real device.
     */
    private fun getSubprocessExecutable(): File? {
        val nativeSo = File(context.applicationInfo.nativeLibraryDir, "libthreema_bridge.so")
        return nativeSo.takeIf { it.exists() }
    }

    @Synchronized
    private fun killSubprocess() {
        processWriter?.close()
        processWriter = null
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "ThreemaClient"
        private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;]*m")

        /**
         * The attachment kinds this build can put on her screen.
         *
         * Deliberately the same words the WhatsApp bridge uses. Her screen must not be able to
         * tell which messenger carried a photograph — a person is a person, and so is a picture
         * of one.
         */
        private val KNOWN_MEDIA_KINDS = WhatsAppBridgeProtocol.KNOWN_MEDIA_KINDS

        /**
         * Mirrors the bridge's stderr into logcat. Off by default: the bridge runs at TRACE and
         * its output includes protocol detail about her conversations. Turn it on only while
         * debugging, on a device that is not hers.
         */
        private const val VERBOSE_BRIDGE_LOGGING = false
        private const val INITIAL_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 60000L
        private const val MISSING_IDENTITY_RETRY_MS = 10000L
        private const val REACTION_SEND_TIMEOUT_MS = 30_000L

        /**
         * How long an attachment is given to go up before a forward is called off.
         *
         * Generous, because this is a film on a mobile connection rather than a message: the
         * alternative to waiting is telling her it failed while it is still working.
         */
        private const val MEDIA_UPLOAD_TIMEOUT_MS = 180_000L

        fun bytesToHex(bytes: ByteArray): String {
            val sb = java.lang.StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
