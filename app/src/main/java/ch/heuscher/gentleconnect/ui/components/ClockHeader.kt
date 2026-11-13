package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top section displaying current time and date in large, clear type.
 * e.g., "09:24" / "Donnerstag, 23. Juli".
 *
 * Tapping the clock [CAREGIVER_TAP_COUNT] times within [CAREGIVER_TAP_WINDOW_MS]
 * reveals the caregiver pairing code via [caregiverTaps]. A hold is deliberately *not* used: resting a finger on
 * the screen is exactly the kind of thing that happens by accident, and a slow hand cannot
 * complete this one at all. Five deliberate taps at speed are something she will not produce
 * while reading the time.
 */
@Composable
fun ClockHeader(
    timeString: String,
    dateString: String,
    onCaregiverGesture: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 8.dp under the date rather than 16.dp, and the appointment that follows is no
            // longer a scroll-gap below it either: the clock is a fixed header now, and the two
            // read as one sentence — what day it is, and what that day holds. Half a
            // centimetre of nothing between them was half a centimetre away from her son's face.
            .padding(top = 8.dp, bottom = 8.dp)
            .caregiverTaps(onCaregiverGesture),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = timeString,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        // Two lines when one will not do, broken where the sentence breaks: "Freitag" over
        // "18. September". Left to itself the date wrapped wherever the width ran out — on the
        // cover screen of a folding phone "Freitag, 18." over "September", beside the help button.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val style = MaterialTheme.typography.titleMedium
            val lines = dateLines(dateString, style, maxWidth)
            // A half that is still too wide gets smaller rather than wrapping in the middle.
            val fitted = fitToWidth(texts = lines, available = maxWidth, base = style, minSize = 14.sp)
            Text(
                text = lines.joinToString("\n"),
                style = fitted,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * The date as one line if it fits [available], otherwise as the weekday and the rest:
 * "Freitag, 18. September" → "Freitag" / "18. September", and "Friday, September 18" likewise.
 * Without a comma it is split at the first space.
 */
@Composable
private fun dateLines(date: String, style: TextStyle, available: Dp): List<String> {
    val measurer = rememberTextMeasurer()
    val budgetPx = with(LocalDensity.current) { available.toPx() }
    return remember(date, style, budgetPx, measurer) {
        val width = measurer.measure(
            text = AnnotatedString(date),
            style = style,
            maxLines = 1,
            softWrap = false
        ).size.width
        val comma = date.indexOf(", ")
        val space = date.indexOf(' ')
        when {
            width <= budgetPx -> listOf(date)
            comma > 0 -> listOf(date.substring(0, comma), date.substring(comma + 2))
            space > 0 -> listOf(date.substring(0, space), date.substring(space + 1))
            else -> listOf(date)
        }
    }
}

