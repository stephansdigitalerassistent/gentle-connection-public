package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.util.UiTrace
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R

/** Which of the four she is looking at. */
enum class BottomTab { HOME, CALL, MESSAGE, LOOK }

/**
 * The four big buttons: Home — Call — Message — View.
 *
 * Shared, not per screen. It used to live inside the home screen alone, so opening her post
 * took the four buttons away and left one back arrow in the corner — the way out of a screen
 * changed depending on which screen she was on, which is exactly the thing this app exists to
 * avoid. It is now on every screen she can reach, always in the same place, with the tab she
 * is on marked.
 */
@Composable
fun GentleBottomBar(
    selected: BottomTab,
    onHomeClicked: () -> Unit,
    onCallClicked: () -> Unit,
    onMessageClicked: () -> Unit,
    onLookClicked: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether somebody has written and she has not seen it yet — see [Home.marksTheMessageTab].
     *
     * The whole reason this parameter exists: the card at the top of the home screen is the only
     * place that says so, and walking to any other screen takes it away. She goes to the address
     * book to call her son, and while she is there nothing on the phone remembers that her
     * daughter wrote ten minutes ago. This bar is on every screen, so the fact can live with it.
     */
    messagesMarked: Boolean = false
) {
    // The strings carry soft hyphens (U+00AD) where a word may be broken: "Nach\u00ADricht".
    // Everything is measured without them; they are only used when a word will not fit even at
    // the smallest size. See [breakAtSoftHyphen].
    val hyphenated = listOf(
        stringResource(R.string.nav_home),
        stringResource(R.string.nav_call),
        stringResource(R.string.nav_message),
        stringResource(R.string.nav_mail)
    )
    val labels = hyphenated.map { it.replace(SOFT_HYPHEN, "") }

    // The width the four buttons actually get, asked before anything is drawn in them, because
    // the words have to be made to fit it. See fitToWidth.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // One size for all four, not one each: four labels in four different sizes would say
        // that some of them matter more. The size used to be a flat 14.sp, which was two
        // mistakes at once — it ignored her text-size setting, so on the wide inner screen of a
        // folding phone every word grew except the four she navigates by, and it fit nothing on
        // the cover screen, where the four German words came out as "Zuhaus/e", "Anrufe/n",
        // "Nachric/ht", "Ansehe/n".
        val keyWidth = (maxWidth - (OuterPadding * 2) - (KeySpacing * (labels.size - 1))) / labels.size
        val available = (keyWidth - (KeyContentHorizontalPadding * 2)).coerceAtLeast(0.dp)
        val labelStyle = fitToWidth(
            texts = labels,
            available = available,
            base = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            minSize = MinLabelSize
        )
        // At the smallest size and still too wide — "Nachricht" on the cover screen of a folding
        // phone at her font scale of 1.3 came out as "Nachri…". Broken where German breaks it,
        // "Nach-" over "richt", rather than cut: two halves of a word she can read are the word.
        val shown = breakAtSoftHyphen(hyphenated, labels, labelStyle, available)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // A minimum, not a fixed height. Pinned at 90.dp the navigation-bar inset was
                // taken out of that same 90.dp rather than added to it, so on a phone with
                // gesture bars the four labels were sliced off along the bottom edge — verified
                // on the device. These four buttons are how she gets anywhere, so they must
                // never be clipped.
                .heightIn(min = 90.dp)
                .padding(OuterPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KeySpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomTabItem(BottomTab.HOME, selected, Icons.Default.Home, shown[0], labelStyle, onHomeClicked)
                BottomTabItem(BottomTab.CALL, selected, Icons.Default.Phone, shown[1], labelStyle, onCallClicked)
                BottomTabItem(
                    BottomTab.MESSAGE,
                    selected,
                    Icons.Default.Message,
                    shown[2],
                    labelStyle,
                    onMessageClicked,
                    marked = messagesMarked
                )
                BottomTabItem(BottomTab.LOOK, selected, Icons.Default.Face, shown[3], labelStyle, onLookClicked)
            }
        }
    }
}

/** Outer padding of the bottom bar container. */
private val OuterPadding = 8.dp

/** Gap between physical keys in the bottom bar. */
private val KeySpacing = 6.dp

/** Horizontal content padding inside each key. */
private val KeyContentHorizontalPadding = 4.dp

/**
 * No word on these four buttons ever gets smaller than this, whatever the screen does.
 *
 * If a translation cannot be made to fit even here it is cut with an ellipsis rather than shrunk
 * into something she cannot read — a word she can half-read is worth more than four she cannot.
 */
private val MinLabelSize = 11.sp

private const val SOFT_HYPHEN = "\u00AD"

/**
 * Each label as it is to be shown: whole when it fits [available] at [style], otherwise broken
 * over two lines at its first soft hyphen ("Nach-\nricht"). A label with no soft hyphen is left
 * whole and cut with an ellipsis, as before.
 */
@Composable
private fun breakAtSoftHyphen(
    hyphenated: List<String>,
    plain: List<String>,
    style: TextStyle,
    available: Dp
): List<String> {
    val measurer = rememberTextMeasurer()
    val budgetPx = with(LocalDensity.current) { available.coerceAtLeast(0.dp).toPx() }
    return remember(hyphenated, style, budgetPx, measurer) {
        hyphenated.mapIndexed { i, word ->
            val fits = measurer.measure(
                text = AnnotatedString(plain[i]),
                style = style,
                maxLines = 1,
                softWrap = false
            ).size.width <= budgetPx
            if (fits || !word.contains(SOFT_HYPHEN)) plain[i]
            else word.replaceFirst(SOFT_HYPHEN, "-\n").replace(SOFT_HYPHEN, "")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.BottomTabItem(
    tab: BottomTab,
    selected: BottomTab,
    icon: ImageVector,
    label: String,
    labelStyle: TextStyle,
    onClick: () -> Unit,
    marked: Boolean = false
) {
    val isSelected = tab == selected
    val haptic = LocalHapticFeedback.current
    GentleKey(
        onClick = {
            if (isSelected) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                UiTrace.event("reselected tab $tab")
            }
            onClick()
        },
        modifier = Modifier
            .weight(1f)
            .semantics { this.selected = isSelected },
        face = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        minHeight = 72.dp,
        contentPadding = PaddingValues(horizontal = KeyContentHorizontalPadding, vertical = 8.dp),
        contentAlignment = Alignment.CenterHorizontally,
        fillWidth = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BadgedBox(
                badge = {
                    // Amber, which in this app means other people — the same colour as the card
                    // at the top of home and the faces of the people who write to her. Not red:
                    // red here would be the only alarm on a phone that deliberately has none, and
                    // nothing has gone wrong. A dot, not a number, and the size of one on a
                    // screen where everything else is large, because it is a mark on a button she
                    // already knows rather than a thing to read.
                    if (marked) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            ) {
                Icon(icon, contentDescription = label.replace("-\n", ""), modifier = Modifier.size(30.dp))
            }
            Text(
                text = label,
                style = labelStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
