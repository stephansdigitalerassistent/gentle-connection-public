package ch.heuscher.gentleconnect.model

/**
 * Pure decision logic for detecting when an open support request is claimed,
 * and evaluating claim lifetime, DND ring-through, and second claimants.
 *
 * Spec section 5 & 8:
 * - What counts as a claim:
 *     1. A call that connects between her and a member, either direction, any transport (cellular,
 *        Threema, WhatsApp). "Connects" = STATE_ACTIVE / talking, not ringing.
 *     2. A reply to the alert on messenger or SMS:
 *        - a quoted reply to the alert that does not say no, or
 *        - an agreeing reaction to the alert (👍 👌 ✅ ✔️ ☑️ 🙏 — never 👎, which is also what an
 *          older Threema client's "decline" arrives as), or
 *        - a text that opens with a yes ("ok", "ja", "komme", "ich kümmere mich", "bin unterwegs",
 *          👍, ✅ …) and does not say no anywhere ("Ok, aber ich kann gerade nicht").
 *     3. The caregiver portal ("Ich kümmere mich" button).
 * - A missed call from a member does not claim it (recorded as "Anna hat versucht anzurufen").
 * - While request is open (< 2 h old, not yet claimed).
 * - Second claimant later: changes nothing on her side; they get reply "Anna kümmert sich schon (seit 14:36)."
 * - Calls from ANY group member ring through quiet hours, DND, and blockUnknownCallers for 2 h from request.
 * - Claimed button state active for 60 min after claim, or 30 min after a call with the claimer ends,
 *   whichever is later (capped by 2 h request lifetime).
 */
object SupportClaimPolicy {

    const val REQUEST_LIFETIME_MS = 2L * 60 * 60 * 1000 // 2 hours
    const val CLAIMED_BUTTON_BASE_DURATION_MS = 60L * 60 * 1000 // 60 min after claim
    const val CLAIMED_BUTTON_CALL_DURATION_MS = 30L * 60 * 1000 // 30 min after call ends
    const val DND_RING_THROUGH_DURATION_MS = 2L * 60 * 60 * 1000 // 2 hours from request

    /** A first word that on its own means "I'll do it". */
    private val CLAIM_FIRST_WORDS = setOf(
        "ok", "okay", "oki", "okey", "ja", "jawohl", "jep", "yes", "komme", "rufe", "schaue",
        "übernehme", "mache", "coming"
    )

    /**
     * "ich" and "bin" only together with what follows. On their own they opened "Ich kann gerade
     * nicht" and "Bin im Zug, kann jemand anders?" — and a claim tells everybody else to stand down.
     * "Bin gleich im Meeting" is why "bin gleich" needs its "da".
     */
    private val CLAIM_OPENINGS = listOf(
        listOf("ich", "komme"), listOf("ich", "kümmere"), listOf("ich", "rufe"),
        listOf("ich", "schaue"), listOf("ich", "schau"), listOf("ich", "übernehme"),
        listOf("ich", "mache"), listOf("ich", "mach"),
        listOf("bin", "unterwegs"), listOf("bin", "schon", "unterwegs"), listOf("bin", "da"),
        listOf("bin", "gleich", "da"), listOf("bin", "gleich", "bei"), listOf("bin", "auf", "dem", "weg"),
        listOf("on", "my", "way")
    )

    /**
     * Anything that says no or not now, anywhere in the text. A missed claim costs a minute —
     * someone else answers, or the SMS goes out; a false one means nobody comes.
     */
    private val DECLINE_WORDS = setOf(
        "nicht", "nein", "später", "leider", "no", "not", "can't", "can’t", "cannot", "later"
    )

    /** Reactions that mean yes. Everything else — 👎, ❓, 😢, even ❤️ — is somebody who saw it. */
    private val CLAIM_REACTIONS = listOf("👍", "👌", "✅", "✔", "☑", "🙏")

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}'’]+")).filter { it.isNotEmpty() }

    /** Whether the text says no or not now anywhere in it. */
    fun isDeclineText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val w = words(text)
        return w.any { it in DECLINE_WORDS } || text.trim().startsWith("👎")
    }

    /**
     * Checks whether an incoming text opens with a yes and does not take it back.
     */
    fun isClaimText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (isDeclineText(text)) return false
        val trimmed = text.trim()
        if (CLAIM_REACTIONS.any { trimmed.startsWith(it) }) return true
        val w = words(trimmed)
        if (w.isEmpty()) return false
        if (w.first() in CLAIM_FIRST_WORDS) return true
        return CLAIM_OPENINGS.any { opening -> w.size >= opening.size && w.subList(0, opening.size) == opening }
    }

    /** Whether a reaction's emoji means yes, in any skin tone and with or without its emoji-style selector. */
    fun isClaimReaction(emoji: String?): Boolean {
        if (emoji.isNullOrBlank()) return false
        val trimmed = emoji.trim()
        return CLAIM_REACTIONS.any { trimmed.startsWith(it) }
    }

    /**
     * Checks whether a request has expired (2 hours from creation).
     */
    fun isRequestExpired(createdAt: Long, now: Long): Boolean {
        return now - createdAt >= REQUEST_LIFETIME_MS
    }

    /**
     * Checks whether the request is eligible to be claimed.
     */
    fun canClaim(request: SupportRequest, now: Long): Boolean {
        if (request.status != SupportStatus.OPEN) return false
        if (isRequestExpired(request.createdAt, now)) return false
        return true
    }

    /**
     * Evaluates whether a call event constitutes a claim.
     * Connected calls only; missed/ringing calls do not claim.
     */
    fun isCallClaim(
        callConnected: Boolean,
        isMember: Boolean,
        request: SupportRequest,
        now: Long
    ): Boolean {
        if (!isMember) return false
        if (!callConnected) return false
        return canClaim(request, now)
    }

    /**
     * Evaluates whether an inbound message or reaction event constitutes a claim.
     *
     * @param text the message text, or the emoji when [isReaction].
     * @param isReaction a reaction to any message. Only one to the alert, and only a yes, claims —
     *   a 👍 on a photograph of hers during a request is about the photograph.
     */
    fun isMessageClaim(
        isQuotedReplyToAlert: Boolean,
        isReactionToAlert: Boolean,
        text: String?,
        isMember: Boolean,
        request: SupportRequest,
        now: Long,
        isReaction: Boolean = isReactionToAlert
    ): Boolean {
        if (!isMember) return false
        if (!canClaim(request, now)) return false
        return isClaimSignal(isQuotedReplyToAlert, isReactionToAlert, text, isReaction)
    }

    /** Whether the event says "I'll do it", regardless of the request's state. */
    fun isClaimSignal(
        isQuotedReplyToAlert: Boolean,
        isReactionToAlert: Boolean,
        text: String?,
        isReaction: Boolean = isReactionToAlert
    ): Boolean {
        if (isReaction) return isReactionToAlert && isClaimReaction(text)
        // Quoting the alert is answering it; what the answer says still decides.
        if (isQuotedReplyToAlert) return !isDeclineText(text)
        return isClaimText(text)
    }

    /**
     * Checks if this event is an attempt by a second member to claim an already claimed request.
     */
    fun isSecondClaim(
        request: SupportRequest,
        memberId: String,
        now: Long
    ): Boolean {
        if (request.status != SupportStatus.CLAIMED) return false
        if (isRequestExpired(request.createdAt, now)) return false
        return request.claimedBy != null && request.claimedBy != memberId
    }

    /**
     * Whether calls from ANY group member should ring through quiet hours, Do Not Disturb,
     * and blockUnknownCallers.
     * Active for 2 hours from request creation.
     */
    fun isRingThroughActive(requestCreatedAt: Long, now: Long): Boolean {
        return now >= requestCreatedAt && (now - requestCreatedAt < DND_RING_THROUGH_DURATION_MS)
    }

    /**
     * Checks whether the button and status screen should show the Claimed state.
     * Valid for 60 min after claim, or 30 min after a call with the claimer ends,
     * whichever is later (up to 2 hours from request creation).
     */
    fun isClaimStateActive(
        requestCreatedAt: Long,
        claimedAt: Long?,
        claimerCallEndedAt: Long?,
        now: Long
    ): Boolean {
        if (claimedAt == null) return false
        if (isRequestExpired(requestCreatedAt, now)) return false
        val baseUntil = claimedAt + CLAIMED_BUTTON_BASE_DURATION_MS
        val callUntil = if (claimerCallEndedAt != null) claimerCallEndedAt + CLAIMED_BUTTON_CALL_DURATION_MS else 0L
        val activeUntil = maxOf(baseUntil, callUntil)
        return now < activeUntil
    }
}
