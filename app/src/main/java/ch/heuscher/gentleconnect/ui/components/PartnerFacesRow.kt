package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Partner
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.ui.theme.LocalGentleFaceScale

/**
 * Section displaying communication partners ("Menschen") on the home screen.
 */
@Composable
fun PartnerFacesRow(
    partners: List<Partner>,
    onPartnerClicked: (Partner) -> Unit,
    modifier: Modifier = Modifier,
    photoSizeMultiplier: Float = 1.0f
) {
    if (partners.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.partners_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            partners.forEach { partner ->
                PartnerContactItem(
                    partner = partner,
                    photoSizeMultiplier = photoSizeMultiplier,
                    onClick = { onPartnerClicked(partner) }
                )
            }
        }
    }
}

@Composable
private fun PartnerContactItem(
    partner: Partner,
    photoSizeMultiplier: Float,
    onClick: () -> Unit
) {
    val baseSize = (80f * photoSizeMultiplier.coerceIn(0.7f, 1.6f) * LocalGentleFaceScale.current).dp
    val name = personName(partner.displayName)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // Rounded, not circular — same reason as FavoriteFacesRow: this Column holds the
            // face *and* the name, and a circular clip cuts the name along the curve.
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            // The label, not the face, sets this width. At face + 20dp a 80dp avatar left about
            // 100dp for the name, and "Claire-Lise" — an ordinary name, and the name of someone
            // she actually calls — came out as "Anne-M…". The face keeps its size; the column
            // around it is simply allowed to be wider than the circle it contains.
            .widthIn(max = (baseSize.value + 56f).dp)
    ) {
        ContactAvatar(
            photoUri = partner.photoUrl,
            name = name,
            modifier = Modifier.size(baseSize),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )

        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            // One line, as in FavoriteFacesRow: two lets a long name break at a letter boundary
            // and leaves an orphan fragment under the face.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
