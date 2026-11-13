package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PhoneFix
import ch.heuscher.gentleconnect.model.PhoneLocation
import ch.heuscher.gentleconnect.model.PhoneLocation.betterOf
import ch.heuscher.gentleconnect.model.PhoneLocation.shouldPublish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what the caregiver is shown as the phone's position.
 *
 * Pinned because every way of getting this wrong looks the same from the outside — a plausible
 * point on a map. Nothing about a stale fix, or a fix from the wrong provider, announces itself
 * to the person reading it; they simply drive to the wrong place.
 */
class PhoneLocationTest {

    private val now = 1_700_000_000_000L
    private fun minutes(n: Long) = n * 60 * 1000

    private fun fix(accuracy: Int?, ageMinutes: Long, latitude: Double = 47.0) =
        PhoneFix(
            latitude = latitude,
            longitude = 8.0,
            accuracyMetres = accuracy,
            at = now - minutes(ageMinutes)
        )

    @Test
    fun `anything beats nothing, in either direction`() {
        val only = fix(accuracy = 50, ageMinutes = 0)
        assertEquals(only, betterOf(null, only))
        assertEquals(only, betterOf(only, null))
        assertNull(betterOf(null, null))
    }

    @Test
    fun `taken at the same moment, the tighter radius wins`() {
        val vague = fix(accuracy = 1200, ageMinutes = 0, latitude = 47.1)
        val tight = fix(accuracy = 30, ageMinutes = 0, latitude = 47.2)
        assertEquals(tight, betterOf(vague, tight))
        assertEquals(tight, betterOf(tight, vague))
    }

    @Test
    fun `a fix with no stated accuracy loses to one that has it`() {
        // The provider said nothing about how sure it was. That is not a claim of perfection.
        val unmeasured = fix(accuracy = null, ageMinutes = 0, latitude = 47.1)
        val measured = fix(accuracy = 900, ageMinutes = 0, latitude = 47.2)
        assertEquals(measured, betterOf(unmeasured, measured))
        assertEquals(measured, betterOf(measured, unmeasured))
    }

    @Test
    fun `a much newer fix wins even though it is less accurate`() {
        // She has left the house. The precise fix is precisely where she is not.
        val preciseThisMorning = fix(accuracy = 8, ageMinutes = 240)
        val roughJustNow = fix(accuracy = 150, ageMinutes = 0)
        assertEquals(roughJustNow, betterOf(preciseThisMorning, roughJustNow))
    }

    @Test
    fun `a good recent fix is defended against a wildly vaguer one`() {
        // Both are current, so the phone has not moved between them; the cell tower simply knows
        // less than the satellites did a moment ago. Taking it would lose a street for nothing.
        val precise = fix(accuracy = 20, ageMinutes = 5)
        val fromACellTower = fix(accuracy = 2000, ageMinutes = 0)
        assertEquals(precise, betterOf(precise, fromACellTower))
    }

    @Test
    fun `that defence expires, and then the vague fix wins`() {
        // Same two readings, except the good one is old enough to be a statement about earlier.
        val precise = fix(accuracy = 20, ageMinutes = 45)
        val fromACellTower = fix(accuracy = 2000, ageMinutes = 0)
        assertEquals(fromACellTower, betterOf(precise, fromACellTower))
    }

    @Test
    fun `a provider handing back a stale reading does not displace a current one`() {
        // getLastKnownLocation answers instantly with whatever it has, including yesterday.
        val current = fix(accuracy = 300, ageMinutes = 0)
        val yesterday = fix(accuracy = 5, ageMinutes = 60 * 24)
        assertEquals(current, betterOf(current, yesterday))
    }

    @Test
    fun `an identical reading still refreshes the moment it was taken`() {
        val older = fix(accuracy = 40, ageMinutes = 1)
        val newer = fix(accuracy = 40, ageMinutes = 0)
        assertEquals(newer, betterOf(older, newer))
        assertEquals(newer, betterOf(newer, older))
    }

    @Test
    fun `folding several providers together is order-independent`() {
        // What the source actually does: ask everything, fold everything, publish one answer.
        val readings = listOf(
            fix(accuracy = 5, ageMinutes = 300),
            fix(accuracy = 1800, ageMinutes = 0),
            fix(accuracy = 120, ageMinutes = 1),
            fix(accuracy = null, ageMinutes = 2)
        )
        val folded = readings.fold(null as PhoneFix?) { best, next -> betterOf(best, next) }
        val reversed = readings.reversed().fold(null as PhoneFix?) { best, next -> betterOf(best, next) }
        assertEquals(120, folded?.accuracyMetres)
        assertEquals(folded, reversed)
    }

    @Test
    fun `nothing is published while nobody looks after the phone`() {
        val somewhere = fix(accuracy = 30, ageMinutes = 0)
        assertFalse(shouldPublish(somewhere, caregivers = 0))
        assertTrue(shouldPublish(somewhere, caregivers = 1))
    }

    @Test
    fun `nothing is published when there is no position to publish`() {
        assertFalse(shouldPublish(null, caregivers = 2))
    }

    @Test
    fun `the thresholds stay in the order the rule depends on`() {
        // defendable() only ever fires inside STILL_TRUE_MS, which is only reachable once a fix is
        // already MUCH_NEWER_MS ahead. Invert these and the accuracy defence becomes unreachable.
        assertTrue(PhoneLocation.STILL_TRUE_MS > PhoneLocation.MUCH_NEWER_MS)
    }
}
