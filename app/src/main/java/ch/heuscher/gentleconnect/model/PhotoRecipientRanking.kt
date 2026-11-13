package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber

/**
 * A candidate for photo recipient ranking.
 *
 * @param key unique stable identifier for this candidate (e.g. normalized name or group key).
 * @param name display name used as a tie-breaker.
 * @param threemaIds known Threema IDs for this person.
 * @param whatsappNumbers known WhatsApp numbers for this person.
 * @param phoneNumbers known telephone numbers for this person.
 * @param group non-null when this candidate is a group rather than a person.
 */
data class PhotoCandidate(
    val key: String,
    val name: String,
    val threemaIds: Set<String> = emptySet(),
    val whatsappNumbers: Set<String> = emptySet(),
    val phoneNumbers: Set<String> = emptySet(),
    val group: GroupIdentity? = null
)

/**
 * An outgoing or interactive communication event.
 *
 * @param address recipient or counterpart telephone number, Threema ID, or WhatsApp number.
 * @param group group identity if this occurred in a group conversation.
 * @param timestamp time of the event in milliseconds since epoch.
 */
data class PhotoEvent(
    val address: String? = null,
    val group: GroupIdentity? = null,
    val timestamp: Long = 0L
)

/**
 * Ranks photo recipients by her own interaction history.
 *
 * ## Cognitive design: stability over recency
 * The list is sorted by HER OWN interaction, not by recency: recency would put a busy group she
 * only reads on top.
 *
 * Score is the number of her sent messages + her reactions + her placed calls with that candidate
 * whose timestamp is in `[todayStart - windowDays days, todayStart)`.
 *
 * Events from TODAY do not count: the order changes at most once a day, never while she is looking
 * at it. She finds people by where they are; a face that moves is a face she has to search for
 * again.
 *
 * A person's score sums ALL their routes (her husband and her son each have a Threema and a WhatsApp
 * chat on her phone; one person, one score).
 *
 * Group messages and reactions count strictly for the group, never for its members.
 *
 * Ties (including all zeros) fall back to the caregiver's favourite order, then name. People with
 * score 0 are still listed (after scored ones); groups with score 0 are not candidates at all.
 */
object PhotoRecipientRanking {

    fun rank(
        candidates: List<PhotoCandidate>,
        sent: List<PhotoEvent>,
        reactions: List<PhotoEvent>,
        placedCalls: List<PhotoEvent>,
        favouriteOrder: List<String>,
        todayStart: Long,
        windowDays: Int = 60
    ): List<String> {
        val windowStart = todayStart - windowDays * 24L * 60 * 60 * 1000L
        val scores = mutableMapOf<String, Int>()
        for (c in candidates) {
            scores[c.key] = 0
        }

        val allEvents = sent + reactions + placedCalls
        for (event in allEvents) {
            if (event.timestamp in windowStart until todayStart) {
                val matched = candidates.firstOrNull { matches(it, event) }
                if (matched != null) {
                    scores[matched.key] = (scores[matched.key] ?: 0) + 1
                }
            }
        }

        // Groups with score 0 are not candidates at all; people with score 0 are retained.
        val eligible = candidates.filter { candidate ->
            if (candidate.group != null) {
                (scores[candidate.key] ?: 0) > 0
            } else {
                true
            }
        }

        // Sort: score desc -> favouriteOrder asc -> name asc
        val sorted = eligible.sortedWith { a, b ->
            val scoreA = scores[a.key] ?: 0
            val scoreB = scores[b.key] ?: 0
            if (scoreA != scoreB) {
                scoreB.compareTo(scoreA)
            } else {
                val favA = favIndex(a, favouriteOrder)
                val favB = favIndex(b, favouriteOrder)
                if (favA != favB) {
                    favA.compareTo(favB)
                } else {
                    a.name.trim().lowercase().compareTo(b.name.trim().lowercase())
                }
            }
        }

        return sorted.map { it.key }
    }

    private fun favIndex(candidate: PhotoCandidate, favouriteOrder: List<String>): Int {
        val byKey = favouriteOrder.indexOf(candidate.key)
        if (byKey >= 0) return byKey
        val byName = favouriteOrder.indexOf(candidate.name.trim().lowercase())
        if (byName >= 0) return byName
        return Int.MAX_VALUE
    }

    private fun matches(candidate: PhotoCandidate, event: PhotoEvent): Boolean {
        // Group event: matches the group candidate only, never members.
        if (event.group != null) {
            return candidate.group != null && candidate.group == event.group
        }

        // 1:1 event: never matches a group candidate.
        if (candidate.group != null) {
            return false
        }

        val addr = event.address?.trim()
        if (addr.isNullOrEmpty()) return false

        // 1. Threema ID match
        if (candidate.threemaIds.any { it.trim().equals(addr, ignoreCase = true) }) {
            return true
        }

        // 2. WhatsApp match
        val waNumber = WhatsAppNumber.numberFromJid(addr) ?: addr
        if (candidate.whatsappNumbers.any {
                WhatsAppNumber.sameSubscriber(it, waNumber) ||
                    WhatsAppNumber.sameSubscriber(it, addr) ||
                    CallHistory.sameNumber(it, addr)
            }
        ) {
            return true
        }

        // 3. Phone number match (placed calls or SMS)
        if (candidate.phoneNumbers.any {
                CallHistory.sameNumber(it, addr) ||
                    WhatsAppNumber.sameSubscriber(it, addr) ||
                    WhatsAppNumber.sameSubscriber(it, waNumber)
            }
        ) {
            return true
        }

        return false
    }
}
