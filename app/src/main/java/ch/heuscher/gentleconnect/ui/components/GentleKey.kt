package ch.heuscher.gentleconnect.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.util.UiTrace

object GentleKeyDefaults {
    val LipHeight = 6.dp          // how far the lip shows below the face at rest
    val PressedLipHeight = 1.dp   // what remains visible while pressed
    val Shape = RoundedCornerShape(18.dp)
    val MinHeight = 56.dp
    val ContentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
}

/** Lip colour for a face colour. Light faces get a darker lip; DARK faces get a LIGHTER lip. */
fun keyLipColor(face: Color): Color =
    if (face.luminance() < 0.35f) lerp(face, Color.White, 0.30f)
    else lerp(face, Color.Black, 0.22f)

/**
 * A physical key on her screen: a raised face resting on a solid lip below it.
 *
 * She has trouble distinguishing what can be touched from what is only there to be read. Text
 * she reads stays completely flat against the screen, while anything pressable sits on a lip
 * like an actual button on a handheld remote control. The lip is the unmistakable physical signal:
 * "this is a key you can press" — and only pressable things get one.
 *
 * When pressed under her thumb, the face visibly sinks down onto the lip, leaving only a thin rim
 * visible. This physical movement is the feedback that her tap registered: she sees the key yield
 * under her touch and does not have to wonder if anything happened or wait for an unfamiliar ripple.
 *
 * A dark button never receives an even darker lip: in the dark theme or with a deep-green primary
 * key, a darker lip disappears against dark backgrounds. Instead, [keyLipColor] lifts dark faces
 * with a lighter lip of their own hue, preserving contrast and tactile affordance everywhere.
 *
 * @param onClick action invoked on a tap.
 * @param modifier modifier applied to the outer key boundary.
 * @param face the background colour of the button face.
 * @param contentColor colour for text and icons inside the key, defaulting to the appropriate
 *   contrast colour for the face.
 * @param shape corner shape for both the face and the lip.
 * @param border optional explicit border stroke, e.g. for accenting a missed call.
 * @param onLongClick optional action invoked on a long press.
 * @param enabled whether the key responds to touch and shows full contrast.
 * @param minHeight minimum height for the face of the key.
 * @param contentPadding inner padding around the key's content.
 * @param contentAlignment horizontal alignment for content inside the face row.
 * @param fillWidth whether the face Row should expand to fill the full width of the key.
 * @param spacing gap between the items on the face.
 * @param onClickLabel accessibility label for the click action.
 * @param content composable content rendered inside the face Row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GentleKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    face: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(face),
    shape: Shape = GentleKeyDefaults.Shape,
    border: BorderStroke? = null,          // extra outline, e.g. the amber "rang" border of ContactRow
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = GentleKeyDefaults.MinHeight,
    contentPadding: PaddingValues = GentleKeyDefaults.ContentPadding,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    fillWidth: Boolean = false,
    /** Gap between the things on the face. 0 for a row that spaces its own contents. */
    spacing: Dp = 10.dp,
    /** What a screen reader says pressing it will do, where the words on it do not say so. */
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(
        if (pressed) GentleKeyDefaults.LipHeight - GentleKeyDefaults.PressedLipHeight else 0.dp,
        label = "keySink"
    )
    val actualFace = if (enabled) face else face.copy(alpha = 0.5f)
    val actualContentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.5f)
    val lip = if (enabled) keyLipColor(face) else keyLipColor(face).copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,               // the sinking IS the feedback
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick?.let { action ->
                    {
                        UiTrace.markAnswered()
                        action()
                    }
                },
                onClick = {
                    UiTrace.markAnswered()
                    onClick()
                }
            ),
        // The face has to be as wide as the key it sits on. Without this, a caller that makes the
        // key fill the row but forgets fillWidth gets a full-width lip under a narrow face — a
        // shelf with a button standing on it.
        propagateMinConstraints = true
    ) {
        // The lip: same shape as the face, LipHeight lower.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = GentleKeyDefaults.LipHeight)
                .shadow(3.dp, shape)
                .background(lip, shape)
        )
        Row(
            modifier = Modifier
                .padding(bottom = GentleKeyDefaults.LipHeight)
                .offset(y = sink)
                .heightIn(min = minHeight)
                .clip(shape)
                // slight top highlight so the face reads as a raised, rounded key
                .background(Brush.verticalGradient(listOf(lerp(actualFace, Color.White, 0.12f), actualFace)))
                .border(border ?: BorderStroke(1.5.dp, lip), shape)
                .padding(contentPadding)
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(spacing, contentAlignment),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides actualContentColor) { content() }
        }
    }
}

/**
 * A primary action key wearing the theme's primary colour.
 *
 * Used for the leading action she is invited to take — confirming a call, sending a message,
 * or acknowledging an outcome.
 */
@Composable
fun GentlePrimaryKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    face: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = GentleKeyDefaults.Shape,
    border: BorderStroke? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = GentleKeyDefaults.MinHeight,
    contentPadding: PaddingValues = GentleKeyDefaults.ContentPadding,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    fillWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    GentleKey(
        onClick = onClick,
        modifier = modifier,
        face = face,
        contentColor = contentColor,
        shape = shape,
        border = border,
        onLongClick = onLongClick,
        enabled = enabled,
        minHeight = minHeight,
        contentPadding = contentPadding,
        contentAlignment = contentAlignment,
        fillWidth = fillWidth,
        content = content
    )
}

/**
 * A secondary or quiet key on a surface face with primary content colour.
 *
 * Used for dismissals, navigation backwards, or secondary options that sit alongside a primary key
 * without competing for prominence.
 */
@Composable
fun GentleSecondaryKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    face: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    shape: Shape = GentleKeyDefaults.Shape,
    border: BorderStroke? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = GentleKeyDefaults.MinHeight,
    contentPadding: PaddingValues = GentleKeyDefaults.ContentPadding,
    contentAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    fillWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    GentleKey(
        onClick = onClick,
        modifier = modifier,
        face = face,
        contentColor = contentColor,
        shape = shape,
        border = border,
        onLongClick = onLongClick,
        enabled = enabled,
        minHeight = minHeight,
        contentPadding = contentPadding,
        contentAlignment = contentAlignment,
        fillWidth = fillWidth,
        content = content
    )
}

/**
 * A round key face on its own lip, for a key that is only part of a larger target: the portrait on
 * the support tile, the card on the payment tile. The tile around it stays flat and takes the tap;
 * this is the part of it that looks pressable, and it is what sinks when the tile is pressed.
 *
 * It takes [size] across and [lipHeight] more below. The lip follows [keyLipColor], so a dark face
 * gets a lighter lip.
 *
 * @param sink how far the face has gone down, from 0 at rest to [lipHeight] less a rim when pressed.
 */
@Composable
fun RaisedDisc(
    face: Color,
    size: Dp,
    sink: Dp,
    modifier: Modifier = Modifier,
    lipHeight: Dp = 5.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val lip = keyLipColor(face)
    Box(modifier = modifier.size(width = size, height = size + lipHeight)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(size)
                .shadow(3.dp, CircleShape)
                .background(lip, CircleShape)
        )
        Box(
            modifier = Modifier
                .offset(y = sink)
                .size(size)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(lerp(face, Color.White, 0.12f), face)))
                .border(1.5.dp, lip, CircleShape),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
