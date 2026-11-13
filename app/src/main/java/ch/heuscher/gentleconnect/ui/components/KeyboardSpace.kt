package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

/**
 * Gives a screen only the room the keyboard has left it.
 *
 * A screen with a search box on it is two things at once: a box she types in and a list she reads
 * the answers from. While she types, the keyboard covers the bottom half of the phone — and the
 * list did not know. It was still laid out for the whole screen, so its lower half, and with it
 * most of what she had just searched for, was drawn underneath the keys. Scrolling did not help:
 * the part of the list she was scrolling towards was under the keyboard too, and the end of it
 * could not be reached at all.
 *
 * This makes the screen end where the keyboard begins, so the list is a shorter list rather than a
 * hidden one, and scrolling reaches the last result while she is still typing.
 *
 * The same thing, and the same fix, on a conversation: the reply box lives under the messages, so
 * the keyboard she opened by tapping it covered the box she was typing into. Here the messages
 * give up the room instead, and what she is writing stays above the keys.
 *
 * Nothing moves the window underneath us: the manifest asks for `adjustNothing`, so the keyboard
 * is reported as an inset and this layout is the only thing that acts on it. That is load-bearing.
 * Android's own default was to pan the window up, and with both of them moving, a conversation's
 * reply box went up twice — once here and once by the system — leaving the messages shoved off the
 * top of the screen and a keyboard's height of black underneath the box she was typing into. The
 * search screens hid it: their box is near the top, so there was never anything for a pan to
 * reveal, and only the conversation showed what was wrong.
 *
 * [scaffoldPadding] is the screen's own `Scaffold` padding, applied and then declared as spoken
 * for: without that second half, the space already reserved for the bar along the bottom would be
 * counted a second time and the list would stop a bar's height short of the keys.
 *
 * The bar along the bottom stays behind the keyboard rather than riding above it. It is four
 * large buttons; lifting it would cost the results more room than the keyboard already does, and
 * the way out of a search she has finished is the same as it always was — the cross in the box,
 * or the way back at the top of the screen.
 */
fun Modifier.aboveTheKeyboard(scaffoldPadding: PaddingValues): Modifier =
    padding(scaffoldPadding)
        .consumeWindowInsets(scaffoldPadding)
        .imePadding()
