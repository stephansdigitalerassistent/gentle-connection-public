package ch.heuscher.gentleconnect.ui.components

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.model.PhotoDecoding
import ch.heuscher.gentleconnect.model.PhotoShape
import ch.heuscher.gentleconnect.model.PictureShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A photograph somebody sent her, drawn under the words of the message it came with.
 *
 * This is the point of downloading them at all. "[Foto]" tells her that her daughter sent a
 * picture and gives her no way to see it — which on a phone whose whole purpose is to keep her in
 * touch with her family is the emptiest thing on the screen. A photograph of the grandchildren is
 * not an attachment to a message. It is the message.
 *
 * One tap on a photograph opens it over the whole screen ([PhotoViewer]); a double tap magnifies
 * the screen where her finger landed, as two fingers spreading on it would, and a second double tap
 * puts it back. It was deliberately inert until 2026-09-28, when her tap trace fired the
 * restlessness alarm twice in five minutes: she tapped a photograph 3–6 times because she wanted it
 * bigger, and every tap landed on nothing. The viewer has one large way back, and a double tap
 * only ever changes the size of things, so a tremor that produces one by accident does no harm.
 *
 * A film's still plays the film on one tap, the same as its Play button underneath, and takes no
 * double tap. Until 2026-10-24 it was the other way round — playing was left to the button alone —
 * and her trace on the Fold caught the cost: she tapped the picture of the family in the film four
 * times in quick succession and nothing happened, because the large picture is obviously the
 * thing to press and the button under it is not. The double tap had to go with it: four quick taps
 * are two double taps, which would have magnified and shrunk the still and never played anything.
 * The film opens over the whole screen anyway, so magnifying its first frame here bought nothing.
 * The still carries the Look screen's play mark ([FilmMark]) so the picture says it plays.
 *
 * It sits at the width of the bubble, in the shape it was taken in — see [PhotoShape], which is also
 * where the two shapes too extreme to be shown whole are decided.
 *
 * Every failure ends as no picture and no message about it. The file may never have arrived, may
 * have been pruned since, may be a format Android will not decode. In all three cases the words
 * are still on the screen, which is what she had before this existed — and an error where a
 * photograph should be is worse than a photograph that is missing.
 */
@Composable
fun MessagePhoto(
    path: String?,
    videoPath: String? = null,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null,
    onPlay: (() -> Unit)? = null
) {
    if (path.isNullOrBlank() && videoPath.isNullOrBlank()) return

    // Two sources for one picture, and which one is drawn changes over the life of the message.
    //
    // A film arrives as two files: the still WhatsApp packs inside the message, which costs no
    // network and is there immediately, and the film itself, downloaded behind it. That embedded
    // still is tiny and heavily compressed — it is meant as a thumbnail in a chat list, and blown
    // up to the width of her bubble it is a smear of colour where her great-grandson's face should
    // be. The film, once it lands, has the same picture in it at the size it was actually filmed.
    //
    // So the still goes up the moment it can, and is replaced by a frame from the film when the
    // film is there. Kept as two states rather than one, because the two reads finish in whatever
    // order they finish: a single variable would let the slow, ugly one land last and overwrite
    // the good one, which is the bug this shape exists to make impossible.
    var still by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var frame by remember(videoPath) { mutableStateOf<ImageBitmap?>(null) }

    // What this card is showing, for the shape it was last drawn in — see [PictureShapes].
    val shapeKey = PictureShapes.keyFor(path, videoPath)

    LaunchedEffect(videoPath) {
        val film = videoPath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { FilmFrames.of(film) } ?: return@LaunchedEffect
        PictureShapes.note(shapeKey, PhotoShape.ratioFor(read.width, read.height))
        frame = read
    }

    LaunchedEffect(path) {
        val file = path?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        still = withContext(Dispatchers.IO) {
            decodePhotoBitmap(file)
        }

        // Noted the moment it is known, and from the picture rather than from the file, so that
        // what is remembered is the shape it will actually be drawn in.
        still?.let { PictureShapes.note(shapeKey, PhotoShape.ratioFor(it.width, it.height)) }
    }

    // The film's own frame wins whenever there is one. Never a flicker back the other way: the
    // still only ever fills the wait, and a film that will not give up a frame keeps it for good.
    val image = frame ?: still

    if (image == null) {
        // Nothing to draw yet — but if this picture has been on her screen before, its shape is
        // known and the room it is about to want is held open until the file has been read again.
        //
        // Drawing nothing is what moved her post out from under her: a list is put back at the
        // card she left it on and at how far into that card she had scrolled, and a card that is
        // briefly a tenth of its height cannot hold that scroll, so the remainder runs on into
        // whatever is below. Pressing back on a letter landed her two pictures further down.
        //
        // A picture she has never seen still draws nothing, because nothing is known about it
        // yet. That case is her scrolling forward into new post, where there is no place to lose.
        val known = PictureShapes.of(shapeKey) ?: return

        Spacer(modifier = Modifier.height(12.dp))
        Spacer(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(known)
        )
        return
    }

    // Keyed on which gestures exist, never on the lambdas: the conversation recomposes often and
    // hands this new lambdas each time, and restarting the detector in between her two taps would
    // lose the first one — see caregiverTaps for the same trap.
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val hasTap = onTap != null
    val hasDoubleTap = onDoubleTap != null
    val tapModifier = if (onPlay != null) {
        // A real click rather than a tap detector: it answers at once instead of waiting to see
        // whether a second tap follows, it is what UiTrace recognises as something to press, and
        // forgivingClick catches the taps her tremor turns into small drags — as on the cards of
        // the Look screen.
        Modifier
            .clickable(onClick = onPlay)
            .forgivingClick(onClick = onPlay)
    } else if (hasTap || hasDoubleTap) {
        Modifier
            .pointerInput(hasTap, hasDoubleTap) {
                detectTapGestures(
                    onTap = if (hasTap) { { _: Offset -> tap?.invoke() } } else null,
                    onDoubleTap = if (hasDoubleTap) { { offset: Offset -> doubleTap?.invoke(offset) } } else null
                )
            }
            // A click in the semantics too, so UiTrace counts a tap here as answered. Without it
            // the tap that opens the photo read as "(nothing)", and on 2026-10-25 that tap plus two
            // on the open photo's edge made a same-spot alarm out of her browsing pictures.
            .then(if (hasTap) Modifier.semantics { onClick { tap?.invoke(); true } } else Modifier)
    } else {
        Modifier
    }

    Spacer(modifier = Modifier.height(12.dp))
    Box(
        modifier = modifier
            .fillMaxWidth()
            // The width of the bubble, and then whatever height that photograph is — rather than
            // a fixed 220.dp box that every picture was cropped into. Upright photographs, which
            // is most of what her family sends, were arriving as a band across the middle with
            // the faces cut off the top and nothing on the screen to say so. See [PhotoShape] for
            // the two shapes that are still overruled, and why.
            .aspectRatio(PhotoShape.ratioFor(image.width, image.height))
            .clip(RoundedCornerShape(16.dp))
            .then(tapModifier)
    ) {
        Image(
            bitmap = image,
            // Null rather than a description: TalkBack is not in use here, and a caption invented
            // by the app ("Bild") would be read aloud by the read-aloud button as though somebody
            // had written it.
            contentDescription = null,
            // Only ever crops at the limits below, where the picture's own shape has been
            // overruled. Between them the box is the shape of the photograph and this does nothing.
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        // Only where pressing the picture plays something. It is the same sign the Look screen
        // puts on a film, and it says what the tap does before she has to find out.
        if (onPlay != null) FilmMark(Modifier.matchParentSize())
    }
}

/**
 * Decodes a photograph file off-thread down to roughly [target] pixels on a side.
 *
 * Two passes: the first reads only dimensions so the second decodes straight to roughly the
 * size it will be drawn at, saving memory on large phone camera images.
 */
internal fun decodePhotoBitmap(
    file: String,
    target: Int = PhotoDecoding.TARGET_PIXELS
): ImageBitmap? = runCatching {
    if (!File(file).isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file, bounds)
    if (bounds.outWidth <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = PhotoDecoding.sampleSizeFor(bounds.outWidth, bounds.outHeight, target = target)
    }
    BitmapFactory.decodeFile(file, options)?.asImageBitmap()
}.getOrNull()

/**
 * The first real picture out of a film, in place of the thumbnail that came with the message.
 *
 * WhatsApp packs a still inside every video message so that a chat list has something to draw. It
 * is sized for a chat list: a hundred-odd pixels across, compressed hard, and this app puts it at
 * the full width of her bubble because a picture of her family is the point. Blown up that far it
 * is a smear. The film sitting next to it on disk has the same moment in it at the size it was
 * filmed, and reading one frame out of a local file costs no network at all.
 *
 * The frame is taken at the start rather than at some clever offset. A film of her grandchildren
 * opens on her grandchildren; picking a "representative" moment would mean deciding on her behalf
 * which second of her family is worth seeing, and being wrong about it silently.
 *
 * Every failure returns null and leaves the embedded still exactly where it was. A truncated
 * download, a codec this phone cannot open, a file pruned between the bubble being drawn and this
 * running — all of them mean "no better picture available", which is not a thing her screen should
 * ever mention.
 */
internal object FilmFrames {

    /**
     * Frames already read, so scrolling past the same film twice does not open it twice.
     *
     * Small on purpose. A frame is a bitmap of a few megabytes and this app is a launcher that
     * must survive being in the background; a cache that grew with the length of her history would
     * be the thing that got the process killed. Two is enough for what a screen can show at once —
     * beyond that, re-reading a local file is cheap and happens off the main thread anyway.
     */
    private const val KEEP = 2

    private val frames = object : LinkedHashMap<String, ImageBitmap>(KEEP + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > KEEP
    }

    fun of(path: String): ImageBitmap? {
        synchronized(frames) { frames[path] }?.let { return it }
        if (!File(path).isFile) return null

        val retriever = MediaMetadataRetriever()
        val bitmap = try {
            retriever.setDataSource(path)
            retriever.frameAtStart()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }

        val image = bitmap?.asImageBitmap() ?: return null
        synchronized(frames) { frames[path] = image }
        return image
    }

    /**
     * A frame from the beginning, decoded no larger than it will be drawn.
     *
     * [MediaMetadataRetriever.getScaledFrameAtTime] does the scaling inside the decoder, which is
     * both faster and — the part that matters on a phone shared with everything else this launcher
     * does — never allocates the full-size bitmap at all. It arrived in API 27 and this app still
     * runs on 26, so the older path stays: a full frame, which for a telephone video is around
     * eight megabytes and is released as soon as Compose has copied it.
     *
     * The target keeps the film's own shape. Handing this two numbers that are not its aspect
     * ratio returns a stretched picture, and a stretched picture of a face is worse than a small
     * one — see [PhotoShape], which is where shape is decided for everything else on this screen.
     */
    private fun MediaMetadataRetriever.frameAtStart(): android.graphics.Bitmap? {
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val target = drawnSize()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && target != null) {
            getScaledFrameAtTime(0L, option, target.first, target.second)
        } else {
            getFrameAtTime(0L, option)
        }
    }

    /**
     * How large to ask for the frame: the film's own shape, no wider than a screen.
     *
     * Null when the file will not say what size it is, which is the signal to take the frame
     * however it comes rather than to invent a shape for it.
     *
     * A film held upright reports its size the way the camera sensor saw it, with the quarter turn
     * kept separately — a portrait film says 1920x1080 and 90 degrees. The frame comes back already
     * turned, so the target has to be turned too; asking for 1920x1080 of a picture that is
     * 1080x1920 returns it stretched, and a stretched face is worse than a small one.
     */
    private fun MediaMetadataRetriever.drawnSize(): Pair<Int, Int>? {
        val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null

        val turned = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?.let { it % 180 != 0 } ?: false
        val shownWidth = if (turned) height else width
        val shownHeight = if (turned) width else height

        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(shownWidth, shownHeight))
        return maxOf(1, (shownWidth * scale).toInt()) to maxOf(1, (shownHeight * scale).toInt())
    }

    /** Wider than any phone screen this runs on, so nothing is ever decoded larger than it is drawn. */
    private const val MAX_EDGE = 1440
}
