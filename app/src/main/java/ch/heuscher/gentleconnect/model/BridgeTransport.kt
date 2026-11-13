package ch.heuscher.gentleconnect.model

/**
 * Which messenger a bridged message travelled over.
 *
 * This is NOT the same thing as [MessageChannel]. [MessageChannel] says how the message
 * reached this app (scraped from a notification, or read from the bridge); this says which
 * service the bridge must use to answer. A message can be BRIDGE + WHATSAPP, or
 * NOTIFICATION + UNKNOWN.
 *
 * Kept deliberately open: the app never shows the transport to her, it only needs it to
 * route a reply back out.
 */
enum class BridgeTransport(val wireName: String) {
    THREEMA("threema"),
    WHATSAPP("whatsapp"),
    UNKNOWN("unknown");

    companion object {
        /** Parses the wire value, falling back to [UNKNOWN] rather than throwing. */
        fun fromWire(value: String?): BridgeTransport =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * How a message travelled, for the facts panel in Advanced Mode.
 *
 * [BridgeTransport] answers one question — which messenger must a reply go back out over — and
 * for a text message the answer is none, so an SMS carries [BridgeTransport.UNKNOWN]. That was
 * fine while nothing showed it. The panel does show it, and it said UNKNOWN about the one kind of
 * message whose route nobody was ever in any doubt about, to the one person who opens that panel
 * to work out where a message went.
 *
 * [MessageChannel.DEVICE] settles it: that channel *is* the phone's SMS inbox. A bridged message
 * whose transport really is unknown still says so, because there it is the truth.
 *
 * Not translated, like the other facts beside it — these are the phone's own words for somebody
 * working out why something did not arrive, not copy for her screen.
 */
fun transportName(channel: MessageChannel, transport: BridgeTransport): String =
    if (channel == MessageChannel.DEVICE) "SMS" else transport.name
