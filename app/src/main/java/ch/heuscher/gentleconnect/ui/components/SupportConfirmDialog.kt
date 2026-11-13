package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportMember
import ch.heuscher.gentleconnect.model.SupportNames
import kotlinx.coroutines.delay
import ch.heuscher.gentleconnect.util.traceTaps

private const val SUPPORT_CONFIRM_TIMEOUT_MILLIS = 60_000
private const val FADE_MILLIS = 2_000
private const val INPUT_IGNORE_MILLIS = 500L

/**
 * Confirmation dialog for the support button.
 *
 * Requirements:
 * - Every member's face side by side (56dp circle clip), scrolling sideways when needed.
 * - Question: "Soll [Name/Gruppe] Bescheid bekommen?"
 * - Buttons: "Ja, Bescheid geben" (large, primary, bottom) and "Nein" (quiet, above it).
 * - Anti-panic: 500ms initial tap ignore; "Ja" is far from top-right.
 * - 60s timeout: silently invokes [onTimeout] (which records [CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED]).
 */
@Composable
fun SupportConfirmDialog(
    members: List<SupportMember>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit
) {
    val timeoutNow by rememberUpdatedState(onTimeout)
    var opacity by remember { mutableFloatStateOf(1f) }
    var isInputAllowed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(INPUT_IGNORE_MILLIS)
        isInputAllowed = true
    }

    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }
        while (true) {
            val elapsedMillis = (withFrameNanos { it } - startedAt) / 1_000_000L
            if (elapsedMillis >= SUPPORT_CONFIRM_TIMEOUT_MILLIS) break
            val fadingFor = elapsedMillis - (SUPPORT_CONFIRM_TIMEOUT_MILLIS - FADE_MILLIS)
            opacity = if (fadingFor <= 0) 1f else 1f - fadingFor.toFloat() / FADE_MILLIS
        }
        opacity = 0f
        timeoutNow()
    }

    // One person is asked for by name. Several are "deine Familie" whatever the portal calls the
    // group: that name is for the family's messages, and a word like "Notfallteam" is not one she
    // would recognise as the people she knows.
    val targetName = if (members.size == 1) SupportNames.shortName(members.first().name)
        else stringResource(R.string.support_default_group_name)
    val question = stringResource(R.string.support_confirm_question, targetName)

    AlertDialog(
        modifier = Modifier.graphicsLayer { alpha = opacity }.traceTaps("support"),
        onDismissRequest = {
            if (isInputAllowed) onDismiss()
        },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Faces row: the whole group, sliding sideways when it does not fit
                if (members.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        members.forEach { member ->
                            ContactAvatar(
                                photoUri = member.buttonPhotoUri,
                                name = member.name,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = question,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // "Nein" (quiet, secondary, above "Ja")
                GentleSecondaryKey(
                    onClick = {
                        if (isInputAllowed) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 48.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.support_confirm_no),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // "Ja, Bescheid geben" (large, primary, bottom)
                GentlePrimaryKey(
                    onClick = {
                        if (isInputAllowed) onConfirm()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 56.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.support_confirm_yes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = null
    )
}
