package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.ui.AdvancedMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AdvancedMode] lifecycle, state transitions, and idle timeout behavior.
 *
 * Advanced Mode allows caregivers to perform diagnostics and destructive actions on-device.
 * The 5-minute timeout is a safety mechanism ensuring the device does not stay in advanced mode
 * if the caregiver leaves the phone with the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedModeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        AdvancedMode.setScopeForTesting(testScope)
        AdvancedMode.disable()
    }

    @After
    fun tearDown() {
        AdvancedMode.disable()
        AdvancedMode.resetForTesting()
    }

    @Test
    fun `advanced mode defaults to disabled`() {
        assertFalse(AdvancedMode.isOn.value)
    }

    @Test
    fun `enable turns advanced mode on`() {
        AdvancedMode.enable()
        assertTrue(AdvancedMode.isOn.value)
    }

    @Test
    fun `disable turns advanced mode off`() {
        AdvancedMode.enable()
        assertTrue(AdvancedMode.isOn.value)

        AdvancedMode.disable()
        assertFalse(AdvancedMode.isOn.value)
    }

    @Test
    fun `toggle flips advanced mode state`() {
        assertFalse(AdvancedMode.isOn.value)

        AdvancedMode.toggle()
        assertTrue(AdvancedMode.isOn.value)

        AdvancedMode.toggle()
        assertFalse(AdvancedMode.isOn.value)
    }

    @Test
    fun `advanced mode automatically disables after 5 minutes of inactivity`() {
        AdvancedMode.enable()
        assertTrue(AdvancedMode.isOn.value)

        // Advance 4 minutes (240,000 ms) - should still be on
        testScope.advanceTimeBy(4 * 60 * 1000L)
        testScope.runCurrent()
        assertTrue(AdvancedMode.isOn.value)

        // Advance another 1 minute + 1ms (total > 5 minutes) - should timeout and disable
        testScope.advanceTimeBy(1 * 60 * 1000L + 1L)
        testScope.runCurrent()
        assertFalse(AdvancedMode.isOn.value)
    }

    @Test
    fun `touch resets the 5-minute inactivity timer`() {
        AdvancedMode.enable()
        assertTrue(AdvancedMode.isOn.value)

        // 4 minutes pass
        testScope.advanceTimeBy(4 * 60 * 1000L)
        testScope.runCurrent()
        assertTrue(AdvancedMode.isOn.value)

        // Caregiver touches / interacts with the phone
        AdvancedMode.touch()

        // 4 more minutes pass (8 minutes total, but only 4 min since touch)
        testScope.advanceTimeBy(4 * 60 * 1000L)
        testScope.runCurrent()
        assertTrue(AdvancedMode.isOn.value)

        // Another 1 minute + 1ms passes (5 min since last touch) - now it disables
        testScope.advanceTimeBy(1 * 60 * 1000L + 1L)
        testScope.runCurrent()
        assertFalse(AdvancedMode.isOn.value)
    }
}
