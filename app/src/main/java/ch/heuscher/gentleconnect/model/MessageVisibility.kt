package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.ConversationList

object MessageVisibility {

    /**
     * Everything she has, with repeats folded away — the one list both screens read from.
     *
     * Her carrier sent the same roaming tariff four times, so the message list opened on four
     * identical cards while the home screen, which had always collapsed them, showed one. The
     * same message must not look like one thing or four depending on how far she has scrolled,
     * so the folding happens once, here, and neither screen decides for itself.
     *
     * The list arrives newest first, and [distinctBy] keeps the first it sees, so what survives
     * is the most recent copy.
     */
    fun visible(
        arrived: List<Message>,
        stored: List<Message>,
        settings: RemoteSettings,
        conversationIdOf: (Message) -> String?
    ): List<Message> {
        // Her own messages belong in this list too. Without them a conversation she has just
        // replied to still shows what the other person said, and the reply she sent thirty
        // seconds ago is nowhere on the screen — see [ConversationList].
        val outgoing = stored.filter { it.direction == MessageDirection.OUTGOING }
        // Statuses are not conversations. They arrive as messages and are stored as messages, but
        // nobody wrote them to her, so they belong on the Look screen and nowhere in here — see
        // [StatusBroadcast]. Filtered at this one point because everything that lists messages
        // reads this flow.
        val conversations = arrived.filterNot { it.isStatus }
        // The groups the caregiver has switched off, gone from every screen at once — the home
        // card, the message list, the nudge and the read-aloud, all of which read this flow. Her
        // own replies to such a group go with it: half a conversation is stranger than none. See
        // [GroupVisibility] for why this is hiding and not leaving.
        val hidden = GroupVisibility.hiddenKeys(settings.hiddenGroups)
        val shown = GroupVisibility.visible(conversations + outgoing, hidden)
        // The caregiver's corrections to who counts as a person, stamped on here because this is
        // the one list the home card, the message list, the portrait and the arrival loop all read
        // — see [SenderJudgement]. Stamped rather than asked at each of those, so that the four
        // cannot end up disagreeing about the same sender.
        val people = SenderJudgement.treatedAsPeople(settings.sendersAsPeople)
        val judged = if (people.isEmpty()) {
            shown
        } else {
            shown.map { message ->
                if (SenderJudgement.isTreatedAsPerson(message, people)) {
                    message.copy(treatedAsPerson = true)
                } else {
                    message
                }
            }
        }
        // And the companies gone altogether, where somebody has asked for that — after the
        // corrections above and never before, because a sender ticked as a person is meant to
        // survive this. See [SenderJudgement.shown].
        val kept = SenderJudgement.shown(judged, settings.showCompanyMessages)
        return ConversationList.newestPerConversation(kept) { message ->
            conversationIdOf(message)
        }
    }
}
