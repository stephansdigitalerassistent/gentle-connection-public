package ch.heuscher.gentleconnect.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.threema.ThreemaAccountTransfer
import ch.heuscher.gentleconnect.data.threema.ThreemaExportResult
import ch.heuscher.gentleconnect.data.threema.ThreemaImportResult

/**
 * The caregiver's screen, reached by five fast taps on the clock.
 *
 * Shows the caregiver 6-character pairing code, the log of what did not work, Threema
 * actions (account export/import/unlink, message backup export/import, directory sync),
 * IMAP mail credentials, and mail allowlist management.
 *
 * This is the only place in the app where a fault is ever put into words. Her screen explains
 * nothing and offers no failed action — so everything the app knows about what went wrong has to
 * surface here, or it surfaces nowhere.
 *
 * A screen rather than the dialog it began as. Everything above is why: this is several sections of
 * forms, credentials and a fault log, and a card floating over the home screen gave all of it the
 * height left over after the margins — so the section somebody came for was read three lines at a
 * time. It is also the one part of the app that is not hers, and a screen of its own says that
 * more plainly than a card she can see her own contacts behind.
 *
 * Knows nothing about Firebase, Threema, WhatsApp or the view model — everything arrives already
 * resolved to words. [CaregiverScreenHost] is what knows where any of it comes from.
 */
@Composable
fun CaregiverScreen(
    pairingCode: String,
    onBack: () -> Unit,
    // Threema actions
    threemaConnectionStatus: String? = null,
    currentThreemaId: String? = null,
    onExportThreemaAccount: (((ThreemaAccountTransfer.Export) -> Unit) -> Unit)? = null,
    onImportThreemaAccount: ((String, String, Boolean, (ThreemaAccountTransfer.Import) -> Unit) -> Unit)? = null,
    onUnlinkThreemaAccount: (() -> Boolean)? = null,
    onExportThreemaMessages: ((Uri, (ThreemaExportResult) -> Unit) -> Unit)? = null,
    onImportBackupRequested: ((Uri, String, (ThreemaImportResult) -> Unit) -> Unit)? = null,
    onSaveThreemaIdentity: ((String, String) -> Unit)? = null,
    /**
     * The name her messages go out under, and the way to change it.
     *
     * Its own control rather than a field of the credentials: the nickname is the one part of an
     * account that is a decision instead of a secret, and it is what her family sees at the top
     * of everything she writes. Until it is set it reads "gentle connection", because a Threema
     * backup carries the account and not the name it writes under.
     */
    threemaNickname: String? = null,
    onSaveThreemaNickname: ((String) -> Unit)? = null,
    contactSyncStatus: String? = null,
    contactSyncInProgress: Boolean = false,
    onSyncContactsRequested: (() -> Unit)? = null,
    // Mail
    initialGmailAddress: String? = null,
    /** Asks Google for read-only access to that address; the callback says whether it worked. */
    onConnectGmail: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onClearMailAccount: (() -> Unit)? = null,
    allowlistAddresses: Set<String> = emptySet(),
    onAddAllowlistAddress: ((String) -> Unit)? = null,
    onRemoveAllowlistAddress: ((String) -> Unit)? = null,
    // Caregiver Log & Portal
    caregiverLog: List<CaregiverLogLine> = emptyList(),
    caregiverCount: Int = 0,
    /**
     * Takes away every caregiver's access to this phone. Null where there is nothing to remove it
     * from. The only way access ever shrinks: a caregiver can only add themselves.
     */
    onRemoveAllCaregivers: (() -> Unit)? = null,
    caregiverPortalUrl: String? = null,
    caregiverPortalAddress: String? = null,
    // WhatsApp
    whatsAppStatus: String? = null,
    whatsAppLinked: Boolean = false,
    whatsAppPairingCode: String? = null,
    onRequestWhatsAppPairing: ((String) -> Unit)? = null,
    onUnlinkWhatsApp: (() -> Unit)? = null,
    whatsAppError: String? = null,
    // Paying: the hint before the PIN that unlocks the card. On this phone only — see PaymentPinHint.
    paymentPinHint: String? = null,
    onSavePaymentPinHint: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    // Threema Account Export state
    var isExportingAccount by remember { mutableStateOf(false) }
    var exportAccountResult by remember { mutableStateOf<ThreemaAccountTransfer.Export?>(null) }

    // Threema Account Import state
    var accountBackupInput by remember { mutableStateOf("") }
    var accountPasswordInput by remember { mutableStateOf("") }
    var isImportingAccount by remember { mutableStateOf(false) }
    var accountImportStatusMessage by remember { mutableStateOf<String?>(null) }
    var isAccountImportError by remember { mutableStateOf(false) }
    var pendingReplaceIncomingId by remember { mutableStateOf<String?>(null) }
    var pendingReplaceExistingId by remember { mutableStateOf<String?>(null) }

    // Threema Manual Key Save state
    var nicknameInput by remember(threemaNickname) { mutableStateOf(threemaNickname.orEmpty()) }
    var nicknameStatusMessage by remember { mutableStateOf<String?>(null) }
    var paymentHintInput by remember(paymentPinHint) { mutableStateOf(paymentPinHint.orEmpty()) }
    var paymentHintStatusMessage by remember { mutableStateOf<String?>(null) }
    var threemaIdInput by remember { mutableStateOf("") }
    var clientKeyInput by remember { mutableStateOf("") }
    var keySaveStatusMessage by remember { mutableStateOf<String?>(null) }

    // Threema Unlink state
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }
    var unlinkStatusMessage by remember { mutableStateOf<String?>(null) }
    var isUnlinkError by remember { mutableStateOf(false) }

    // Threema Messages Export state
    var isExportingMessages by remember { mutableStateOf(false) }
    var messageExportResult by remember { mutableStateOf<ThreemaExportResult?>(null) }

    val messageExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            isExportingMessages = true
            messageExportResult = null
            onExportThreemaMessages?.invoke(uri) { result ->
                isExportingMessages = false
                messageExportResult = result
            }
        }
    }

    // Threema Messages Import state
    var selectedMessageBackupUri by remember { mutableStateOf<Uri?>(null) }
    var messageBackupPasswordInput by remember { mutableStateOf("") }
    var isImportingMessages by remember { mutableStateOf(false) }
    var messageImportStatusMessage by remember { mutableStateOf<String?>(null) }
    var isMessageImportStatusError by remember { mutableStateOf(false) }

    val messageImportPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedMessageBackupUri = uri
            messageImportStatusMessage = null
        }
    }

    // WhatsApp state
    var whatsAppNumber by remember { mutableStateOf("") }

    // Mail Account state
    var mailAddress by remember { mutableStateOf(initialGmailAddress ?: "") }
    var mailAccountStatusMessage by remember {
        mutableStateOf<String?>(
            if (initialGmailAddress != null) context.getString(R.string.mail_account_connected) else null
        )
    }
    var isMailStatusError by remember { mutableStateOf(false) }

    // Mail Allowlist state
    var newAllowlistAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
    ) {
        // The way out, and the name of the screen. Not [GentleScreenHeader]: that carries the "?"
        // that explains a screen to her, and this screen is not hers to be explained.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.pairing_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Pairing Code
                Text(
                    text = stringResource(R.string.pairing_dialog_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = pairingCode.chunked(1).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Text(
                    text = stringResource(R.string.pairing_dialog_validity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (caregiverCount > 0) {
                        pluralStringResource(
                            R.plurals.caregiver_count,
                            caregiverCount,
                            caregiverCount
                        )
                    } else {
                        stringResource(R.string.caregiver_count_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Anybody who opened a live pairing link keeps access until it is taken away here,
                // including somebody who opened it once on a neighbour's phone. Everybody who
                // should still have it pairs again with a fresh code.
                if (caregiverCount > 0 && onRemoveAllCaregivers != null) {
                    var confirmRemoveCaregivers by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { confirmRemoveCaregivers = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.caregiver_remove_all))
                    }
                    if (confirmRemoveCaregivers) {
                        AlertDialog(
                            onDismissRequest = { confirmRemoveCaregivers = false },
                            title = { Text(text = stringResource(R.string.caregiver_remove_all_title)) },
                            text = { Text(text = stringResource(R.string.caregiver_remove_all_message)) },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        confirmRemoveCaregivers = false
                                        onRemoveAllCaregivers()
                                    }
                                ) {
                                    Text(text = stringResource(R.string.caregiver_remove_all_confirm))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmRemoveCaregivers = false }) {
                                    Text(text = stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }
                }

                if (caregiverPortalUrl != null) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(caregiverPortalUrl))
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.caregiver_portal_open),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = caregiverPortalAddress ?: caregiverPortalUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Section 2: What did not work (Caregiver Log)
                Text(
                    text = stringResource(R.string.caregiver_log_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                var showCaregiverActions by remember { mutableStateOf(false) }
                val actionCount = caregiverLog.count { it.isCaregiverAction }
                val shownLog = if (showCaregiverActions) {
                    caregiverLog
                } else {
                    caregiverLog.filterNot { it.isCaregiverAction }
                }

                if (actionCount > 0) {
                    Text(
                        text = if (showCaregiverActions) {
                            stringResource(R.string.caregiver_log_hide_actions)
                        } else {
                            stringResource(R.string.caregiver_log_show_actions, actionCount)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCaregiverActions = !showCaregiverActions }
                            .padding(vertical = 4.dp)
                    )
                }

                if (shownLog.isEmpty()) {
                    Text(
                        text = if (caregiverLog.isEmpty()) {
                            stringResource(R.string.caregiver_log_empty)
                        } else {
                            stringResource(R.string.caregiver_log_no_faults)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val whenStyle = MaterialTheme.typography.labelMedium
                    val whatStyle = MaterialTheme.typography.bodyMedium
                    val visibleHeight = with(LocalDensity.current) {
                        val entry = whenStyle.lineHeight.takeOrElse { whenStyle.fontSize }.toDp() +
                            whatStyle.lineHeight.takeOrElse { whatStyle.fontSize }.toDp() *
                            LOG_ENTRY_BODY_LINES +
                            LOG_ENTRY_INNER_SPACING + LOG_ENTRY_PADDING * 2
                        entry * LOG_ENTRIES_SHOWN + LOG_ENTRY_GAP * (LOG_ENTRIES_SHOWN - 1)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = visibleHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(LOG_ENTRY_GAP)
                    ) {
                        shownLog.forEach { line ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = LOG_ENTRY_PADDING),
                                verticalArrangement = Arrangement.spacedBy(LOG_ENTRY_INNER_SPACING)
                            ) {
                                Text(
                                    text = line.whenText,
                                    style = whenStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = line.whatText,
                                    style = whatStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Section 3: Threema (Grouped and clearly titled)
                Text(
                    text = stringResource(R.string.threema_section_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3a. Connection Status
                threemaConnectionStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 3b. Account & Identity
                Text(
                    text = stringResource(R.string.threema_section_account_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (!currentThreemaId.isNullOrBlank()) {
                        stringResource(R.string.threema_current_id, currentThreemaId)
                    } else {
                        stringResource(R.string.threema_no_identity)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                // Export Account Button
                Button(
                    onClick = {
                        isExportingAccount = true
                        exportAccountResult = null
                        onExportThreemaAccount?.invoke { result ->
                            isExportingAccount = false
                            exportAccountResult = result
                        }
                    },
                    enabled = !isExportingAccount,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.threema_export_account),
                        fontWeight = FontWeight.Bold
                    )
                }

                exportAccountResult?.let { result ->
                    when (result) {
                        is ThreemaAccountTransfer.Export.Ready -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.threema_export_account_instruction),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.threema_export_account_backup_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = result.backup,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.threema_export_account_password_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = result.password,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is ThreemaAccountTransfer.Export.NoIdentity -> {
                            Text(
                                text = stringResource(R.string.threema_export_account_no_identity),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        is ThreemaAccountTransfer.Export.Failed -> {
                            Text(
                                text = stringResource(R.string.threema_export_account_failed, result.reason),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Restore Account from Backup Form
                Text(
                    text = stringResource(R.string.threema_restore_account_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = accountBackupInput,
                    onValueChange = { accountBackupInput = it },
                    label = { Text(stringResource(R.string.threema_restore_account_backup_label)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = accountPasswordInput,
                    onValueChange = { accountPasswordInput = it },
                    label = { Text(stringResource(R.string.threema_restore_account_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = {
                        if (accountBackupInput.isNotBlank() && accountPasswordInput.isNotBlank()) {
                            isImportingAccount = true
                            accountImportStatusMessage = null
                            onImportThreemaAccount?.invoke(
                                accountBackupInput,
                                accountPasswordInput,
                                false
                            ) { result ->
                                isImportingAccount = false
                                when (result) {
                                    is ThreemaAccountTransfer.Import.Restored -> {
                                        accountImportStatusMessage = context.getString(R.string.threema_restore_account_success, result.threemaId)
                                        isAccountImportError = false
                                    }
                                    is ThreemaAccountTransfer.Import.Unchanged -> {
                                        accountImportStatusMessage = context.getString(R.string.threema_restore_account_unchanged, result.threemaId)
                                        isAccountImportError = false
                                    }
                                    is ThreemaAccountTransfer.Import.WouldReplace -> {
                                        pendingReplaceIncomingId = result.incoming
                                        pendingReplaceExistingId = result.existing
                                    }
                                    is ThreemaAccountTransfer.Import.Failed -> {
                                        accountImportStatusMessage = context.getString(R.string.threema_restore_account_failed, result.reason)
                                        isAccountImportError = true
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isImportingAccount && accountBackupInput.isNotBlank() && accountPasswordInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.threema_restore_account_button),
                        fontWeight = FontWeight.Bold
                    )
                }

                accountImportStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isAccountImportError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Manual Credentials Entry
                Text(
                    text = stringResource(R.string.threema_key_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = threemaIdInput,
                    onValueChange = { threemaIdInput = it.uppercase() },
                    label = { Text(stringResource(R.string.threema_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = clientKeyInput,
                    onValueChange = { clientKeyInput = it },
                    label = { Text(stringResource(R.string.threema_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (onSaveThreemaNickname != null) {
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = {
                            nicknameInput = it
                            nicknameStatusMessage = null
                        },
                        label = { Text(stringResource(R.string.threema_nickname_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (nicknameInput.isNotBlank()) {
                                onSaveThreemaNickname(nicknameInput.trim())
                                nicknameStatusMessage = context.getString(R.string.threema_nickname_saved)
                            }
                        },
                        enabled = nicknameInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.threema_nickname_save),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    nicknameStatusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Button(
                    onClick = {
                        if (threemaIdInput.isNotBlank() && clientKeyInput.isNotBlank()) {
                            onSaveThreemaIdentity?.invoke(threemaIdInput, clientKeyInput)
                            keySaveStatusMessage = context.getString(R.string.threema_key_saved)
                        }
                    },
                    enabled = threemaIdInput.isNotBlank() && clientKeyInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.threema_key_save),
                        fontWeight = FontWeight.Bold
                    )
                }

                keySaveStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Unlink Account Button
                OutlinedButton(
                    onClick = { showUnlinkConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.threema_unlink_account),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                unlinkStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUnlinkError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 3c. Messages & Backup
                Text(
                    text = stringResource(R.string.threema_section_messages_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                // Export Messages
                Button(
                    onClick = {
                        messageExportLauncher.launch("threema_backup_${System.currentTimeMillis()}.zip")
                    },
                    enabled = !isExportingMessages,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.threema_export_messages_button),
                        fontWeight = FontWeight.Bold
                    )
                }

                messageExportResult?.let { result ->
                    when (result) {
                        is ThreemaExportResult.Success -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.threema_export_messages_success),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = result.password,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.threema_export_messages_password_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is ThreemaExportResult.Error -> {
                            Text(
                                text = stringResource(R.string.threema_export_messages_failed, result.message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Import Messages
                OutlinedButton(
                    onClick = {
                        messageImportPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (selectedMessageBackupUri != null) {
                            stringResource(R.string.threema_import_file_selected, selectedMessageBackupUri?.lastPathSegment ?: "ZIP")
                        } else {
                            stringResource(R.string.threema_import_select_file)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (selectedMessageBackupUri != null) {
                    OutlinedTextField(
                        value = messageBackupPasswordInput,
                        onValueChange = { messageBackupPasswordInput = it },
                        label = { Text(stringResource(R.string.threema_import_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            val uri = selectedMessageBackupUri ?: return@Button
                            if (messageBackupPasswordInput.isEmpty()) return@Button
                            isImportingMessages = true
                            messageImportStatusMessage = null

                            onImportBackupRequested?.invoke(uri, messageBackupPasswordInput) { result ->
                                isImportingMessages = false
                                when (result) {
                                    is ThreemaImportResult.Success -> {
                                        val messagesLine = if (result.restoredCount > 0) {
                                            context.getString(R.string.threema_import_success_count, result.restoredCount, result.totalCount)
                                        } else if (result.totalCount > 0) {
                                            context.getString(R.string.threema_import_success_unchanged, result.totalCount)
                                        } else {
                                            context.getString(R.string.threema_import_success)
                                        }
                                        // Said only when it happened: a backup that carried no
                                        // account, or carried one this phone already has, leaves
                                        // this line off rather than claiming something.
                                        val accountLine = result.restoredThreemaId?.let {
                                            context.getString(R.string.threema_import_account_restored, it)
                                        }
                                        messageImportStatusMessage =
                                            listOfNotNull(messagesLine, accountLine).joinToString("\n")
                                        isMessageImportStatusError = false
                                    }
                                    is ThreemaImportResult.WrongPassword -> {
                                        messageImportStatusMessage = context.getString(R.string.threema_import_wrong_password)
                                        isMessageImportStatusError = true
                                    }
                                    is ThreemaImportResult.Error -> {
                                        messageImportStatusMessage = context.getString(R.string.threema_import_error, result.message)
                                        isMessageImportStatusError = true
                                    }
                                }
                            }
                        },
                        enabled = !isImportingMessages && messageBackupPasswordInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.threema_import_start),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                messageImportStatusMessage?.let { statusMsg ->
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMessageImportStatusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 3d. Directory Sync
                Text(
                    text = stringResource(R.string.threema_section_contacts_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.caregiver_contacts_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (onSyncContactsRequested != null) {
                    Button(
                        onClick = onSyncContactsRequested,
                        enabled = !contactSyncInProgress,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.caregiver_contacts_sync),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (contactSyncStatus != null) {
                    Text(
                        text = contactSyncStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Section 4: Her Gmail, read-only through the Gmail API
                Text(
                    text = stringResource(R.string.mail_account_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = mailAddress,
                    onValueChange = { mailAddress = it },
                    label = { Text(stringResource(R.string.mail_account_address_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val connect = onConnectGmail
                            if (connect == null) {
                                mailAccountStatusMessage = context.getString(R.string.mail_account_connect_failed)
                                isMailStatusError = true
                            } else {
                                connect(mailAddress.trim()) { success ->
                                    mailAccountStatusMessage = context.getString(
                                        if (success) R.string.mail_account_connected
                                        else R.string.mail_account_connect_failed
                                    )
                                    isMailStatusError = !success
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mail_account_connect),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onClearMailAccount?.invoke()
                            mailAddress = ""
                            mailAccountStatusMessage = context.getString(R.string.mail_account_cleared)
                            isMailStatusError = false
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mail_account_clear),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                mailAccountStatusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMailStatusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Section 5: Caregiver Mail Allowlist Management
                Text(
                    text = stringResource(R.string.mail_allowlist_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.mail_allowlist_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newAllowlistAddress,
                        onValueChange = { newAllowlistAddress = it },
                        label = { Text(stringResource(R.string.mail_allowlist_add_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (newAllowlistAddress.isNotBlank()) {
                                onAddAllowlistAddress?.invoke(newAllowlistAddress.trim())
                                newAllowlistAddress = ""
                            }
                        },
                        enabled = newAllowlistAddress.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.mail_allowlist_add_button))
                    }
                }

                if (allowlistAddresses.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mail_allowlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allowlistAddresses.forEach { addr ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = addr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedButton(
                                    onClick = { onRemoveAllowlistAddress?.invoke(addr) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(text = stringResource(R.string.mail_allowlist_remove))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Section 6: WhatsApp
                Text(
                    text = stringResource(R.string.whatsapp_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                whatsAppError?.let { error ->
                    Text(
                        text = stringResource(R.string.whatsapp_error, error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                whatsAppStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (whatsAppPairingCode != null) {
                    Text(
                        text = stringResource(R.string.whatsapp_code_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = whatsAppPairingCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.whatsapp_code_expires),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!whatsAppLinked) {
                    Text(
                        text = stringResource(R.string.whatsapp_number_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = whatsAppNumber,
                        onValueChange = { whatsAppNumber = it },
                        label = { Text(stringResource(R.string.whatsapp_number_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onRequestWhatsAppPairing?.invoke(whatsAppNumber) },
                        enabled = whatsAppNumber.filter { it.isDigit() }.length >= 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (whatsAppPairingCode == null) {
                                stringResource(R.string.whatsapp_request_code)
                            } else {
                                stringResource(R.string.whatsapp_request_new_code)
                            }
                        )
                    }
                }

                if (whatsAppLinked) {
                    OutlinedButton(
                        onClick = { onUnlinkWhatsApp?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.whatsapp_unlink))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Paying: the hint shown before the PIN that unlocks the card. Blank saves as
                // "no hint", which is why Save is never greyed out here, unlike the nickname.
                if (onSavePaymentPinHint != null) {
                    Text(
                        text = stringResource(R.string.caregiver_payment_hint_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = stringResource(R.string.caregiver_payment_hint_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = paymentHintInput,
                        onValueChange = {
                            paymentHintInput = it
                            paymentHintStatusMessage = null
                        },
                        label = { Text(stringResource(R.string.caregiver_payment_hint_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            onSavePaymentPinHint(paymentHintInput)
                            paymentHintStatusMessage = context.getString(
                                if (paymentHintInput.isBlank()) R.string.caregiver_payment_hint_removed
                                else R.string.caregiver_payment_hint_saved
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.caregiver_payment_hint_save),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    paymentHintStatusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                // Section 7: Advanced Mode (Erweiterter Modus)
                Text(
                    text = stringResource(R.string.advanced_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.advanced_mode_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                val isAdvancedMode by AdvancedMode.isOn.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isAdvancedMode) {
                            stringResource(R.string.advanced_mode_switch_on)
                        } else {
                            stringResource(R.string.advanced_mode_switch_off)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Switch(
                        checked = isAdvancedMode,
                        onCheckedChange = { checked ->
                            if (checked) AdvancedMode.enable() else AdvancedMode.disable()
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Confirmation Dialog for Unlinking Threema Account
    if (showUnlinkConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirmDialog = false },
            title = { Text(text = stringResource(R.string.threema_unlink_confirm_title)) },
            text = { Text(text = stringResource(R.string.threema_unlink_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnlinkConfirmDialog = false
                        val success = onUnlinkThreemaAccount?.invoke() ?: false
                        unlinkStatusMessage = if (success) {
                            context.getString(R.string.threema_unlink_success)
                        } else {
                            context.getString(R.string.threema_unlink_none)
                        }
                        isUnlinkError = !success
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(text = stringResource(R.string.threema_unlink_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirmDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // Confirmation Dialog for Replacing Existing Account on Import
    if (pendingReplaceIncomingId != null && pendingReplaceExistingId != null) {
        val incomingId = pendingReplaceIncomingId!!
        val existingId = pendingReplaceExistingId!!
        AlertDialog(
            onDismissRequest = {
                pendingReplaceIncomingId = null
                pendingReplaceExistingId = null
            },
            title = { Text(text = stringResource(R.string.threema_restore_replace_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.threema_restore_replace_message,
                        incomingId,
                        existingId
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingReplaceIncomingId = null
                        pendingReplaceExistingId = null
                        isImportingAccount = true
                        onImportThreemaAccount?.invoke(
                            accountBackupInput,
                            accountPasswordInput,
                            true
                        ) { result ->
                            isImportingAccount = false
                            when (result) {
                                is ThreemaAccountTransfer.Import.Restored -> {
                                    accountImportStatusMessage = context.getString(R.string.threema_restore_account_success, result.threemaId)
                                    isAccountImportError = false
                                }
                                is ThreemaAccountTransfer.Import.Unchanged -> {
                                    accountImportStatusMessage = context.getString(R.string.threema_restore_account_unchanged, result.threemaId)
                                    isAccountImportError = false
                                }
                                is ThreemaAccountTransfer.Import.Failed -> {
                                    accountImportStatusMessage = context.getString(R.string.threema_restore_account_failed, result.reason)
                                    isAccountImportError = true
                                }
                                is ThreemaAccountTransfer.Import.WouldReplace -> {
                                    // Already confirmed
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(text = stringResource(R.string.threema_restore_replace_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingReplaceIncomingId = null
                        pendingReplaceExistingId = null
                    }
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * How many faults the log shows before it starts scrolling within itself.
 *
 * Eight. It was three, on the reasoning that the newest few answer "is something wrong, and how
 * recently" — but in use three was a keyhole: a single incident writes several lines, so the box
 * showed one incident and the cause scrolled out of sight. Eight still leaves the setup below it
 * one swipe away. It is not a limit on what is kept; that is `CaregiverLog.MAX_ENTRIES`.
 */
private const val LOG_ENTRIES_SHOWN = 8

/** The space above and below one entry's text. Shared with the height above so the two agree. */
private val LOG_ENTRY_PADDING = 8.dp

/** Between the two lines within an entry. */
private val LOG_ENTRY_INNER_SPACING = 2.dp

/** Between one entry and the next. */
private val LOG_ENTRY_GAP = 8.dp

/**
 * How many lines a fault's sentence is assumed to take.
 *
 * Two, because they are sentences and not labels: "The messenger could not connect, so messages
 * can neither arrive nor be sent" is two lines at her ordinary text size and stays two lines when
 * she magnifies the screen, since the type and the width grow together. Assuming one showed two
 * entries and a sliver of a third, cut through the middle of a word — which reads as a fault in
 * the screen rather than as an invitation to scroll.
 */
private const val LOG_ENTRY_BODY_LINES = 2
