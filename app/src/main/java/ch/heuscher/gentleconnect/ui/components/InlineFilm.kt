package ch.heuscher.gentleconnect.ui.components

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.heuscher.gentleconnect.model.FilmLength
import kotlinx.coroutines.delay
import java.io.File

/**
 * A film playing inline inside the burst viewer, without a Dialog.
 *
 * Playing inline is acceptable here though [MessageVideo] refuses it in conversations: the
 * viewer is a full-screen state holding one film at a time, and leaving the page or the
 * viewer stops it, so it can never go on sounding from somewhere she cannot see — which is
 * the hazard that made [FilmPlayer] a Dialog and [MessageVideo] a button.
 *
 * Identical rules otherwise: starts by itself, no controls but the way out, keepScreenOn,
 * closes on lifecycle pause, and the same progress bar drawn the same way — polled into a
 * float state read only inside `drawBehind`, so the bar moves and nothing else recomposes.
 */
@Composable
fun InlineFilm(
    path: String,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val finish by rememberUpdatedState(onEnded)
    val context = LocalContext.current

    val player = remember(path) {
        VideoView(context).apply {
            setOnPreparedListener { prepared ->
                prepared.isLooping = false
                start()
            }
            setOnCompletionListener { finish() }
            setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            keepScreenOn = true
        }
    }

    val watched = remember(player) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            watched.floatValue = FilmLength.watched(
                positionMillis = runCatching { player.currentPosition.toLong() }.getOrDefault(0L),
                durationMillis = runCatching { player.duration.toLong() }.getOrDefault(0L)
            )
            delay(200L)
        }
    }

    LaunchedEffect(path) {
        if (!File(path).isFile) {
            finish()
            return@LaunchedEffect
        }
        player.setVideoPath(path)
    }

    DisposableEffect(player) {
        onDispose {
            runCatching { player.stopPlayback() }
            player.keepScreenOn = false
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) finish()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { player },
            modifier = Modifier.fillMaxSize()
        )

        val filled = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.DarkGray)
                .drawBehind {
                    val fraction = watched.floatValue
                    if (fraction > 0f) {
                        drawRect(
                            color = filled,
                            size = Size(size.width * fraction, size.height)
                        )
                    }
                }
        )
    }
}
