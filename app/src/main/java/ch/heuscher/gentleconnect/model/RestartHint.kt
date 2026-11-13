package ch.heuscher.gentleconnect.model

/**
 * Pure logic deciding when to suggest a device restart to the user [docs/phase1b.md §3b].
 *
 * When gentle connection is made the home launcher, Android may not fully route home button
 * presses or clear old launcher state until the phone has been rebooted once.
 *
 * Formula:
 * - holdsHome: true (the app is currently the default home launcher)
 * - currentBoot > 0 (valid boot count reported by Settings.Global.BOOT_COUNT)
 * - lastNotHomeBoot == currentBoot (the phone was in a non-home state during THIS boot session,
 *   meaning the user switched during this boot)
 * - dismissedBoot != currentBoot (the user has not already dismissed the hint during this boot session)
 */
object RestartHint {

    fun shouldShow(
        holdsHome: Boolean,
        currentBoot: Int,
        lastNotHomeBoot: Int,
        dismissedBoot: Int
    ): Boolean = holdsHome && currentBoot > 0 && lastNotHomeBoot == currentBoot && dismissedBoot != currentBoot
}
