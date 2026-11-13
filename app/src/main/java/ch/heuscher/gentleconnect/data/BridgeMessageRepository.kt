package ch.heuscher.gentleconnect.data

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import ch.heuscher.gentleconnect.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository listening to Firestore subcollection `devices/{deviceId}/messages`.
 * Manages bridge messages coming from the server-side Threema bridge.
 */
class BridgeMessageRepository(
    private val context: Context,
    private val deviceIdProvider: () -> String = { DeviceIdProvider.getDeviceId(context) }
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val firestore by lazy { Firebase.firestore }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private var registration: ListenerRegistration? = null

    init {
        // Attach only once the device is signed in — see FirebaseAuthGate for why a listener
        // attached too early goes permanently silent instead of failing loudly.
        FirebaseAuthGate.whenSignedIn(
            onSignedOut = {
                registration?.remove()
                registration = null
            }
        ) {
            attachListener()
        }
    }

    private fun attachListener() {
        val deviceId = deviceIdProvider()
        try {
            registration?.remove()
            registration = firestore.collection("devices")
                .document(deviceId)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) {
                        return@addSnapshotListener
                    }
                    // The bridge stores both directions so the caregiver portal can show a
                    // full history, but her home screen must only ever show what came IN.
                    // Without this filter her own replies come back as unread messages
                    // from herself.
                    val list = snapshot.documents
                        .filter { (it.data?.get("direction") as? String) != "out" }
                        .mapNotNull { doc -> BridgeMapper.mapToMessage(doc.id, doc.data) }
                    _messages.value = list
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun markAsRead(messageId: String) {
        val deviceId = deviceIdProvider()
        scope.launch {
            try {
                firestore.collection("devices")
                    .document(deviceId)
                    .collection("messages")
                    .document(messageId)
                    .update("isRead", true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateReaction(messageId: String, emoji: String) {
        val deviceId = deviceIdProvider()
        scope.launch {
            try {
                firestore.collection("devices")
                    .document(deviceId)
                    .collection("messages")
                    .document(messageId)
                    .update(
                        mapOf(
                            "isRead" to true,
                            "reaction" to emoji
                        )
                    )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
