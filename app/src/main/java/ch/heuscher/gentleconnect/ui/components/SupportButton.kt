package ch.heuscher.gentleconnect.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.theme.PaleSage
import ch.heuscher.gentleconnect.ui.theme.SupportBlue
import ch.heuscher.gentleconnect.ui.theme.SupportBlueOnDark
import ch.heuscher.gentleconnect.ui.theme.WarmAmber
import ch.heuscher.gentleconnect.ui.theme.WarmAmberOnDark
import ch.heuscher.gentleconnect.ui.theme.WarmForestGreen

/**
 * The room the button takes in a layout: its width, and the height a header is laid out for. Home
 * keeps the same width free on the other side of the clock.
 */
val SupportButtonSize = 56.dp

/**
 * How tall the tile is drawn: face on top, the question mark below, two 48dp squares inside a 4dp
 * frame. Everything past [SupportButtonSize] hangs below its slot, over whatever is under the
 * header, so that the header stays the height it was before the "?" arrived. The row it sits in
 * has to carry `zIndex(1f)`, or the content after it paints over the "?".
 */
val SupportButtonHeight = 108.dp

/**
 * Extra clearance kept between the button and the screen edge on wide (unfolded) screens,
 * so that the holding hand does not accidentally trigger the button while gripping the device bezel.
 */
val WideScreenGripMargin = 80.dp

/**
 * The support button: a tall 56dp-wide tile with the face of the person it reaches on top and a
 * big "?" underneath, in high-contrast SupportBlue. What it says is "something is strange" — she
 * does not have to decide that she needs help, only notice that something is off.
 *
 * Face and sign are the same size on purpose. She does not see well: a 16dp badge in the corner
 * of a portrait is invisible to her, so the sign has to be as big as the face. They are stacked,
 * not side by side, because a 112dp-wide pill left the clock between it and its mirror spacer on
 * Home about 70dp on the 334dp cover screen of a folding phone.
 *
 * The face is the support photo, or the person's initials when there is none. Once a request is
 * out, the "?" becomes a big tick and the tile turns green; when somebody has taken it on, the
 * face becomes theirs.
 *
 * When [isRestless] is true, the **Warm Beacon** activates:
 * - The portrait remains 100% crisp, stable, and bright (never dimmed or desaturated).
 * - A radiant, translucent aura breathes outward from behind the tile (up to 16dp beyond it).
 * - The aura performs an ultra-slow, harmonious color melt between Blue -> Green -> Warm Amber (7.5s cycle).
 * - The tile itself keeps its colour, with a gentle 20% harmony tint.
 *
 * If [state] is [SupportButtonState.None], an invisible spacer of size [SupportButtonSize]
 * is rendered to keep header layouts balanced.
 */
@Composable
fun SupportButton(
    state: SupportButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRestless: Boolean = false
) {
    if (state is SupportButtonState.None) {
        Spacer(modifier = modifier.size(SupportButtonSize))
        return
    }

    val member = when (state) {
        is SupportButtonState.Idle -> state.firstMember
        is SupportButtonState.Sent -> state.firstMember
        is SupportButtonState.Claimed -> state.claimer
        SupportButtonState.None -> null
    }

    val isActive = state is SupportButtonState.Sent || state is SupportButtonState.Claimed
    val description = stringResource(R.string.support_button_description)

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Palette for the Warm Beacon color melt
    val beaconBlue = if (isDark) SupportBlueOnDark else SupportBlue
    val beaconGreen = if (isDark) PaleSage else WarmForestGreen
    val beaconAmber = if (isDark) WarmAmberOnDark else WarmAmber

    // Blue while it asks, green once somebody has been told. The pale dark-theme colours carry a
    // dark sign, the deep light-theme ones a white one.
    val baseRingColor = if (isActive) beaconGreen else beaconBlue
    val onRing = when {
        !isDark -> Color.White
        isActive -> Color(0xFF12301A)
        else -> Color(0xFF0F2540)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // The portrait sinks onto its lip under her thumb, like every other key on the phone.
    val sink by animateDpAsState(
        targetValue = if (isPressed) 5.dp - GentleKeyDefaults.PressedLipHeight else 0.dp,
        label = "SupportButtonSink"
    )

    // Warm Beacon infinite transitions:
    // 1. Respiratory pulse: 2.4s expanding wave
    // 2. Color melt: 7.5s continuous loop through Blue -> Green -> Warm Amber -> Blue
    val infiniteTransition = rememberInfiniteTransition(label = "WarmBeaconTransition")

    // Both phases run unconditionally, and only their *values* are gated on [isRestless] below.
    //
    // They used to be created inside `if (isRestless) … else remember { mutableFloatStateOf(0f) }`,
    // which is why the beacon never lit on the phone. Measured on a test phone: four taps
    // recorded a RESTLESSNESS_DETECTED event at 23:20:01 — so the tracker fired and isRestless was
    // true — while the button's 280x280 pixel neighbourhood stayed *byte-identical* across
    // captures at 0.2 s, 1.2 s and 3.2 s. With both phases stuck at their initial 0 that is
    // exactly the expected picture, and it is two coincidences at once:
    //
    //   - `colorPhase = 0` makes activeAuraColor `blend(beaconBlue, beaconGreen, 0)` = beaconBlue,
    //     and beaconBlue *is* baseRingColor, so the ring blends itself with itself and no pixel
    //     of it moves;
    //   - `pulsePhase = 0` makes currentExpandPx 0, so both aura circles land on exactly
    //     baseRadius — the radius of the opaque ring-and-portrait drawn on top of them.
    //
    // An animation that costs nothing while she is calm is a far better trade than a beacon whose
    // first frame is the one that has to start it.
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AuraPulsePhase"
    )

    val colorPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AuraColorPhase"
    )

    // Interpolate the active beacon color along Blue -> Green -> Amber -> Blue
    val activeAuraColor = if (isRestless) {
        when {
            colorPhase < 1f -> blendColors(beaconBlue, beaconGreen, colorPhase)
            colorPhase < 2f -> blendColors(beaconGreen, beaconAmber, colorPhase - 1f)
            else -> blendColors(beaconAmber, beaconBlue, colorPhase - 2f)
        }
    } else {
        baseRingColor
    }

    // Core ring remains anchored in SupportBlue, with a 20% harmonic tint from the active aura
    val effectiveRingColor = if (isRestless) {
        blendColors(baseRingColor, activeAuraColor, 0.20f)
    } else {
        baseRingColor
    }

    val tileShape = RoundedCornerShape(SupportButtonSize / 2)

    Box(
        modifier = modifier
            .size(SupportButtonSize)
            // Laid out as a 56dp square, drawn — and tapped — 108dp tall from its top edge down.
            .wrapContentSize(Alignment.TopCenter, unbounded = true)
            .size(width = SupportButtonSize, height = SupportButtonHeight)
            .semantics { this.contentDescription = description }
            .clickable(
                interactionSource = interactionSource,
                indication = null,               // the sinking IS the feedback
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(width = SupportButtonSize, height = SupportButtonHeight)
                .drawBehind {
                    if (isRestless) {
                        val maxExpandPx = 16.dp.toPx()
                        val currentExpandPx = pulsePhase * maxExpandPx
                        val currentAlpha = (1f - pulsePhase) * 0.70f

                        // The tile's own stadium, grown by [grow] on every side.
                        fun stadium(grow: Float, color: Color) = drawRoundRect(
                            color = color,
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + 2 * grow, size.height + 2 * grow),
                            cornerRadius = CornerRadius(size.width / 2f + grow)
                        )

                        // Outer expanding ripple wave
                        stadium(currentExpandPx, activeAuraColor.copy(alpha = currentAlpha * 0.45f))
                        // Inner soft luminous glow halo
                        stadium(currentExpandPx * 0.5f, activeAuraColor.copy(alpha = currentAlpha * 0.75f))
                    }
                }
                // After drawBehind, so the aura still reaches past the tile.
                .clip(tileShape),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .size(width = SupportButtonSize, height = SupportButtonHeight)
                    .background(effectiveRingColor, tileShape)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Who: the portrait remains 100% crisp, stable, and clear at all times. Their initials
                // when there is no photograph — a person either way, never a symbol.
                //
                // The portrait is the key, not the tile: it stands on a lip of its own and sinks
                // when the tile is pressed. A little smaller than the tile's width, to leave room
                // for the lip under it.
                RaisedDisc(face = effectiveRingColor, size = 44.dp, sink = sink) {
                    ContactAvatar(
                        photoUri = member?.buttonPhotoUri,
                        name = member?.name ?: "",
                        modifier = Modifier.size(44.dp),
                        containerColor = effectiveRingColor,
                        contentColor = onRing,
                        textStyle = MaterialTheme.typography.titleLarge
                    )
                }

                // What: "something is strange", and once somebody has been told, a tick.
                Box(
                    modifier = Modifier.size(SupportButtonSize - 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = onRing,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_support_strange),
                            contentDescription = null,
                            tint = onRing,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pure linear interpolation between two colors in linear RGB space.
 */
private fun blendColors(start: Color, end: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t
    )
}
