package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R

/**
 * Visual indicator band rendered across the top of every screen while Advanced Mode is active.
 *
 * ## The safety argument
 *
 * An earlier revision of the caregiver mode idea was rejected because an invisible or quiet mode
 * is dangerous: a phone left in advanced mode with no visible banner looks almost identical to
 * her ordinary phone, but has selection handles and long-press menus that invite accidental taps.
 * A mode nobody remembers switching on is far worse than having no advanced mode at all.
 *
 * The band solves this by being unmissable:
 *  - Rendered in [BandColor] (a dark slate/indigo tone that appears nowhere else in her warm cream/forest theme)
 *  - Explicit copy: "Erweiterter Modus – zum Beenden tippen"
 *  - Tap-to-exit: tapping anywhere on the band immediately disables Advanced Mode
 *  - **Zero space when inactive**: when off, this composable emits nothing and takes 0dp, so her
 *    normal layout never shifts by a single pixel.
 *
 * It sits below the status bar while painting through it, and the screens underneath are told the
 * status bar has been dealt with — see MainActivity, or they inset themselves a second time and
 * every screen drops by the height of the clock.
 */
@Composable
fun AdvancedModeBand(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Colour first, then the inset, so the band paints the strip behind the status bar
            // and puts its words underneath it. Without this it drew *under* the clock and the
            // wifi icon: the one thing in this app whose whole job is to be unmissable, with
            // "tap to exit" crossed out by the signal strength. The tap target starts below the
            // status bar too, which is the system's to pull down and not ours to take.
            .background(BandColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.advanced_mode_band_text),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = BandTextColor,
            textAlign = TextAlign.Center
        )
    }
}

/** Distinct from all palette colors in [ch.heuscher.gentleconnect.ui.theme.Color]. */
private val BandColor = Color(0xFF1E293B)
private val BandTextColor = Color(0xFFF8FAFC)
