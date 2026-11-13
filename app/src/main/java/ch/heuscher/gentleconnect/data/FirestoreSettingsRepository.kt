package ch.heuscher.gentleconnect.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import ch.heuscher.gentleconnect.model.CaregiverAlerts
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.KnownBluetoothDevice
import ch.heuscher.gentleconnect.model.KnownGroup
import ch.heuscher.gentleconnect.model.KnownPoster
import ch.heuscher.gentleconnect.model.KnownSender
import ch.heuscher.gentleconnect.model.LocatedWith
import ch.heuscher.gentleconnect.model.LocatedWithSummary
import ch.heuscher.gentleconnect.model.PhoneFix
import ch.heuscher.gentleconnect.model.PhoneLocation
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import ch.heuscher.gentleconnect.telecom.UnknownCallers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

/**
 * Real Firestore implementation of SettingsRepository for gentle connection.
 * Connects to Firebase Firestore backend, handles anonymous device authentication,
 * listens for caregiver settings updates, and generates short-lived pairing codes.
 */
class FirestoreSettingsRepository(private val context: Context) : SettingsRepository {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val firestore by lazy {
        Firebase.firestore.apply {
            firestoreSettings = firestoreSettings {
                isPersistenceEnabled = true
            }
        }
    }
    private val auth by lazy { Firebase.auth }

    val deviceId: String by lazy { getOrCreateDeviceId() }

    private val _settings = MutableStateFlow(RemoteSettings())
    override val settings: StateFlow<RemoteSettings> = _settings.asStateFlow()

    /**
     * What was last sent for the starred list, so an unchanged one is not sent again.
     *
     * The favourites flow recomputes on every contacts refresh, and the app refreshes contacts on
     * every onResume. Without this, walking back into the app would be a Firestore write, for
     * ever, for nothing.
     */
    private var lastPublishedStarred: List<Map<String, String>>? = null

    /**
     * What was last published as going wrong, so an unchanged list is not published again.
     *
     * The log emits on every write, including the six kinds of event that never travel, so
     * without this a failed contact sync would cost a Firestore write and wake every caregiver's
     * browser to tell it nothing had changed.
     */
    private var lastPublishedAlerts: List<CaregiverEvent>? = null

    /**
     * What was last sent as the list of groups, so an unchanged one is not sent again.
     *
     * The names are recomputed whenever a member's name resolves or a control message lands, which
     * on a busy morning is many times over for a list that has not changed.
     */
    private var lastPublishedGroups: List<Map<String, String>>? = null

    /**
     * What was last sent as the list of company senders, so an unchanged one is not sent again.
     *
     * Recomputed on every change to her message list, which on a phone that is being used is
     * constantly — and the answer is the same all day.
     */
    private var lastPublishedSenders: List<Map<String, String>>? = null

    /** What was last sent as the list of status posters, for the same reason. */
    private var lastPublishedPosters: List<Map<String, Any>>? = null

    /** What was last sent as the list of Bluetooth devices, so an unchanged list is not sent again. */
    private var lastPublishedBluetoothDevices: List<Map<String, Any>>? = null

    /** Bluetooth neighborhood source for nearby peripheral and proximity sampling. */
    private val bluetoothSource by lazy { BluetoothNeighborhoodSource(context) }

    /** The last resolved proximity attribution summary. */
    private var lastLocatedWith: LocatedWithSummary? = null

    /** Where the phone is, asked once per heartbeat and never in between. */
    private val locationSource by lazy { PhoneLocationSource(context) }

    /**
     * The best position seen since the app started.
     *
     * Kept rather than re-derived because the sources are lossy in opposite directions: a good
     * fix taken while the screen was on is not available again once the screen goes off, and the
     * cheap provider that still answers then knows much less. Folding each new reading into this
     * — see [PhoneLocation.betterOf] — is what stops a phone on a bedside table degrading from a
     * street address to a district over the course of a night.
     */
    private var bestFix: PhoneFix? = null

    init {
        initFirebaseConnection()
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)

        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }

        return id
    }

    /**
     * What this phone calls itself, for the caregiver's device list: "Samsung SM-S916B".
     *
     * The hardware name, not a name anybody chose — see RemoteSettings.deviceName for that. It is
     * what the phone can answer on its own, and on its own it is already the difference between
     * two phones a caregiver can tell apart and two hexadecimal ids they cannot.
     */
    private fun hardwareName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = when {
            model.isEmpty() -> maker
            maker.isEmpty() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "${maker.replaceFirstChar { it.uppercase() }} $model"
        }
        return name.take(60)
    }

    private fun initFirebaseConnection() {
        scope.launch {
            try {
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }

                val currentUid = auth.currentUser?.uid ?: ""
                val deviceDocRef = firestore.collection(COLLECTION_DEVICES).document(deviceId)

                val snap = deviceDocRef.get().await()
                if (!snap.exists()) {
                    val initialDoc = mapOf(
                        "deviceUid" to currentUid,
                        "caregiverUids" to emptyList<String>(),
                        "createdAt" to Timestamp.now(),
                        "lastSeenAt" to Timestamp.now(),
                        "model" to hardwareName()
                    )
                    deviceDocRef.set(initialDoc).await()
                } else {
                    // The model goes up with every heartbeat-of-a-start, not only at creation:
                    // the phones that were already in the list predate this field, and a device
                    // list that names the new phones and numbers the old ones is worse than one
                    // that numbers all of them.
                    deviceDocRef.update(
                        mapOf(
                            "lastSeenAt" to Timestamp.now(),
                            "model" to hardwareName()
                        )
                    ).await()
                }

                // Both branches above wrote lastSeenAt: that is this start's first beat.
                rememberBeatReachedServer()

                // From here on it keeps saying so, so that going quiet means something.
                startHeartbeat(deviceDocRef)

                // Who looks after this phone, from the document the website actually writes it to.
                //
                // The website adds a caregiver with arrayUnion on devices/{id}.caregiverUids —
                // the device document, not the settings document. The app only ever listened to
                // settings/current, so it read the field out of a map that never contains it and
                // the caregiver dialog said "nobody has paired yet" while the phone had in fact
                // been claimed. Two documents, one field, and nothing to make them meet.
                //
                // A listener rather than a one-off read: pairing happens while this dialog is on
                // the screen, and the count changing is how the caregiver learns it worked.
                deviceDocRef.addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        return@addSnapshotListener
                    }
                    val uids = (snapshot.get("caregiverUids") as? List<*>)
                        ?.filterIsInstance<String>()
                        ?: emptyList()
                    _settings.update { it.copy(caregiverUids = uids) }
                }

                // Listen for real-time remote settings changes
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_CURRENT)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            return@addSnapshotListener
                        }
                        if (snapshot != null && snapshot.exists()) {
                            val updated = RemoteSettingsMapper.mapToRemoteSettings(snapshot.data, _settings.value)
                            _settings.value = updated
                            // Written to the device the moment it arrives, because the one part of
                            // the app that needs it cannot wait for this flow: the telephone
                            // service is started by an incoming call, in a process that may have
                            // no settings loaded and no signal. See UnknownCallers.isOn.
                            UnknownCallers.remember(
                                context = context,
                                blocking = updated.blockUnknownCallers,
                                showInCallLog = updated.showBlockedCallsInCallLog,
                                notify = updated.notifyBlockedCalls
                            )
                            ch.heuscher.gentleconnect.telecom.RingerSettings.rememberSilenceTimeout(context, updated.ringSilenceTimeoutSeconds)
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun updateSettings(newSettings: RemoteSettings) {
        _settings.value = newSettings
        scope.launch {
            try {
                val map = RemoteSettingsMapper.remoteSettingsToMap(newSettings)
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_CURRENT)
                    .set(map, SetOptions.merge())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish who is starred, so the website has names to put against its arrows.
     *
     * Its own document rather than a field on `current`, because the two have different owners:
     * the phone writes this one, the caregiver writes `current.favouriteOrder`, and neither ever
     * has to merge with the other. A single document would have the phone's periodic refresh
     * racing the caregiver mid-arrangement.
     *
     * Id and name only. No numbers, no photographs, nobody who is not starred.
     */
    override fun publishStarredContacts(contacts: List<Contact>) {
        val published = contacts.map { mapOf("id" to it.id, "name" to it.name) }
        if (published == lastPublishedStarred) return
        lastPublishedStarred = published

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_STARRED)
                    .set(mapOf("contacts" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish which groups exist, so the website has names to put its switches against.
     *
     * Its own document rather than a field on `current`, for the reason the starred list is one:
     * the phone writes this and the caregiver writes `current.hiddenGroups`, and neither has to
     * merge with the other. A single document would have the phone's refresh racing a caregiver
     * mid-decision.
     *
     * Written whole, empty list included — see [SettingsRepository.publishGroups].
     */
    override fun publishGroups(groups: List<KnownGroup>) {
        val published = groups.map { mapOf("key" to it.key, "name" to it.name) }
        if (published == lastPublishedGroups) return
        lastPublishedGroups = published

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_GROUPS)
                    .set(mapOf("groups" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish which senders the phone has filed as companies, so the website has names to put its
     * switches against.
     *
     * Its own document, for the reason the groups list has one: the phone writes this and the
     * caregiver writes `current.sendersAsPeople`, so neither has to merge with the other.
     *
     * Written whole, empty list included — see [SettingsRepository.publishSenders].
     */
    override fun publishSenders(senders: List<KnownSender>) {
        val published = senders.map { mapOf("key" to it.key, "name" to it.name) }
        if (published == lastPublishedSenders) return
        lastPublishedSenders = published

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_SENDERS)
                    .set(mapOf("senders" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish whose statuses she receives, so the website has names to put its switches against.
     *
     * Its own document, for the reason the groups list has one: the phone writes this and the
     * caregiver writes `current.hiddenStatusPosters`.
     */
    override fun publishStatusPosters(posters: List<KnownPoster>) {
        val published = posters.map { mapOf("key" to it.key, "name" to it.name, "count" to it.count) }
        if (published == lastPublishedPosters) return
        lastPublishedPosters = published

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_STATUS_POSTERS)
                    .set(mapOf("posters" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish what has gone wrong, so a caregiver who is not in the room can find out.
     *
     * Its own subcollection rather than a document under `settings`, and read-only to caregivers
     * in the rules, because this is the phone's account of what happened to it. `settings` is a
     * place both sides write; a fault log that its readers can edit is not a fault log.
     *
     * Written whole rather than merged, and the empty list is written too — that write is how a
     * fault that has cleared stops being displayed, and skipping it as "nothing to say" would
     * leave last week's failure on the website for ever.
     */
    override fun publishCaregiverAlerts(alerts: List<CaregiverEvent>) {
        if (!CaregiverAlerts.changed(lastPublishedAlerts, alerts)) return
        lastPublishedAlerts = alerts

        val published = alerts.map { alert ->
            mapOf(
                "kind" to alert.kind.name,
                "at" to alert.at,
                "subject" to alert.subject,
                "count" to alert.count,
                "total" to alert.total,
                "occurrences" to alert.occurrences
            )
        }

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(COLLECTION_STATUS)
                    .document(DOC_CURRENT)
                    .set(mapOf("alerts" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Publish which Bluetooth devices are paired or regularly seen nearby, so the caregiver
     * can attribute them to a place, person or own device on the portal.
     */
    override fun publishBluetoothDevices(devices: List<KnownBluetoothDevice>) {
        val published = devices.map {
            mapOf(
                "key" to it.key,
                "name" to it.name,
                "bonded" to it.bonded,
                "sightings" to it.sightings,
                "lastSeenAt" to it.lastSeenAt
            )
        }
        if (published == lastPublishedBluetoothDevices) return
        lastPublishedBluetoothDevices = published

        scope.launch {
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .collection(DOC_SETTINGS)
                    .document(DOC_BLUETOOTH_DEVICES)
                    .set(mapOf("devices" to published, "updatedAt" to Timestamp.now()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Say "still here", and where here is, for as long as this app is running.
     *
     * The heartbeat is what makes the phone's own absence legible. Every other fault is reported
     * by the app; this one is the app not reporting, and the website reads the gap — see
     * [ch.heuscher.gentleconnect.model.CaregiverAlerts.silence].
     *
     * Deliberately something this process owns rather than a scheduled job. A `WorkManager` task
     * would survive the app being killed, and surviving is precisely the wrong behaviour here: a
     * launcher whose process is gone is not working, and a heartbeat that kept arriving from a
     * background worker would report a dead app as healthy. What this measures is "the app that
     * answers her phone is running and can reach Firestore", which is the thing a caregiver wants
     * to know, and the only honest way to measure it is from inside it.
     *
     * It is an alarm rather than a loop with a `delay` in it, and that is not a detail: a `delay`
     * measures time the phone spends awake, which on hers is an eighth of the day. See
     * [HeartbeatAlarm], where the measurements are.
     *
     * Failures are swallowed on purpose. The next beat is in fifteen minutes and the gap is
     * itself the signal, so there is nothing useful for a retry to do that waiting does not.
     */
    private fun startHeartbeat(deviceDocRef: DocumentReference) {
        HeartbeatAlarm(
            context = context,
            intervalMs = CaregiverAlerts.HEARTBEAT_INTERVAL_MS,
            scope = scope
        ) {
            try {
                deviceDocRef.update(beat()).await()
                rememberBeatReachedServer()
            } catch (e: Exception) {
                // See above: silence is the report.
            }
        }.start()
    }

    /**
     * Only gentle device reads this (DeviceLinkProvider's `status`): when a beat last reached the
     * server, as seen from inside. Without it the first beat after an update would be fifteen
     * minutes away and every update would look like a stalled app. On a phone without gentle
     * device, unread.
     */
    private fun rememberBeatReachedServer() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_BEAT_OK_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * One beat: that the phone is running, and where it was when it said so.
     *
     * The position rides along on a write that was happening anyway. That is not a saving for its
     * own sake — it is what keeps this feature from having a cost of its own to justify. There is
     * no location subscription, no extra document, no second timer, and on a phone where the
     * permission was never granted, no difference at all.
     *
     * The two halves are deliberately stamped differently. `lastSeenAt` is *now*, because it is a
     * statement about this write. The position carries the moment it was actually measured, which
     * on a phone that has been in a pocket with the screen off may be hours earlier — see
     * [PhoneLocationSource]. Stamping both with now would turn "where it was this morning" into
     * "where it is", which is the one mistake in this feature that sends somebody to the wrong
     * address.
     *
     * A fix is only ever added, never cleared. A phone that stops being able to place itself —
     * permission withdrawn, location switched off, moved somewhere with no signal — leaves the
     * last position it knew standing, with its own timestamp saying how old it is. That is the
     * honest reading of "where the phone was", and it is the reading somebody looking for a phone
     * that has gone quiet actually needs.
     */
    private suspend fun beat(): Map<String, Any> {
        val beat = mutableMapOf<String, Any>("lastSeenAt" to Timestamp.now())

        bestFix = PhoneLocation.betterOf(bestFix, locationSource.current())
        val fix = bestFix
        if (fix != null && PhoneLocation.shouldPublish(fix, _settings.value.caregiverUids.size)) {
            beat["lastLocation"] = buildMap<String, Any> {
                put("latitude", fix.latitude)
                put("longitude", fix.longitude)
                put("at", fix.at)
                // Omitted rather than sent as a sentinel when the provider stated nothing. The
                // website says "auf etwa N m genau" only when there is an N to say.
                fix.accuracyMetres?.let { put("accuracyMetres", it) }
            }
        }

        val sightings = bluetoothSource.sampleNow()
        val known = LocatedWith.knownDevices(
            devices = bluetoothSource.knownHistory(),
            attributions = _settings.value.bluetoothAttributions,
            now = System.currentTimeMillis()
        )
        if (_settings.value.caregiverUids.isNotEmpty()) {
            publishBluetoothDevices(known)
        }

        val currentLocated = LocatedWith.resolve(
            sightings = sightings,
            attributions = _settings.value.bluetoothAttributions,
            at = System.currentTimeMillis(),
            knownDevices = known
        )
        lastLocatedWith = LocatedWith.merge(lastLocatedWith, currentLocated)

        val located = lastLocatedWith
        if (located != null && _settings.value.caregiverUids.isNotEmpty()) {
            beat["lastLocatedWith"] = buildMap<String, Any> {
                located.place?.let {
                    put("place", it)
                    put("placeAt", located.placeAt)
                }
                if (located.people.isNotEmpty()) put("people", located.people)
                if (located.ownDevices.isNotEmpty()) put("ownDevices", located.ownDevices)
                put("at", located.at)
            }
        }

        return beat
    }

    override fun pairWithCode(inputCode: String): Boolean {
        if (_settings.value.pairingCode.equals(inputCode.trim(), ignoreCase = true)) {
            _settings.update { it.copy(isPaired = true) }
            return true
        }
        return false
    }

    override fun setFavorites(contacts: List<Contact>) {
        updateSettings(_settings.value.copy(favoriteContacts = contacts))
    }

    override fun setTextSizeMultiplier(multiplier: Float) {
        updateSettings(_settings.value.copy(textSizeMultiplier = multiplier))
    }

    override fun setPhotoSizeMultiplier(multiplier: Float) {
        updateSettings(_settings.value.copy(photoSizeMultiplier = multiplier))
    }

    override fun setSpeechRate(rate: Float) {
        updateSettings(_settings.value.copy(speechRate = Speech.sane(rate)))
    }

    override fun setDarkMode(enabled: Boolean) {
        updateSettings(_settings.value.copy(isDarkMode = enabled))
    }

    override suspend fun generateTemporaryPairingCode(): String {
        // A code that is already somebody else's must never be shown. The rules refuse to let this
        // phone write over it, but the caregiver who typed it would still find it — pointing at the
        // other phone — and link to that one instead, then fill it with her contacts. So a refusal
        // means drawing another code. Collisions among 2 billion codes are rare; squatting is not
        // impossible, which is why this is a loop and not a shrug.
        //
        // Signed in first, briefly: a write without a session is refused with the same
        // PERMISSION_DENIED as a taken code, and would burn every attempt on a misreading.
        FirebaseAuthGate.awaitSignedIn(SIGN_IN_WAIT_MS)
        var code = RemoteSettingsMapper.generatePairingCode()
        for (attempt in 1..PAIRING_CODE_ATTEMPTS) {
            if (publishPairingCode(code) != PairingPublish.TAKEN) break
            Log.w(TAG, "pairing code refused; drawing another (attempt $attempt)")
            if (attempt < PAIRING_CODE_ATTEMPTS) code = RemoteSettingsMapper.generatePairingCode()
        }
        _settings.update { it.copy(pairingCode = code) }
        return code
    }

    private enum class PairingPublish { PUBLISHED, TAKEN, PENDING }

    /**
     * Writes [code] for this phone, waiting a few seconds at most for the answer.
     *
     * Not waited for indefinitely. Firestore has offline persistence on, so an unreachable backend
     * does not fail a write — it queues it, and the Task simply never completes. Awaiting it
     * without a limit suspended forever and the caregiver dialog sat there with an empty code box.
     * A write still queued when the wait ends goes on in the background, and the code is shown
     * anyway: if it never lands, pairing fails at the portal, which is visible and diagnosable.
     *
     * Waited for briefly all the same, because that is the only way to hear "this code belongs to
     * another phone" ([PairingPublish.TAKEN]) before handing it to a caregiver.
     */
    private suspend fun publishPairingCode(code: String): PairingPublish {
        val expiresAtMillis = System.currentTimeMillis() + RemoteSettingsMapper.PAIRING_CODE_VALIDITY_MS
        // The failure is caught inside the async, never thrown out of it: this scope is not a
        // supervisor, and a child failing with an exception would cancel the heartbeat and every
        // listener this repository runs.
        val write = scope.async {
            runCatching {
                val pairingData = mapOf(
                    "deviceId" to deviceId,
                    "expiresAt" to Timestamp(Date(expiresAtMillis)),
                    "createdAt" to Timestamp.now()
                )
                firestore.collection(COLLECTION_PAIRING_CODES)
                    .document(code)
                    .set(pairingData)
                    .await()
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .update("pairingCode", code)
                    .await()
            }
        }
        val outcome = withTimeoutOrNull(PAIRING_PUBLISH_WAIT_MS) { write.await() }
            ?: return PairingPublish.PENDING
        val error = outcome.exceptionOrNull() ?: return PairingPublish.PUBLISHED
        if ((error as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            return PairingPublish.TAKEN
        }
        Log.w(TAG, "publishing the pairing code failed", error)
        return PairingPublish.PENDING
    }

    /**
     * Takes away every caregiver's access to this phone.
     *
     * The phone is the one party the rules let shrink this list; a caregiver can only add
     * themselves, or leave. The pairing code that is live right now goes too, or whoever removed
     * here could walk straight back in with it in the minutes it has left.
     */
    override fun removeAllCaregivers() {
        val liveCode = _settings.value.pairingCode
        scope.launch {
            if (!FirebaseAuthGate.awaitSignedIn()) return@launch
            try {
                firestore.collection(COLLECTION_DEVICES)
                    .document(deviceId)
                    .update("caregiverUids", emptyList<String>())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "removing the caregivers failed", e)
            }
            if (liveCode.isNotBlank()) {
                try {
                    firestore.collection(COLLECTION_PAIRING_CODES).document(liveCode).delete().await()
                } catch (e: Exception) {
                    // Expected when the code was never published or belongs to nobody; it
                    // expires on its own within ten minutes either way.
                    Log.i(TAG, "live pairing code not deleted: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "gentle_connection_prefs"
        private const val KEY_DEVICE_ID = "device_id"

        /** Wall time of the last heartbeat Firestore accepted; read by DeviceLinkProvider. */
        internal const val KEY_LAST_BEAT_OK_MS = "last_beat_ok_ms"
        private const val COLLECTION_DEVICES = "devices"
        private const val COLLECTION_PAIRING_CODES = "pairing_codes"
        private const val TAG = "FirestoreSettings"

        /** How many codes to draw before giving up on finding one that is not taken. */
        private const val PAIRING_CODE_ATTEMPTS = 5

        /** How long a pairing code's write is waited for. See publishPairingCode. */
        private const val PAIRING_PUBLISH_WAIT_MS = 6_000L

        /** How long a pairing code waits for the phone's Firebase session before trying anyway. */
        private const val SIGN_IN_WAIT_MS = 3_000L
        private const val DOC_SETTINGS = "settings"
        private const val DOC_CURRENT = "current"

        /** Who is starred on the phone. Written here, arranged on the website. */
        private const val DOC_STARRED = "starred"

        /** Which groups exist. Written here, switched on and off on the website. */
        private const val DOC_GROUPS = "groups"

        /** Which senders are filed as companies. Written here, corrected on the website. */
        private const val DOC_SENDERS = "senders"

        /** Whose statuses arrive. Written here, switched on and off on the website. */
        private const val DOC_STATUS_POSTERS = "statusPosters"

        /** Which Bluetooth devices are known nearby or paired. Attributed on the website. */
        private const val DOC_BLUETOOTH_DEVICES = "bluetoothDevices"

        /** What has gone wrong. Written here, read on the website, never written back. */
        private const val COLLECTION_STATUS = "status"
    }
}
