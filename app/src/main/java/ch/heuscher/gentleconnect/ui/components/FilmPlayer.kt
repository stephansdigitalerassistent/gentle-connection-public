package ch.heuscher.gentleconnect.ui.components

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.FilmLength
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.WideScreenGripMargin
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import kotlinx.coroutines.delay
import java.io.File
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * A film, on the whole screen, playing.
 *
 * Everything about this is decided by one fact: she is 84, and every control on a screen is a
 * thing she may press by accident and then cannot undo. So there are no controls at all except
 * the way out.
 *
 * - **It starts by itself.** She has already pressed something to get here — see [MessageVideo] —
 *   and a second press, on a small triangle in the middle of a picture, is the press she misses.
 * - **No scrubber, no pause, no speed, no volume, no full-screen toggle.** The same list
 *   [MessageVoice] refuses, for the same reasons. A film that stops when her thumb rests on it,
 *   with no visible sign of what she did, is a broken telephone as far as she can tell.
 * - **A bar that fills as the film runs, which is not a control.** It answers the question a
 *   picture on a black screen otherwise leaves open — is this nearly over, or has it stopped? A
 *   film that has frozen and a film that is playing a still shot of a sleeping baby look exactly
 *   alike, and without the bar the only way to tell is to wait and see. It accepts no touch, for
 *   the reason [GentleScrollbar] accepts none: a small target with a large effect, and getting it
 *   slightly wrong throws away the place she had got to.
 * - **Tapping the picture does nothing.** This is the one place where a tap-anywhere-to-close
 *   would be actively wrong: she holds the phone in the hand she taps with, and losing the film
 *   she just started because she adjusted her grip is not recoverable — she would have to find
 *   the message again.
 * - **One large control, at the bottom, that ends it.** In the words the rest of the app already
 *   uses for leaving something.
 * - **It closes itself when the film ends**, so that doing nothing is a way out too. If she puts
 *   the phone down at the end of a ten-second clip she is not left in a state she has to leave.
 *
 * Black behind it rather than the app's background: it is the only screen in this app that is not
 * a page of her phone, and the film should be the only lit thing on it.
 *
 * Failure is silent and looks like nothing having happened. A file that was pruned between the
 * bubble being drawn and this opening, a codec Android will not decode, a truncated download —
 * all three close this immediately and return her to the conversation, which still has the
 * picture and the words. An error message here would be a screen about a technical event she has
 * no way to act on.
 */
@Composable
fun FilmPlayer(
    path: String,
    onFinished: () -> Unit,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false
) {
    // Held across recomposition so that the listeners installed on the view below always call the
    // current one, without re-creating the view — which would restart the film.
    val finish by rememberUpdatedState(onFinished)

    val context = LocalContext.current
    val player = remember(path) {
        VideoView(context).apply {
            // No MediaController, which is what would otherwise appear on a tap: a strip of small
            // buttons and a drag handle, at the bottom of the screen, over the film.
            setOnPreparedListener { prepared ->
                // Once, not round and round. A film that restarts by itself is one she cannot
                // tell from a film that never ended.
                prepared.isLooping = false
                start()
            }
            setOnCompletionListener { finish() }
            setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            // Films are watched, not read: nothing is being touched for a minute at a time, and
            // the screen going dark mid-film is indistinguishable from the app having stopped.
            keepScreenOn = true
        }
    }

    // How much of the film has gone, asked of the player rather than counted here: a clock of our
    // own would drift away from what she is actually watching the first time the decoder stalls.
    //
    // Polled, because a VideoView announces the beginning and the end and nothing in between. Five
    // times a second is far below what the eye reads as a moving bar and far above what it costs:
    // the position is a number the player already holds.
    //
    // Held as a state object rather than unwrapped with `by`, and deliberately never read in this
    // function. Whoever reads it is who gets rebuilt when it changes, and the first version of this
    // read it here — which rebuilt this whole screen five times a second, re-measured the column,
    // and re-measured the film with it. A [VideoView] re-measured mid-playback resizes its surface,
    // and the film jumped up and down the screen for as long as it ran. The only reader is the one
    // draw below, so a tick now repaints twelve pixels of bar and touches nothing else.
    val watched = remember(player) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            // Both are guarded because either can be asked of a player that has been released a
            // moment ago — she closed the film, or something took the screen — and the answer to
            // that is a zero-length bar rather than a crash on the way out.
            watched.floatValue = FilmLength.watched(
                positionMillis = runCatching { player.currentPosition.toLong() }.getOrDefault(0L),
                durationMillis = runCatching { player.duration.toLong() }.getOrDefault(0L)
            )
            delay(POSITION_TICK_MS)
        }
    }

    // Checked here rather than trusted from the row, because the pruner runs on its own schedule
    // and the path on a message may name a file that was deleted an hour ago.
    LaunchedEffect(path) {
        if (!File(path).isFile) {
            finish()
            return@LaunchedEffect
        }
        player.setVideoPath(path)
    }

    // A VideoView left behind holds a decoder and an audio track, and goes on sounding into a
    // room whose screen has moved on. The same hazard the voice player has, and the same care.
    DisposableEffect(player) {
        onDispose {
            runCatching { player.stopPlayback() }
            player.keepScreenOn = false
        }
    }

    // Anything that takes the screen away ends the film: her daughter ringing, the phone locking
    // itself while she watches, a call she places from the notification. Without this the sound
    // carries on from behind whatever replaced it — she would be hearing her grandson while
    // looking at an incoming call, with no control anywhere on that screen that stops him.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) finish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Measured out here, in the screen this was opened from, and carried in as plain numbers.
    //
    // The bar along the bottom of her phone is 48dp of buttons belonging to the telephone rather
    // than to this app, and "Schliessen" was underneath it — pressing at the one control on this
    // screen got Home instead. The obvious repair, asking for the insets from inside the dialog,
    // does not work here and was tried on the phone first: a dialog is its own window, that window
    // reported nothing to inset for, and the button simply moved further down. What the window
    // does report is reliable one level up, where the film was started from, so it is taken there.
    val bars = WindowInsets.systemBars.asPaddingValues()

    Dialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(
            // Full-bleed. The platform default would inset this into a card with the app's
            // background showing around it, which turns a film into a small picture in a frame.
            usePlatformDefaultWidth = false,
            // Nothing between the window and the display either — see the window below, which is
            // what actually settles it.
            decorFitsSystemWindows = false
        )
    ) {
        // And here is the part that was still wrong after two attempts, measured on the phone
        // both times.
        //
        // A dialog window is placed inside the area left over after the status bar, and asking
        // it to fill its parent then gives it the height of the whole display — so it began 94px
        // down and ended 94px past the bottom edge of the glass. Any margin worked out inside it
        // was therefore 94px too low, which is precisely how far into the navigation bar the
        // button still sat. This puts the window over the display itself, so that the bottom of
        // the window and the bottom of her screen are the same edge and the margins below mean
        // what they say.
        //
        // The status bar's icons are told to be light at the same time. They are drawn over
        // whatever is behind them, and what is behind them here is now black film rather than the
        // system's own grey — dark icons on black is a clock she cannot read while the film runs.
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(MATCH_PARENT, MATCH_PARENT)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .traceTaps("film")
                // The only screen in this app that is not a page of her phone. Black, so the film
                // is the one lit thing on it. Painted before the insets below, so the black still
                // runs to all four edges and only what she has to press is moved inwards. Her
                // bars stay where they are and stay visible; it is this that keeps out from under
                // them.
                .background(Color.Black)
                .padding(bars)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Given the whole box rather than only its width, so that both of the view's
                // measurements are settled from the outside. Told only its width, a [VideoView]
                // works its own height out from the film and hands back a different one the moment
                // the film is ready — which is a second way for the picture to jump, on top of the
                // one the bar used to cause. It letterboxes inside a fixed box by itself.
                AndroidView(
                    factory = { player },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // The bar itself: a dark track the width of the screen, filled from the left by the
            // same green her buttons are. Drawn by hand rather than with the platform's progress
            // indicator for the reason [GentleScrollbar] is — that one is a hairline in somebody
            // else's colours, and this has to be readable across a room, on black, by somebody
            // who is not looking for it.
            //
            // Above the way out rather than over the film, because anything drawn on top of the
            // picture is something she may take for part of it.
            //
            // Painted rather than laid out. A second box measured to a fraction of the first is
            // the obvious way to write this and is what made the film jump: a child whose width
            // changes is a child the column has to measure again, and everything beside it with
            // it. This draws into a box whose size never changes, so five ticks a second cost five
            // repaints of a strip of bar and no measurement anywhere.
            val filled = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.DarkGray)
                    .drawBehind {
                        // Read here and nowhere else — see the poll above. The rounded ends come
                        // from the clip this is drawn inside, so the fill itself is a plain
                        // rectangle and needs no shape of its own.
                        val fraction = watched.floatValue
                        if (fraction > 0f) {
                            drawRect(
                                color = filled,
                                size = Size(size.width * fraction, size.height)
                            )
                        }
                    }
            )

            Spacer(modifier = Modifier.height(16.dp))

            GentlePrimaryKey(
                onClick = onFinished,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                minHeight = 72.dp,
                fillWidth = true
            ) {
                Text(
                    // The word the rest of the app already uses for leaving something, rather
                    // than a new one for this screen. She has learned "Schliessen" once.
                    text = stringResource(R.string.close),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        SupportButton(
            state = support,
            onClick = onSupportPressed,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(bars)
                .padding(top = 16.dp, start = 16.dp, bottom = 16.dp, end = if (isWideWindow()) WideScreenGripMargin else 16.dp),
            isRestless = isRestless
        )
        }
    }
}

/** How often the film is asked where it has got to. Five times a second; see the poll above. */
private const val POSITION_TICK_MS = 200L

/**
 * Thick enough to see from the far side of a room without being a second thing on the screen
 * competing with the film — half again the 8.dp of the scroll bar she already knows, because
 * this one is read at arm's length rather than under her thumb.
 */
private val BAR_HEIGHT = 12.dp
