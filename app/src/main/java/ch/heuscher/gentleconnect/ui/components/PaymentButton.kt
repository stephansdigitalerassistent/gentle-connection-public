package ch.heuscher.gentleconnect.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.PaymentButtonState
import ch.heuscher.gentleconnect.ui.theme.PaleSage
import ch.heuscher.gentleconnect.ui.theme.SupportBlue
import ch.heuscher.gentleconnect.ui.theme.SupportBlueOnDark
import ch.heuscher.gentleconnect.ui.theme.WarmForestGreen
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * The same 56dp square and 108dp drop as [SupportButtonSize] and [SupportButtonHeight] at the
 * other end of the clock row — and, below, laid out the same way as well, which is the part that
 * matters: one button wide in the row, the rest drawn past its bottom edge.
 */
val PaymentButtonSize = 56.dp
val PaymentButtonHeight = 108.dp

/**
 * Her card, at the top-left of the home screen, opposite the support button.
 *
 * It has two faces and no timer. [PaymentButtonState.Idle] shows the card over "PIN", and
 * touching it opens the system's own PIN screen. [PaymentButtonState.Ready] shows a tick and the
 * whole tile turns green: the phone has been strongly unlocked recently enough that the payment
 * key works, so the terminal will take the tap. Which of the two it is, is read from the phone
 * every few seconds — see data/payment/PaymentAuthGate — so the tick goes out by itself when the
 * unlock window does.
 *
 * Both faces are drawn as a *filled* tile in a solid colour, blue or green, the way the support
 * button opposite is. The first version painted it at 15% alpha over the background with a muted
 * glyph inside, which on the phone read as a slightly darker patch of wallpaper: on a test phone on
 * 2026-09-20 neither state stood out at arm's length, and the green one — the state the whole
 * feature exists to announce — was the weaker of the two, because white on PaleSage is barely a
 * colour at all. Solid fill, and the ink follows the support button's rule: white on the deep
 * light-theme colours, near-black on the pale dark-theme ones.
 *
 * [PaymentButtonState.Unavailable] is not drawn here at all; the home screen leaves the slot
 * empty. See HomeScreen.
 */
@Composable
fun PaymentButton(
    state: PaymentButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReady = state == PaymentButtonState.Ready

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val readyGreen = if (isDark) PaleSage else WarmForestGreen
    val cardBlue = if (isDark) SupportBlueOnDark else SupportBlue

    // Green the moment the phone will take a tap, blue while it still wants the PIN. Animated so
    // that the change catches the eye of somebody who was not looking straight at it.
    val tileColor by animateColorAsState(
        targetValue = if (isReady) readyGreen else cardBlue,
        animationSpec = tween(400),
        label = "PaymentTileColor"
    )

    // What is drawn *on* the tile. Same rule as SupportButton: the deep light-theme colours carry
    // white, the pale dark-theme ones carry a dark sign, so neither state is a light-on-light
    // smudge.
    val onTile = when {
        !isDark -> Color.White
        isReady -> Color(0xFF12301A)
        else -> Color(0xFF0F2540)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // The card sinks onto its lip under her thumb, like every other key on the phone.
    val sink by animateDpAsState(
        targetValue = if (isPressed) 5.dp - GentleKeyDefaults.PressedLipHeight else 0.dp,
        label = "PaymentButtonSink"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ReadyPaymentPulse")
    val auraExpand by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ReadyPaymentAura"
    )

    val description = if (isReady) {
        stringResource(R.string.payment_ready_title)
    } else {
        stringResource(R.string.payment_button_description)
    }

    val tileShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            // One button wide and one button tall is all this takes from the clock row; the
            // second tile is drawn below its slot, over whatever is under it. Laid out the full
            // 108dp instead, the row grows to 108dp, the clock re-centres itself inside it, and
            // everything below the clock steps down the day a caregiver turns the card on —
            // which is exactly what [SupportButton] goes to the same trouble to avoid.
            .size(PaymentButtonSize)
            .wrapContentSize(Alignment.TopCenter, unbounded = true)
            .size(width = PaymentButtonSize, height = PaymentButtonHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,               // the sinking IS the feedback
                onClick = onClick
            )
            .semantics { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .size(width = PaymentButtonSize, height = PaymentButtonHeight)
                .then(
                    if (isReady) {
                        Modifier.drawBehind {
                            // Around the whole tile now, not just the card circle at the top: the
                            // green tile is the thing that is announcing itself, so the halo is the
                            // same shape as it, breathing a few dp out on every side.
                            val expand = auraExpand.dp.toPx()
                            drawRoundRect(
                                color = readyGreen.copy(alpha = 0.30f),
                                topLeft = Offset(-expand, -expand),
                                size = Size(size.width + expand * 2f, size.height + expand * 2f),
                                cornerRadius = CornerRadius(28.dp.toPx() + expand)
                            )
                        }
                    } else Modifier
                )
                .clip(tileShape)
                .background(tileColor)
                .padding(4.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.size(48.dp, 100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top tile: the card itself, as a disc of the ink colour with the card cut out of it.
                // A plain glyph on the fill was legible; a disc reads as an object she can point at.
                //
                // The disc is the key, not the tile: it stands on a lip of its own and sinks when
                // the tile is pressed. A little smaller than the tile's width, to leave room for
                // the lip under it.
                RaisedDisc(face = onTile, size = 44.dp, sink = sink) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_payment_card),
                        contentDescription = null,
                        tint = tileColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Bottom tile: what the phone says about it — a tick, or the PIN it wants first.
                // Straight on the fill, so that the two halves do not read as two separate buttons.
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isReady) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = onTile,
                            modifier = Modifier.size(34.dp)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.payment_pin_label),
                            color = onTile,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * The notice under the clock while the phone will really take a tap.
 *
 * It says the card is ready and what to do with it — hold the phone to the machine at the till
 * — and that is all it says: no seconds, no minutes, and no button to press. It goes when the
 * phone stops being ready, which she can bring about herself by letting the phone sleep.
 *
 * The instruction was taken out once, on the reasoning that at a till she has already held the
 * phone out by the time she could read it. Put back on 2026-09-20 as decided. Watching
 * it used is what decides this and not the argument, and the argument was only ever about a
 * line she can ignore in a fifth of a second if it is telling her something she knows.
 *
 * How to stop being ready is not here. That is a question somebody asks by pressing the lit
 * card, and it is answered where it is asked, in [PaymentSleepDialog] — unasked it would be a
 * line of small print under the one thing that matters at the till.
 */
@Composable
fun PaymentReadyNotice(modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val noticeGreen = if (isDark) PaleSage else WarmForestGreen

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = noticeGreen.copy(alpha = if (isDark) 0.25f else 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(noticeGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF12301A) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.payment_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // What to do about it, under what it is. Said in the order she needs the two
                    // halves in: the card is ready, and here is the hand movement that spends it.
                    Text(
                        text = stringResource(R.string.payment_ready_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * What she is told when she presses a card that is already green.
 *
 * Two lines under the clock and everything else in here. The notice is read at a till, at a
 * glance, over a beeping terminal and a queue — so it carries what is true at that moment and
 * what to do about it, and nothing beside: the card is ready, hold the phone to the machine.
 * The line about the side key stays here rather than joining them, because it was an answer to
 * a question nobody is asking yet while the queue is waiting.
 *
 * The question does get asked — reliably, by pressing the green card again, which is what
 * somebody does when a card is lit and they did not mean it to be. So that press opens this,
 * where there is room for it. A second PIN screen, which is what it used to open, asks her to
 * unlock a phone that is unlocked enough to pay.
 *
 * It goes away by itself like every other dialog on this phone, and for the same reason
 * ([GentleConfirmDialog] has it at length): a question she does not answer must not be a phone
 * that has stopped working, and here the clock can do no harm at all — running out and pressing
 * the button do the same nothing. Nothing in here ends the payment; the phone going to sleep
 * does, which is exactly what the words say.
 */
@Composable
fun PaymentSleepDialog(onDismiss: () -> Unit) {
    val opacity = fadingOutAfterWait(onDismiss)

    // On the wide inner screen of the folding phone, the side key is not the thing to reach for:
    // she closes it, and a closed phone is a locked phone just the same. Telling her to press a
    // button on a phone she is holding open in both hands is an instruction she cannot follow.
    // [isWideWindow] is the same "the fold is open" test the two-column home screen uses.
    val sleepHint = if (isWideWindow()) {
        R.string.payment_sleep_hint_fold
    } else {
        R.string.payment_sleep_hint
    }

    AlertDialog(
        modifier = Modifier.graphicsLayer { alpha = opacity }.traceTaps("payment"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.payment_sleep_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = stringResource(sleepHint),
                style = MaterialTheme.typography.titleMedium
            )
        },
        confirmButton = {
            GentlePrimaryKey(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.payment_sleep_dialog_dismiss),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}

/**
 * What she is told before the PIN that unlocks the card, when somebody has set a hint on this
 * phone — see [ch.heuscher.gentleconnect.data.payment.PaymentPinHint].
 *
 * The hint first, in large letters, because it is the thing written for her in particular; then
 * why a PIN screen is about to appear, so that the keypad arriving is the step she was told
 * about and not the phone asking her something out of nowhere. Only the big button goes on to
 * the PIN. Left alone it goes away by itself like [PaymentSleepDialog], and nothing is lost:
 * the card is still there to press.
 */
@Composable
fun PaymentPinHintDialog(hint: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val opacity = fadingOutAfterWait(onDismiss)

    AlertDialog(
        modifier = Modifier.graphicsLayer { alpha = opacity }.traceTaps("payment"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.payment_auth_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.payment_pin_hint_explanation),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        confirmButton = {
            GentlePrimaryKey(onClick = onContinue) {
                Text(
                    text = stringResource(R.string.payment_pin_hint_continue),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        dismissButton = {
            GentleSecondaryKey(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.close),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}

/**
 * The payment message boxes' patience: full strength for most of [SLEEP_DIALOG_TIMEOUT_MILLIS],
 * fading over the last [SLEEP_DIALOG_FADE_MILLIS], then [onTimeout]. Returns the opacity to draw
 * the box at.
 *
 * The frame clock, not a tween: battery saver sets the animator duration scale to zero, and a
 * tween would then finish on the frame it started — a message box that appeared and left in the
 * same breath. See GentleConfirmDialog.
 */
@Composable
private fun fadingOutAfterWait(onTimeout: () -> Unit): Float {
    val timeoutNow by rememberUpdatedState(onTimeout)
    var opacity by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }
        while (true) {
            val elapsedMillis = (withFrameNanos { it } - startedAt) / 1_000_000L
            if (elapsedMillis >= SLEEP_DIALOG_TIMEOUT_MILLIS) break
            val fadingFor = elapsedMillis - (SLEEP_DIALOG_TIMEOUT_MILLIS - SLEEP_DIALOG_FADE_MILLIS)
            opacity = if (fadingFor <= 0) 1f else 1f - fadingFor.toFloat() / SLEEP_DIALOG_FADE_MILLIS
        }
        opacity = 0f
        timeoutNow()
    }
    return opacity
}

/** How long the message box waits. Long: she is reading it in a shop, holding a card reader. */
private const val SLEEP_DIALOG_TIMEOUT_MILLIS = 30_000

/** The last two seconds of that wait, spent leaving. See [GentleConfirmDialog]. */
private const val SLEEP_DIALOG_FADE_MILLIS = 2_000
