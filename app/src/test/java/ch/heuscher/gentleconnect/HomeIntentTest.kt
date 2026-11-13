package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.HomeIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIntentTest {

    @Test
    fun `category home returns true`() {
        assertTrue(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = setOf(HomeIntent.CATEGORY_HOME)
            )
        )
    }

    @Test
    fun `category launcher returns true for main action`() {
        assertTrue(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = setOf(HomeIntent.CATEGORY_LAUNCHER)
            )
        )
    }

    @Test
    fun `empty categories returns true for main action`() {
        assertTrue(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = emptySet()
            )
        )
    }

    @Test
    fun `null categories returns true for main action`() {
        assertTrue(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = null
            )
        )
    }

    @Test
    fun `attention intent does not count as home`() {
        assertFalse(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = setOf(HomeIntent.CATEGORY_HOME),
                hasAttention = true
            )
        )
    }

    @Test
    fun `missed call intent does not count as home`() {
        assertFalse(
            HomeIntent.isHome(
                action = HomeIntent.ACTION_MAIN,
                categories = setOf(HomeIntent.CATEGORY_HOME),
                hasMissedCall = true
            )
        )
    }

    @Test
    fun `dial action does not count as home`() {
        assertFalse(
            HomeIntent.isHome(
                action = "android.intent.action.DIAL",
                categories = setOf("android.intent.category.DEFAULT")
            )
        )
    }

    @Test
    fun `sendto action does not count as home`() {
        assertFalse(
            HomeIntent.isHome(
                action = "android.intent.action.SENDTO",
                categories = null
            )
        )
    }
}
