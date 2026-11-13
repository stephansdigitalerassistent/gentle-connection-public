package ch.heuscher.gentleconnect.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.model.PhotoDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

private val avatarBitmapCache = object : LruCache<String, ImageBitmap>(64) {}

/**
 * A circular portrait for a person: their photograph when one is available, or their initials
 * in a circle when there is no face at all.
 *
 * Used across the app (Look screen, Messages, Contacts, Call screen, Partner faces) for letters,
 * posted pictures, callers, and correspondents. When a contact has no photo in the address book
 * and no WhatsApp avatar fallback, drawing their initials in the circle ensures there is never a
 * broken image box, a generic grey silhouette, or an empty blank space.
 *
 * All image decoding runs off the UI thread on [Dispatchers.IO], is downsampled to the target size,
 * and is cached in memory to eliminate jank when scrolling.
 */
@Composable
fun ContactAvatar(
    photoUri: String?,
    name: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    /**
     * Round for a person, which is what every screen draws. Square only where the face is one of
     * several people — see [GroupAvatar] — so the shape itself says which of the two she is
     * writing to.
     */
    shape: Shape = CircleShape
) {
    val context = LocalContext.current
    var photo by remember(photoUri) {
        mutableStateOf(photoUri?.let { avatarBitmapCache.get(it) })
    }

    LaunchedEffect(photoUri) {
        if (photoUri.isNullOrBlank()) {
            photo = null
            return@LaunchedEffect
        }

        val cached = avatarBitmapCache.get(photoUri)
        if (cached != null) {
            photo = cached
            return@LaunchedEffect
        }

        val decoded = withContext(Dispatchers.IO) {
            decodeSampledBitmap(context, photoUri, targetSizePx = 256)
        }
        if (decoded != null) {
            avatarBitmapCache.put(photoUri, decoded)
        }
        photo = decoded
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        val bmp = photo
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = PersonName.initials(name),
                style = textStyle,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun decodeSampledBitmap(
    context: Context,
    uriString: String,
    targetSizePx: Int = 128
): ImageBitmap? = runCatching {
    val uri = Uri.parse(uriString)

    // 1. Decode bounds only to calculate inSampleSize.
    //
    // The stream is checked for itself, and the decode's own answer is thrown away on purpose.
    // With inJustDecodeBounds set, decodeStream returns null by contract — the size comes back on
    // `options`, never as a bitmap. This was written as
    //
    //     openUriStream(context, uri)?.use { decodeStream(stream, null, options) } ?: return null
    //
    // which reads as "give up if the photograph cannot be opened" and in fact was "give up,
    // always": `use` handed back the null decodeStream is documented to return, the elvis fired
    // on it, and the function left before it had looked at `options` at all. Every photograph in
    // the app failed here — her favourites, her post, her messages, every face in every circle —
    // and each one fell through to the initials that exist for people who genuinely have no
    // picture. Nothing looked broken; the app simply behaved as though her address book had no
    // photographs in it.
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    val bounds = openUriStream(context, uri) ?: return null
    bounds.use { stream -> BitmapFactory.decodeStream(stream, null, options) }

    val width = options.outWidth
    val height = options.outHeight
    if (width <= 0 || height <= 0) return null

    // The same arithmetic as every other photograph the app decodes, rather than a second copy
    // of it here — see [PhotoDecoding.sampleSizeFor], which is where it is proved.
    val inSampleSize = PhotoDecoding.sampleSizeFor(width, height, targetSizePx)

    // 2. Decode the downsampled bitmap
    val decodeOptions = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }

    openUriStream(context, uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOptions)?.asImageBitmap()
    }
}.getOrNull()

/**
 * A photograph, wherever it is: her address book, or a file this app wrote.
 *
 * A scheme-less path counts as a file, and that is not a nicety. Every face here used to come
 * from `ContactsContract` as a `content://` URI, so the imported group avatars — written to
 * `files/threema_group_photos` and stored as plain paths — parsed to a URI with no scheme at all,
 * went to the content resolver, and threw. Caught, and drawn as two letters in a circle: five
 * group pictures sitting on the phone, none of them on her screen.
 */
private fun openUriStream(context: Context, uri: Uri): InputStream? = runCatching {
    if (uri.scheme == null || uri.scheme == "file") {
        val path = uri.path ?: return null
        val file = File(path)
        if (file.exists() && file.canRead()) FileInputStream(file) else null
    } else {
        context.contentResolver.openInputStream(uri)
    }
}.getOrNull()
