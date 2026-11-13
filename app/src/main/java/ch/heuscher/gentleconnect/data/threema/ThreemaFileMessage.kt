package ch.heuscher.gentleconnect.data.threema

import java.io.File
import java.util.Locale

/**
 * The message a photograph travels in.
 *
 * Threema sends everything that is not words as a **file message**: a small JSON body naming two
 * blobs — the thing itself and a still — and the one key that opens either. The blobs are put up
 * by the bridge ([ThreemaClient.uploadMedia]); this is the envelope that names them, and it goes
 * out through [ThreemaClient.sendRawMessage] like any other message.
 *
 * The field names are one letter each. They are Threema's, not ours — the same names the bridge
 * reads incoming messages by, in `media.rs` — and the reason this is a file of its own with tests
 * is that getting one of them wrong is invisible: the message sends, and the recipient sees
 * nothing at all.
 */
object ThreemaFileMessage {

    /** `FILE = 0x17` in Threema's `common.proto`. What a file message is called on the wire. */
    const val MESSAGE_TYPE: Int = 0x17

    /** Shown inline — a photograph, a film, somebody talking. */
    private const val RENDERING_MEDIA = 1

    /** A document: named, and left to the recipient to open. */
    private const val RENDERING_FILE = 0

    /**
     * The JSON body of a file message.
     *
     * @param blobId hex name of the file's own blob.
     * @param thumbnailBlobId hex name of the still, where there is one.
     * @param key hex, the key both blobs are sealed with.
     * @param mime what the file is, which is what decides how the recipient's app draws it.
     * @param name what to call it on the other side.
     * @param size of the plaintext, in bytes.
     * @param caption what she wants said under it. Empty for a forward, which carries no words of
     *   her own — the picture is the message.
     */
    fun body(
        blobId: String,
        thumbnailBlobId: String?,
        key: String,
        mime: String,
        name: String,
        size: Long,
        caption: String = ""
    ): String = buildString {
        append("{")
        append(""""b":"""").append(escaped(blobId)).append("\"")
        if (!thumbnailBlobId.isNullOrBlank()) {
            append(""","t":"""").append(escaped(thumbnailBlobId)).append("\"")
        }
        append(""","k":"""").append(escaped(key)).append("\"")
        append(""","m":"""").append(escaped(mime)).append("\"")
        append(""","n":"""").append(escaped(name)).append("\"")
        append(""","s":""").append(size)
        // `i` is the rendering type Threema used before `j`, and old clients still read it; `j`
        // is the one modern clients act on. Both are sent, and they agree.
        append(""","i":0""")
        append(""","j":""").append(renderingOf(mime))
        if (caption.isNotBlank()) {
            append(""","d":"""").append(escaped(caption)).append("\"")
        }
        append("}")
    }

    /**
     * Written by hand rather than with `JSONObject`, which does not exist off the phone — and a
     * message body nobody can test is a message body nobody can check. The escaping is the whole
     * of what a JSON string needs; everything else here is hex, a mime type or a number.
     */
    private fun escaped(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    /**
     * Inline or as a document.
     *
     * By mime type, exactly as the bridge decides what an arriving message is — a photograph she
     * forwards must arrive at her son's phone as a photograph, not as a file called
     * `IMG_2093.jpg` that he has to tap twice to see.
     */
    private fun renderingOf(mime: String): Int =
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            RENDERING_MEDIA
        } else {
            RENDERING_FILE
        }

    /** What a file message body looks like on the wire: the bytes of the JSON, as hex. */
    fun hex(body: String): String =
        body.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    /**
     * What the file is, worked out from its name.
     *
     * The counterpart of the bridge's `extension()`, which wrote these files in the first place:
     * everything this app has on disk got its ending from that table, so this is that table read
     * backwards. An ending it does not know is not guessed — `application/octet-stream` is
     * honest, and the recipient's phone will look inside the file anyway.
     */
    fun mimeOf(path: String): String =
        when (File(path).extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "amr" -> "audio/amr"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }

    /**
     * What to call the file on the other side.
     *
     * Not the name it has here, which is a message id and a dot and an extension — a name that
     * says nothing to anybody and quietly tells the recipient which of her messages this came
     * out of. A plain name and the right ending instead.
     */
    fun nameFor(path: String, mime: String): String {
        val extension = File(path).extension.lowercase(Locale.ROOT).ifBlank { "bin" }
        val stem = when {
            mime.startsWith("image/") -> "bild"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "sprachnachricht"
            else -> "datei"
        }
        return "$stem.$extension"
    }
}
