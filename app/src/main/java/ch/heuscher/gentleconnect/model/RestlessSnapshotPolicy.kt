package ch.heuscher.gentleconnect.model

/**
 * Retention and throttling policy for silent window snapshots captured during restlessness.
 *
 * When an agitation trigger fires, the phone captures a short burst of images of its own screen
 * so a caregiver or developer can understand what caused the confusion or panic. Because these
 * images stay on device storage, they are throttled to avoid filling the disk, and pruned by
 * both age (at most 7 days) and count (at most 30 files).
 */
object RestlessSnapshotPolicy {
    /**
     * Follow-up delays for capturing snapshots after a trigger.
     * One shot at the trigger and two after it, because the first one may already be "after" the problem.
     */
    val FOLLOW_UP_DELAYS_MS = listOf(0L, 3_000L, 6_000L)

    /** Minimum interval between bursts of snapshot captures (2 minutes). */
    const val MIN_INTERVAL_MS = 2 * 60_000L

    /** Maximum number of snapshots kept on disk. */
    const val MAX_FILES = 30

    /** Maximum age of a snapshot file before it is pruned (7 days). */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Maximum length of the longest edge in pixels (downscale target). */
    const val MAX_EDGE_PX = 720

    private val SNAPSHOT_REGEX = Regex("""^restless-(\d+)-[a-zA-Z0-9_]+-(?:(\d+)\.jpg|trail\.txt)$""")

    /**
     * Formats a snapshot file name.
     */
    fun fileName(now: Long, trigger: AgitationTrigger, index: Int): String =
        "restless-$now-${trigger.name.lowercase()}-$index.jpg"

    /**
     * Formats a sidecar trail file name containing the last ~30 seconds of UiTrace lines.
     */
    fun trailFileName(now: Long, trigger: AgitationTrigger): String =
        "restless-$now-${trigger.name.lowercase()}-trail.txt"

    /**
     * Parses timestamps from filenames matching the snapshot or trail pattern (ignoring others),
     * and returns the names of files that should be deleted: those older than [MAX_AGE_MS]
     * plus the oldest beyond the newest [MAX_FILES].
     */
    fun filesToDelete(names: List<String>, now: Long): List<String> {
        val snapshots = names.mapNotNull { name ->
            val match = SNAPSHOT_REGEX.matchEntire(name) ?: return@mapNotNull null
            val timestamp = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val index = match.groupValues[2].toIntOrNull() ?: -1
            SnapshotFile(name, timestamp, index)
        }

        val cutoff = now - MAX_AGE_MS
        val older = snapshots.filter { it.timestamp < cutoff }
        val fresh = snapshots.filter { it.timestamp >= cutoff }

        val sortedFresh = fresh.sortedWith(
            compareByDescending<SnapshotFile> { it.timestamp }.thenByDescending { it.index }
        )
        val excessFresh = sortedFresh.drop(MAX_FILES)

        return (older.map { it.name } + excessFresh.map { it.name }).distinct()
    }

    private data class SnapshotFile(val name: String, val timestamp: Long, val index: Int)
}

/**
 * Throttles snapshot captures so they occur at most once per [RestlessSnapshotPolicy.MIN_INTERVAL_MS].
 */
class RestlessSnapshotThrottle {
    private var lastCaptureAt: Long? = null

    @Synchronized
    fun shouldCapture(now: Long): Boolean {
        val last = lastCaptureAt
        if (last == null || now - last >= RestlessSnapshotPolicy.MIN_INTERVAL_MS) {
            lastCaptureAt = now
            return true
        }
        return false
    }
}
