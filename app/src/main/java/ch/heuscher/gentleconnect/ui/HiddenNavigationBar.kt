package ch.heuscher.gentleconnect.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android's own row of buttons at the bottom, put away the way a game puts it away.
 *
 * On her phone it sat directly under this app's own bottom bar: two rows of buttons, one of them
 * small, grey and meaning something else — Recents most of all, which leads nowhere she has ever
 * wanted to go. There are no other apps on her phone and this one is her home, so the row has
 * nothing to offer her that the app's own bar does not.
 *
 * Hidden, not removed: a swipe up from the bottom edge brings it back for a few seconds and it
 * goes again by itself. She will rarely do that, and when she does she is unlikely to press
 * anything before it has gone. Shown for as long as Advanced Mode is on, so whoever is helping her
 * has the buttons they expect.
 *
 * Only the navigation row. The status bar stays: it is where the phone says it is charging.
 */
object HiddenNavigationBar {

    fun apply(activity: Activity, hidden: Boolean = !AdvancedMode.isOn.value) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

/** Keeps [HiddenNavigationBar] in step with Advanced Mode for as long as the caller is shown. */
@Composable
fun FollowAdvancedModeWithNavigationBar(activity: Activity) {
    val advanced by AdvancedMode.isOn.collectAsState()
    LaunchedEffect(advanced) { HiddenNavigationBar.apply(activity, hidden = !advanced) }
}
