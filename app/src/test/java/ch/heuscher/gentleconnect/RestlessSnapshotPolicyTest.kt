package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AgitationTrigger
import ch.heuscher.gentleconnect.model.RestlessSnapshotPolicy
import ch.heuscher.gentleconnect.model.RestlessSnapshotThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies throttling, file naming, and retention pruning for silent restless snapshots.
 *
 * When she struggles with the telephone, taking pictures must not spam the disk or burn CPU,
 * so bursts are spaced apart by at least two minutes. Old snapshots expire after a week,
 * and total files are capped at thirty so her storage is never threatened.
 */
class RestlessSnapshotPolicyTest {

    @Test
    fun `throttle allows first capture immediately`() {
        val throttle = RestlessSnapshotThrottle()
        val now = 1_000_000L

        assertTrue(throttle.shouldCapture(now))
    }

    @Test
    fun `throttle suppresses captures within MIN_INTERVAL_MS`() {
        val throttle = RestlessSnapshotThrottle()
        val now = 1_000_000L

        assertTrue(throttle.shouldCapture(now))
        assertFalse(throttle.shouldCapture(now + 1_000L))
        assertFalse(throttle.shouldCapture(now + RestlessSnapshotPolicy.MIN_INTERVAL_MS - 1L))
    }

    @Test
    fun `throttle allows capture after MIN_INTERVAL_MS and resets window`() {
        val throttle = RestlessSnapshotThrottle()
        val now = 1_000_000L

        assertTrue(throttle.shouldCapture(now))

        val nextBurst = now + RestlessSnapshotPolicy.MIN_INTERVAL_MS
        assertTrue(throttle.shouldCapture(nextBurst))
        // Window is reset to nextBurst
        assertFalse(throttle.shouldCapture(nextBurst + 1_000L))
        assertTrue(throttle.shouldCapture(nextBurst + RestlessSnapshotPolicy.MIN_INTERVAL_MS))
    }

    @Test
    fun `fileName formats correctly with lowercase trigger name and index`() {
        val now = 1_700_000_000_000L

        assertEquals(
            "restless-1700000000000-burst_taps-0.jpg",
            RestlessSnapshotPolicy.fileName(now, AgitationTrigger.BURST_TAPS, 0)
        )
        assertEquals(
            "restless-1700000000000-call_retries-1.jpg",
            RestlessSnapshotPolicy.fileName(now, AgitationTrigger.CALL_RETRIES, 1)
        )
        assertEquals(
            "restless-1700000000000-unconfirmed_support-2.jpg",
            RestlessSnapshotPolicy.fileName(now, AgitationTrigger.UNCONFIRMED_SUPPORT, 2)
        )
        assertEquals(
            "restless-1700000000000-nocturnal_activity-0.jpg",
            RestlessSnapshotPolicy.fileName(now, AgitationTrigger.NOCTURNAL_ACTIVITY, 0)
        )
    }

    @Test
    fun `follow up delays are 0s, 3s, and 6s`() {
        assertEquals(listOf(0L, 3_000L, 6_000L), RestlessSnapshotPolicy.FOLLOW_UP_DELAYS_MS)
    }

    @Test
    fun `trailFileName formats correctly with lowercase trigger name`() {
        val now = 1_700_000_000_000L

        assertEquals(
            "restless-1700000000000-burst_taps-trail.txt",
            RestlessSnapshotPolicy.trailFileName(now, AgitationTrigger.BURST_TAPS)
        )
        assertEquals(
            "restless-1700000000000-call_retries-trail.txt",
            RestlessSnapshotPolicy.trailFileName(now, AgitationTrigger.CALL_RETRIES)
        )
        assertEquals(
            "restless-1700000000000-unanswered_touches-trail.txt",
            RestlessSnapshotPolicy.trailFileName(now, AgitationTrigger.UNANSWERED_TOUCHES)
        )
    }

    @Test
    fun `filesToDelete ignores non-matching names`() {
        val now = 1_000_000_000L
        val nonMatching = listOf(
            "app.log",
            "caregiver-stats.json",
            "restless.jpg",
            "restless-abc-burst_taps-0.jpg",
            "restless-1000-burst_taps-0.jpg.tmp",
            "other-1000-burst_taps-0.jpg",
            "restless-1000-burst_taps-trail.txt.tmp",
            "restless-trail.txt",
            "other-1000-burst_taps-trail.txt"
        )

        val toDelete = RestlessSnapshotPolicy.filesToDelete(nonMatching, now)
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `filesToDelete prunes files older than MAX_AGE_MS`() {
        val now = 1_000_000_000L
        val oldFile1 = RestlessSnapshotPolicy.fileName(
            now - RestlessSnapshotPolicy.MAX_AGE_MS - 1000L,
            AgitationTrigger.BURST_TAPS,
            0
        )
        val oldFile2 = RestlessSnapshotPolicy.fileName(
            now - RestlessSnapshotPolicy.MAX_AGE_MS - 60_000L,
            AgitationTrigger.CALL_RETRIES,
            1
        )
        val freshFile = RestlessSnapshotPolicy.fileName(
            now - 10_000L,
            AgitationTrigger.BURST_TAPS,
            0
        )

        val toDelete = RestlessSnapshotPolicy.filesToDelete(
            listOf(oldFile1, freshFile, oldFile2),
            now
        )

        assertEquals(2, toDelete.size)
        assertTrue(toDelete.contains(oldFile1))
        assertTrue(toDelete.contains(oldFile2))
        assertFalse(toDelete.contains(freshFile))
    }

    @Test
    fun `filesToDelete prunes oldest files exceeding MAX_FILES`() {
        val now = 1_000_000_000L
        // 35 fresh files: index 0 is newest, index 34 is oldest
        val freshFiles = (0 until 35).map { i ->
            RestlessSnapshotPolicy.fileName(now - i * 1_000L, AgitationTrigger.BURST_TAPS, 0)
        }

        val toDelete = RestlessSnapshotPolicy.filesToDelete(freshFiles, now)

        // 30 kept, 5 oldest deleted
        assertEquals(5, toDelete.size)
        val expectedDeleted = freshFiles.takeLast(5).toSet()
        assertEquals(expectedDeleted, toDelete.toSet())

        // The 30 newest files are kept
        for (kept in freshFiles.take(30)) {
            assertFalse(toDelete.contains(kept))
        }
    }

    @Test
    fun `filesToDelete prunes both aged and excess files while keeping non-matching`() {
        val now = 1_000_000_000L
        val nonMatching = listOf("app.log", "caregiver-stats.json", "notes.txt")
        val agedFiles = listOf(
            RestlessSnapshotPolicy.fileName(now - RestlessSnapshotPolicy.MAX_AGE_MS - 5000L, AgitationTrigger.BURST_TAPS, 0),
            RestlessSnapshotPolicy.fileName(now - RestlessSnapshotPolicy.MAX_AGE_MS - 10000L, AgitationTrigger.CALL_RETRIES, 1)
        )
        // 32 fresh files (exceeding MAX_FILES by 2)
        val freshFiles = (0 until 32).map { i ->
            RestlessSnapshotPolicy.fileName(now - i * 1_000L, AgitationTrigger.BURST_TAPS, 0)
        }

        val allNames = nonMatching + agedFiles + freshFiles
        val toDelete = RestlessSnapshotPolicy.filesToDelete(allNames, now)

        // 2 aged + 2 excess fresh = 4 files to delete
        assertEquals(4, toDelete.size)
        assertTrue(toDelete.contains(agedFiles[0]))
        assertTrue(toDelete.contains(agedFiles[1]))
        assertTrue(toDelete.contains(freshFiles[30]))
        assertTrue(toDelete.contains(freshFiles[31]))

        // Non-matching files are never deleted
        for (nm in nonMatching) {
            assertFalse(toDelete.contains(nm))
        }

        // Newest 30 fresh files are kept
        for (kept in freshFiles.take(30)) {
            assertFalse(toDelete.contains(kept))
        }
    }

    @Test
    fun `filesToDelete prunes aged trail files`() {
        val now = 1_000_000_000L
        val oldTrail = RestlessSnapshotPolicy.trailFileName(
            now - RestlessSnapshotPolicy.MAX_AGE_MS - 5000L,
            AgitationTrigger.BURST_TAPS
        )
        val freshTrail = RestlessSnapshotPolicy.trailFileName(
            now - 5000L,
            AgitationTrigger.BURST_TAPS
        )

        val toDelete = RestlessSnapshotPolicy.filesToDelete(listOf(oldTrail, freshTrail), now)
        assertEquals(listOf(oldTrail), toDelete)
    }

    @Test
    fun `filesToDelete prunes excess trail files when total exceeds MAX_FILES`() {
        val now = 1_000_000_000L
        val freshTrails = (0 until 35).map { i ->
            RestlessSnapshotPolicy.trailFileName(now - i * 1_000L, AgitationTrigger.UNANSWERED_TOUCHES)
        }

        val toDelete = RestlessSnapshotPolicy.filesToDelete(freshTrails, now)
        assertEquals(5, toDelete.size)
        assertEquals(freshTrails.takeLast(5).toSet(), toDelete.toSet())
    }
}
