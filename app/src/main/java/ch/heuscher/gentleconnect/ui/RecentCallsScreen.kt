package ch.heuscher.gentleconnect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import ch.heuscher.gentleconnect.ui.components.GentleKey
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.CallDirection
import ch.heuscher.gentleconnect.model.CallRecord
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.ConfirmCallDialog
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.formatTimeInWords
import ch.heuscher.gentleconnect.ui.components.scrollbarFor

/**
 * Who has rung, and who she has rung — the telephone's own record, read out plainly.
 *
 * It answers a question the rest of this app cannot: *did somebody ring while I was in the
 * kitchen?* The card on the home screen says so about the newest missed call and then goes away
 * once it is answered, which is right for a card and useless as a record. This is the record.
 *
 * Three things it deliberately does not have:
 *
 * - **No counts.** Ringing her son four times running is one row saying she rang him — see
 *   [ch.heuscher.gentleconnect.model.CallHistory]. "(4)" beside his name reads as four separate
 *   afternoons, or as something having gone wrong.
 * - **No durations.** How long a call lasted is a fact about a bill, not about her family.
 * - **No second button on the row.** Every row does one thing, which is ring that number back —
 *   the same rule the address book keeps, and for the same reason: a list where some rows have
 *   an extra control and others do not invites the question of why her son's row is different
 *   from her sister's.
 *
 * Correcting the address book is here all the same, on a hold rather than a tap. That is the
 * caregiver's gesture in this app — the same one that re-sends a message that did not go — and
 * saving the number the surgery rang from is the caregiver's job. The help text says so; nothing
 * on the screen does, because for her there is nothing here to do but ring somebody back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentCallsScreen(
    calls: List<CallRecord>,
    /** Whether the telephone will let this app read its record of calls at all. */
    canReadCalls: Boolean,
    onCallBack: (CallRecord) -> Unit,
    /** Hold a row: correct whoever it is, or keep the number of whoever it is not. */
    onEditRequested: (CallRecord) -> Unit,
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
    onOpenAdvancedSheet: ((CallRecord) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isAdvanced by AdvancedMode.isOn.collectAsState()
    var callingBack by remember { mutableStateOf<CallRecord?>(null) }
    val listState = rememberLazyListState()

    callingBack?.let { record ->
        ConfirmCallDialog(
            name = record.name?.let { personName(it) } ?: record.number,
            onConfirm = {
                callingBack = null
                onCallBack(record)
            },
            onDismiss = { callingBack = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.CALL,
                onHomeClicked = onHomeTab,
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
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                title = stringResource(R.string.recent_calls_title),
                onBack = onBackToPhoneBook,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            if (calls.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = OUTSIDE_THE_LANE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        // Two different nothings, as everywhere else in this app. "No calls yet"
                        // to somebody whose telephone is simply not letting the app look would be
                        // the phone stating something false, and the thing that needs fixing is
                        // then invisible to whoever set it up.
                        text = stringResource(
                            if (canReadCalls) {
                                R.string.recent_calls_empty
                            } else {
                                R.string.recent_calls_unavailable
                            }
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(calls, key = { it.id }) { record ->
                            CallRow(
                                record = record,
                                onClick = { callingBack = record },
                                onHold = {
                                    if (isAdvanced && onOpenAdvancedSheet != null) {
                                        onOpenAdvancedSheet(record)
                                    } else {
                                        onEditRequested(record)
                                    }
                                }

                            )
                        }
                    }

                    GentleScrollbar(
                        bar = scrollbarFor(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * One call.
 *
 * Shaped like the rows in the message list on purpose: a mark on the left, who it was, when it
 * was. Two lists that hold different things but behave the same way are one thing to learn.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    record: CallRecord,
    onClick: () -> Unit,
    onHold: () -> Unit
) {
    val missed = record.direction == CallDirection.MISSED

    GentleKey(
        onClick = onClick,
        onLongClick = onHold,
        modifier = Modifier.fillMaxWidth(),
        // Amber for a missed call, which is what amber already means everywhere in this app:
        // somebody wanted her and she has not got to it yet. The same colour as the card at
        // the top of the home screen that announces it, so the two are recognisable as the
        // same event.
        face = if (missed) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (missed) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(20.dp),
        minHeight = 72.dp,
        contentPadding = PaddingValues(16.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (missed) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                // Which way the call went, as an arrow rather than a word. Three arrows are
                // three shapes to recognise; three sentences would be three lines to read on
                // every row of a list she is scanning.
                imageVector = when (record.direction) {
                    CallDirection.MISSED -> Icons.AutoMirrored.Filled.CallMissed
                    CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                    CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                    CallDirection.BLOCKED -> Icons.Filled.Block
                },
                contentDescription = stringResource(
                    when (record.direction) {
                        CallDirection.MISSED -> R.string.call_direction_missed
                        CallDirection.OUTGOING -> R.string.call_direction_outgoing
                        CallDirection.INCOMING -> R.string.call_direction_incoming
                        CallDirection.BLOCKED -> R.string.call_direction_blocked
                    }
                ),
                tint = if (missed) {
                    MaterialTheme.colorScheme.onTertiary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier.size(26.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                // The name when there is one, the number when there is not — never "Unknown
                // caller", which makes a stranger out of a number she may well recognise on
                // sight. The number is the only true thing there is to say.
                text = record.name?.let { personName(it) } ?: record.number,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (missed) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatTimeInWords(record.timestamp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
