package ch.heuscher.gentleconnect.data

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import ch.heuscher.gentleconnect.model.Partner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository listening to Firestore subcollection `devices/{deviceId}/partners`.
 * Exposes a StateFlow of communication partners filtered to `canInitiate == true`.
 */
class PartnerRepository(
    private val context: Context,
    private val deviceIdProvider: () -> String = { DeviceIdProvider.getDeviceId(context) }
) {
    private val firestore by lazy { Firebase.firestore }

    private val _partners = MutableStateFlow<List<Partner>>(emptyList())
    val partners: StateFlow<List<Partner>> = _partners.asStateFlow()

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
                .collection("partners")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) {
                        return@addSnapshotListener
                    }
                    val list = snapshot.documents.mapNotNull { doc ->
                        BridgeMapper.mapToPartner(doc.id, doc.data)
                    }.filter { it.canInitiate }
                    _partners.value = list
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
