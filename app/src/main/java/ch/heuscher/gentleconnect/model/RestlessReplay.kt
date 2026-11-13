package ch.heuscher.gentleconnect.model

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * An agitation fire emitted during trace replay.
 */
data class ReplayFire(val at: Long, val clock: String /*HH:mm:ss.SSS*/, val trigger: AgitationTrigger)

/**
 * Result of replaying a user touch trace or logcat buffer.
 */
data class ReplayResult(val fires: List<ReplayFire>, val clickThroughs: Int, val gripTouches: Int = 0) {
    /** Fires at least RestlessSnapshotPolicy.MIN_INTERVAL_MS apart — what the phone would actually report. */
    fun episodes(): List<ReplayFire> {
        val result = mutableListOf<ReplayFire>()
        val throttle = RestlessSnapshotThrottle()
        for (fire in fires) {
            if (throttle.shouldCapture(fire.at)) {
                result.add(fire)
            }
        }
        return result
    }
}

/**
 * Replays touch traces and logcat dumps through [AgitationTracker] to verify restlessness
 * detection against real recorded device interaction logs.
 */
object RestlessReplay {

    private val LOGCAT_REGEX = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+\d+\s+\d+\s+I\s+UiTrace\s*:\s*(.*)$""")
    private val LOGCAT_GENERIC = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+.*$""")
    private val TRAIL_REGEX = Regex("""^(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(.*)$""")

    private val DOWN_REGEX = Regex("""^down\s+\[([^/\]]+)(?:/[^\]]*)?\]\s+([\d.]+),([\d.]+)dp\s+->\s*(.*)$""")
    private val UP_REGEX = Regex("""^up\s+\[([^/\]]+)(?:/[^\]]*)?\]\s+(tap|drag|cancelled)\b.*$""")
    private val EVENT_REGEX = Regex("""^event(?:\s+\[[^\]]*\])?\s+(.*)$""")

    private data class PendingDown(
        val atMs: Long,
        val screen: String,
        val xDp: Float,
        val yDp: Float,
        val target: String
    )

    fun run(
        lines: List<String>,
        quietHours: QuietHours = QuietHours(),
        screenWidthDp: Float = 0f
    ): ReplayResult {
        val tracker = AgitationTracker(quietHours)
        val fires = mutableListOf<ReplayFire>()

        var prevTimeOfDayMs: Long? = null
        var dayOffsetMs = 0L
        var pendingDown: PendingDown? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val parsed = parseLine(line) ?: continue

            // Update rollover state for trail lines
            if (parsed.isTrail) {
                if (prevTimeOfDayMs != null && parsed.timeOfDayMs < prevTimeOfDayMs) {
                    dayOffsetMs += 86_400_000L
                }
                prevTimeOfDayMs = parsed.timeOfDayMs
            }

            val atMs = parsed.atMs + (if (parsed.isTrail) dayOffsetMs else 0L)
            val clock = parsed.clock
            val hour = parsed.hour
            val body = parsed.body

            // 1. screen X
            if (body.startsWith("screen ")) {
                val screenName = body.removePrefix("screen").trim()
                val trigger = tracker.onScreenChange(screenName, atMs, hour)
                if (trigger != null) {
                    fires.add(ReplayFire(atMs, clock, trigger))
                }
                continue
            }

            // 2. down [S/area] x,ydp -> target
            val downMatch = DOWN_REGEX.matchEntire(body)
            if (downMatch != null) {
                val screen = downMatch.groupValues[1]
                val xDp = downMatch.groupValues[2].toFloatOrNull() ?: -1f
                val yDp = downMatch.groupValues[3].toFloatOrNull() ?: -1f
                val target = downMatch.groupValues[4].trim()
                pendingDown = PendingDown(atMs, screen, xDp, yDp, target)
                continue
            }

            // 3. up [S/area] tap|drag|cancelled Nms Ndp
            val upMatch = UP_REGEX.matchEntire(body)
            if (upMatch != null) {
                val down = pendingDown
                pendingDown = null
                if (down != null) {
                    val kind = upMatch.groupValues[2]
                    // On the phone the reaction row marks its own touches. A trail only has the
                    // label, and has to match it whole: "🔊 Vorlesen" starts with an emoji too.
                    val onReactionFace = down.target.removeSurrounding("click \"", "\"") in QuickReactions.FACES
                    val isTap = (kind == "tap") && !onReactionFace
                    val outcome = when (kind) {
                        "cancelled", "drag" -> TouchOutcome.NEITHER
                        "tap" -> if (down.target.startsWith("click")) TouchOutcome.ANSWERED else TouchOutcome.UNANSWERED
                        else -> TouchOutcome.NEITHER
                    }
                    val sample = TouchSample(
                        downAt = down.atMs,
                        upAt = atMs,
                        xDp = down.xDp,
                        yDp = down.yDp,
                        screenWidthDp = screenWidthDp,
                        screen = down.screen,
                        isTap = isTap,
                        outcome = outcome
                    )
                    val trigger = tracker.onTouch(sample, hour)
                    if (trigger != null) {
                        fires.add(ReplayFire(atMs, clock, trigger))
                    }
                }
                continue
            }

            // 4. event [S] ...
            val eventMatch = EVENT_REGEX.matchEntire(body)
            if (eventMatch != null) {
                val what = eventMatch.groupValues[1].trim()
                when {
                    what == "back without effect" -> {
                        val trigger = tracker.onBackWithoutEffect(atMs, hour)
                        if (trigger != null) fires.add(ReplayFire(atMs, clock, trigger))
                    }
                    what == "screen on" -> {
                        val trigger = tracker.onScreenOn(atMs, hour)
                        if (trigger != null) fires.add(ReplayFire(atMs, clock, trigger))
                    }
                    what == "hinge shut" -> {
                        val trigger = tracker.onHinge(true, atMs, hour)
                        if (trigger != null) fires.add(ReplayFire(atMs, clock, trigger))
                    }
                    what == "hinge open" -> {
                        val trigger = tracker.onHinge(false, atMs, hour)
                        if (trigger != null) fires.add(ReplayFire(atMs, clock, trigger))
                    }
                    what.startsWith("goal") -> {
                        tracker.onGoal(atMs)
                    }
                }
                continue
            }
        }

        return ReplayResult(fires = fires, clickThroughs = tracker.clickThroughs, gripTouches = tracker.gripTouches)
    }

    private data class IntermediateParsedLine(
        val atMs: Long,
        val timeOfDayMs: Long,
        val clock: String,
        val hour: Int,
        val body: String,
        val isTrail: Boolean
    )

    private fun parseLine(line: String): IntermediateParsedLine? {
        val logcatMatch = LOGCAT_REGEX.matchEntire(line)
        if (logcatMatch != null) {
            val month = logcatMatch.groupValues[1].toInt()
            val day = logcatMatch.groupValues[2].toInt()
            val hour = logcatMatch.groupValues[3].toInt()
            val minute = logcatMatch.groupValues[4].toInt()
            val second = logcatMatch.groupValues[5].toInt()
            val millis = logcatMatch.groupValues[6].toInt()
            val clock = "${logcatMatch.groupValues[3]}:${logcatMatch.groupValues[4]}:${logcatMatch.groupValues[5]}.${logcatMatch.groupValues[6]}"
            val atMs = LocalDateTime.of(2026, month, day, hour, minute, second, millis * 1_000_000)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
            val body = logcatMatch.groupValues[7].trim()
            return IntermediateParsedLine(
                atMs = atMs,
                timeOfDayMs = 0L,
                clock = clock,
                hour = hour,
                body = body,
                isTrail = false
            )
        }

        // If line is another logcat line (non-UiTrace), ignore it
        if (LOGCAT_GENERIC.matches(line)) {
            return null
        }

        val trailMatch = TRAIL_REGEX.matchEntire(line)
        if (trailMatch != null) {
            val hour = trailMatch.groupValues[1].toInt()
            val minute = trailMatch.groupValues[2].toInt()
            val second = trailMatch.groupValues[3].toInt()
            val millis = trailMatch.groupValues[4].toInt()
            val clock = "${trailMatch.groupValues[1]}:${trailMatch.groupValues[2]}:${trailMatch.groupValues[3]}.${trailMatch.groupValues[4]}"
            val timeOfDayMs = hour * 3600_000L + minute * 60_000L + second * 1000L + millis
            val body = trailMatch.groupValues[5].trim()
            return IntermediateParsedLine(
                atMs = timeOfDayMs,
                timeOfDayMs = timeOfDayMs,
                clock = clock,
                hour = hour,
                body = body,
                isTrail = true
            )
        }

        return null
    }
}
