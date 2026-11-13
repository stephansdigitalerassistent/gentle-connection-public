package ch.heuscher.gentleconnect.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.WideScreenGripMargin
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import ch.heuscher.gentleconnect.util.UiTrace
import ch.heuscher.gentleconnect.util.traceTaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photograph opened over the whole screen, with a clear way back.
 *
 * ## Why this exists (evidence from her phone, 2026-09-28 19:42 and 19:46)
 *
 * She tapped a photograph in a conversation 3–6 times in a burst, trying to see it bigger, and
 * hit nothing because photos in chat bubbles were completely inert. She wants the photograph
 * larger, without distractions.
 *
 * ## The way back and getting help
 *
 * A full-screen view is a state she has to know how to leave, so there are three ways and all are
 * ones she already knows: the round green back arrow at the top left (the same place as every
 * other screen), the large "← Zurück" key at the bottom centre (the same size and place as the
 * key that undoes a magnification), and the phone's own Back.
 *
 * At the top right, the support button is available while not zoomed, so she is never stranded in
 * a full-screen view without the ability to reach her support group.
 *
 * A double tap does here what it does everywhere else in the app — magnifies the photograph where
 * it landed, and a second one puts it back. It never closes the viewer: an accidental double touch
 * from her tremor must not take away the picture she is looking at.
 */
@Composable
fun PhotoViewer(
    path: String,
    onClose: () -> Unit,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var isZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        UiTrace.event("photo viewer open")
        val decoded = withContext(Dispatchers.IO) {
            decodePhotoBitmap(path, target = 2048)
        }
        if (decoded == null) {
            UiTrace.event("photo viewer close (decode failed)")
            onClose()
        } else {
            bitmap = decoded
        }
    }

    Dialog(
        onDismissRequest = {
            UiTrace.event("photo viewer close (back)")
            onClose()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            UiTrace.event("photo viewer close (back)")
            onClose()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .traceTaps("photo-viewer")
        ) {
            val image = bitmap
            if (image != null) {
                PinchToZoom(
                    modifier = Modifier.fillMaxSize(),
                    resetButtonInsets = WindowInsets.navigationBars,
                    onZoomChanged = { isZoomed = it }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .doubleTapToMagnify()
                        )
                    }
                }
            }

            if (!isZoomed) {
                GentleKey(
                    onClick = {
                        UiTrace.event("photo viewer close (arrow)")
                        onClose()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(16.dp)
                        .width(56.dp),
                    face = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                    minHeight = 56.dp,
                    contentPadding = PaddingValues(0.dp),
                    contentAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.viewer_back),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                SupportButton(
                    state = support,
                    onClick = onSupportPressed,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 16.dp, start = 16.dp, bottom = 16.dp, end = if (isWideWindow()) WideScreenGripMargin else 16.dp),
                    isRestless = isRestless
                )

                GentleKey(
                    onClick = {
                        UiTrace.event("photo viewer close (button)")
                        onClose()
                    },
                    face = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 28.dp),
                    minHeight = 64.dp
                ) {
                    Text(
                        text = stringResource(R.string.photo_viewer_close),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
