package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Emoji
import ch.heuscher.gentleconnect.model.QuickReactions
import ch.heuscher.gentleconnect.model.ReplyRow
import ch.heuscher.gentleconnect.speech.EmojiNames

/** The gap between two of the five circles. */
private val CircleGap = 8.dp

/**
 * As wide as one of them is ever allowed to get.
 *
 * They share the width so that five always fit on a narrow phone — the reason weights replaced
 * fixed sizes here. Sharing with no ceiling is the other failure: on a wide screen five circles
 * grew to a hundred and ten points each and the row took a third of the screen, with a small face
 * adrift in the middle of every one.
 */
private val MaxCircle = 76.dp

/**
 * As small as one is ever allowed to get, whatever the width says.
 *
 * Sharing the width works until the width runs out — below this they stop shrinking and wrap onto
 * another line instead. Deliberately below what the narrowest ordinary phone already produces, so
 * that nothing about a phone screen changes.
 */
private val MinCircle = 48.dp

/**
 * The width below which [MinCircle] starts holding them apart — narrower than any phone.
 *
 * The narrowest window this app is ever drawn in is the folding phone's outer screen, which leaves
 * this block about 294 points.
 */
private val NarrowerThanAPhone = 280.dp

/**
 * The button that opens the keyboard: its smallest width, and how tall it stands.
 *
 * Wide enough to hold the word "Antworten" at her text size without breaking it or shortening it
 * to something that is not a word, and as tall as the writing line it replaces, so the block does
 * not jump when she presses it.
 */
private val AnswerButton = 220.dp
private val AnswerButtonHeight = 64.dp
private val CameraButtonWidth = 64.dp

/** How much of the circle the face fills. 0.5 keeps the 32sp face a 64dp circle has always had. */
private const val FACE_SHARE_OF_CIRCLE = 0.5f

/**
 * The send button, and the plane on it. The button that stops writing is its twin on the other
 * side of the line: same circle, same size, so the two ends of the line are the two things she
 * can do with it.
 */
private val SendButton = 56.dp
private val SendIcon = 30.dp

private const val MORE_FACES = "🙂" // 🙂

/**
 * The five faces, and the way to words.
 *
 * Two states, and only ever one of them on the screen:
 *
 *  - **At rest** — the faces she can send, and a button saying "Antworten". No writing box and no
 *    keyboard. This is nearly always what the bottom of a conversation looks like, because nearly
 *    every answer she sends is one of the five faces.
 *  - **Writing** — the box, focused, with the keyboard already up, a way to send at one end and a
 *    way out at the other. The faces are gone: she has just said she is writing words, and the
 *    height they were taking belongs to the conversation above.
 *
 * Why the box is no longer simply always there: it was a permanently open writing line under every
 * conversation, tall enough to read in a narrow column, and it cost that height on every screen
 * whether or not she ever typed in it — on the open Fold the placeholder alone wrapped onto three
 * lines. A button is one line, says what pressing it does, and the keyboard now appears only when
 * she has asked for it rather than when her hand brushed the box.
 *
 * What she has written is **kept** when she leaves the box, so pressing the way out is never the
 * loss of a sentence: "Antworten" opens it again with her words still in it.
 *
 * Shown only where there is somebody at the other end to receive them — never greyed out, never
 * accompanied by a reason. Something she cannot do is not offered.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ReplyControls(
    onReactionSelected: (emoji: String) -> Unit,
    onTextReplySubmitted: (text: String) -> Unit,
    /**
     * Whether the last slot opens [EmojiChoiceDialog] instead of sending the laughing face.
     *
     * Off by default, and off is the phone as it has always been — five faces, one press each.
     * See [ch.heuscher.gentleconnect.model.RemoteSettings.enableEmojiChoice] for whose decision
     * this is and what it costs.
     */
    canChooseEmoji: Boolean = false,
    /** Optional action to launch the camera, shown to the left of the answer button. */
    onCameraRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var replyText by remember { mutableStateOf("") }
    var choosingEmoji by remember { mutableStateOf(false) }

    // Whether she is writing rather than looking. The one piece of state this block has, and what
    // decides which of the two things is drawn.
    var writing by remember { mutableStateOf(false) }

    val keyboardUp = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val box = remember { FocusRequester() }

    // The way back out that she did not press: the system's own back, which takes the keyboard
    // down and would otherwise leave her staring at a writing line with no keys under it.
    //
    // Only counted once the keyboard has actually been up. It is not up at the moment she presses
    // "Antworten" — it arrives a frame or two later — and a rule reading "no keyboard means she
    // has stopped" would close the box before it ever opened.
    var keyboardHasBeenUp by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardUp, writing) {
        if (!writing) {
            keyboardHasBeenUp = false
        } else if (keyboardUp) {
            keyboardHasBeenUp = true
        } else if (keyboardHasBeenUp) {
            writing = false
            focusManager.clearFocus()
        }
    }

    val stopWriting = {
        writing = false
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val reactionEmojis = QuickReactions.faces(canChooseEmoji)

    // Every face the reading-aloud voice has a word for, drawn as pictures rather than as the
    // printer's symbols some of them started life as. Worked out once: the table does not change
    // while the app is running, and this composable is under every conversation.
    val emojiChoices = remember { EmojiNames.RESOURCE_MAP.keys.map { Emoji.asPicture(it) } }

    // How wide this block has been given, asked once for the whole of it: whether the faces stand
    // beside the button or above it is the only question, and it has to be asked of one width.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Taken out here, in points, because inside the column below this scope is out of reach:
        // a layout scope hides the one outside it, and maxWidth belongs to this one.
        val blockWidth = maxWidth.value

        if (choosingEmoji) {
            EmojiChoiceDialog(
                choices = emojiChoices,
                onChosen = onReactionSelected,
                onDismiss = { choosingEmoji = false }
            )
        }

        val circleCount = reactionEmojis.size + if (canChooseEmoji) 1 else 0
        val cameraWidth = if (onCameraRequested != null) CameraButtonWidth.value else 0f

        // Beside the button where the whole line fits, which is the open Fold and nothing she
        // holds closed. See [ReplyRow.facesBesideButton].
        val sideBySide = !writing && ReplyRow.facesBesideButton(
            available = blockWidth,
            circles = circleCount,
            diameter = MinCircle.value,
            gap = CircleGap.value,
            button = AnswerButton.value,
            extraButton = cameraWidth
        )

        // The five faces. Centred when they have the line to themselves, and gathered to the left
        // when they share it with the button, so that the row reads as one line — faces, then the
        // way to words — rather than as two things drifting apart.
        //
        // Sized from the width they are actually given rather than from what was reserved for
        // them. The button is drawn at the width its word needs and [AnswerButton] is only the
        // smallest that may be — "Antworten" at her text size comes out about twenty-four points
        // wider than that — so arithmetic done up there was handing the circles a width nobody
        // ever gave them, and the fifth face dropped onto a line of its own. Measured here it
        // cannot be wrong: whatever room is left, the circles share exactly that.
        val theFaces: @Composable (Modifier) -> Unit = { placed ->
            BoxWithConstraints(modifier = placed) {
                // Each circle takes its share — up to [MaxCircle], below which five of them on a
                // wide screen become dinner plates, and down to [MinCircle], below which they
                // stop shrinking and wrap instead.
                val diameter = ReplyRow.circleDiameter(
                    available = maxWidth.value,
                    circles = circleCount,
                    gap = CircleGap.value,
                    ceiling = MaxCircle.value,
                    floor = MinCircle.value,
                    floorBelow = NarrowerThanAPhone.value
                ).dp
                val faceSize = (diameter.value * FACE_SHARE_OF_CIRCLE).sp

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        CircleGap,
                        if (sideBySide) Alignment.Start else Alignment.CenterHorizontally
                    ),
                    verticalArrangement = Arrangement.spacedBy(CircleGap)
                ) {
                    reactionEmojis.forEach { emoji ->
                        GentleKey(
                            onClick = { onReactionSelected(emoji) },
                            modifier = Modifier
                                .width(diameter)
                                .reactionZone(),
                            face = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                            minHeight = diameter,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = emoji,
                                fontSize = faceSize
                            )
                        }
                    }

                    if (canChooseEmoji) {
                        // A face she cannot send, and a plus. Grey because it is not one of the
                        // things being offered — pressing it sends nothing — and a face rather than a
                        // bare plus because what is behind it is more faces: the picture says what
                        // the door leads to, and the plus says it is a door.
                        // Rendered as a physical key like the reactions so it is clearly pressable.
                        GentleKey(
                            onClick = { choosingEmoji = true },
                            modifier = Modifier.width(diameter),
                            face = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                            minHeight = diameter,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(diameter),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = MORE_FACES,
                                    fontSize = faceSize,
                                    modifier = Modifier.alpha(0.4f)
                                )
                                Text(
                                    text = "+",
                                    fontSize = faceSize * 0.75f,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 6.dp, bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // The word, not a pencil or a speech bubble. It is the one thing on this screen that
        // asks for the keyboard, and she reads it. Now a raised physical key that sinks when pressed.
        val theAnswerButton: @Composable (Modifier) -> Unit = { placed ->
            GentleKey(
                onClick = { writing = true },
                modifier = placed,
                face = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp),
                minHeight = AnswerButtonHeight
            ) {
                Text(
                    text = stringResource(R.string.reply_open),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // Camera button placed immediately to the left of the answer button.
        val theCameraButton: @Composable (Modifier) -> Unit = { placed ->
            GentleKey(
                onClick = { onCameraRequested?.invoke() },
                modifier = placed,
                face = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp),
                minHeight = AnswerButtonHeight,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.camera_send_photo),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        if (writing) {
            // The keyboard comes up because she asked for it, not because she brushed a box.
            LaunchedEffect(Unit) {
                box.requestFocus()
                keyboard?.show()
            }

            // Something to send, as opposed to an empty box or a boxful of spaces.
            val readyToSend = replyText.isNotBlank()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // The way out, at the far end of the line from the way to send, so the two are
                // never confused by a hand that does not aim well. It keeps what she has written:
                // pressing it is leaving the keyboard, not losing a sentence.
                //
                // The arrow she already knows, and quietly. A secondary key rather than a primary
                // one, because the two controls either end of this line are not equals:
                // sending is the thing she came here to do and is the only coloured thing on the
                // row, and going back is the way out for when she has changed her mind. A hand
                // that aims badly between two identical circles should err towards the one that
                // does what she meant.
                GentleSecondaryKey(
                    onClick = stopWriting,
                    modifier = Modifier.width(SendButton),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                    minHeight = SendButton,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reply_cancel),
                        modifier = Modifier.size(SendIcon)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    placeholder = { Text(stringResource(R.string.reply_placeholder)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(box),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // A paper plane rather than the word, so that the line she writes on keeps the
                // width the word was costing it. The word survives as what the reading-aloud
                // voice says. Greyed and unpressable until there is something to send, so an
                // empty press cannot be a failure she has to understand.
                GentlePrimaryKey(
                    onClick = {
                        onTextReplySubmitted(replyText)
                        replyText = ""
                        stopWriting()
                    },
                    enabled = readyToSend,
                    modifier = Modifier.width(SendButton),
                    shape = CircleShape,
                    minHeight = SendButton,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send_reply),
                        modifier = Modifier.size(SendIcon)
                    )
                }
            }
        } else if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                theFaces(Modifier.weight(1f))
                if (onCameraRequested != null) {
                    Spacer(modifier = Modifier.width(CircleGap))
                    theCameraButton(Modifier.width(CameraButtonWidth))
                }
                Spacer(modifier = Modifier.width(CircleGap))
                theAnswerButton(Modifier.widthIn(min = AnswerButton))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                theFaces(Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                if (onCameraRequested != null) {
                    // The answer key keeps the whole rest of the line, as wide as it is without
                    // the camera, so the word she uses most never shrinks for the one she uses less.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        theCameraButton(Modifier.width(CameraButtonWidth))
                        Spacer(modifier = Modifier.width(CircleGap))
                        theAnswerButton(Modifier.weight(1f))
                    }
                } else {
                    theAnswerButton(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
