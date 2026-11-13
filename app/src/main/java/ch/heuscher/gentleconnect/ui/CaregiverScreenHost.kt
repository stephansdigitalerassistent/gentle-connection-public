package ch.heuscher.gentleconnect.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.ContactSyncPolicy
import ch.heuscher.gentleconnect.data.mail.GmailAuthorizer
import ch.heuscher.gentleconnect.data.mail.MailAccountRepository
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppLinkState
import ch.heuscher.gentleconnect.model.CaregiverPortalLink
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Everything [CaregiverScreen] shows, fetched and put into words.
 *
 * The screen itself knows nothing about Firebase, Threema, WhatsApp or the view model, and that
 * is worth keeping: it is a long screen of forms, and the moment it can reach a repository it
 * becomes the place where anything to do with setting up the phone goes. So the two are separate
 * files, and this is the only one that knows where a thing comes from.
 */
@Composable
fun CaregiverScreenHost(
    caregiver: CaregiverSettingsViewModel,
    /** Where the PIN hint is kept. See [PaymentViewModel]. */
    payment: PaymentViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by caregiver.remoteSettings.collectAsState()
    val whatsAppLinkState by caregiver.whatsAppLinkState.collectAsState()
    val whatsAppPairingCode by caregiver.whatsAppPairingCode.collectAsState()
    val whatsAppError by caregiver.whatsAppError.collectAsState()

    // Asked for once the screen is already up, rather than before opening it. Waiting on the
    // coroutine first is what the gesture used to do, and anything going wrong in there — or
    // simply never resuming — left five taps on the clock looking like a phone that had stopped
    // responding, with no way to tell whether the gesture had fired at all.
    var pairingCode by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (pairingCode.isEmpty()) pairingCode = caregiver.generatePairingCode()
    }

    val allowlistAddresses by caregiver.allowlistAddresses.collectAsState()
    val threemaState by caregiver.threemaConnectionState.collectAsState()
    val caregiverEvents by caregiver.caregiverEvents.collectAsState()
    val contactSync by caregiver.contactSyncOutcome.collectAsState()

    // Connecting her Gmail: ask Play services for the gmail.readonly grant, and when it has never
    // been given, show Google's consent screen here, on her phone, while the caregiver is holding
    // it. The address is only saved once the grant is real, so the worker never tries an account
    // it cannot read.
    val scope = rememberCoroutineScope()
    var pendingGmail by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    val gmailConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingGmail
        pendingGmail = null
        val granted = result.resultCode == Activity.RESULT_OK && runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(result.data)
                .accessToken != null
        }.getOrDefault(false)
        if (pending != null) {
            val saved = granted && caregiver.saveGmailAddress(context, pending.first)
            pending.second(saved)
        }
    }
    val connectGmail: (String, (Boolean) -> Unit) -> Unit = { address, done ->
        if (!MailAccountRepository.isPlausibleAddress(address)) {
            done(false)
        } else {
            scope.launch {
                val authorization = runCatching {
                    Identity.getAuthorizationClient(context)
                        .authorize(GmailAuthorizer.request(address))
                        .await()
                }.getOrNull()
                val consent = authorization?.pendingIntent
                when {
                    authorization == null -> done(false)
                    authorization.hasResolution() && consent != null -> {
                        pendingGmail = address to done
                        gmailConsent.launch(IntentSenderRequest.Builder(consent.intentSender).build())
                    }
                    else -> done(caregiver.saveGmailAddress(context, address))
                }
            }
        }
    }

    CaregiverScreen(
        pairingCode = pairingCode,
        onBack = onBack,
        currentThreemaId = caregiver.getThreemaIdentity(context),
        onExportThreemaAccount = { callback ->
            caregiver.exportThreemaAccount(context, callback)
        },
        onImportThreemaAccount = { backup, password, replaceExisting, callback ->
            caregiver.importThreemaAccount(context, backup, password, replaceExisting, callback)
        },
        onUnlinkThreemaAccount = {
            caregiver.unlinkThreemaAccount(context)
        },
        onExportThreemaMessages = { uri, callback ->
            caregiver.exportThreemaMessages(context, uri, callback)
        },
        onImportBackupRequested = { uri, password, callback ->
            caregiver.importThreemaBackup(context, uri, password, callback)
        },
        // Without this the Save button reported "credentials saved" and saved nothing —
        // the callback defaulted to null and the whole provisioning path was a no-op.
        onSaveThreemaIdentity = { threemaId, clientKey ->
            caregiver.saveThreemaIdentity(context, threemaId, clientKey)
        },
        paymentPinHint = payment.getPaymentPinHint(context),
        onSavePaymentPinHint = { hint ->
            payment.setPaymentPinHint(context, hint)
        },
        threemaNickname = caregiver.getThreemaNickname(context),
        onSaveThreemaNickname = { nickname ->
            caregiver.setThreemaNickname(context, nickname)
        },
        threemaConnectionStatus = when (val state = threemaState) {
            ThreemaConnectionState.Connected -> stringResource(R.string.threema_status_connected)
            ThreemaConnectionState.Connecting -> stringResource(R.string.threema_status_connecting)
            ThreemaConnectionState.Disconnected -> stringResource(R.string.threema_status_disconnected)
            is ThreemaConnectionState.Failed ->
                stringResource(R.string.threema_status_failed, state.reason)
        },
        initialGmailAddress = caregiver.getGmailAddress(context),
        onConnectGmail = connectGmail,
        onClearMailAccount = {
            caregiver.clearMailAccount(context)
        },
        allowlistAddresses = allowlistAddresses,
        onAddAllowlistAddress = { address ->
            caregiver.addAllowlistAddress(context, address)
        },
        onRemoveAllowlistAddress = { address ->
            caregiver.removeAllowlistAddress(context, address)
        },
        caregiverLog = caregiverLogLines(caregiverEvents),
        caregiverCount = settings.caregiverUids.size,
        onRemoveAllCaregivers = { caregiver.removeAllCaregivers() },
        // Worked out from the Firebase project this build is wired to, rather than written
        // down anywhere: the portal is Firebase Hosting for that same project, and a
        // hard-coded address would be wrong for every other deployment of this app. The
        // button carries the live code; the address printed under it does not, because that
        // one is for typing into a laptop.
        caregiverPortalUrl = caregiverPortalUrl(pairingCode),
        caregiverPortalAddress = caregiverPortalAddress(),
        contactSyncStatus = when (val outcome = contactSync) {
            ContactSyncPolicy.Outcome.Idle -> null
            ContactSyncPolicy.Outcome.Running ->
                stringResource(R.string.caregiver_contacts_running)
            ContactSyncPolicy.Outcome.Failed ->
                stringResource(R.string.caregiver_contacts_failed)
            is ContactSyncPolicy.Outcome.Matched -> stringResource(
                R.string.caregiver_contacts_done,
                outcome.onThreema,
                outcome.submitted
            )
            is ContactSyncPolicy.Outcome.TooSoon -> stringResource(
                R.string.caregiver_contacts_too_soon,
                timeOfDay(outcome.nextAllowedAt)
            )
        },
        contactSyncInProgress = contactSync is ContactSyncPolicy.Outcome.Running,
        onSyncContactsRequested = { caregiver.requestContactSync() },
        // Resolved to words here for the same reason the Threema status is: the dialog knows
        // nothing about where any of this comes from.
        whatsAppStatus = when (whatsAppLinkState) {
            WhatsAppLinkState.NOT_LINKED -> stringResource(R.string.whatsapp_state_not_linked)
            WhatsAppLinkState.LINKED_OFFLINE -> stringResource(R.string.whatsapp_state_offline)
            WhatsAppLinkState.LIVE -> stringResource(R.string.whatsapp_state_live)
        },
        whatsAppLinked = whatsAppLinkState != WhatsAppLinkState.NOT_LINKED,
        whatsAppPairingCode = whatsAppPairingCode,
        onRequestWhatsAppPairing = { number -> caregiver.requestWhatsAppPairing(number) },
        onUnlinkWhatsApp = { caregiver.unlinkWhatsApp() },
        whatsAppError = whatsAppError
    )
}

/**
 * Where the caregiver website lives for this build, and what to do on arrival.
 *
 * See [CaregiverPortalLink] for why the pairing code travels in the fragment. Null when there is
 * no Firebase configuration at all, in which case the screen offers no link rather than a link
 * that goes nowhere.
 *
 * @param pairingCode the live code, carried in the link so nobody has to retype it.
 */
private fun caregiverPortalUrl(pairingCode: String?): String? =
    CaregiverPortalLink.forProject(firebaseProjectId(), pairingCode)

/** The plain address, for a caregiver typing it into a laptop rather than tapping the button. */
private fun caregiverPortalAddress(): String? =
    CaregiverPortalLink.forProject(firebaseProjectId(), null)

private fun firebaseProjectId(): String? = try {
    com.google.firebase.FirebaseApp.getInstance().options.projectId
} catch (e: Exception) {
    null
}

/** The clock time of a moment, for telling a caregiver when to come back. */
private fun timeOfDay(atMillis: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(atMillis))
