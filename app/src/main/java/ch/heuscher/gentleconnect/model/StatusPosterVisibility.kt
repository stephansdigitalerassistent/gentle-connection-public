package ch.heuscher.gentleconnect.model

/**
 * Somebody who posts statuses, as the caregiver's website has to see them to be able to switch
 * them off.
 *
 * [key] is the phone's own name for the poster — see [StatusPosterVisibility.keyOf] — and [name]
 * is what her screen calls them. [count] is how many of their pictures the phone holds, which is
 * what tells a caregiver *why* a name is on the list: "Martin Nauer, 35" explains itself. No
 * pictures and no captions leave the phone through this.
 */
data class KnownPoster(val key: String, val name: String, val count: Int)

/**
 * Whose statuses she is shown at all.
 *
 * Most of what lands on the Look screen is not from her family. Read out of her phone: 575
 * statuses, a handful of acquaintances posting most of them — Martin Nauer 35 in nine seconds,
 * Alex Sommer 19 in a minute. Folding a burst into one album (see [StatusBurst]) stopped one man's
 * afternoon taking two screens; it did not stop him being there every day. Whether he should be is
 * a decision about one particular person's life, and like the groups it belongs to whoever looks
 * after her, taken remotely, on the website.
 *
 * **Hidden, not blocked.** A hidden poster's statuses still arrive and are still stored; they are
 * not drawn. Nobody is told anything, and switching them back on brings the pictures back.
 *
 * **Only a shorter list is offered.** Her phone has heard from dozens of posters, and a website
 * listing all of them is a list nobody works through. What is offered is everybody who posted in
 * the last [RECENT_DAYS] days (at least the [LIST_LENGTH] most recent, on a quiet phone) and the
 * [LIST_LENGTH] who post most — the two ways somebody becomes the one a caregiver wants gone — plus
 * anybody already switched off, so a switch never disappears from under the decision it records.
 *
 * The window used to be just the five most recent. On the Fold those five were also the five most
 * frequent, so "Isa", whose yoga pictures were still on her Look screen, had no switch at all:
 * whoever she can still scroll past has to be someone a caregiver can hide.
 *
 * Owned by the website, like [GroupVisibility]: the phone publishes [KnownPoster]s and reads the
 * list of keys back; it never writes that list itself.
 */
object StatusPosterVisibility {

    /** How many of the most recent, and how many of the most frequent, posters are offered. */
    const val LIST_LENGTH = 5

    /** How far back somebody's last status may be and still earn them a switch. */
    const val RECENT_DAYS = 30L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * The phone's name for a poster: [StatusBurst.posterOf], lower-cased so that it compares as it
     * stands. The same rule that decides two pictures are one person's burst decides they are one
     * person's switch — two rules would be a burst half of which is hidden.
     */
    fun keyOf(message: Message): String = StatusBurst.posterOf(message).lowercase()

    /**
     * The keys the caregiver has switched off, normalised the way [keyOf] produces them.
     *
     * Defensive about what comes back out of Firestore, for the reason [GroupVisibility.hiddenKeys]
     * is: a key that fails to match is a switch that appears to do nothing.
     */
    fun hiddenKeys(raw: Collection<String?>?): Set<String> =
        raw.orEmpty().mapNotNull { it?.trim()?.lowercase()?.ifEmpty { null } }.toSet()

    /** Whether this status is from somebody switched off. Never true of anything but a status. */
    fun isHidden(message: Message, hidden: Set<String>): Boolean =
        hidden.isNotEmpty() && message.isStatus && keyOf(message) in hidden

    /** The statuses she is shown, with the switched-off posters left out. */
    fun visible(statuses: List<Message>, hidden: Set<String>): List<Message> {
        if (hidden.isEmpty()) return statuses
        return statuses.filterNot { isHidden(it, hidden) }
    }

    /**
     * The posters to offer the caregiver: the most recent (everybody within [RECENT_DAYS] of [now]),
     * then the most frequent, then anybody already switched off.
     *
     * Asked of every stored status, hidden ones included — a poster switched off yesterday must
     * still have a switch today, or there is no way to switch them back on.
     *
     * The name is the newest one the poster used, which is the one her screen would show. Most
     * frequent ties go to the more recent poster, so the list is stable between two visits rather
     * than shuffling on equal counts.
     */
    fun known(
        statuses: List<Message>,
        hidden: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis()
    ): List<KnownPoster> {
        data class Tally(val name: String, var count: Int, val newest: Long)

        val byPoster = LinkedHashMap<String, Tally>()
        statuses.filter { it.isStatus }.sortedByDescending { it.timestamp }.forEach { status ->
            val key = keyOf(status)
            if (key.isEmpty()) return@forEach
            val tally = byPoster[key]
            if (tally == null) {
                byPoster[key] = Tally(status.senderName.trim().ifEmpty { key }, 1, status.timestamp)
            } else {
                tally.count++
            }
        }

        // Newest first already, because the statuses were walked newest first.
        val since = now - RECENT_DAYS * DAY_MS
        val recent = byPoster.keys.take(LIST_LENGTH) +
            byPoster.filterValues { it.newest >= since }.keys
        val frequent = byPoster.entries
            .sortedWith(compareByDescending<Map.Entry<String, Tally>> { it.value.count }
                .thenByDescending { it.value.newest })
            .take(LIST_LENGTH)
            .map { it.key }
        val switchedOff = byPoster.keys.filter { it in hidden }

        return (recent + frequent + switchedOff).distinct().map { key ->
            val tally = byPoster.getValue(key)
            KnownPoster(key, tally.name, tally.count)
        }
    }
}
