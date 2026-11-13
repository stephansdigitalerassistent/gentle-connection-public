package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.ui.messageInWords
import ch.heuscher.gentleconnect.ui.personName

/**
 * A quiet citation above a message bubble showing what it is answering.
 *
 * Shows who wrote the quoted message and up to two lines of what they said, with a 4dp accent bar
 * on the leading edge. Never tappable: jumping to an earlier point in the conversation is a
 * gesture that disorients someone who has trouble navigating back. Her own quoted message is
 * labelled "Your message", never with her name.
 */
@Composable
fun QuotedMessageCard(
    quotedSenderName: String?,
    quotedText: String?,
    quotedIsHers: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (quotedText.isNullOrBlank()) return

    val authorName = when {
        quotedIsHers -> stringResource(R.string.quote_your_message)
        else -> personName(quotedSenderName)
    }

    val words = messageInWords(quotedText)
    val displayWords = truncateForCard(words)

    val barColor = MaterialTheme.colorScheme.primary
    val barWidth = 4.dp
    val cardShape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .drawBehind {
                drawRect(
                    color = barColor,
                    size = Size(barWidth.toPx(), size.height)
                )
            }
            .padding(start = 12.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
    ) {
        Text(
            text = authorName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = displayWords,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val MAX_CARD_TEXT_LENGTH = 120

private fun truncateForCard(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length <= MAX_CARD_TEXT_LENGTH) return trimmed
    return trimmed.take(MAX_CARD_TEXT_LENGTH).trimEnd() + "…"
}
