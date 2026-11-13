package ch.heuscher.gentleconnect.model

/**
 * A picture somebody posted to everybody, rather than a message somebody sent to her.
 *
 * WhatsApp calls this a status. It arrives as an ordinary message whose chat is the fixed address
 * `status@broadcast`, and whatsmeow reports it with `IsGroup = true` — its `IsGroup` means "a
 * group **or a broadcast list**". So without this, every status from everybody collapsed into one
 * pseudo-conversation and sat in her Messages list as if it had been written to her.
 *
 * On her phone that was not a small thing. In one week 54 statuses arrived against 23 real
 * messages, 48 of them unread, from 13 people — most of them acquaintances rather than her
 * family. The home screen shows three messages, so her daughter was being pushed off it by other
 * people's holiday photographs, and the card at the top of the screen said "David Adler wrote a
 * message" about a picture posted to two hundred contacts. Nobody had written to her.
 *
 * The distinction is not a detail of plumbing, it is the one thing that decides where this goes
 * on her screen: everything in her Messages list is *from a person, to her*. A status is not. So
 * it keeps its own place — see [ch.heuscher.gentleconnect.ui.LookScreen] — where the promise is
 * only that she can look at it.
 *
 * Pure, so CI can prove it: this is exactly the kind of rule that quietly stops holding.
 */
object StatusBroadcast {

    /** The address every status in the world arrives from. */
    const val JID = "status@broadcast"

    /**
     * Whether this is a status rather than a conversation.
     *
     * Asked of the group identity because that is where the chat address survives into storage —
     * a status is filed under `status@broadcast` as though it were a group, and that is what
     * makes it recognisable again after a restart without a new column to hold it.
     */
    fun isStatus(group: GroupIdentity?): Boolean =
        group != null && group.groupId.equals(JID, ignoreCase = true)

    /** Whether this chat address is the status one. */
    fun isStatusJid(jid: String?): Boolean = jid?.trim().equals(JID, ignoreCase = true)
}
