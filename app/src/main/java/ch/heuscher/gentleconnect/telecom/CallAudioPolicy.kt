package ch.heuscher.gentleconnect.telecom

/**
 * Where the sound of a call goes, decided without touching a telephone.
 *
 * Until 2026-10 this followed the proximity sensor: phone at her ear → earpiece, away → loudspeaker.
 * On her Samsung phones that sensor could not be trusted with it. On the Fold the infrared part
 * switched itself off mid-call and light leaking past her ear read as *far*, so on 2026-10-25 she
 * spent sixteen of seventeen minutes with the loudspeaker at full volume against her ear and the
 * sound flipped four times while she held still; one test device read *near* lying flat and never came back
 * to the loudspeaker at all. A sensor that is wrong a third of the time is worse than asking her,
 * so now nothing guesses:
 *
 * 1. Bluetooth hearing aids or headsets win over everything. Putting them on is a deliberate choice
 *    and audio belongs there regardless of anything else.
 * 2. Wired headsets win next. Plugging a cable in is an unambiguous intention to listen through it.
 * 3. On a folding phone the hinge decides ([hingeClosed]): folded shut is a telephone held to the
 *    ear, so the earpiece; opened up is a screen to look at, so the loudspeaker. Folding and
 *    unfolding is something she does with the whole phone and cannot do by accident.
 * 4. Everywhere else she decides, with the two choices on the call screen ([offersChoice]). Each
 *    one always means the same thing — pressing Hörer twice is still Hörer — because her finger
 *    often lands twice, and a toggle would undo on the second landing what the first one did.
 * 5. Until she presses one, a call starts on [AT_START].
 *
 * Never returns [current] (returns null if the audio is already routed correctly, avoiding fighting
 * the platform) and never returns a route not present in available.
 */
object CallAudioPolicy {

    /**
     * Where a call starts when nothing has said otherwise.
     *
     * The loudspeaker, because that is where every call started under the old rule too — she
     * presses a face or answers while looking at the screen — and because a call she can hear too
     * loudly is a call she can still have, where one she cannot hear at all looks like a call that
     * has failed.
     */
    val AT_START = AudioRoute.LOUDSPEAKER

    /** Hinge angle below which a folding phone counts as shut. 0° is shut, 180° is flat. */
    const val HINGE_CLOSED_BELOW = 30f

    /**
     * Hinge angle above which it counts as open again. The gap between the two is there so that
     * a hinge resting near one threshold cannot switch the sound back and forth.
     */
    const val HINGE_OPEN_ABOVE = 60f

    /**
     * Decide the desired [AudioRoute], or return null if no route change is required.
     *
     * @param available the set of audio routes currently reported as supported by the hardware.
     * @param current the currently active route, or null if unknown or not yet set.
     * @param wanted where the sound should go when no headset is involved — from [wanted].
     * @return the new [AudioRoute] to switch to, or null if the current route should remain unchanged.
     */
    fun decide(available: Set<AudioRoute>, current: AudioRoute?, wanted: AudioRoute): AudioRoute? {
        if (available.isEmpty()) return null

        val target = when {
            // Bluetooth hearing aids or headsets win over everything: she put them on deliberately.
            AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH

            // A plugged wired headset is also a deliberate physical action.
            AudioRoute.HEADSET in available -> AudioRoute.HEADSET

            wanted in available -> wanted

            // A phone without the one she wants — a tablet has no earpiece.
            AudioRoute.LOUDSPEAKER in available -> AudioRoute.LOUDSPEAKER
            AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
            else -> available.firstOrNull()
        }

        return if (target != null && target != current && target in available) target else null
    }

    /**
     * Where the sound should go, leaving headsets aside.
     *
     * @param hingeClosed whether a folding phone is shut, or null on a phone without a hinge — and
     *   on a folding one until the hinge has been read.
     * @param chosen what she last pressed on this call, or null if she has not.
     */
    fun wanted(hingeClosed: Boolean?, chosen: AudioRoute?): AudioRoute = when (hingeClosed) {
        true -> AudioRoute.EARPIECE
        false -> AudioRoute.LOUDSPEAKER
        null -> chosen ?: AT_START
    }

    /**
     * Whether the hinge now counts as shut.
     *
     * @param angleDegrees the hinge sensor's reading, 0° shut to 180° flat.
     * @param wasClosed the answer for the previous reading, or null for the first one.
     */
    fun hingeClosed(angleDegrees: Float, wasClosed: Boolean?): Boolean = when {
        angleDegrees < HINGE_CLOSED_BELOW -> true
        angleDegrees > HINGE_OPEN_ABOVE -> false
        wasClosed != null -> wasClosed
        else -> angleDegrees < (HINGE_CLOSED_BELOW + HINGE_OPEN_ABOVE) / 2
    }

    /**
     * Whether the call screen offers her the two choices rather than just naming the route.
     *
     * Only when both are real: the phone has an earpiece and a loudspeaker, and the sound is on
     * one of them. With a headset in, pressing either would be overruled at once by rule 1 or 2,
     * and a button that does nothing is worse than none. With a hinge in charge the fold is the
     * control and two buttons arguing with it would be a second one.
     */
    fun offersChoice(available: Set<AudioRoute>, current: AudioRoute?, byHinge: Boolean): Boolean =
        !byHinge &&
            AudioRoute.EARPIECE in available &&
            AudioRoute.LOUDSPEAKER in available &&
            AudioRoute.BLUETOOTH !in available &&
            AudioRoute.HEADSET !in available &&
            (current == AudioRoute.EARPIECE || current == AudioRoute.LOUDSPEAKER)
}
