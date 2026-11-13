package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.BridgeTransport

/** The machinery that actually carries a message off the phone. Never shown to her. */
enum class SendMechanism {
    /** The embedded Threema client, speaking CSP from this device as her own identity. */
    ON_DEVICE_THREEMA,

    /** The embedded WhatsApp bridge, linked to her own account as a companion device. */
    ON_DEVICE_WHATSAPP,

    /** A document in the Firestore outbox, for a server-side bridge to pick up and send. */
    BRIDGE_OUTBOX,

    /** Nothing can carry it. The caller must report failure rather than imply it was sent. */
    NONE
}

/**
 * Chooses the machinery for a transport [MessageRouter] has already picked.
 *
 * [MessageRouter] answers *which service* reaches a person; this answers *what sends it*, and
 * the two are genuinely separate questions now that Threema runs on the device and WhatsApp
 * does not.
 *
 * Threema does not fall back to the outbox, and that is the whole point of this object. The
 * outbox is a queue: writing to it succeeds whether or not anything is on the other side, and
 * nothing currently drains it — no Cloud Function is deployed from this repository and no
 * process on any host reads it. A fallback would therefore turn "the Threema client is down"
 * into "sent", which is the failure this app can least afford: she would be told her message
 * was on its way and it would sit in a collection nobody reads. Better to say it did not go.
 */
object SendDispatch {

    fun forTransport(transport: BridgeTransport): SendMechanism = when (transport) {
        BridgeTransport.THREEMA -> SendMechanism.ON_DEVICE_THREEMA

        // WhatsApp now has a carrier on this device: `native/whatsapp-bridge`, linked to her
        // own account as a companion and reached the same way the Threema client is. This was
        // NONE for exactly as long as that was untrue.
        //
        // What it must never go back to being is BRIDGE_OUTBOX. That was the original value, on
        // the reasoning that there was no second option to prefer so leaving it wired "cost
        // nothing and kept the shape ready" — but the outbox is a queue nobody drains, a write
        // to it succeeds, and there is still no `functions/` directory in this repository and no
        // process on any host reading `devices/{id}/outbox`. Having a real carrier does not make
        // the queue safer; it only means the trap is no longer the one that would be sprung.
        BridgeTransport.WHATSAPP -> SendMechanism.ON_DEVICE_WHATSAPP

        BridgeTransport.UNKNOWN -> SendMechanism.NONE
    }
}
