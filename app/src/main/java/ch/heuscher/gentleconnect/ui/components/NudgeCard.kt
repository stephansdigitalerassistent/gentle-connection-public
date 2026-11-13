package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.ui.messageInWords
import ch.heuscher.gentleconnect.ui.personName

/** How much of a message the card shows before "…" and the conversation behind it take over. */
private const val MESSAGE_MAX_LINES = 3

/**
 * A missed call is a name, and a name gets as many lines as it needs. Six is not a limit anybody
 * will reach — it is there so that a contact saved as a whole sentence cannot push the faces off
 * the screen.
 */
private const val MISSED_CALL_MAX_LINES = 6

/**
 * Prominent card showing at most ONE single nudge prompt on the home screen.
 */
@Composable
fun NudgeCard(
    nudge: Nudge,
    onViewClicked: (Nudge) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The word on the button, and on the card, since the whole card is the button.
     *
     * "Open" for everything now. It used to say "Call" when a missed call could be rung back,
     * and the card rang the telephone; a card that appears under her thumb unasked is the
     * easiest press on the phone to make by accident, so it now only ever shows her something.
     */
    actionLabel: String = stringResource(R.string.nudge_action)
) {
    val headlineText = when (nudge) {
        // One sentence, not a headline with the message filed under it. "Laura hat geschrieben"
        // and then, separately, "Go" was the card asking her to put two things together; what
        // she is owed is the thing itself — who, and what they said, in the order somebody
        // standing in the kitchen would say it.
        is Nudge.MessageNudge -> stringResource(
            R.string.nudge_message_wrote,
            personName(nudge.senderName),
            messageInWords(nudge.message.text)
        )
        is Nudge.MissedCallNudge -> if (nudge.senderName.isBlank()) {
            stringResource(R.string.nudge_missed_call)
        } else {
            stringResource(R.string.nudge_missed_call_from, personName(nudge.senderName))
        }
    }

    GentleKey(
        onClick = { onViewClicked(nudge) },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        face = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        fillWidth = true,
        contentPadding = PaddingValues(20.dp)
    ) {
        // The whole card is a physical key, not only the button on the end of it. The card is one sentence about
        // one message and it has one meaning — press here to see it — so the part of it that
        // answers her thumb should be all of it. A card where the words do nothing and a small
        // green rectangle beside them does everything is a card she can press and be ignored
        // by, on the screen where being ignored matters most.
        //
        // Always, now. This used to go quiet when the card had nothing obvious behind it —
        // a missed call from a number nobody has saved — on the reasoning that a button which
        // does nothing teaches her that pressing things does not work. True, and the wrong
        // conclusion: what it produced was the loudest thing on her screen, with a name she
        // recognised on it, ignoring her thumb. The answer was never silence, it was somewhere
        // to go. See HomeScreen, where a call from a stranger now opens her list of calls.
        //
        // No "Notification" heading above this. It named the channel the message came in
        // on, which is the one thing this app never tells her — and it said nothing the
        // sentence underneath does not already say better, with a person's name in it.
        //
        // She was fetched to the phone to read what her daughter wrote, and she can read it
        // here without pressing anything; the button then takes her to the conversation to
        // answer it. One size of type for the whole sentence — the name is not a heading over
        // the words, it is the first half of them — and it is allowed to run on past the
        // first line rather than being cut at the colon.
        //
        // The "Open" label sits under the sentence, not beside it, on every screen — the folded cover,
        // the open phone and the two-column layout alike. Beside it, it took a fixed width out of
        // the one column that has to say who wrote, which is how "Anruf verpasst von Ste…"
        // happened; under it, the sentence has the card's whole width and the label still says
        // where to press.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = headlineText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // Three lines is a cap for a message, whose text can run on for a paragraph and
                // has somewhere to be read in full. It is the wrong cap for a missed call, whose
                // whole content is a name: folded, on the cover screen of a folding phone, the
                // column beside the button is about a hundred and twenty points wide and
                // "Anruf verpasst von Simon" came out as "Anruf verpasst von Ste…" — measured
                // on the device. The card was loud, orange, and did not say who had rung. A name
                // is never long enough to be worth truncating, so it is not truncated.
                maxLines = when (nudge) {
                    is Nudge.MissedCallNudge -> MISSED_CALL_MAX_LINES
                    is Nudge.MessageNudge -> MESSAGE_MAX_LINES
                },
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // A label, not a second button. The card is already the key — it has the lip, it
            // sinks under her thumb — and a key on a key was two things to press that did the
            // same thing, and two stops for a screen reader. This keeps the word and the colour
            // that say where to press, and stays flat: only pressable things get a lip, and
            // the thing being pressed here is the card it sits on.
            Box(
                modifier = Modifier
                    .heightIn(min = GentleKeyDefaults.MinHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(GentleKeyDefaults.ContentPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
