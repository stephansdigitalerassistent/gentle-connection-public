package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.MessageRouter

/**
 * Someone she can write to first, and every way of reaching them that is known.
 *
 * A favourite contact carries a telephone number and possibly a Threema ID; a partner carries
 * an address on one transport. The same woman is often both, so they are merged into this
 * before anything is shown — she has one Claire-Lise, not a telephone Claire-Lise and a Threema
 * Claire-Lise, and she must never be asked which one she means.
 *
 * Which route is actually used is [ch.heuscher.gentleconnect.data.MessageRouter]'s decision,
 * and it is never shown to her.
 */
data class Recipient(
    val name: String,
    val photoUri: String? = null,
    val threemaId: String? = null,
    val whatsappId: String? = null,
    val phoneNumber: String? = null,
    /**
     * The several people, when this is a group rather than a person.
     *
     * Groups were answerable and not writable-to: she could reply to the family group if somebody
     * there wrote first, and had no way to start. So the group she talks to most was the one
     * conversation on the phone she could never open herself. A group has no telephone number and
     * no identity of its own to route to — the fan-out is the route — which is why this sits
     * beside the three addresses rather than in one of them.
     */
    val groupIdentity: GroupIdentity? = null
) {
    /**
     * Whether anything can actually carry a message to this person.
     *
     * Asks the router rather than checking the three fields itself. Holding the same rule in
     * two places is how a name ends up in the list of people she can write to while the send
     * that follows has nowhere to go — she would compose the message, press send, and be told
     * it failed.
     *
     * A group is only ever built into a [Recipient] once `GroupReplyPolicy` has said who is in it,
     * so by the time one exists the question has already been answered — and answered by the one
     * piece of code that is allowed to answer it.
     */
    val isReachable: Boolean
        get() = groupIdentity != null || MessageRouter.canReach(threemaId, whatsappId, phoneNumber)
}
