package ch.heuscher.gentleconnect.data.whatsapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.math.min

/** Where the WhatsApp session stands, as far as this phone can tell. */
enum class WhatsAppLinkState {
    /** No session has ever been established, or the last one was logged out. */
    NOT_LINKED,

    /** Linked, but the socket is not up. Ordinary — this is most of a night. */
    LINKED_OFFLINE,

    /** Linked and connected. Messages can arrive and can leave. */
    LIVE
}

/**
 * Drives the WhatsApp bridge subprocess.
 *
 * Same shape as [ch.heuscher.gentleconnect.data.threema.ThreemaClient] and for the same reasons:
 * the binary ships in the APK as a `.so`, is extracted into the app's native library directory —
 * the only place Android still permits an app to execute a file it shipped — and is spoken to in
 * one JSON object per line. Everything that decides anything is in [WhatsAppBridgeProtocol],
 * which is pure and tested; this starts the process, keeps it alive and passes lines across.
 *
 * Nothing here is verifiable in CI. What CI proves is that it compiles and that the protocol it
 * speaks is the one the bridge speaks; that the process actually starts was proved by running the
 * binary on her phone, and everything past linking can only be proved the same way.
 */
class WhatsAppClient(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val _linkState = MutableStateFlow(WhatsAppLinkState.NOT_LINKED)
    val linkState: StateFlow<WhatsAppLinkState> = _linkState.asStateFlow()

    /**
     * Everything the bridge says, for whoever is interested.
     *
     * A shared flow with replay 0: a message is delivered to the collectors that exist when it
     * arrives, and the durable copy is the database, not this. Extra buffer so a slow collector
     * cannot block the reader thread and wedge the subprocess behind a full pipe.
     */
    private val _events = MutableSharedFlow<WhatsAppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WhatsAppEvent> = _events.asSharedFlow()

    /**
     * Every group she is in, by JID: what it is called and how many people are in it.
     *
     * A state, not an event. The bridge names her groups once, a second after it connects, and
     * the bridge is started by the messenger service — usually long before her screen exists. As
     * events with no replay the names went to nobody, and every WhatsApp group on her screen was
     * "Monika and 6 others" until the next reconnect (observed on an Android device). Kept on
     * disk too, so a phone that has just started has names before the bridge is back.
     */
    private val _groups = MutableStateFlow<Map<String, WhatsAppEvent.Group>>(emptyMap())
    val groups: StateFlow<Map<String, WhatsAppEvent.Group>> = _groups.asStateFlow()

    /**
     * Where [groups] is kept: names, head counts, and who is in them.
     *
     * The membership used to be left out on purpose. It is kept now because the phone *draws* a
     * group as its people, and without a member list it drew everybody who had ever written —
     * including people who have long since left. She saw them: "i saw olf names not belonging to
     * group chat" (2026-09-20). Addresses of people she is already in a group with, on her own
     * phone, in the same file as their names.
     */
    private val groupsFile: File
        get() = File(context.filesDir, "whatsapp").apply { mkdirs() }.resolve("groups.json")

    init {
        scope.launch {
            val saved = readGroups()
            // Whatever the bridge has said since start-up is newer than the file.
            if (saved.isNotEmpty()) _groups.update { saved + it }
        }
    }

    /**
     * Whether a session exists, as the bridge itself reports it.
     *
     * The bridge is the only thing that knows. This used to be inferred from the session database
     * existing on disk, and that was wrong on the phone in the most visible way possible: the
     * bridge *creates* that file the moment it starts, so an app that had never been linked to
     * anything reported "Linked, but not connected" and offered a caregiver an Unlink button.
     * The file existing means the bridge has run, not that anybody has linked.
     */
    @Volatile
    private var linked = false

    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var supervisor: Job? = null

    /** The DNS servers the running bridge was last given, at start or since. */
    @Volatile
    private var suppliedDns: List<String> = emptyList()

    /**
     * Hands the bridge the servers of whatever network the phone is now on.
     *
     * The bridge is told its DNS servers because it cannot ask Android (see [dnsServers]), and a
     * list read once at start is only right until she leaves the flat. On 2026-09-17 the phone
     * moved from Wi-Fi to mobile data, the bridge went on asking the router, and WhatsApp stayed
     * offline — silently, for as long as the app kept running.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val servers = serversOf(linkProperties)
            // A network mid-switch can report none. Keeping the last ones is better than nothing.
            if (servers.isEmpty() || servers == suppliedDns) return
            if (send(WhatsAppBridgeProtocol.dnsCommand(servers))) {
                suppliedDns = servers
                Log.i(TAG, "dns servers changed, " + servers.size + " sent to bridge")
            }
        }
    }

    @Volatile
    private var networkCallbackRegistered = false

    /** The session database. App-private, and never backed up — see android:allowBackup="false". */
    private val storeFile: File
        get() = File(context.filesDir, "whatsapp").apply { mkdirs() }.resolve("session.db")

    private val binaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * Whether a session exists, according to the bridge.
     *
     * False until the bridge has said otherwise, which it does within a second of starting. That
     * is the honest default: "not linked" is what an app with no answer yet should show, and it
     * corrects itself the moment the first line arrives.
     */
    fun hasSession(): Boolean = linked

    /**
     * Start the bridge, and keep restarting it for as long as this app is running.
     *
     * Idempotent: called on every resume, does nothing if a supervisor is already running.
     */
    fun start() {
        if (supervisor?.isActive == true) return
        supervisor = scope.launch { supervise() }
        watchNetwork()
    }

    fun stop() {
        supervisor?.cancel()
        supervisor = null
        unwatchNetwork()
        closeProcess()
        _linkState.value = if (linked) WhatsAppLinkState.LINKED_OFFLINE else WhatsAppLinkState.NOT_LINKED
    }

    /**
     * Ask for the code that links this phone.
     *
     * The number is hers, and it goes down the pipe rather than into argv: a process's command
     * line is readable through /proc by anything on the device.
     */
    fun requestPairingCode(phoneNumber: String): Boolean =
        send(WhatsAppBridgeProtocol.pairCommand(phoneNumber))

    /** Send a message as her. Returns false when there is no bridge to send it with. */
    fun sendText(toJid: String, text: String, messageId: String? = null, ref: String? = null): Boolean =
        send(WhatsAppBridgeProtocol.sendCommand(toJid, text, messageId, ref))

    /**
     * Send a photograph, film or voice message she was sent on to somebody else.
     *
     * Returns false only when there is no bridge to ask. Whether it actually went is reported
     * afterwards the way every other send is — the upload takes seconds, and the answer cannot
     * be waited for here.
     */
    fun sendMedia(
        toJid: String,
        path: String,
        mime: String,
        messageId: String? = null,
        ref: String? = null
    ): Boolean =
        send(WhatsAppBridgeProtocol.sendMediaCommand(toJid, path, mime, messageId, ref))

    fun sendReaction(
        toJid: String,
        emoji: String,
        messageId: String,
        fromMe: Boolean,
        participant: String? = null,
        ref: String? = null
    ): Boolean =
        send(WhatsAppBridgeProtocol.reactCommand(toJid, emoji, messageId, fromMe, participant, ref))

    /**
     * Turn a ringing WhatsApp call away. Returns false when there is no bridge to ask; whether it
     * worked arrives afterwards as the call ending, like every other end of a call.
     */
    fun rejectCall(callId: String): Boolean =
        send(WhatsAppBridgeProtocol.rejectCallCommand(callId))

    fun unlink() {
        send(WhatsAppBridgeProtocol.logoutCommand())
    }

    /** Request a profile picture from WhatsApp for a JID or phone number. */
    fun requestAvatar(jid: String): Boolean =
        send(WhatsAppBridgeProtocol.avatarCommand(jid))

    private fun send(line: String): Boolean {
        val target = writer ?: return false
        return try {
            target.println(line)
            !target.checkError()
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun supervise() {
        var backoffMs = MIN_BACKOFF_MS

        while (scope.isActive) {
            val binary = binaryFile
            if (!binary.exists()) {
                // Nothing to restart into. Reported once and then left alone: retrying a missing
                // file every few seconds for the life of the process helps nobody.
                Log.w(TAG, "no bridge binary; WhatsApp is not available on this build")
                return
            }

            try {
                runBridge(binary)
                backoffMs = MIN_BACKOFF_MS
            } catch (e: Exception) {
                Log.w(TAG, "bridge stopped: ${e.javaClass.simpleName}")
            }

            _linkState.value =
                if (linked) WhatsAppLinkState.LINKED_OFFLINE else WhatsAppLinkState.NOT_LINKED

            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun runBridge(binary: File) = withContext(Dispatchers.IO) {
        // Android resolves names through netd, which a cgo-free Go binary cannot reach: it looks
        // for /etc/resolv.conf, finds nothing, falls back to localhost and fails every lookup.
        // This is the public way to find out what this network's servers actually are, and it is
        // better than a hardcoded public resolver — it works behind a router that answers for
        // local names and on a network that blocks outbound port 53.
        val command = buildList {
            add(binary.absolutePath)
            add("--store"); add(storeFile.absolutePath)
            val dns = dnsServers()
            suppliedDns = dns
            if (dns.isNotEmpty()) {
                add("--dns"); add(dns.joinToString(","))
            }
        }

        Log.i(TAG, "starting bridge, dns servers supplied: " + command.contains("--dns"))
        val proc = ProcessBuilder(command).start()
        process = proc
        writer = PrintWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8), true)

        val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
        scope.launch(Dispatchers.IO) {
            // The bridge's own diagnostic notes, and nothing else: whatsmeow's logger is
            // waLog.Noop precisely because it prints message text and JIDs. What comes through
            // here is protocol state — connected, disconnected, pairing issued, pair success —
            // which is the trace needed to tell "the code expired" apart from "the handshake
            // failed", and which was invisible while this stream was thrown away.
            runCatching {
                var errLine: String?
                while (errReader.readLine().also { errLine = it } != null) {
                    Log.i(TAG, errLine.orEmpty())
                }
            }
        }

        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val event = WhatsAppBridgeProtocol.parse(line)
            if (event == null) {
                // Named, never quoted. An unparsed line is usually a message, and a message
                // belongs on her screen rather than in logcat.
                Log.i(TAG, "unparsed line from bridge, length " + (line?.length ?: 0))
                continue
            }
            // The kind only. Incoming carries her correspondents and their words; Failure
            // carries a protocol stage, which is the thing worth reading.
            when (event) {
                is WhatsAppEvent.Failure ->
                    Log.i(TAG, "event Failure at " + event.stage + ": " + event.message)
                is WhatsAppEvent.Incoming -> Log.i(TAG, "event Incoming, group=" + event.isGroup)
                else -> Log.i(TAG, "event " + event::class.simpleName)
            }
            applyState(event)
            // emit, not tryEmit. A history import is thousands of messages in a burst, and
            // tryEmit drops silently once the buffer is full — which would lose most of an
            // import and look like the import having half worked. Suspending here applies
            // backpressure to the reader instead, which is exactly what a pipe is for.
            _events.emit(event)
        }

        proc.waitFor()
        closeProcess()
    }

    /**
     * Keeps [linkState] in step with what the bridge reports.
     *
     * Every branch guards the same mistake in a different place: never claim to be linked on the
     * strength of something that is also true when unlinked. An unlinked bridge still opens a
     * socket, still disconnects, and still creates its database — so connecting and disconnecting
     * say nothing about whether anybody has linked this phone. Only [WhatsAppEvent.State],
     * [WhatsAppEvent.Paired] and [WhatsAppEvent.LoggedOut] carry that, and only they move [linked].
     */
    private fun applyState(event: WhatsAppEvent) {
        when (event) {
            is WhatsAppEvent.State -> {
                linked = event.linked
                _linkState.value =
                    if (linked) WhatsAppLinkState.LINKED_OFFLINE else WhatsAppLinkState.NOT_LINKED
            }

            is WhatsAppEvent.Paired -> {
                linked = true
                _linkState.value = WhatsAppLinkState.LIVE
            }

            // A socket, not a session. An unlinked bridge connects too, and calling that LIVE is
            // how a caregiver is told everything is fine while nothing can arrive.
            is WhatsAppEvent.Connected ->
                _linkState.value =
                    if (linked) WhatsAppLinkState.LIVE else WhatsAppLinkState.NOT_LINKED

            // Ordinary, and equally uninformative about linkage.
            is WhatsAppEvent.Disconnected ->
                _linkState.value =
                    if (linked) WhatsAppLinkState.LINKED_OFFLINE else WhatsAppLinkState.NOT_LINKED

            is WhatsAppEvent.LoggedOut -> {
                linked = false
                _linkState.value = WhatsAppLinkState.NOT_LINKED
            }

            is WhatsAppEvent.Group -> {
                val updated = _groups.updateAndGet { it + (event.jid to event) }
                writeGroups(updated)
            }

            else -> Unit
        }
    }

    /**
     * The DNS servers this network uses, which the bridge cannot find out for itself.
     *
     * The first version of this asked only the active network, and on the phone it came back
     * empty every single time — so the bridge was started without `--dns`, fell back to Go's own
     * resolver, and failed every connection against `[::1]:53`. Nothing worked, and because the
     * bridge's output was being thrown away, nothing said so. The whole linking saga was this.
     *
     * So every network is asked, not just the active one, and what was found is logged. A phone
     * with a VPN, a work profile, or a tunnel — this one has a reverse SSH tunnel to it — can
     * easily have an active network whose LinkProperties carry no servers at all while another
     * network does.
     */
    private fun dnsServers(): List<String> = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork

        // The active network's own servers when it has any, and only those. The resolver in the
        // bridge rotates through every server it is given, and a UDP "dial" to one that cannot be
        // reached still succeeds — so a stale server from another network does not get skipped,
        // it costs a lookup its whole timeout.
        val activeServers = active
            ?.let { runCatching { manager?.getLinkProperties(it) }.getOrNull() }
            ?.let { serversOf(it) }
            .orEmpty()

        val networks = buildList {
            active?.let { add(it) }
            manager?.allNetworks?.forEach { add(it) }
        }
        val servers = activeServers.ifEmpty {
            networks.asSequence()
                .mapNotNull { runCatching { manager?.getLinkProperties(it) }.getOrNull() }
                .flatMap { serversOf(it).asSequence() }
                .distinct()
                .toList()
        }

        // Count only. A DNS server address is not hers, but the number is the whole diagnosis:
        // zero means the bridge is about to fail every connection for a reason nothing else
        // would report.
        Log.i(TAG, "dns servers found: " + servers.size + " across " + networks.size + " networks")
        servers
    } catch (e: Exception) {
        Log.i(TAG, "dns lookup failed: " + e.javaClass.simpleName)
        emptyList()
    }

    private fun serversOf(linkProperties: LinkProperties): List<String> =
        linkProperties.dnsServers
            .mapNotNull { it.hostAddress }
            .filter { it.isNotBlank() }
            .distinct()

    private fun watchNetwork() {
        if (networkCallbackRegistered) return
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { Log.i(TAG, "network watch failed: " + it.javaClass.simpleName) }
    }

    private fun unwatchNetwork() {
        if (!networkCallbackRegistered) return
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun closeProcess() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
    }

    private fun readGroups(): Map<String, WhatsAppEvent.Group> = runCatching {
        val file = groupsFile
        if (!file.exists()) return@runCatching emptyMap()
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { jid ->
            val entry = json.getJSONObject(jid)
            WhatsAppEvent.Group(
                jid = jid,
                name = entry.optString("name"),
                participants = entry.optInt("participants", 0),
                members = entry.optJSONArray("members").let { saved ->
                    if (saved == null) emptyList()
                    else (0 until saved.length()).mapNotNull { index ->
                        saved.optString(index).trim().takeIf { it.isNotEmpty() }
                    }
                }
            )
        }
    }.getOrElse {
        Log.w(TAG, "group names unreadable, starting without them: " + it.javaClass.simpleName)
        emptyMap()
    }

    private fun writeGroups(groups: Map<String, WhatsAppEvent.Group>) {
        runCatching {
            val json = JSONObject()
            groups.forEach { (jid, group) ->
                val entry = JSONObject()
                    .put("name", group.name)
                    .put("participants", group.participants)
                    .put("members", JSONArray(group.members))
                json.put(jid, entry)
            }
            val target = groupsFile
            val partial = File(target.parentFile, target.name + ".tmp")
            partial.writeText(json.toString())
            if (!partial.renameTo(target)) Log.w(TAG, "group names not saved")
        }.onFailure { Log.w(TAG, "group names not saved: " + it.javaClass.simpleName) }
    }

    companion object {
        private const val TAG = "WhatsAppClient"

        /** Shipped as a library so the installer extracts it somewhere executable. */
        private const val BINARY_NAME = "libwhatsapp_bridge.so"

        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
