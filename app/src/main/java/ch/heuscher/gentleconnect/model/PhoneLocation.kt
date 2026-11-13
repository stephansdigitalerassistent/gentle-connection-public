package ch.heuscher.gentleconnect.model

/**
 * Where the phone was, and how sure of it we are.
 *
 * [accuracyMetres] is the radius the operating system says the true position is probably within,
 * and it is null when nothing said — a fix from a provider that reported no accuracy at all. Null
 * is treated as the vaguest possible reading everywhere below rather than as "perfect", because
 * the one thing that must never happen is an unmeasured fix beating a measured one.
 *
 * [at] is when the fix was taken, not when it was published. A caregiver reading this needs to know
 * whether they are looking at where the phone is or where it was, and only the phone can say.
 */
data class PhoneFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Int?,
    val at: Long
)

/**
 * Which of two positions is the one worth having.
 *
 * The phone has several sources of position and none of them is reliably the best. The network
 * provider answers indoors, in seconds, to within a street or two. GPS answers to within a few
 * metres, outdoors, eventually, and not at all in a flat. Each has a last-known reading of its
 * own age, and on a phone that mostly sits in one room the newest reading and the most accurate
 * reading are routinely different readings.
 *
 * So they are folded together rather than chosen between, and the fold has to hold one idea that
 * a plain "prefer accuracy" or "prefer newest" does not: **an accurate fix stops being true when
 * the phone moves.** A 10 m GPS fix from this morning is not better information than a 300 m cell
 * fix from a minute ago, because the phone is not where it was this morning. That is the entire
 * reason this is a rule and not a comparison.
 *
 * What this is *for* frames every threshold in it: somebody who looks after her wants to know that
 * the phone is at her flat, or that it is not. Not which room. The question is answered at the
 * scale of a building, so a fix good to a hundred metres answers it and a fix good to two
 * kilometres does not.
 *
 * Pure, so CI can prove it — nothing else in this feature can be tested here at all.
 */
object PhoneLocation {

    /**
     * How much newer a fix has to be before its age outweighs its vagueness.
     *
     * Two minutes: long enough that fixes arriving together from different providers are compared
     * on accuracy rather than on the order they happened to answer in, short enough that a phone
     * being carried out of the house is followed rather than remembered.
     */
    const val MUCH_NEWER_MS = 2L * 60 * 1000

    /**
     * How long a good fix goes on being worth defending against a vague newer one.
     *
     * Only inside this window can accuracy beat age. Beyond it the older fix has to give way
     * whatever its accuracy, because it has stopped being a statement about now — and a phone
     * that has moved is exactly the case somebody is looking this up to find out about.
     */
    const val STILL_TRUE_MS = 10L * 60 * 1000

    /**
     * The accuracy gap that counts as a different kind of answer rather than a worse one.
     *
     * Two hundred metres apart is the difference between "at the flat" and "somewhere in the
     * neighbourhood". Below that the two fixes say the same thing about the same building and the
     * newer one may as well win.
     */
    const val WILDLY_VAGUER_METRES = 200

    /** Nothing reported an accuracy, so it loses every comparison it is in. */
    private fun radius(fix: PhoneFix): Int = fix.accuracyMetres ?: Int.MAX_VALUE

    /**
     * The better of two fixes, either or both of which may be missing.
     *
     * @param known what is held now — typically the best of everything seen so far.
     * @param incoming what has just arrived from a provider.
     */
    fun betterOf(known: PhoneFix?, incoming: PhoneFix?): PhoneFix? {
        if (incoming == null) return known
        if (known == null) return incoming

        val newerBy = incoming.at - known.at
        return when {
            // Much newer, and not so much vaguer that it answers a different question.
            newerBy >= MUCH_NEWER_MS && !defendable(known, incoming) -> incoming

            // Much older. Providers hand back last-known readings from hours ago; those never
            // displace something current, however precisely they were once measured.
            newerBy <= -MUCH_NEWER_MS -> known

            // Taken at about the same moment: the tighter radius wins, and a tie goes to the
            // newer one so that repeated identical readings still refresh the timestamp.
            radius(incoming) < radius(known) -> incoming
            radius(incoming) > radius(known) -> known
            newerBy > 0 -> incoming
            else -> known
        }
    }

    /**
     * Whether [known] is precise enough, and recent enough, to hold its ground against [incoming].
     *
     * Both halves are required. Precision alone would pin a caregiver to yesterday's address for
     * ever; recency alone would let a two-kilometre cell tower overwrite a fix that could actually
     * find the phone.
     */
    private fun defendable(known: PhoneFix, incoming: PhoneFix): Boolean =
        incoming.at - known.at <= STILL_TRUE_MS &&
            radius(incoming) - radius(known) >= WILDLY_VAGUER_METRES

    /**
     * Whether a position may leave the phone at all.
     *
     * Nobody paired, nothing published — not "published where only the paired can read it", not
     * published. Until somebody has claimed the phone there is no one this could inform, and a
     * position sitting in a database informing nobody is the definition of what this feature is
     * not for. It is also the state a phone spends its first hours in, in a shop or on a kitchen
     * table, before it belongs to anyone.
     *
     * @param caregivers how many people are linked to this phone right now.
     */
    fun shouldPublish(fix: PhoneFix?, caregivers: Int): Boolean = fix != null && caregivers > 0
}
