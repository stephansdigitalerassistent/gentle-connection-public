package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photograph, or a film's first frame, drawn into whatever box it is given — fitted whole on a
 * page of the viewer, cropped to its frame on the album card.
 *
 * [MessagePhoto] sizes itself to the picture's own shape at the width of a bubble, which is right in
 * a conversation and wrong in both of these places: the viewer has a page of fixed size to fit a
 * picture into, and the album's second picture a fixed 4:3 frame so that an upright photograph
 * cannot make the card a screen tall. Decoded the way [MessagePhoto] decodes, at
 * [ch.heuscher.gentleconnect.model.PhotoDecoding.TARGET_PIXELS], off the main thread; a film's frame
 * comes already scaled from [FilmFrames].
 *
 * Nothing until the file has been read, and nothing at all if it cannot be — whatever is behind it
 * shows through, and nothing on her screen mentions a fault.
 */
@Composable
fun DecodedPhoto(
    path: String?,
    videoPath: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    var still by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var frame by remember(videoPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(videoPath) {
        val film = videoPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        frame = withContext(Dispatchers.IO) { FilmFrames.of(film) }
    }

    LaunchedEffect(path) {
        val file = path?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        still = withContext(Dispatchers.IO) { decodePhotoBitmap(file) }
    }

    // The film's own frame wins whenever there is one, as in [MessagePhoto]: the still that came
    // packed in the message only fills the wait.
    val image = frame ?: still ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
    )
}
