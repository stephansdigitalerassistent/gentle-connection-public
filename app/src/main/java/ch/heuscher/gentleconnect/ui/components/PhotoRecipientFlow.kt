package ch.heuscher.gentleconnect.ui.components

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.PhotoTarget
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.util.traceTaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recipient selection and confirmation flow that follows "Weiter" in photo-first mode.
 *
 * ## Cognitive Design
 * The flow presents one calm decision at a time:
 * 1. **Picker ("Wem schicken?")**: Who should receive this photo, showing familiar faces and names.
 *    Ranked by her own interactions (no unfamiliar or inactive groups).
 * 2. **Confirmation**: A calm confirmation question naming the recipient. If sending to a group,
 *    it additionally displays a small thumbnail of the photo, avatars of group members (+N), and
 *    how many people will see it ("6 Leute sehen das Foto"), ensuring she is never surprised by
 *    who sees her picture.
 *
 * BackHandler hierarchy: confirm -> picker -> frozen camera.
 */
@Composable
fun PhotoRecipientFlow(
    photoPath: String,
    targets: List<PhotoTarget>,
    onSend: suspend (target: PhotoTarget) -> Boolean,
    onSuccess: (target: PhotoTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTarget by remember { mutableStateOf<PhotoTarget?>(null) }
    var isSending by remember { mutableStateOf(false) }

    var preparedBitmap by remember(photoPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoPath) {
        preparedBitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(photoPath)?.asImageBitmap()
        }
    }

    val currentTarget = selectedTarget
    if (currentTarget == null) {
        // Step 1: Picker "Wem schicken?"
        BackHandler { onDismiss() }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .traceTaps("photo-picker")
                    .padding(8.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.photo_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        targets.forEach { target ->
                            val recipient = target.recipient
                            val isGroup = recipient.groupIdentity != null

                            GentleKey(
                                onClick = { selectedTarget = target },
                                modifier = Modifier.fillMaxWidth(),
                                face = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(16.dp),
                                minHeight = 72.dp,
                                fillWidth = true,
                                contentAlignment = Alignment.Start
                            ) {
                                if (isGroup) {
                                    GroupAvatar(
                                        picture = target.picture,
                                        size = 56.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = recipient.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val count = target.memberCount
                                        if (count > 0) {
                                            Text(
                                                text = pluralStringResource(
                                                    R.plurals.photo_group_member_count,
                                                    count,
                                                    count
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.8f
                                                ),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                } else {
                                    ContactAvatar(
                                        photoUri = recipient.photoUri,
                                        name = personName(recipient.name),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = personName(recipient.name),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    GentleSecondaryKey(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        minHeight = 48.dp
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    } else {
        // Step 2: Confirm
        BackHandler {
            if (!isSending) selectedTarget = null
        }

        val onConfirmSend = {
            if (!isSending) {
                isSending = true
                scope.launch {
                    val success = onSend(currentTarget)
                    isSending = false
                    if (success) {
                        onSuccess(currentTarget)
                    } else {
                        Toast.makeText(context, R.string.camera_send_failed, Toast.LENGTH_LONG).show()
                        selectedTarget = null
                    }
                }
            }
        }

        val onDismissConfirm = {
            if (!isSending) selectedTarget = null
        }

        if (currentTarget.recipient.groupIdentity == null) {
            // Confirm Person
            GentleConfirmDialog(
                question = stringResource(
                    R.string.photo_confirm_person,
                    personName(currentTarget.recipient.name)
                ),
                confirmLabel = stringResource(R.string.camera_send),
                dismissLabel = stringResource(R.string.camera_back),
                timeoutMillis = FORWARD_QUESTION_MILLIS,
                onConfirm = onConfirmSend,
                onDismiss = onDismissConfirm,
                isLoading = isSending
            )
        } else {
            // Confirm Group with photo preview, member avatars, and count explanation
            GroupPhotoConfirmDialog(
                target = currentTarget,
                photoBitmap = preparedBitmap,
                isSending = isSending,
                onConfirm = onConfirmSend,
                onDismiss = onDismissConfirm
            )
        }
    }
}

/**
 * Group photo confirmation dialog.
 *
 * Displays a small photo preview, member avatars, and explicit recipient count copy,
 * ensuring she is clearly aware that the photo will be visible to everyone in the group.
 */
@Composable
private fun GroupPhotoConfirmDialog(
    target: PhotoTarget,
    photoBitmap: ImageBitmap?,
    isSending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val opacity = gentleDialogOpacity(FORWARD_QUESTION_MILLIS, onDismiss)

    AlertDialog(
        modifier = Modifier
            .graphicsLayer { alpha = opacity }
            .traceTaps("confirm-group-photo"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.photo_confirm_group, target.recipient.name),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                photoBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                val members = target.picture?.faces.orEmpty()
                val shown = members.take(5)
                val hidden = members.size - shown.size
                if (shown.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        shown.forEach { face ->
                            ContactAvatar(
                                photoUri = face.photoUri,
                                name = personName(face.name),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        if (hidden > 0) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$hidden",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                val count = target.memberCount.coerceAtLeast(members.size)
                if (count > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.photo_group_members_see,
                            count,
                            count
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            GentlePrimaryKey(
                onClick = onConfirm,
                enabled = !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.camera_send),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        },
        dismissButton = {
            GentleSecondaryKey(
                onClick = onDismiss,
                enabled = !isSending
            ) {
                Text(
                    text = stringResource(R.string.camera_back),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}
