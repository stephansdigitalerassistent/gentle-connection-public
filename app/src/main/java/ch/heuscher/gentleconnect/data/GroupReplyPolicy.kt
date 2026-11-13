package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.GroupIdentity

/**
 * Whether several people may be answered, and who the answer has to reach.
 *
 * A Threema group message is not one message to a group address. It is the same message sent to
 * every member individually, each wrapped in an envelope naming the group — the fan-out *is* the
 * send. Which makes the membership list load-bearing in a way it never is for one person: if it is
 * short by one, her message reaches everyone except that one, **and looks exactly like success**.
 * She writes to the family, two of the five get it, and nothing on her screen or theirs says so.
 *
 * So the rule is that she may only answer a group whose membership we were told authoritatively —
 * one the creator has sent a setup message for. A group that existed before this app was installed
 * and has been quiet since is a group whose members we are guessing at, and guessing is the one
 * thing this must not do. It stays unanswerable, silently, until a setup message arrives: the reply
 * field is simply absent, with no explanation, because a fault she did not cause and cannot fix is
 * the caregiver's business.
 *
 * Pure, because every one of these decisions is invisible to her when it goes wrong.
 */
object GroupReplyPolicy {

    /** What to do about a reply to several people. */
    sealed interface Decision {

        /**
         * Send it, and to exactly these people.
         *
         * Every one of them or none: see [GroupSendOutcome]. A partial fan-out is a failure, not a
         * partial success.
         */
        data class SendTo(val recipients: List<String>) : Decision

        /** Do not offer a reply at all. The reason is for the caregiver, never for her screen. */
        data class Refused(val reason: Reason) : Decision
    }

    /** Why several people cannot be answered. Written down so the caregiver's log can say it. */
    enum class Reason {
        /**
         * No setup message has ever arrived, so who is in this group is not something we know.
         * Ordinary rather than broken: it is every group that was quiet since the app was
         * installed.
         */
        MEMBERSHIP_UNKNOWN,

        /**
         * The last member list the creator sent did not have her in it — she was removed, or she
         * left. Answering would be writing to a conversation she is no longer part of.
         */
        NO_LONGER_A_MEMBER,

        /** She is the only one left. There is nobody for the message to go to. */
        NOBODY_LEFT_TO_REACH
    }

    /**
     * @param group the group being answered; its creator is always one of the recipients.
     * @param ownIdentity her own Threema ID, which is never a recipient of her own message.
     * @param knownMembers the members from the last setup message — **as Threema sends them, with
     *   the creator not repeated** and with her own identity present.
     * @param lastSetupAt when that setup arrived; `0` means no setup has ever arrived, which is the
     *   difference between knowing the membership and merely having messages from the group.
     */
    fun decide(
        group: GroupIdentity,
        ownIdentity: String?,
        knownMembers: List<String>,
        lastSetupAt: Long
    ): Decision {
        val own = identityOrNull(ownIdentity)
        // Without her own identity we cannot tell her out of the fan-out, and a message that
        // arrives back from herself is at best confusing to someone who may not remember sending
        // it. Treated as not knowing the membership, because that is exactly what it is.
        if (own == null || lastSetupAt <= 0L) return Decision.Refused(Reason.MEMBERSHIP_UNKNOWN)

        val creator = identityOrNull(group.creatorIdentity)
            ?: return Decision.Refused(Reason.MEMBERSHIP_UNKNOWN)

        // The creator belongs in the roster whether or not the member list names them: Threema's
        // setup message leaves the creator out because the creator is the one sending it. Left out
        // here too, the one person guaranteed to be in the group would be the one person her reply
        // never reached.
        val roster = (knownMembers.mapNotNull(::identityOrNull) + creator).distinct()

        if (own !in roster) return Decision.Refused(Reason.NO_LONGER_A_MEMBER)

        val recipients = roster.filterNot { it == own }
        if (recipients.isEmpty()) return Decision.Refused(Reason.NOBODY_LEFT_TO_REACH)

        // Sorted so that the same group produces the same list every time, which makes a failed
        // fan-out reproducible rather than a different half each attempt.
        return Decision.SendTo(recipients.sorted())
    }

    /** Eight characters from Threema's own alphabet, and nothing else, is ever written to. */
    private fun identityOrNull(value: String?): String? {
        val identity = value?.trim()?.uppercase().orEmpty()
        if (identity.length != 8) return null
        if (!identity.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        return identity
    }
}

/**
 * How a fan-out went, once every member has been attempted.
 *
 * **Partial delivery is failure.** "Sent to 3 of 5" is a fact she can do nothing with — she cannot
 * tell which three, cannot resend to the other two, and cannot ask. So her message stays visibly
 * unfinished, exactly as one that did not leave the phone at all, and the caregiver gets the two
 * identities that failed.
 */
object GroupSendOutcome {

    /**
     * @param intended everyone the message had to reach, from [GroupReplyPolicy.Decision.SendTo].
     * @param delivered the ones the bridge accepted for sending — which is not the same as anybody
     *   having received it, and must never be drawn as though it were.
     */
    fun succeeded(intended: List<String>, delivered: Collection<String>): Boolean {
        if (intended.isEmpty()) return false
        val reached = delivered.map { it.trim().uppercase() }.toSet()
        return intended.all { it.trim().uppercase() in reached }
    }
}
