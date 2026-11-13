package ch.heuscher.gentleconnect.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.MailAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What came with a letter, drawn underneath its words.
 *
 * The letter used to be the words and nothing else, which for a great deal of her post meant the
 * letter was nothing: a photograph of the grandchildren sent by mail arrived as an empty screen,
 * and an appointment sent as a PDF arrived as "Im Anhang finden Sie…" followed by nothing at all.
 * Both had reached the phone. Neither had reached her, and there was no sign on the screen that
 * anything was missing — which is the failure she cannot detect, the same one that made every
 * letter visible rather than only those from senders somebody had stored.
 *
 * A PDF is drawn as its pages rather than offered as a file. There is nowhere on this phone to
 * send a file to, no other app she is expected to know, and no state she would have to get back
 * out of — the pages simply continue below the letter, and she scrolls the way she was already
 * scrolling. See [pdfPages] for what happens when a PDF is longer than that promise can carry.
 *
 * Nothing here is interactive, the same rule [MessagePhoto] follows: no tap to enlarge, no
 * gallery. It is there, at the width of the letter, and the whole app is already inside the
 * magnifier the caregiver can turn on.
 */
@Composable
fun LetterAttachments(attachments: List<MailAttachment>, modifier: Modifier = Modifier) {
    val shown = attachments.filter { it.kind != null }
    if (shown.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.letter_attachments_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        shown.forEach { attachment ->
            when {
                attachment.isPicture -> MessagePhoto(path = attachment.path)
                attachment.isPaper -> LetterPaper(attachment = attachment)
            }
        }
    }
}

/**
 * One PDF, as its pages.
 *
 * When not a single page can be drawn — a file that never finished downloading, an encrypted PDF,
 * a format this phone's renderer will not take — what is left is the name the sender gave it. That
 * is a deliberate exception to the rule everywhere else in this app that a failure shows nothing:
 * here, showing nothing would put her back exactly where this change started, reading "see the
 * attachment" with no attachment and no way to know one had ever existed. A name is little, but it
 * is something she can read out to whoever is visiting.
 */
@Composable
private fun LetterPaper(attachment: MailAttachment) {
    var pages by remember(attachment.path) { mutableStateOf<List<ImageBitmap>?>(null) }

    LaunchedEffect(attachment.path) {
        pages = withContext(Dispatchers.IO) { pdfPages(attachment.path) }
    }

    val drawn = pages ?: return

    if (drawn.isEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Spacer(modifier = Modifier.height(12.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        drawn.forEach { page ->
            Image(
                bitmap = page,
                // Null rather than a description, as under a photograph: the words invented here
                // would be read out by the read-aloud button as though somebody had written them.
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(page.width.toFloat() / page.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/**
 * The first few pages of a PDF, as pictures.
 *
 * Three pages, and only three. Each one is a bitmap of several megabytes in a launcher that has to
 * survive being in the background — the process being killed while she reads a letter would cost
 * her the screen she was on, which is far worse than a page she did not see. Nearly everything her
 * doctor's surgery and her bank send is one or two pages; what is longer than that is a contract,
 * and a contract is not a thing this phone can usefully put in front of her anyway.
 *
 * Every failure returns an empty list and never throws: a truncated file, a PDF with a password on
 * it, a renderer that will not open it. [LetterPaper] then falls back to the file's name.
 */
private fun pdfPages(path: String): List<ImageBitmap> {
    val file = File(path)
    if (!file.isFile) return emptyList()

    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)

        val pages = mutableListOf<ImageBitmap>()
        for (index in 0 until minOf(renderer.pageCount, MAX_PAGES)) {
            val page = renderer.openPage(index) ?: continue
            try {
                if (page.width <= 0 || page.height <= 0) continue
                val height = maxOf(
                    1,
                    (PAGE_WIDTH_PIXELS.toFloat() * page.height / page.width).toInt()
                )
                val bitmap = Bitmap.createBitmap(PAGE_WIDTH_PIXELS, height, Bitmap.Config.ARGB_8888)
                // A PDF page is drawn onto whatever is already there, and what is already there is
                // transparency. Without this her black text lands on the dark background of the
                // reader and the page is unreadable — paper is white.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pages.add(bitmap.asImageBitmap())
            } finally {
                try {
                    page.close()
                } catch (e: Throwable) {
                    // Closing a page that failed to open properly. Nothing to do about it.
                }
            }
        }
        pages
    } catch (e: Throwable) {
        emptyList()
    } finally {
        try {
            renderer?.close()
        } catch (e: Throwable) {
        }
        try {
            descriptor?.close()
        } catch (e: Throwable) {
        }
    }
}

/** How many pages of one PDF are ever drawn. See [pdfPages]. */
private const val MAX_PAGES = 3

/**
 * How wide a page is rendered, in pixels. Wider than the screen of the phone she has, so a page
 * is never drawn smaller than it is shown, and small enough that three of them together are a
 * cost this process can carry.
 */
private const val PAGE_WIDTH_PIXELS = 1080
