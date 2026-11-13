package ch.heuscher.gentleconnect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Attachment

/**
 * A message as she should read it: her sender's words, or a word for what arrived instead.
 *
 * One place, so a photograph is called the same thing in the list on the home screen and in the
 * conversation it opens — the same reason [dayInWords] is one place. It is also what read-aloud
 * is handed, so the voice says "Photo" rather than spelling out a token.
 */
@Composable
fun messageInWords(text: String): String {
    // Her sender's own words win over any word this app could supply. A captioned photograph is
    // "Grüsse aus dem Garten" and not "Photo" — and until the token was allowed to carry a caption
    // it was neither, it was "[Foto] Grüsse aus dem Garten". See [Attachment].
    val caption = Attachment.captionIn(text)
    if (caption.isNotBlank()) return caption
    return attachmentInWords(text)
}

@Composable
private fun attachmentInWords(text: String): String = when (Attachment.of(text)) {
    Attachment.PHOTO -> stringResource(R.string.attachment_photo)
    Attachment.VOICE -> stringResource(R.string.attachment_voice)
    Attachment.VIDEO -> stringResource(R.string.attachment_video)
    Attachment.LOCATION -> stringResource(R.string.attachment_location)
    Attachment.FILE -> stringResource(R.string.attachment_file)
    Attachment.POLL -> stringResource(R.string.attachment_poll)
    Attachment.CONTACT -> stringResource(R.string.attachment_contact)
    Attachment.STICKER -> stringResource(R.string.attachment_sticker)
    Attachment.MESSAGE -> stringResource(R.string.attachment_message)
    Attachment.OTHER -> stringResource(R.string.attachment_other)
    null -> text
}
