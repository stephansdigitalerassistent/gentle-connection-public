package ch.heuscher.gentleconnect.ui.components

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.FilmLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The one control that plays a film somebody sent her, and how long that film is.
 *
 * A video message has been two things on her screen until now: the word "Video", and — since the
 * still inside the message started being kept — a photograph of whoever is in it. That picture is
 * most of the value and it stays exactly where it is, drawn by [MessagePhoto] above this. This
 * adds the rest: her grandchildren moving and talking, rather than a picture of them not doing so.
 *
 * Two things have changed around it since. The word is gone once this control is on the screen —
 * see [ch.heuscher.gentleconnect.model.Message.showsWords] — because a line reading "Video" above
 * a still with a play button under it describes what she is looking at. And the picture above is
 * no longer WhatsApp's thumbnail once the film has landed: [MessagePhoto] reads a frame out of the
 * film itself, so the moment this button offers to play is the moment shown above it.
 *
 * One control, under the picture, the width of the bubble. It is deliberately the same shape and
 * size as [MessageVoice]'s, because the two are the same act — *something arrived that plays* —
 * and giving them different clothes would make her learn twice.
 *
 * Under the control, and only there, the length of the film. Pressing this takes the whole screen,
 * so unlike a voice message it is a decision, and until now it was one made blind: eight seconds
 * of her great-grandson and four minutes of a wedding looked identical beforehand. It sits *under*
 * the button rather than inside it so that the button's own word never changes — she has learned
 * one shape that says "Ansehen", and a button whose label grows a number is a new button. See
 * [FilmLength] for why it is words rather than "1:47".
 *
 * What it does **not** do is play here, in the bubble. A film playing inline raises a question
 * this app has no good answer to: what happens when she scrolls and it leaves the screen. Either
 * it goes on sounding from somewhere she cannot see, or it stops for a reason she did not cause.
 * So this control opens [FilmPlayer], which is the whole screen and has exactly one way out.
 *
 * Absent rather than broken when there is no film: one that was never fetched, expired on the
 * server, or has been pruned since simply has no control, and the message keeps its picture and
 * its words. Nothing on her screen ever says a film failed to arrive. The length obeys the same
 * rule one level down — a film whose duration cannot be read still gets its button, with no label
 * under it, because "unknown" is a word about her telephone rather than about her family.
 */
@Composable
fun MessageVideo(
    path: String?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (path.isNullOrBlank()) return

    val length = filmLength(path)

    Spacer(modifier = Modifier.height(12.dp))
    GentleKey(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        minHeight = 64.dp,
        fillWidth = true
    ) {
        Text(
            text = stringResource(R.string.video_message_play),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }

    if (length != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = filmLengthInWords(length),
            style = MaterialTheme.typography.titleMedium,
            // Quieter than the button above it and quieter than the message's own words: it is
            // the one thing in the bubble that nobody wrote to her.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The length in the words of whichever language her phone is in. */
@Composable
private fun filmLengthInWords(length: FilmLength.Length): String = when (length) {
    is FilmLength.Length.Seconds -> pluralStringResource(
        R.plurals.video_message_length_seconds,
        length.count,
        length.count
    )
    is FilmLength.Length.Minutes -> pluralStringResource(
        R.plurals.video_message_length_minutes,
        length.count,
        length.count
    )
}

/**
 * How long the film at [path] runs, once the file has been asked.
 *
 * Null until it answers, and null forever if it will not. The bubble draws around whichever it
 * is, so the button appears at the same moment it always did and the label joins it a moment
 * later — rather than the whole message waiting on a file read, which with four years of history
 * on this phone would be a list that pauses as she scrolls.
 *
 * Off the main thread, because opening a media file is a disk read and a header parse, and on the
 * main thread that is a stutter in the scroll of the very list this is drawn in.
 */
@Composable
private fun filmLength(path: String): FilmLength.Length? {
    var length by remember(path) { mutableStateOf(FilmLengths.known(path)) }

    LaunchedEffect(path) {
        if (length == null) length = withContext(Dispatchers.IO) { FilmLengths.read(path) }
    }

    return length
}

/**
 * The lengths already read, kept for as long as the app is up.
 *
 * A conversation with a hundred films is scrolled up and down, and every pass over the same bubble
 * would otherwise open the same file again. Keyed by path, which is what a film is identified by
 * everywhere else here, and holding nothing but two small numbers per entry.
 */
private object FilmLengths {

    private val lengths = ConcurrentHashMap<String, FilmLength.Length>()

    fun known(path: String): FilmLength.Length? = lengths[path]

    fun read(path: String): FilmLength.Length? {
        lengths[path]?.let { return it }

        val retriever = MediaMetadataRetriever()
        val millis = try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            // A file pruned since the bubble was drawn, a truncated download, a container Android
            // will not parse. All of them mean the same thing here — no label — and none of them
            // are worth a line on her screen or a crash.
            null
        } finally {
            runCatching { retriever.release() }
        }

        val length = millis?.let(FilmLength::of) ?: return null
        lengths[path] = length
        return length
    }
}
