package ch.heuscher.gentleconnect.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.CallRecord
import ch.heuscher.gentleconnect.model.transportName
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.data.SendRetryPolicy
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.personName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How long a destructive question waits before deciding nobody is going to answer it.
 *
 * Longer than the twenty seconds "Shall I ring Karolina?" gets, for two reasons: these questions
 * are several words longer and name a thing to be lost, and the person reading them arrived here
 * on purpose rather than by pressing a face. Running out still means doing nothing, which for a
 * deletion is the only safe way for a timer to answer — see [GentleConfirmDialog].
 */
private const val DESTRUCTIVE_QUESTION_TIMEOUT_MILLIS = 30_000

/**
 * The entity target for which the caregiver Advanced Actions Sheet was opened.
 */
sealed interface AdvancedTarget {
    /**
     * A single message in the conversation screen.
     */
    data class MessageTarget(
        val message: Message,
        val conversationMessages: List<Message> = emptyList()
    ) : AdvancedTarget

    /**
     * A conversation row on the Messages screen or home unread section.
     */
    data class ChatTarget(
        val message: Message,
        val allMessagesInChat: List<Message> = emptyList(),
        val conversationId: String? = null
    ) : AdvancedTarget

    /**
     * A call row on the Recent Calls screen.
     */
    data class CallTarget(
        val record: CallRecord
    ) : AdvancedTarget

    /**
     * A contact row in the phone book / contacts screen.
     */
    data class ContactTarget(
        val contact: Contact
    ) : AdvancedTarget

    /**
     * A mail item on the Look / View screen.
     */
    data class MailTarget(
        val mail: Mail
    ) : AdvancedTarget
}

/**
 * Caregiver bottom sheet providing read-only technical facts, convenience utilities (copy, share,
 * save), and confirmed destructive operations (delete on phone, block number, mute conversation).
 *
 * ## The share action & launcher security
 *
 * ⚠️ Note on the share action: Because this app is configured as the default Android HOME launcher,
 * invoking `Intent.createChooser` with an `ACTION_SEND` intent is a genuine door *out* of the
 * walled garden. If a caregiver hands the phone back mid-share, she might find herself in Gmail,
 * WhatsApp, or Chrome. That risk is accepted here because Advanced Mode is strictly disabled by
 * default and automatically expires via [AdvancedMode.IDLE_TIMEOUT_MS].
 *
 * ## Deleting call logs is deliberately omitted
 *
 * Deleting call records from the Android system call log is intentionally not implemented.
 * Modifying `CallLog.Calls` requires `WRITE_CALL_LOG`, an aggressive runtime permission that this
 * simplified launcher avoids. Local message deletions and number blocking provide the necessary
 * caregiver tools without altering system call history tables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedActionsSheet(
    target: AdvancedTarget?,
    onDismiss: () -> Unit,
    onDeleteMessage: ((messageId: String, subjectName: String?) -> Unit)? = null,
    onDeleteConversation: ((conversationId: String, subjectName: String?) -> Unit)? = null,
    onBlockNumber: ((number: String) -> Unit)? = null,
    onToggleMute: ((conversationId: String, subjectName: String?) -> Boolean)? = null,
    isConversationMuted: ((conversationId: String) -> Boolean)? = null,
    onResendRequested: ((Message) -> Unit)? = null,
    onEditContact: ((Contact) -> Unit)? = null,
    onEditCallRecord: ((CallRecord) -> Unit)? = null,
    onCallBack: ((CallRecord) -> Unit)? = null,
    onCallContact: ((Contact) -> Unit)? = null
) {
    if (target == null) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Confirmation dialog states for destructive actions
    var confirmDeleteMessage by remember { mutableStateOf(false) }
    var confirmDeleteConversation by remember { mutableStateOf(false) }
    var confirmBlockNumber by remember { mutableStateOf<String?>(null) }
    var confirmMuteConversation by remember { mutableStateOf<Pair<String, String?>?>(null) }

    // Ringing somebody is asked about here for the same reason it is asked about on her own
    // screens: this sheet opens over her real address book, and "Anrufen" sits in a list beside
    // Kopieren and Teilen, which do nothing to anybody. One tap slightly wrong on a row that
    // dials is a real telephone ringing in somebody's house — and the caregiver who did it then
    // has to explain the call. See [ConfirmCallDialog]; running out rings nobody.
    var confirmCallRecord by remember { mutableStateOf<CallRecord?>(null) }
    var confirmCallContact by remember { mutableStateOf<Contact?>(null) }

    ModalBottomSheet(
        onDismissRequest = {
            AdvancedMode.touch()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Category and Subject Name
            SheetHeader(target = target)

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Read-Only Fact Info Block
            FactInfoBlock(target = target)

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            // Section 2: Actions
            when (target) {
                is AdvancedTarget.MessageTarget -> {
                    val msg = target.message
                    val hasMedia = !msg.photoPath.isNullOrBlank() || !msg.videoPath.isNullOrBlank() || !msg.audioPath.isNullOrBlank()
                    val mediaPath = msg.photoPath ?: msg.videoPath ?: msg.audioPath

                    // Copy Text
                    if (msg.text.isNotBlank()) {
                        ActionRow(
                            icon = Icons.Default.ContentCopy,
                            title = stringResource(R.string.advanced_action_copy_text),
                            onClick = {
                                copyToClipboard(context, "Message", msg.text)
                                AdvancedMode.touch()
                                onDismiss()
                            }
                        )
                    }

                    // Share
                    ActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.advanced_action_share),
                        onClick = {
                            AdvancedMode.touch()
                            if (hasMedia && mediaPath != null) {
                                shareMedia(context, mediaPath, msg.text)
                            } else {
                                shareText(context, msg.text)
                            }
                            onDismiss()
                        }
                    )

                    // Save Attachment to MediaStore
                    if (hasMedia && mediaPath != null) {
                        ActionRow(
                            icon = Icons.Default.SaveAlt,
                            title = stringResource(R.string.advanced_action_save),
                            onClick = {
                                AdvancedMode.touch()
                                val success = saveAttachmentToMediaStore(context, mediaPath)
                                Toast.makeText(
                                    context,
                                    if (success) R.string.advanced_action_save_success else R.string.advanced_action_save_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                        )
                    }

                    // Resend (Absorbed HoldToResend)
                    val resendable = SendRetryPolicy.mayResend(
                        isOutgoing = msg.isOutgoing,
                        state = msg.sendState,
                        sentAt = msg.timestamp,
                        now = System.currentTimeMillis()
                    )
                    if (onResendRequested != null && resendable) {
                        ActionRow(
                            icon = Icons.Default.Refresh,
                            title = stringResource(R.string.advanced_action_resend),
                            onClick = {
                                AdvancedMode.touch()
                                onResendRequested(msg)
                                onDismiss()
                            }
                        )
                    }

                    // Destructive: Delete Message
                    if (onDeleteMessage != null) {
                        DestructiveActionRow(
                            icon = Icons.Default.Delete,
                            title = stringResource(R.string.advanced_action_delete_message),
                            onClick = {
                                AdvancedMode.touch()
                                confirmDeleteMessage = true
                            }
                        )
                    }

                    // Destructive: Delete Conversation
                    val conversationId = msg.conversationId
                    if (onDeleteConversation != null && !conversationId.isNullOrBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.DeleteSweep,
                            title = stringResource(R.string.advanced_action_delete_conversation),
                            onClick = {
                                AdvancedMode.touch()
                                confirmDeleteConversation = true
                            }
                        )
                    }

                    // Mute / Unmute
                    if (onToggleMute != null && !conversationId.isNullOrBlank()) {
                        val isMuted = isConversationMuted?.invoke(conversationId) == true
                        ActionRow(
                            icon = if (isMuted) Icons.Default.VolumeUp else Icons.Default.NotificationsOff,
                            title = stringResource(
                                if (isMuted) R.string.advanced_action_unmute_conversation else R.string.advanced_action_mute_conversation
                            ),
                            onClick = {
                                AdvancedMode.touch()
                                if (isMuted) {
                                    onToggleMute(conversationId, msg.displayName)
                                    onDismiss()
                                } else {
                                    confirmMuteConversation = conversationId to msg.displayName
                                }
                            }
                        )
                    }

                    // Block Number (if sender has phone number)
                    val senderPhone = msg.senderAddress?.filter { it.isDigit() || it == '+' }
                    if (onBlockNumber != null && canBlockNumbers(context) && !senderPhone.isNullOrBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.advanced_action_block_number),
                            onClick = {
                                AdvancedMode.touch()
                                confirmBlockNumber = senderPhone
                            }
                        )
                    }
                }

                is AdvancedTarget.ChatTarget -> {
                    val msg = target.message
                    val conversationId = target.conversationId ?: msg.conversationId

                    // Copy
                    if (msg.text.isNotBlank()) {
                        ActionRow(
                            icon = Icons.Default.ContentCopy,
                            title = stringResource(R.string.advanced_action_copy_text),
                            onClick = {
                                copyToClipboard(context, "Chat", msg.text)
                                AdvancedMode.touch()
                                onDismiss()
                            }
                        )
                    }

                    // Share
                    if (msg.text.isNotBlank()) {
                        ActionRow(
                            icon = Icons.Default.Share,
                            title = stringResource(R.string.advanced_action_share),
                            onClick = {
                                AdvancedMode.touch()
                                shareText(context, msg.text)
                                onDismiss()
                            }
                        )
                    }

                    // Destructive: Delete Conversation
                    if (onDeleteConversation != null && !conversationId.isNullOrBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.DeleteSweep,
                            title = stringResource(R.string.advanced_action_delete_conversation),
                            onClick = {
                                AdvancedMode.touch()
                                confirmDeleteConversation = true
                            }
                        )
                    }

                    // Mute / Unmute
                    if (onToggleMute != null && !conversationId.isNullOrBlank()) {
                        val isMuted = isConversationMuted?.invoke(conversationId) == true
                        ActionRow(
                            icon = if (isMuted) Icons.Default.VolumeUp else Icons.Default.NotificationsOff,
                            title = stringResource(
                                if (isMuted) R.string.advanced_action_unmute_conversation else R.string.advanced_action_mute_conversation
                            ),
                            onClick = {
                                AdvancedMode.touch()
                                if (isMuted) {
                                    onToggleMute(conversationId, msg.displayName)
                                    onDismiss()
                                } else {
                                    confirmMuteConversation = conversationId to msg.displayName
                                }
                            }
                        )
                    }

                    // Block Number (if sender is phone number)
                    val senderPhone = msg.senderAddress?.filter { it.isDigit() || it == '+' }
                    if (onBlockNumber != null && canBlockNumbers(context) && !senderPhone.isNullOrBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.advanced_action_block_number),
                            onClick = {
                                AdvancedMode.touch()
                                confirmBlockNumber = senderPhone
                            }
                        )
                    }
                }

                is AdvancedTarget.CallTarget -> {
                    val record = target.record

                    // Call back
                    if (onCallBack != null) {
                        ActionRow(
                            icon = Icons.Default.Call,
                            title = stringResource(R.string.advanced_action_call_back),
                            onClick = {
                                AdvancedMode.touch()
                                confirmCallRecord = record
                            }
                        )
                    }

                    // Edit Contact / Save Number (Absorbed long-press)
                    if (onEditCallRecord != null) {
                        ActionRow(
                            icon = Icons.Default.Edit,
                            title = stringResource(
                                if (record.isKnown) R.string.advanced_action_edit_contact else R.string.advanced_action_save_number
                            ),
                            onClick = {
                                AdvancedMode.touch()
                                onEditCallRecord(record)
                                onDismiss()
                            }
                        )
                    }

                    // Copy Number
                    ActionRow(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.advanced_action_copy_number),
                        onClick = {
                            copyToClipboard(context, "Phone Number", record.number)
                            AdvancedMode.touch()
                            onDismiss()
                        }
                    )

                    // Share Number
                    ActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.advanced_action_share),
                        onClick = {
                            AdvancedMode.touch()
                            shareText(context, record.number)
                            onDismiss()
                        }
                    )

                    // Block Number
                    if (onBlockNumber != null && canBlockNumbers(context) && record.number.isNotBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.advanced_action_block_number),
                            onClick = {
                                AdvancedMode.touch()
                                confirmBlockNumber = record.number
                            }
                        )
                    }
                }

                is AdvancedTarget.ContactTarget -> {
                    val contact = target.contact

                    // Call
                    if (onCallContact != null) {
                        ActionRow(
                            icon = Icons.Default.Call,
                            title = stringResource(R.string.action_call),
                            onClick = {
                                AdvancedMode.touch()
                                confirmCallContact = contact
                            }
                        )
                    }

                    // Edit Contact (Absorbed long-press)
                    if (onEditContact != null) {
                        ActionRow(
                            icon = Icons.Default.Edit,
                            title = stringResource(R.string.advanced_action_edit_contact),
                            onClick = {
                                AdvancedMode.touch()
                                onEditContact(contact)
                                onDismiss()
                            }
                        )
                    }

                    // Copy Number
                    ActionRow(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.advanced_action_copy_number),
                        onClick = {
                            copyToClipboard(context, "Phone Number", contact.phoneNumber)
                            AdvancedMode.touch()
                            onDismiss()
                        }
                    )

                    // Share Contact
                    ActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.advanced_action_share),
                        onClick = {
                            AdvancedMode.touch()
                            shareText(context, "${contact.name}: ${contact.phoneNumber}")
                            onDismiss()
                        }
                    )

                    // Block Number
                    if (onBlockNumber != null && canBlockNumbers(context) && contact.phoneNumber.isNotBlank()) {
                        DestructiveActionRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.advanced_action_block_number),
                            onClick = {
                                AdvancedMode.touch()
                                confirmBlockNumber = contact.phoneNumber
                            }
                        )
                    }
                }

                is AdvancedTarget.MailTarget -> {
                    val mail = target.mail

                    // Copy Text
                    ActionRow(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.advanced_action_copy_text),
                        onClick = {
                            val content = buildString {
                                append(mail.subject)
                                if (mail.body.isNotBlank()) {
                                    append("\n\n")
                                    append(mail.body)
                                }
                            }
                            copyToClipboard(context, "Mail", content)
                            AdvancedMode.touch()
                            onDismiss()
                        }
                    )

                    // Copy Address
                    ActionRow(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.advanced_action_copy_address),
                        onClick = {
                            copyToClipboard(context, "Email Address", mail.senderAddress)
                            AdvancedMode.touch()
                            onDismiss()
                        }
                    )

                    // Share
                    ActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.advanced_action_share),
                        onClick = {
                            AdvancedMode.touch()
                            val shareContent = buildString {
                                append("Subject: ")
                                append(mail.subject)
                                append("\nFrom: ")
                                append(mail.senderAddress)
                                if (mail.body.isNotBlank()) {
                                    append("\n\n")
                                    append(mail.body)
                                }
                            }
                            shareText(context, shareContent)
                            onDismiss()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Confirmation dialogs for destructive operations
    if (confirmDeleteMessage) {
        val msg = (target as? AdvancedTarget.MessageTarget)?.message
        if (msg != null) {
            GentleConfirmDialog(
                question = stringResource(R.string.advanced_confirm_delete_message_title),
                confirmLabel = stringResource(R.string.advanced_confirm_delete_message_button),
                dismissLabel = stringResource(R.string.advanced_confirm_cancel),
                timeoutMillis = DESTRUCTIVE_QUESTION_TIMEOUT_MILLIS,
                onConfirm = {
                    confirmDeleteMessage = false
                    AdvancedMode.touch()
                    onDeleteMessage?.invoke(msg.id, msg.displayName)
                    onDismiss()
                },
                onDismiss = {
                    confirmDeleteMessage = false
                    AdvancedMode.touch()
                }
            )
        }
    }

    if (confirmDeleteConversation) {
        val (convId, displayName) = when (target) {
            is AdvancedTarget.MessageTarget -> target.message.conversationId to target.message.displayName
            is AdvancedTarget.ChatTarget -> (target.conversationId ?: target.message.conversationId) to target.message.displayName
            else -> null to null
        }
        if (!convId.isNullOrBlank()) {
            GentleConfirmDialog(
                question = stringResource(R.string.advanced_confirm_delete_conversation_title, displayName ?: convId),
                confirmLabel = stringResource(R.string.advanced_confirm_delete_conversation_button),
                dismissLabel = stringResource(R.string.advanced_confirm_cancel),
                timeoutMillis = DESTRUCTIVE_QUESTION_TIMEOUT_MILLIS,
                onConfirm = {
                    confirmDeleteConversation = false
                    AdvancedMode.touch()
                    onDeleteConversation?.invoke(convId, displayName)
                    onDismiss()
                },
                onDismiss = {
                    confirmDeleteConversation = false
                    AdvancedMode.touch()
                }
            )
        }
    }

    confirmBlockNumber?.let { numberToBlock ->
        GentleConfirmDialog(
            question = stringResource(R.string.advanced_confirm_block_number_title, numberToBlock),
            confirmLabel = stringResource(R.string.advanced_confirm_block_number_button),
            dismissLabel = stringResource(R.string.advanced_confirm_cancel),
            timeoutMillis = DESTRUCTIVE_QUESTION_TIMEOUT_MILLIS,
            onConfirm = {
                confirmBlockNumber = null
                AdvancedMode.touch()
                onBlockNumber?.invoke(numberToBlock)
                onDismiss()
            },
            onDismiss = {
                confirmBlockNumber = null
                AdvancedMode.touch()
            }
        )
    }

    // The same question her own screens ask, worded and timed the same way — a caregiver being
    // asked "Shall I ring X?" is not being condescended to, they are being shown which name the
    // row they just pressed actually belongs to.
    confirmCallRecord?.let { record ->
        ConfirmCallDialog(
            name = record.name?.let { personName(it) } ?: record.number,
            onConfirm = {
                confirmCallRecord = null
                AdvancedMode.touch()
                onCallBack?.invoke(record)
                onDismiss()
            },
            onDismiss = {
                confirmCallRecord = null
                AdvancedMode.touch()
            }
        )
    }

    confirmCallContact?.let { contact ->
        ConfirmCallDialog(
            name = personName(contact.name),
            onConfirm = {
                confirmCallContact = null
                AdvancedMode.touch()
                onCallContact?.invoke(contact)
                onDismiss()
            },
            onDismiss = {
                confirmCallContact = null
                AdvancedMode.touch()
            }
        )
    }

    confirmMuteConversation?.let { (convId, displayName) ->
        GentleConfirmDialog(
            question = stringResource(R.string.advanced_confirm_mute_conversation_title, displayName ?: convId),
            confirmLabel = stringResource(R.string.advanced_confirm_mute_conversation_button),
            dismissLabel = stringResource(R.string.advanced_confirm_cancel),
            timeoutMillis = DESTRUCTIVE_QUESTION_TIMEOUT_MILLIS,
            onConfirm = {
                confirmMuteConversation = null
                AdvancedMode.touch()
                onToggleMute?.invoke(convId, displayName)
                onDismiss()
            },
            onDismiss = {
                confirmMuteConversation = null
                AdvancedMode.touch()
            }
        )
    }
}

@Composable
private fun SheetHeader(target: AdvancedTarget) {
    val (typeTitle, subject) = when (target) {
        is AdvancedTarget.MessageTarget -> stringResource(R.string.advanced_sheet_title_message) to (target.message.displayName ?: "")
        is AdvancedTarget.ChatTarget -> stringResource(R.string.advanced_sheet_title_chat) to (target.message.displayName ?: "")
        is AdvancedTarget.CallTarget -> stringResource(R.string.advanced_sheet_title_call) to (target.record.name ?: target.record.number)
        is AdvancedTarget.ContactTarget -> stringResource(R.string.advanced_sheet_title_contact) to target.contact.name
        is AdvancedTarget.MailTarget -> stringResource(R.string.advanced_sheet_title_mail) to target.mail.senderName
    }

    Column {
        Text(
            text = typeTitle.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        if (subject.isNotBlank()) {
            Text(
                text = subject,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FactInfoBlock(target: AdvancedTarget) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (target) {
                is AdvancedTarget.MessageTarget -> {
                    val msg = target.message
                    FactRow(stringResource(R.string.advanced_info_date_time), dateFormat.format(Date(msg.timestamp)))
                    FactRow(stringResource(R.string.advanced_info_transport), transportName(msg.channel, msg.transport))
                    FactRow(stringResource(R.string.advanced_info_channel), msg.channel.name)
                    FactRow(stringResource(R.string.advanced_info_sender_id), msg.senderId ?: msg.senderAddress ?: "-")
                    FactRow(stringResource(R.string.advanced_info_direction), msg.direction.name)
                    msg.sendState?.let { FactRow(stringResource(R.string.advanced_info_send_state), it.name) }
                    msg.groupIdentity?.let {
                        FactRow(stringResource(R.string.advanced_info_group), "${it.creatorIdentity}:${it.groupId}")
                    }
                }

                is AdvancedTarget.ChatTarget -> {
                    val msg = target.message
                    FactRow(stringResource(R.string.advanced_info_transport), transportName(msg.channel, msg.transport))
                    FactRow(stringResource(R.string.advanced_info_channel), msg.channel.name)
                    msg.displayName?.let { FactRow(stringResource(R.string.advanced_info_participants), it) }
                    msg.groupIdentity?.let {
                        FactRow(stringResource(R.string.advanced_info_group), "${it.creatorIdentity}:${it.groupId}")
                    }
                    if (target.allMessagesInChat.isNotEmpty()) {
                        FactRow(stringResource(R.string.advanced_info_message_count), target.allMessagesInChat.size.toString())
                        val oldest = target.allMessagesInChat.minByOrNull { it.timestamp }
                        val newest = target.allMessagesInChat.maxByOrNull { it.timestamp }
                        if (oldest != null) {
                            FactRow(stringResource(R.string.advanced_info_first_message), dateFormat.format(Date(oldest.timestamp)))
                        }
                        if (newest != null) {
                            FactRow(stringResource(R.string.advanced_info_last_message), dateFormat.format(Date(newest.timestamp)))
                        }
                    }
                }

                is AdvancedTarget.CallTarget -> {
                    val call = target.record
                    FactRow(stringResource(R.string.advanced_info_date_time), dateFormat.format(Date(call.timestamp)))
                    FactRow(stringResource(R.string.advanced_info_direction), call.direction.name)
                    FactRow(stringResource(R.string.advanced_info_number), call.number)
                    if (call.durationSeconds > 0) {
                        // m:ss rather than "252 s" or "4 Min. 12 Sek." — the first is arithmetic
                        // to read and the second needs translating, and this row exists to answer
                        // one question quickly: did the call connect, or did it ring out.
                        val minutes = call.durationSeconds / 60
                        val seconds = call.durationSeconds % 60
                        FactRow(
                            stringResource(R.string.advanced_info_duration),
                            "%d:%02d".format(minutes, seconds)
                        )
                    }
                    call.contactId?.let { FactRow(stringResource(R.string.advanced_info_contact_id), it) }
                }

                is AdvancedTarget.ContactTarget -> {
                    val contact = target.contact
                    FactRow(stringResource(R.string.advanced_info_contact_id), contact.id)
                    FactRow(stringResource(R.string.advanced_info_all_numbers), contact.phoneNumber)
                    contact.threemaId?.let { FactRow("Threema ID", it) }
                }

                is AdvancedTarget.MailTarget -> {
                    val mail = target.mail
                    FactRow(stringResource(R.string.advanced_info_sender_address), mail.senderAddress)
                    FactRow(stringResource(R.string.advanced_info_subject), mail.subject)
                    FactRow(stringResource(R.string.advanced_info_received_time), dateFormat.format(Date(mail.timestamp)))
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.5f)
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DestructiveActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, R.string.advanced_action_copy_success, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun shareMedia(context: Context, filePath: String, caption: String?) {
    val file = File(filePath)
    if (!file.exists()) {
        shareText(context, caption.orEmpty())
        return
    }

    val extension = file.extension.lowercase()
    val mimeType = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "aac", "mp3" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    // No `Uri.fromFile` fallback. Handing a `file://` URI to another app throws
    // FileUriExposedException on everything this app runs on, so the "safety net" would be a
    // crash — and a crash in the launcher is her home screen disappearing. If the provider
    // cannot name the file, the words that came with it are still worth something.
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        shareText(context, caption.orEmpty())
        return
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (!caption.isNullOrBlank()) {
            putExtra(Intent.EXTRA_TEXT, caption)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun saveAttachmentToMediaStore(context: Context, filePath: String): Boolean {
    val file = File(filePath)
    if (!file.exists()) return false

    val extension = file.extension.lowercase()
    val mimeType = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "aac", "mp3" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val collectionUri = when {
        mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    return try {
        val uri = resolver.insert(collectionUri, contentValues) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Checks whether the current user has permissions to block numbers via BlockedNumberContract.
 */
fun canBlockNumbers(context: Context): Boolean {
    return try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
    } catch (e: Exception) {
        false
    }
}
