package ch.heuscher.gentleconnect.model

/**
 * Telling the sender that a message arrived, and that she read it.
 *
 * This app was not doing either. Messages came in and were shown to her, and the person who sent
 * them saw one grey tick for ever — the message had reached Threema's server and, as far as their
 * telephone could tell, gone nowhere. For the people who write to her that is the wrong end of
 * the only question they have. Somebody who cannot reliably reply is somebody whose family reads
 * the ticks instead, and this app was telling them, every time, that nothing had got through.
 *
 * A delivery receipt is a message like any other: type `0x80`, body a receipt kind followed by
 * the ids of the messages it refers to. There are two kinds worth sending, and this app sends
 * both for their real meanings — *received* the moment it is stored, *read* when she opens the
 * conversation. Never both at once, and never *read* because a background sync happened to
 * touch the row: a read receipt for a message she has not looked at is a lie told to her
 * daughter, and it is the kind of lie that stops anybody trusting the ticks at all.
 *
 * Everything here is pure, because the failure it guards against is silent in both directions.
 * A malformed receipt is dropped by the other end with no error anywhere, and the only symptom
 * is the thing that was already wrong: no tick.
 */
object ThreemaReceipt {

    /** `DELIVERY_RECEIPT` in Threema's `common.proto`. One-to-one only; groups are `0x81`. */
    const val MESSAGE_TYPE = 0x80

    /** It reached her telephone and is stored. */
    const val RECEIVED = 0x01

    /** She opened the conversation and it was in front of her. */
    const val READ = 0x02

    /** Threema message ids are eight bytes, which is sixteen hexadecimal characters. */
    private const val MESSAGE_ID_HEX_LENGTH = 16

    /**
     * The body of a receipt for one message, as hex, or null if the id is not usable.
     *
     * **The byte order is the whole of the difficulty.** A Threema message id is a 64-bit number
     * that goes on the wire little-endian — `MessageId::to_bytes` is `to_le_bytes` — but the
     * bridge reports it with `{:016x}`, which prints the *number*, most significant byte first.
     * The two are byte-reversed views of the same id. Sending the printed order produces a
     * perfectly well-formed receipt for a message that does not exist, which the other end
     * discards in silence: no error, no tick, and nothing anywhere to say why.
     */
    fun body(receipt: Int, messageIdHex: String): String? {
        val id = messageIdHex.trim().lowercase()
        if (id.length != MESSAGE_ID_HEX_LENGTH) return null
        if (!id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (receipt != RECEIVED && receipt != READ) return null

        val reversed = id.chunked(2).asReversed().joinToString("")
        return "%02x".format(receipt) + reversed
    }

    /**
     * As [body], for a message id carrying the app's own `threema_` prefix.
     *
     * Anything that is not a Threema message returns null rather than being coerced: a WhatsApp
     * id put through here would name an arbitrary Threema message, and acknowledging somebody
     * else's message is worse than acknowledging nothing.
     */
    fun bodyForStoredId(receipt: Int, storedId: String, prefix: String): String? {
        if (!storedId.startsWith(prefix)) return null
        return body(receipt, storedId.removePrefix(prefix))
    }
}
