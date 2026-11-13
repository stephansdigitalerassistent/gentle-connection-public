package ch.heuscher.gentleconnect.data

import android.content.Context
import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.data.threema.ThreemaIdentityRepository
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppClient
import ch.heuscher.gentleconnect.voip.VoipCallManager

/**
 * The messenger clients that have to outlive any single screen.
 *
 * A screen closing is not the phone going away. Android puts a backgrounded app's UID in
 * `APP_BACKGROUND` and cuts its network, so [ch.heuscher.gentleconnect.service.MessengerService]
 * runs in the foreground to keep the process alive. If the messenger clients and VoIP registration
 * lived in a ViewModel, destroying the activity would stop the bridges while the service went on
 * telling her that her messengers were connected.
 *
 * Like [AppStores], these belong to the process and survive activity recreation. Each client is
 * created once and reused, preserving all collectors and registrations attached to it.
 */
object Messengers {

    private var threemaIdentityRepository: ThreemaIdentityRepository? = null
    private var threemaClient: ThreemaClient? = null
    private var whatsAppClient: WhatsAppClient? = null
    private var callManager: VoipCallManager? = null
    private var settingsRepository: SettingsRepository? = null

    @Synchronized
    fun threemaIdentity(context: Context): ThreemaIdentityRepository {
        val appContext = context.applicationContext
        threemaIdentityRepository?.let { return it }
        val repo = ThreemaIdentityRepository(appContext)
        threemaIdentityRepository = repo
        return repo
    }

    @Synchronized
    fun threema(context: Context): ThreemaClient {
        val appContext = context.applicationContext
        threemaClient?.let { return it }
        val repo = threemaIdentity(appContext)
        val client = ThreemaClient(appContext, repo)
        threemaClient = client
        return client
    }

    @Synchronized
    fun whatsApp(context: Context): WhatsAppClient {
        val appContext = context.applicationContext
        whatsAppClient?.let { return it }
        val client = WhatsAppClient(appContext)
        whatsAppClient = client
        return client
    }

    @Synchronized
    fun settings(context: Context): SettingsRepository {
        val appContext = context.applicationContext
        settingsRepository?.let { return it }
        val repo = FirestoreSettingsRepository(appContext)
        settingsRepository = repo
        return repo
    }

    @Synchronized
    fun callManager(context: Context): VoipCallManager {
        val appContext = context.applicationContext
        callManager?.let { return it }
        val client = threema(appContext)
        // VoipCallManager must be constructed immediately after the ThreemaClient it belongs to,
        // before startThreemaClientIfProvisioned() is called. This guarantees that its init registers
        // the SELF_MANAGED PhoneAccount with Telecom and attaches its signalling listener to
        // threemaClient.callMessages before the client connects, ensuring incoming call offers (0x60)
        // are never dropped on the floor.
        val manager = VoipCallManager(appContext, client)
        callManager = manager
        return manager
    }

    @Synchronized
    fun stopAll() {
        // The two clients are kept rather than dropped. Both can be started again, and every
        // collector in the app closes over the instance it was given — handing out a new one
        // later would leave those collectors bound to a stopped client, which is the failure the
        // comment in MessengerRuntime.provisionThreema describes: a connection that reports
        // itself healthy while nothing arrives.
        threemaClient?.stop()
        whatsAppClient?.stop()
        // The call manager is different and must be dropped. Its stop() unregisters the
        // PhoneAccount with Telecom, cancels the signalling job and clears
        // GentleConnectionService.activeManager, and nothing puts any of that back. Kept, it
        // would be handed out again as a telephone that can never ring; dropped, the next caller
        // builds a live one.
        callManager?.stop()
        callManager = null
    }
}
