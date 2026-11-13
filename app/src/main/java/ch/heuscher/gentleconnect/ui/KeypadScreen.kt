package ch.heuscher.gentleconnect.ui

import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.CallHistory
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.ConfirmCallDialog
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.keypadZone
import java.util.Locale

/**
 * The keys, for a number that belongs to nobody yet.
 *
 * Almost every call she makes is a face on the home screen or a name in the address book, and
 * that is the point of this app — but not every number she needs is a person she has. The
 * surgery rings back from a different line, a taxi has to be ordered, the number is on a letter
 * in front of her. Until this screen existed the answer to all of those was that the phone could
 * not do it, and somebody else had to be found who could.
 *
 * So it is deliberately not the way in. It sits behind the Call button, one press past the
 * address book, which is where somebody who came looking for keys will look and where somebody
 * who did not will never land by accident.
 *
 * Two things the ordinary Android dialer does not do:
 *
 * - It says who the number is, as soon as enough of it is typed. She may well be typing a number
 *   that is already her daughter's, off a piece of paper, and being told so before it rings is
 *   the difference between a call and a doubt.
 * - It offers to keep the number. A number rung once and not saved is a number to be typed again
 *   next week — see [ch.heuscher.gentleconnect.data.ContactEditing], which is where that goes and
 *   why it goes to the telephone's own editor rather than a screen of this app's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadScreen(
    /** Her address book, so a typed number can be recognised as somebody. */
    contacts: List<Contact>,
    onCallNumber: (String) -> Unit,
    onSaveNumber: (String) -> Unit,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onBackToPhoneBook: () -> Unit,
    onSpeakRequested: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onMessagesRequested: () -> Unit,
    onMailRequested: () -> Unit,
    messagesMarked: Boolean = false,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    /**
     * A number this screen was opened *with*, from another app asking the telephone to dial.
     *
     * Now that this app is the telephone, a number pressed in a browser or a letter in the mail
     * arrives here rather than in somebody else's dialer. It is put in the field and left there:
     * nothing is rung without her saying so, which is the same rule as everywhere else in the app.
     */
    initialNumber: String = "",
    modifier: Modifier = Modifier
) {
    // Saveable: a number half typed when the screen rotates or Android reclaims the activity is a
    // number she has to read off the letter and type again.
    var typed by rememberSaveable { mutableStateOf(initialNumber) }

    // Keyed on the number rather than fired once: another app can ask for a second number while
    // this screen is already open, and leaving the first one in the field would ring the wrong
    // person. Blank never clears what she has typed — that would make coming back to the keypad
    // by any other route wipe the number she was halfway through.
    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) typed = initialNumber
    }
    var confirming by remember { mutableStateOf(false) }

    // Who this is, if it is anybody. Matched on the end of the number, because her address book
    // holds `+41 79 …` and she is typing `079 …` — see [CallHistory.sameNumber].
    val known = remember(typed, contacts) {
        if (typed.count { it.isDigit() } < RECOGNISABLE_FROM) {
            null
        } else {
            contacts.firstOrNull { CallHistory.sameNumber(it.phoneNumber, typed) }
        }
    }

    // The same question the address book asks, in the same words, for the same reason: nothing on
    // this phone starts ringing on one press.
    if (confirming && typed.isNotEmpty()) {
        ConfirmCallDialog(
            name = known?.let { personName(it.name) } ?: readableNumber(typed),
            onConfirm = {
                confirming = false
                onCallNumber(typed)
            },
            onDismiss = { confirming = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.CALL,
                onHomeClicked = onHomeTab,
                // The Call button goes back to the people, which is what "Call" has always meant
                // here. It is the tab she is inside, so it must lead somewhere rather than do
                // nothing — and the address book is where this screen was opened from.
                onCallClicked = onBackToPhoneBook,
                onMessageClicked = onMessagesRequested,
                onLookClicked = onMailRequested,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            GentleScreenHeader(
                title = stringResource(R.string.keypad_title),
                onBack = onBackToPhoneBook,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            // What she has typed, and who it turns out to be.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = typed.ifEmpty { stringResource(R.string.keypad_placeholder) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (typed.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    // Held even when it is nobody, so that the keys do not walk up and down the
                    // screen as she types — a moving keypad is a keypad she has to re-find after
                    // every digit.
                    text = known?.let { personName(it.name) }.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .keypadZone(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                KEYS.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { key ->
                            KeypadKey(
                                key = key,
                                onClick = { if (typed.length < MOST_DIGITS) typed += key },
                                // Holding zero types a plus, which is how every Android dialer
                                // has always behaved. Not a fifth row of its own: the plus is a
                                // key for whoever is helping her copy an international number off
                                // a letter, and paying a row of screen height for it would push
                                // the keys she does press down into the bar at the bottom.
                                onLongClick = if (key == "0") {
                                    { if (typed.length < MOST_DIGITS) typed += "+" }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keeping the number, on the left where nothing rings. Offered only for a number
                // long enough to be a real one and only when it is nobody she already has — the
                // point of it is to stop the address book filling with second copies of people.
                RoundAction(
                    icon = Icons.Default.PersonAdd,
                    contentDescription = stringResource(R.string.keypad_save_number),
                    enabled = known == null && typed.count { it.isDigit() } >= RECOGNISABLE_FROM,
                    onClick = { onSaveNumber(typed) }
                )

                Box(
                    modifier = Modifier
                        .size(CALL_SIZE)
                        .clip(CircleShape)
                        .background(
                            if (typed.isEmpty()) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        .clickable(enabled = typed.isNotEmpty()) { confirming = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = stringResource(R.string.keypad_call),
                        tint = if (typed.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Press for one digit, hold to clear the lot. Holding is the caregiver's shortcut;
                // pressing eleven times does the same thing and is what she will do.
                Box(
                    modifier = Modifier
                        .size(ACTION_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .keypadZone()
                        .combinedClickable(
                            enabled = typed.isNotEmpty(),
                            onClick = { typed = typed.dropLast(1) },
                            onLongClick = { typed = "" }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = stringResource(R.string.keypad_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

/** One key. Round, large, and the same as every other one — there is nothing to distinguish. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadKey(key: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .keypadZone()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * A round button that is not the telephone.
 *
 * Disabled means faded and unpressable, not absent: a button that comes and goes as she types
 * changes the shape of the row under her thumb, and she has no way to know why.
 */
@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(ACTION_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            modifier = Modifier.size(30.dp)
        )
    }
}

/**
 * A number as it would be read out, for the "shall I ring …" question about a stranger.
 *
 * The framework's formatter when it can manage it — it carries every country's grouping and this
 * app has no business reimplementing that — and the digits as typed when it cannot.
 */
private fun readableNumber(number: String): String =
    runCatching { PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: number

private val KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#")
)

/**
 * How many digits before a number is worth recognising or keeping.
 *
 * Below this it is a fragment, and matching a fragment against the address book would announce
 * her daughter's name over the first four digits of the surgery's number.
 */
private const val RECOGNISABLE_FROM = 6

/** Longer than any real number, and short enough to stay on one line. */
private const val MOST_DIGITS = 18

private val KEY_SIZE = 76.dp
private val ACTION_SIZE = 64.dp
private val CALL_SIZE = 80.dp
