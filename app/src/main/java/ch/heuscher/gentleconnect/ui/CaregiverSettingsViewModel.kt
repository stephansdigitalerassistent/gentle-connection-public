package ch.heuscher.gentleconnect.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.ContactSyncPolicy
import ch.heuscher.gentleconnect.data.MessageCatalog
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.MessengerRuntime
import ch.heuscher.gentleconnect.data.SettingsRepository
import ch.heuscher.gentleconnect.data.mail.MailAccountRepository
import ch.heuscher.gentleconnect.data.mail.MailAllowlistRepository
import ch.heuscher.gentleconnect.data.threema.ThreemaAccountTransfer
import ch.heuscher.gentleconnect.data.threema.ThreemaBackupExporter
import ch.heuscher.gentleconnect.data.threema.ThreemaBackupImporter
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.data.threema.ThreemaExportResult
import ch.heuscher.gentleconnect.data.threema.ThreemaIdentityRepository
import ch.heuscher.gentleconnect.data.threema.ThreemaImportResult
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppLinkState
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.service.MailSyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The caregiver's hidden screen: linking the messengers, moving a Threema account, her mail
 * account and its allowlist, pairing caregivers, and the log of what went wrong.
 *
 * None of it is ever on her screen, and none of it is needed by anything that is, which is why it
 * is its own view model rather than a third of the home screen's. The messenger state itself
 * belongs to [MessengerRuntime]; this only shows it and passes on what the caregiver asks for.
 */
class CaregiverSettingsViewModel(
    /** The application context, for the repositories that are built on demand here. */
    private val app: Context,
    private val remoteSettingsRepository: SettingsRepository,
    private val catalog: MessageCatalog,
    caregiverEvents: Flow<List<CaregiverEvent>>
) : ViewModel() {

    val remoteSettings: StateFlow<RemoteSettings> = remoteSettingsRepository.settings

    /**
     * What did not work, newest first — for the caregiver's dialog, never for her. Read off the log
     * on disk, so the faults that happened while nobody was looking are here when somebody looks.
     */
    val caregiverEvents: StateFlow<List<CaregiverEvent>> =
        caregiverEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var threemaIdentityRepository: ThreemaIdentityRepository? = null

    /**
     * Where the WhatsApp link stands. Caregiver-facing only.
     *
     * She never sees this. It is a service name, and a service name is the one thing her screen
     * does not carry — see the caregiver dialog, which is the only place it is shown.
     */
    val whatsAppLinkState: StateFlow<WhatsAppLinkState> = MessengerRuntime.whatsAppLinkState

    /** The code a caregiver types into WhatsApp to link this phone. See [MessengerRuntime.whatsAppPairingCode]. */
    val whatsAppPairingCode: StateFlow<String?> = MessengerRuntime.whatsAppPairingCode

    /** What went wrong with WhatsApp, for the caregiver dialog. See [MessengerRuntime.whatsAppError]. */
    val whatsAppError: StateFlow<String?> = MessengerRuntime.whatsAppError

    /**
     * Whether the embedded Threema client is connected. Caregiver-facing only — she never sees
     * a service name, and a connection state is a service name with extra steps.
     */
    val threemaConnectionState: StateFlow<ThreemaConnectionState> =
        MessengerRuntime.threemaConnectionState

    private val _allowlistAddresses = MutableStateFlow<Set<String>>(emptySet())
    val allowlistAddresses: StateFlow<Set<String>> = _allowlistAddresses.asStateFlow()


    /** See [MessengerRuntime.requestWhatsAppPairing]. Caregiver-initiated only, from the hidden dialog. */
    fun requestWhatsAppPairing(phoneNumber: String) = MessengerRuntime.requestWhatsAppPairing(phoneNumber)

    /** Forget the last WhatsApp error, so a retry starts from a clean screen. */
    fun clearWhatsAppError() = MessengerRuntime.clearWhatsAppError()

    /** Forget the code once the dialog is done with it. */
    fun clearWhatsAppPairingCode() = MessengerRuntime.clearWhatsAppPairingCode()

    /** Unlink this phone from her WhatsApp. Deliberate, and only from the caregiver dialog. */
    fun unlinkWhatsApp() = MessengerRuntime.unlinkWhatsApp()

    /** What the caregiver dialog shows about matching, and whether one is in flight. */
    val contactSyncOutcome: StateFlow<ContactSyncPolicy.Outcome> = MessengerRuntime.contactSyncOutcome

    /**
     * Matches her address book against Threema, because a caregiver asked. See
     * [MessengerRuntime.requestContactSync]; the faces on her home screen stand in for an address
     * book that cannot be read.
     */
    fun requestContactSync() {
        MessengerRuntime.requestContactSync(app, catalog.favoriteContacts.value)
    }

    /**
     * Stores her Threema credentials and brings the client up straight away.
     *
     * This is the caregiver's deliberate provisioning act — the only path that is allowed to
     * result in a running Threema client. The key is handed straight to the repository and is
     * never logged, never held in a field, and never shown back on screen.
     */
    fun saveThreemaIdentity(context: Context, threemaId: String, clientKey: String) {
        // Stopping the old client, reusing the instance and starting it again under the new
        // identity is [MessengerRuntime]'s, because its listeners are the ones bound to that client.
        MessengerRuntime.provisionThreema(context, threemaId, clientKey)
    }

    private var threemaAccountTransfer: ThreemaAccountTransfer? = null

    private fun getThreemaAccountTransfer(context: Context): ThreemaAccountTransfer {
        return threemaAccountTransfer ?: synchronized(this) {
            val repo = threemaIdentityRepository ?: Messengers.threemaIdentity(context).also {
                threemaIdentityRepository = it
            }
            ThreemaAccountTransfer(context.applicationContext, repo).also {
                threemaAccountTransfer = it
            }
        }
    }

    /**
     * Set the name her messages arrive under at the other end.
     *
     * Not cosmetic and not local: the nickname goes out with every message she sends, and until
     * somebody sets it that name is the literal string "gentle connection" — so her family sees
     * an app's name where hers should be. A restored account cannot supply it, because a Threema
     * backup carries the account and not the name it writes under.
     *
     * @return false when there is no account on this phone to name.
     */
    fun setThreemaNickname(context: Context, nickname: String): Boolean {
        val repo = threemaIdentityRepository ?: Messengers.threemaIdentity(context).also {
            threemaIdentityRepository = it
        }
        return repo.setNickname(nickname)
    }

    /** The name her messages currently go out under, or null when there is no account. */
    fun getThreemaNickname(context: Context): String? {
        val repo = threemaIdentityRepository ?: Messengers.threemaIdentity(context).also {
            threemaIdentityRepository = it
        }
        return repo.getIdentity()?.nickname
    }

    fun getThreemaIdentity(context: Context): String? {
        val repo = threemaIdentityRepository ?: Messengers.threemaIdentity(context).also {
            threemaIdentityRepository = it
        }
        return repo.getIdentity()?.threemaId?.trim()?.uppercase()
    }

    fun exportThreemaAccount(
        context: Context,
        onResult: (ThreemaAccountTransfer.Export) -> Unit
    ) {
        val transfer = getThreemaAccountTransfer(context)
        viewModelScope.launch {
            val result = transfer.export()
            onResult(result)
        }
    }

    fun importThreemaAccount(
        context: Context,
        backup: String,
        password: String,
        replaceExisting: Boolean = false,
        onResult: (ThreemaAccountTransfer.Import) -> Unit
    ) {
        val transfer = getThreemaAccountTransfer(context)
        viewModelScope.launch {
            val result = transfer.import(backup, password, replaceExisting)
            if (result is ThreemaAccountTransfer.Import.Restored) {
                val repo = threemaIdentityRepository ?: Messengers.threemaIdentity(context).also {
                    threemaIdentityRepository = it
                }
                catalog.ownThreemaId.value = repo.getIdentity()?.threemaId?.trim()?.uppercase()
                MessengerRuntime.restartThreema(context)
            }
            onResult(result)
        }
    }

    fun unlinkThreemaAccount(context: Context): Boolean {
        val transfer = getThreemaAccountTransfer(context)
        val unlinked = transfer.unlink()
        if (unlinked) {
            catalog.ownThreemaId.value = null
            MessengerRuntime.threemaUnlinked(context)
        }
        return unlinked
    }

    fun exportThreemaMessages(
        context: Context,
        destinationUri: Uri,
        onResult: (ThreemaExportResult) -> Unit
    ) {
        val db = AppGraph.database(context)
        val exporter = ThreemaBackupExporter(context.applicationContext, db)
        viewModelScope.launch {
            val result = exporter.exportBackup(destinationUri)
            onResult(result)
        }
    }

    fun loadAllowlist(context: Context) {
        val repo = MailAllowlistRepository(context.applicationContext)
        _allowlistAddresses.value = repo.getAllowlist()
    }

    fun addAllowlistAddress(context: Context, address: String) {
        val repo = MailAllowlistRepository(context.applicationContext)
        repo.addAddress(address)
        _allowlistAddresses.value = repo.getAllowlist()
    }

    fun removeAllowlistAddress(context: Context, address: String) {
        val repo = MailAllowlistRepository(context.applicationContext)
        repo.removeAddress(address)
        _allowlistAddresses.value = repo.getAllowlist()
    }

    fun getGmailAddress(context: Context): String? {
        return MailAccountRepository(context.applicationContext).getGmailAddress()
    }

    /** Call only once the gmail.readonly grant for [address] is in place. */
    fun saveGmailAddress(context: Context, address: String): Boolean {
        val repo = MailAccountRepository(context.applicationContext)
        val success = repo.saveGmailAddress(address)
        if (success) {
            MailSyncWorker.enqueueOneTimeSync(context.applicationContext)
        }
        return success
    }

    fun clearMailAccount(context: Context) {
        MailAccountRepository(context.applicationContext).clearAccount()
    }

    suspend fun generatePairingCode(): String {
        return remoteSettingsRepository.generateTemporaryPairingCode()
    }

    /** Takes away every caregiver's access to this phone. See [SettingsRepository.removeAllCaregivers]. */
    fun removeAllCaregivers() = remoteSettingsRepository.removeAllCaregivers()

    fun importThreemaBackup(
        context: Context,
        uri: Uri,
        password: String,
        onResult: (ThreemaImportResult) -> Unit
    ) {
        val db = AppGraph.database(context)
        // With the account transfer, so that a backup carrying an identity can put it back in the
        // same gesture that restores the conversations it belongs to.
        val importer = ThreemaBackupImporter(
            context.applicationContext,
            db,
            getThreemaAccountTransfer(context)
        )
        viewModelScope.launch {
            val result = importer.importBackup(uri, password)
            onResult(result)
        }
    }

    // Last, so the flow it writes exists first.
    init {
        loadAllowlist(app)
    }
}
