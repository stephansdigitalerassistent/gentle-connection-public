package ch.heuscher.gentleconnect.model

/**
 * The address of the caregiver website, with the pairing code already in it.
 *
 * The dialog used to offer the bare address and a six-character code in large type above it, and
 * the caregiver had to carry the code from one to the other by eye — on a phone, into a field with
 * `text-transform: uppercase` and a ten-minute expiry. Typing it wrong is silent: the website says
 * the code is invalid, which is indistinguishable from the code having run out, and the way to
 * find out which is to walk back to her phone.
 *
 * So the link carries the code. The caregiver taps once and the website claims the phone.
 *
 * **The code goes in the fragment, never the query.** A fragment is not sent to the server, so it
 * stays out of Hosting's request logs, out of any `Referer` the page later sends, and out of
 * anything sitting in front of the origin. It is still a secret in a URL — but one that expires in
 * ten minutes, names no person, and is already printed on the screen the link is being read from.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object CaregiverPortalLink {

    /** What the website reads the code out of. Kept here so both ends agree in one place. */
    const val CODE_FRAGMENT_KEY = "code"

    /**
     * Where the caregiver website lives for this build, and what to do when it opens.
     *
     * The host is derived rather than written down: Firebase Hosting serves every project at
     * `<projectId>.web.app`, and the project id is already in the app because Firebase needs it.
     * A fork, a staging project or somebody else's deployment therefore gets its own correct
     * link instead of this one's.
     *
     * @param projectId the Firebase project this build is wired to, or null if there is none.
     * @param pairingCode the live code, or null when there is not one worth carrying. A blank or
     *   malformed code yields the plain address rather than a link that fails on arrival — the
     *   caregiver can still sign in and type it.
     * @return the address to open, or null when there is no Firebase configuration at all and the
     *   dialog should offer no link rather than one that goes nowhere.
     */
    fun forProject(projectId: String?, pairingCode: String?): String? {
        val host = projectId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val base = "https://$host.web.app"
        val code = pairingCode?.trim()?.uppercase()?.takeIf { isWellFormed(it) } ?: return base
        return "$base/#$CODE_FRAGMENT_KEY=$code"
    }

    /**
     * Whether a code is worth putting in a link.
     *
     * Six characters from the alphabet the phone generates. This is not validation of whether the
     * code is live — only the directory knows that — it is a guard against putting a placeholder,
     * an empty string or something with a `#` in it into a URL.
     */
    fun isWellFormed(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in 'A'..'Z' || it in '0'..'9' }

    private const val CODE_LENGTH = 6
}
