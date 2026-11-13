package ch.heuscher.gentleconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two parts of the app holding their own copy of a store must still agree about what is in it.
 *
 * Both of these stores used to read the disk once and then answer from memory. The home screen
 * and the arrival pipeline each built one, so a conversation muted on the screen went on being
 * announced out loud until the process restarted, and a missed call dealt with from a
 * notification stayed on the home screen until something re-read the file.
 */
class SharedStoreStateTest {

    @Test
    fun `a conversation muted through one copy is muted in the other`() {
        val prefs = FakeSharedPreferences()
        val screen = MutedConversationsRepository(prefs)
        val pipeline = MutedConversationsRepository(prefs)

        screen.mute("contact:42")

        assertTrue(pipeline.isMuted("contact:42"))
        assertEquals(setOf("contact:42"), pipeline.muted.value)
    }

    @Test
    fun `unmuting is seen by the other copy too`() {
        val prefs = FakeSharedPreferences()
        val screen = MutedConversationsRepository(prefs)
        val pipeline = MutedConversationsRepository(prefs)
        screen.mute("contact:42")

        screen.toggleMute("contact:42")

        assertFalse(pipeline.isMuted("contact:42"))
    }

    @Test
    fun `a missed call dealt with elsewhere reaches the home screen at once`() {
        val prefs = FakeSharedPreferences()
        val homeScreen = NudgeMemory(prefs)
        val notification = NudgeMemory(prefs)

        notification.dealtWithMissedCall(5_000L)

        assertEquals(5_000L, homeScreen.missedCallsDealtWithUpTo.value)
    }

    @Test
    fun `dealing with an older call never brings a newer card back`() {
        val memory = NudgeMemory(FakeSharedPreferences())

        memory.dealtWithMissedCall(5_000L)
        memory.dealtWithMissedCall(3_000L)

        assertEquals(5_000L, memory.missedCallsDealtWithUpTo.value)
    }

    @Test
    fun `the announcement watermark is shared and only moves forward`() {
        val prefs = FakeSharedPreferences()
        val pipeline = NudgeMemory(prefs)
        val other = NudgeMemory(prefs)

        pipeline.rememberAnnouncedUpTo(9_000L)
        pipeline.rememberAnnouncedUpTo(4_000L)

        assertEquals(9_000L, other.announcedUpTo.value)
    }
}
