package ch.heuscher.gentleconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.telecom.AudioRoute
import ch.heuscher.gentleconnect.ui.components.ConfirmHangUpDialog
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.components.GentleKey
import ch.heuscher.gentleconnect.ui.components.SupportButton
import ch.heuscher.gentleconnect.ui.components.WideScreenGripMargin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.ui.theme.LocalGentleFaceScale
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * The one screen a call is answered and ended on, whichever kind of call it is.
 *
 * A telephone call over the network and a Threema call over the internet are the same event to
 * her: somebody wants to talk. They used to be two screens because they are two mechanisms, and
 * that is precisely the kind of difference she cannot be expected to hold — a green circle in one
 * place and a green circle somewhere else, sized differently, is two things to learn instead of
 * one. So both callers are drawn here, and neither path gets to invent its own idea of what
 * answering looks like.
 *
 * What is deliberately absent: mute, keypad, hold, video, the name of the network it arrived on,
 * and any indication of how long it has lasted. Every one of those is a control she can hit by
 * accident while holding the phone to her ear, and the recovery from each is a thing she would
 * have to know she had done.
 *
 * The help button ("?") is present at the top-right corner, exactly where it is on every other
 * screen she sees. A call is where she is most likely to be unsure what is happening — who is
 * talking, why the phone is making sounds, or how she got here. Reaching her support group must
 * never depend on knowing how to get off the call first.
 *
 * @param who the person, already resolved to a name if the address book knows one. Never blank —
 *   a caller with no name at all should arrive here as the number, and a caller with no number as
 *   whatever the caller-unknown string says, because a screen with a nameless face on it does not
 *   tell her whether to pick up.
 * @param status one line under the name, already translated: ringing, connecting, ended.
 * @param canAnswer whether this call can still be picked up. Drives whether the green circle is
 *   on the screen at all — a green circle that does nothing is worse than no green circle.
 * @param askBeforeHangingUp whether the red circle asks first. True only while the two of them
 *   are actually talking: that is when the phone is against her ear and a brush against the
 *   glass costs her the conversation. A ringing telephone she does not want stays one press,
 *   because a call she is trying to stop is the wrong thing to put a question in front of.
 * @param audioRoute the current physical audio routing ([AudioRoute]), or null if not yet routed.
 * @param onChooseRoute where her press of Hörer or Lautsprecher goes, or null to only name the
 *   route — with a headset in, or on a folding phone, where the hinge decides.
 */
@Composable
fun GentleCallScreen(
    who: String,
    photoUri: String?,
    status: String,
    canAnswer: Boolean,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
    askBeforeHangingUp: Boolean = false,
    audioRoute: AudioRoute? = null,
    onChooseRoute: ((AudioRoute) -> Unit)? = null,
    isKnownContact: Boolean = false,
    onSilence: () -> Unit = {},
    /**
     * Whether there is a ringer to silence at all.
     *
     * True for a call the platform is ringing. False for one this app carries itself over the
     * internet: nothing is making a sound, so a Stumm circle would be a large grey button that
     * does nothing to a phone that is already quiet — and this screen is the one place where a
     * control that does not do what it says is worst.
     */
    canSilence: Boolean = true,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {}
) {
    var confirmingHangUp by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val requestHangUp: () -> Unit = { if (askBeforeHangingUp) confirmingHangUp = true else onHangUp() }

        Box(modifier = Modifier.fillMaxSize()) {

        // Side by side when the window is wide — the open inner screen of a folding phone. It is
        // the same screen, with the same two circles the same size in the same order; what
        // changes is that the face stops having to share the one axis with the name and the
        // buttons. Down one column on a 745dp-tall window the photograph is capped at about a
        // third of the height so that nothing pushes the green circle off the bottom; beside
        // them it is as big as half the screen allows, which is what the width was for.
        if (isWideWindow()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    // The shorter side of its own half of the screen, less a margin: a face that
                    // filled its box edge to edge would touch the name beside it.
                    CallFace(photoUri = photoUri, who = who, size = minOf(maxWidth, maxHeight) * 0.9f)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CallWho(
                        who = who,
                        status = status,
                        audioRoute = audioRoute,
                        onChooseRoute = onChooseRoute,
                        canAnswer = canAnswer
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    CallActions(
                        canAnswer = canAnswer,
                        isKnownContact = isKnownContact,
                        canSilence = canSilence,
                        onAnswer = onAnswer,
                        onSilence = onSilence,
                        onHangUp = requestHangUp
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val faceScale = LocalGentleFaceScale.current
                    // The face is worth making big on a large screen, but never at the price of
                    // the buttons that answer or end the call sliding off the bottom of it.
                    val faceSize = minOf(
                        FACE_SIZE * faceScale,
                        (LocalConfiguration.current.screenHeightDp * 0.35f).dp
                    )
                    CallFace(photoUri = photoUri, who = who, size = faceSize)

                    Spacer(modifier = Modifier.height(24.dp))

                    CallWho(
                        who = who,
                        status = status,
                        audioRoute = audioRoute,
                        onChooseRoute = onChooseRoute,
                        canAnswer = canAnswer
                    )
                }

                CallActions(
                    modifier = Modifier.padding(bottom = 56.dp),
                    canAnswer = canAnswer,
                    isKnownContact = isKnownContact,
                    canSilence = canSilence,
                    onAnswer = onAnswer,
                    onSilence = onSilence,
                    onHangUp = requestHangUp
                )
            }
        }

            SupportButton(
                state = support,
                onClick = onSupportPressed,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 16.dp, start = 16.dp, bottom = 16.dp, end = if (isWideWindow()) WideScreenGripMargin else 16.dp)
            )
        }
    }

    // `&& askBeforeHangingUp` so the question cannot outlive the conversation it is about: the
    // other end hanging up mid-question takes the stage out of TALKING, and a dialog still
    // offering to end a call that is already over is a button that does nothing.
    if (confirmingHangUp && askBeforeHangingUp) {
        ConfirmHangUpDialog(
            name = who,
            onConfirm = {
                confirmingHangUp = false
                onHangUp()
            },
            onDismiss = { confirmingHangUp = false }
        )
    }
}

/**
 * A read-only label indicating the active audio output route (e.g. loudspeaker, earpiece, headset, Bluetooth).
 *
 * Placed beneath the status line, where [AudioRouteChoice] goes when there is a choice to offer. Not
 * a control: not clickable, no ripple, no toggle.
 * Hidden while the call is still ringing incoming because routing is not yet meaningful until answered.
 */
@Composable
private fun AudioRouteLabel(
    route: AudioRoute,
    modifier: Modifier = Modifier
) {
    val (icon, textRes) = when (route) {
        AudioRoute.EARPIECE -> Icons.Default.Phone to R.string.call_audio_earpiece
        AudioRoute.LOUDSPEAKER -> Icons.Default.VolumeUp to R.string.call_audio_loudspeaker
        AudioRoute.HEADSET -> Icons.Default.Headphones to R.string.call_audio_headset
        AudioRoute.BLUETOOTH -> Icons.Default.Bluetooth to R.string.call_audio_bluetooth
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The same size as the status line above it, and deliberately not smaller. This is the
        // one screen where she is least able to bring the phone closer to read something — it is
        // against her ear, or on a table at arm's length — and a word she cannot make out is a
        // word that is not there. What makes this secondary to the status is its colour, not its
        // size; shrinking it would only make it secondary to her eyesight as well.
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(textRes),
            fontSize = 24.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Hörer and Lautsprecher side by side, the one in force filled in.
 *
 * Two choices rather than one switch, because each of them always means the same thing: her finger
 * often lands twice, and a switch pressed twice puts the sound back where it was while she is
 * already lifting the phone to her ear. Pressing the filled one again changes nothing.
 *
 * Under the name and well away from the red circle, which is where her cheek or palm brushes when the
 * screen wakes against her face.
 */
@Composable
private fun AudioRouteChoice(
    current: AudioRoute,
    onChoose: (AudioRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((route, icon, textRes) in listOf(
            Triple(AudioRoute.EARPIECE, Icons.Default.Phone, R.string.call_audio_earpiece),
            Triple(AudioRoute.LOUDSPEAKER, Icons.Default.VolumeUp, R.string.call_audio_loudspeaker)
        )) {
            val chosen = route == current
            GentleKey(
                onClick = {
                    UiTrace.event("call audio chosen: $route")
                    onChoose(route)
                },
                modifier = Modifier.weight(1f),
                face = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                minHeight = 88.dp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                fillWidth = true
            ) {
                // Word under the picture, not beside it: "Lautsprecher" at a size she can read
                // does not fit beside an icon in half a phone's width.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(textRes),
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * One of the two circles, both exactly the same size.
 *
 * Same size on purpose, even though hanging up is the commoner of the two: a bigger red circle
 * beside a smaller green one reads as "this is the one to press", and on an incoming call from her
 * daughter that is the wrong advice.
 */
@Composable
private fun CallCircle(
    colour: Color,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = CIRCLE_SIZE,
    iconColor: Color = Color.White
) {
    GentleKey(
        onClick = onClick,
        modifier = Modifier.width(size),
        face = colour,
        contentColor = iconColor,
        shape = CircleShape,
        minHeight = size,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(size * 0.5f),
            tint = iconColor
        )
    }
}

// Not from the colour scheme. Green means answer and red means end on every telephone she has ever
// used, and that convention is older and better learned than anything this app can teach her, so
// these two do not follow the theme the way the rest of the app does.
private val ANSWER_GREEN = Color(0xFF2E7D32)
private val HANG_UP_RED = Color(0xFFC62828)

private val FACE_SIZE = 200.dp

// Large enough to be found without aiming, which is what a phone against an ear needs.
private val CIRCLE_SIZE = 130.dp


/** The photograph, at whatever size the layout has decided it can be. */
@Composable
private fun CallFace(photoUri: String?, who: String, size: Dp) {
    ContactAvatar(
        photoUri = photoUri,
        name = who,
        modifier = Modifier.size(size),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        textStyle = MaterialTheme.typography.displayLarge
    )
}

/** Who it is and what the call is doing, in that order, centred. */
@Composable
private fun CallWho(
    who: String,
    status: String,
    audioRoute: AudioRoute?,
    onChooseRoute: ((AudioRoute) -> Unit)?,
    canAnswer: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = who,
            fontSize = 38.sp,
            // Setting fontSize alone leaves the line height at the default style's, which
            // is sized for ordinary body text. At 38sp the second line then rides up into
            // the first: "Tobias Müller" came out with Müller printed through the middle
            // of Simon, and the umlaut lost inside the letters above it. A name that
            // wraps is the common case on this screen, so the line height has to be set
            // wherever the size is.
            lineHeight = 46.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            // Two lines, because a full name wraps and a truncated one is a stranger.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = status,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (audioRoute != null && !canAnswer) {
            if (onChooseRoute != null) {
                Spacer(modifier = Modifier.height(24.dp))
                AudioRouteChoice(current = audioRoute, onChoose = onChooseRoute)
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                AudioRouteLabel(route = audioRoute)
            }
        }
    }
}

/**
 * The circles, in the one order she has learned them in, whichever way round the screen is.
 */
@Composable
private fun CallActions(
    canAnswer: Boolean,
    isKnownContact: Boolean,
    canSilence: Boolean,
    onAnswer: () -> Unit,
    onSilence: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (canAnswer) Arrangement.SpaceEvenly else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canAnswer) {
            CallCircle(
                colour = ANSWER_GREEN,
                icon = Icons.Default.Call,
                description = stringResource(R.string.call_answer),
                onClick = onAnswer,
                size = if (isKnownContact) CIRCLE_SIZE * 1.3f else CIRCLE_SIZE
            )
        }
        
        if (canAnswer && isKnownContact && canSilence) {
            CallCircle(
                colour = MaterialTheme.colorScheme.surfaceVariant,
                icon = Icons.Default.VolumeOff,
                description = stringResource(R.string.call_silence),
                onClick = onSilence,
                iconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            CallCircle(
                colour = HANG_UP_RED,
                icon = Icons.Default.CallEnd,
                description = stringResource(R.string.call_hangup),
                // Already carries the question, or not, depending on whether they are talking:
                // see the caller of this.
                onClick = onHangUp
            )
        }
    }
}
