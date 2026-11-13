package ch.heuscher.gentleconnect.model

import java.util.Calendar
import kotlin.math.hypot

/**
 * Pure decision logic and telemetry tracking for restlessness, anxiety, and agitation.
 *
 * An elderly parent with cognitive decline cannot put distress or confusion into words, but
 * the telephone can notice the shape of it:
 *
 * 1. **Burst tapping**: Multiple rapid, frantic taps in short succession (four within 2.5 s),
 *    indicating panic, trembling, or pressing a button that is not responding as expected.
 * 2. **Repeated call retries**: Calling the same person 3 or more times within 10 minutes when
 *    calls are not answered or drop quickly.
 * 3. **Unconfirmed support loops**: Opening the support dialog repeatedly without pressing "Ja"
 *    or "Nein", indicating hesitation, searching for help, or forgetting what the button does.
 * 4. **Nocturnal restlessness (sundowning)**: Active screen interaction during deep night hours
 *    (01:00 to 05:00) or her learned quiet hours ([QuietHours]).
 * 5. **Unanswered touches**: Rapid touches (5 within 10 s) that produce no effect (no screen change,
 *    no click handled, no scroll), indicating she is tapping on dead space or disabled buttons.
 * 6. **Same-spot dead taps**: Three or more unanswered taps within 10 s on the same screen and
 *    within 48 dp of each other, when she takes a label or background element for a button.
 * 7. **Exit hunting**: Three or more unanswered taps in the top corners where the back arrow
 *    normally lives, or back presses on the home screen that go nowhere, within 20 s.
 * 8. **Navigation loop**: Five consecutive screen changes each within 3 s of the last without
 *    reaching a goal (a soft sign worth 2 points by day, 4 at night).
 * 9. **Fold cycles**: Folding and unfolding the telephone three times within a minute (soft sign).
 * 10. **Screen cycles**: Switching the screen on three times within 3 minutes without unfolding
 *    (soft sign).
 *
 * Unanswered taps in the outermost [AgitationPolicy.EDGE_GRIP_DP] of the left or right edge are
 * grip touches, her thumb or palm resting on the rim while she holds the opened Fold. They count
 * towards none of the above except exit hunting, which keeps its top corners.
 *
 * When the accumulated score within [AgitationPolicy.SCORE_WINDOW_MS] reaches
 * [AgitationPolicy.FIRE_SCORE] (3 points), the tracker enters an active restless state for
 * [AgitationPolicy.ACTIVE_DURATION_MS] (5 minutes). Reaching a goal (a call connecting or a
 * message being sent) within [AgitationPolicy.RESOLVE_MS] (15 s) resolves the episode and
 * settles the tracker back to normal.
 */
enum class AgitationTrigger(val label: String) {
    BURST_TAPS("schnelles Tippen"),
    CALL_RETRIES("wiederholte Anrufe"),
    UNCONFIRMED_SUPPORT("Hilfe geöffnet, nicht bestätigt"),
    NOCTURNAL_ACTIVITY("nächtliche Aktivität"),
    UNANSWERED_TOUCHES("Berührungen ohne Wirkung"),
    SAME_SPOT_DEAD_TAPS("tippt wiederholt auf dieselbe Stelle"),
    EXIT_HUNTING("sucht den Weg zurück"),
    NAVIGATION_LOOP("irrt zwischen Bildschirmen"),
    FOLD_CYCLES("klappt wiederholt auf und zu"),
    SCREEN_CYCLES("schaltet den Bildschirm wiederholt ein")
}

object AgitationPolicy {
    /**
     * Sliding window over which detections add their points together. Two soft signs (2 points
     * each, such as wandering between screens and then folding and unfolding the phone) within
     * two minutes are enough to cross [FIRE_SCORE], while a single soft sign by day is left alone.
     */
    const val SCORE_WINDOW_MS = 2L * 60 * 1000 // 2 minutes

    /**
     * Points needed within [SCORE_WINDOW_MS] to fire. Every hard trigger is worth 3 points so it
     * still fires on its own by day, as decided on 2026-09-17; the three soft triggers
     * are worth 2 points so they need either a second sign or a quiet hour (which doubles them).
     */
    const val FIRE_SCORE = 3

    /**
     * If she reaches what she was trying to do (a call connects or a message goes out) within
     * 15 seconds of a fire, the struggle ended in success rather than distress: the deferred
     * caregiver log is cancelled and the snapshot trail is marked resolved.
     */
    const val RESOLVE_MS = 15_000L // 15 seconds

    /**
     * A tap whose finger lands within 700 ms after a screen change that itself followed within
     * [CLICK_THROUGH_CAUSE_MS] (400 ms) of the previous tap's lift, within [CLICK_THROUGH_RADIUS_DP]
     * (120 dp) of that previous tap, is a click-through: her finger came down again where the last
     * screen's button was and met the new screen's button in the same place (e.g. Anrufen →
     * Nachricht → Ansehen along the bottom bar). Excluded from every tap and dead-tap count.
     */
    const val CLICK_THROUGH_MS = 700L
    const val CLICK_THROUGH_CAUSE_MS = 400L
    const val CLICK_THROUGH_RADIUS_DP = 120f

    /**
     * An unanswered tap within 24 dp of the left or right edge of the window is a grip touch:
     * the thumb or the heel of her hand resting on the rim while she holds the unfolded telephone,
     * not an attempt to press something. On 2026-11-05 at 23:38 five dead taps at the rims (x = 636
     * and 637 dp on a 640 dp wide window, then 34, 9 and 16 dp) fired
     * [AgitationTrigger.UNANSWERED_TOUCHES] while she was calmly reading the home screen; four of
     * them fall inside this strip, which leaves the fifth on its own. They are left out of the unanswered, same-spot,
     * burst and night counts and kept only for [AgitationTrigger.EXIT_HUNTING], whose back arrow
     * sits in a top corner. Taps that were answered are unaffected. The right edge needs a known
     * [TouchSample.screenWidthDp].
     */
    const val EDGE_GRIP_DP = 24f

    /**
     * Three unanswered taps on the same screen within 10 seconds, all within 48 dp (roughly the
     * width of a fingertip target) of the newest one: she has mistaken something that does not
     * press for a button and is trying again at her own pace, which is often slower than
     * [BURST_TAP_WINDOW_MS].
     */
    const val SAME_SPOT_THRESHOLD = 3
    const val SAME_SPOT_WINDOW_MS = 10_000L
    const val SAME_SPOT_RADIUS_DP = 48f

    /**
     * Three unanswered taps in a top corner (120 × 140 dp, where the green back arrow and header
     * controls sit on screens that have them) or back presses on the home screen within 20 seconds:
     * she is trying to leave a screen and has not found the way out.
     */
    const val EXIT_HUNTING_THRESHOLD = 3
    const val EXIT_HUNTING_WINDOW_MS = 20_000L
    const val EXIT_CORNER_WIDTH_DP = 120f
    const val EXIT_CORNER_HEIGHT_DP = 140f

    /**
     * Five consecutive screen changes each within 3 seconds of the previous one, with no goal
     * reached since the first: she is flipping from screen to screen without settling on what
     * she came for. Worth 2 points so it needs a second sign by day.
     */
    const val NAVIGATION_LOOP_THRESHOLD = 5
    const val LOOP_DWELL_MS = 3_000L

    /**
     * Three hinge state transitions (open ↔ shut) within 60 seconds: fidgeting with the fold or
     * opening and closing the telephone repeatedly without settling. Worth 2 points by day.
     */
    const val FOLD_CYCLE_THRESHOLD = 3
    const val FOLD_CYCLE_WINDOW_MS = 60_000L

    /**
     * Three screen-on events within 3 minutes, ignoring any screen-on within 2 seconds of a hinge
     * change (because unfolding the Fold wakes the screen as a side effect of [FOLD_CYCLES]).
     */
    const val SCREEN_CYCLE_THRESHOLD = 3
    const val SCREEN_CYCLE_WINDOW_MS = 3L * 60 * 1000
    const val SCREEN_ON_AFTER_HINGE_IGNORE_MS = 2_000L

    /**
     * Minimum taps within [BURST_TAP_WINDOW_MS] to count as frantic tapping.
     *
     * Four taps in two and a half seconds, tuned on 2026-09-17. It was five in one and a
     * half, which is a sustained rate of nearly three taps a second — a rate a frustrated younger
     * hand reaches and hers does not. The point of this signal is to notice her pressing something
     * that is not answering, and she does that at a slower, heavier tempo than the number was
     * written for; four in 2.5 s is that tempo. Loosening it costs the occasional beacon lit by an
     * ordinary flurry of taps, which is a far cheaper mistake than a beacon that never lights.
     */
    const val BURST_TAP_THRESHOLD = 4
    const val BURST_TAP_WINDOW_MS = 2500L

    /** Repeated calls to the same target within this window to count as distress redialing. */
    const val CALL_RETRY_THRESHOLD = 3
    const val CALL_RETRY_WINDOW_MS = 10L * 60 * 1000 // 10 minutes

    /** Unconfirmed opens of the support dialog within this window to count as searching/hesitation. */
    const val SUPPORT_LOOP_THRESHOLD = 2
    const val SUPPORT_LOOP_WINDOW_MS = 15L * 60 * 1000 // 15 minutes

    /** Night hours window for sundowning / nocturnal disorientation. */
    const val NOCTURNAL_START_HOUR = 1 // 01:00 AM
    const val NOCTURNAL_END_HOUR = 5   // 05:00 AM
    const val NOCTURNAL_TAP_THRESHOLD = 3

    /**
     * How far back [AgitationTracker.onTap] looks when counting taps at night.
     *
     * Much longer than [BURST_TAP_WINDOW_MS] because it is describing a different thing. Burst
     * tapping is a hand pressing the same spot in panic; nocturnal restlessness is somebody awake
     * at three in the morning picking the telephone up and putting it down again. A minute of
     * unhurried taps is exactly what that looks like, and none of it registers as a burst.
     */
    const val NOCTURNAL_TAP_WINDOW_MS = 60L * 1000 // 1 minute

    /**
     * Touches that produced no effect within [UNANSWERED_TOUCH_WINDOW_MS] to count as agitation.
     * N=5 touches within 10 s that produced no screen change, no click handled, and no scroll.
     */
    const val UNANSWERED_TOUCH_THRESHOLD = 5
    const val UNANSWERED_TOUCH_WINDOW_MS = 10L * 1000 // 10 seconds

    /** How long the restless state remains active after a trigger before settling back down. */
    const val ACTIVE_DURATION_MS = 5L * 60 * 1000 // 5 minutes

    /** Minimum interval between writing duplicate restlessness events to the caregiver log. */
    const val LOG_THROTTLE_MS = 10L * 60 * 1000 // 10 minutes

    fun isNocturnalHour(hourOfDay: Int): Boolean =
        hourOfDay in NOCTURNAL_START_HOUR until NOCTURNAL_END_HOUR

    fun pointsFor(trigger: AgitationTrigger, isQuietHour: Boolean): Int {
        val base = when (trigger) {
            AgitationTrigger.SAME_SPOT_DEAD_TAPS,
            AgitationTrigger.EXIT_HUNTING,
            AgitationTrigger.UNANSWERED_TOUCHES,
            AgitationTrigger.BURST_TAPS,
            AgitationTrigger.CALL_RETRIES,
            AgitationTrigger.UNCONFIRMED_SUPPORT,
            AgitationTrigger.NOCTURNAL_ACTIVITY -> 3
            AgitationTrigger.NAVIGATION_LOOP,
            AgitationTrigger.FOLD_CYCLES,
            AgitationTrigger.SCREEN_CYCLES -> 2
        }
        return if (isQuietHour && trigger != AgitationTrigger.NOCTURNAL_ACTIVITY) base * 2 else base
    }
}

/**
 * State tracker holding sliding windows of timestamps for pure, deterministic evaluation.
 */
class AgitationTracker(val quietHours: QuietHours = QuietHours()) {

    private data class Detection(val at: Long, val trigger: AgitationTrigger, val points: Int)

    private val detections = ArrayDeque<Detection>()
    private val tapTimestamps = ArrayDeque<Long>()
    private val callAttempts = ArrayDeque<Pair<String, Long>>()
    private val supportTimestamps = ArrayDeque<Long>()
    private val unansweredTimestamps = ArrayDeque<Long>()
    private val deadTaps = ArrayDeque<TouchSample>()
    private val exitAttempts = ArrayDeque<Long>()
    private val screenChangeTimestamps = ArrayDeque<Long>()
    private val hingeChangeTimestamps = ArrayDeque<Long>()
    private val screenOnTimestamps = ArrayDeque<Long>()

    private var lastTap: TouchSample? = null
    private var lastScreenChangeAt: Long? = null
    private var lastHingeClosed: Boolean? = null
    private var lastHingeChangeAt: Long? = null
    private var hasFired: Boolean = false

    @Volatile
    var clickThroughs: Int = 0

    /** Unanswered taps ignored as [AgitationPolicy.EDGE_GRIP_DP] grip touches. */
    @Volatile
    var gripTouches: Int = 0

    @Volatile
    var lastFireAt: Long = 0L
        private set

    @Volatile
    var activeUntil: Long = 0L
        private set

    @Volatile
    var lastLogAt: Long = 0L
        private set

    @Synchronized
    fun onTouch(sample: TouchSample, hourOfDay: Int = currentHour(sample.upAt)): AgitationTrigger? {
        val now = sample.upAt

        if (sample.isTap) {
            val screenAt = lastScreenChangeAt
            val prev = lastTap
            val validCoords = sample.xDp >= 0f && sample.yDp >= 0f && !sample.xDp.isNaN() && !sample.yDp.isNaN()
            val prevValidCoords = prev != null && prev.xDp >= 0f && prev.yDp >= 0f && !prev.xDp.isNaN() && !prev.yDp.isNaN()

            val isClickThrough = validCoords && screenAt != null &&
                (sample.downAt - screenAt in 0L..AgitationPolicy.CLICK_THROUGH_MS) &&
                prevValidCoords &&
                (screenAt - prev!!.upAt in 0L..AgitationPolicy.CLICK_THROUGH_CAUSE_MS) &&
                (hypot(sample.xDp - prev.xDp, sample.yDp - prev.yDp) <= AgitationPolicy.CLICK_THROUGH_RADIUS_DP)

            lastTap = sample

            if (isClickThrough) {
                clickThroughs += 1
                return null
            }

            if (sample.outcome == TouchOutcome.UNANSWERED && isEdgeGrip(sample)) {
                gripTouches += 1
                // Only the top corners still count, as a missed back arrow.
                if (isTopCorner(sample)) {
                    exitAttempts.addLast(now)
                    pruneOld(exitAttempts, now - AgitationPolicy.EXIT_HUNTING_WINDOW_MS)
                    if (exitAttempts.size >= AgitationPolicy.EXIT_HUNTING_THRESHOLD) {
                        exitAttempts.clear()
                        clearTapBuffers()
                        recordDetection(AgitationTrigger.EXIT_HUNTING, now, hourOfDay)
                    }
                }
                return fireIfReady(now)
            }
        }

        if (sample.outcome == TouchOutcome.ANSWERED) {
            unansweredTimestamps.clear()
        } else if (sample.outcome == TouchOutcome.UNANSWERED) {
            unansweredTimestamps.addLast(now)
            pruneOld(unansweredTimestamps, now - AgitationPolicy.UNANSWERED_TOUCH_WINDOW_MS)
            if (unansweredTimestamps.size >= AgitationPolicy.UNANSWERED_TOUCH_THRESHOLD) {
                unansweredTimestamps.clear()
                recordDetection(AgitationTrigger.UNANSWERED_TOUCHES, now, hourOfDay)
            }
        }

        if (sample.isTap) {
            val isCornerExit = sample.outcome == TouchOutcome.UNANSWERED && isTopCorner(sample)
            if (isCornerExit) {
                exitAttempts.addLast(now)
                pruneOld(exitAttempts, now - AgitationPolicy.EXIT_HUNTING_WINDOW_MS)
            }

            val isPositionedDeadTap = sample.outcome == TouchOutcome.UNANSWERED &&
                sample.xDp >= 0f && sample.yDp >= 0f && !sample.xDp.isNaN() && !sample.yDp.isNaN()
            if (isPositionedDeadTap) {
                deadTaps.addLast(sample)
                while (deadTaps.isNotEmpty() && deadTaps.first().upAt < now - AgitationPolicy.SAME_SPOT_WINDOW_MS) {
                    deadTaps.removeFirst()
                }
            }

            tapTimestamps.addLast(now)
            // Kept for the *longest* window any test below needs, and each test then counts its own
            // span out of it. Pruning to the burst window instead is what made nocturnal restlessness
            // unreportable: three taps ten seconds apart at 02:00 left one timestamp in the deque by
            // the time the night branch counted it, so the trigger could only ever fire for taps that
            // were already inside 1.5 s of each other — a burst by another name. `maxOf` rather than
            // the nocturnal constant spelled out, so that widening either window cannot quietly
            // reintroduce the same bug.
            pruneOld(
                tapTimestamps,
                now - maxOf(AgitationPolicy.BURST_TAP_WINDOW_MS, AgitationPolicy.NOCTURNAL_TAP_WINDOW_MS)
            )

            // Priority: EXIT_HUNTING > SAME_SPOT_DEAD_TAPS > BURST_TAPS > NOCTURNAL_ACTIVITY.
            // Whichever rule detects takes the taps with it (clearTapBuffers), so the same taps
            // cannot be counted a second time by another rule on the next touch.
            if (isCornerExit && exitAttempts.size >= AgitationPolicy.EXIT_HUNTING_THRESHOLD) {
                exitAttempts.clear()
                clearTapBuffers()
                recordDetection(AgitationTrigger.EXIT_HUNTING, now, hourOfDay)
            } else if (isPositionedDeadTap && deadTaps.count {
                    it.screen == sample.screen && hypot(it.xDp - sample.xDp, it.yDp - sample.yDp) <= AgitationPolicy.SAME_SPOT_RADIUS_DP
                } >= AgitationPolicy.SAME_SPOT_THRESHOLD
            ) {
                clearTapBuffers()
                recordDetection(AgitationTrigger.SAME_SPOT_DEAD_TAPS, now, hourOfDay)
            } else if (tapTimestamps.count { it >= now - AgitationPolicy.BURST_TAP_WINDOW_MS } >= AgitationPolicy.BURST_TAP_THRESHOLD) {
                clearTapBuffers()
                recordDetection(AgitationTrigger.BURST_TAPS, now, hourOfDay)
            } else if (quietHours.isQuiet(hourOfDay) &&
                tapTimestamps.count { it >= now - AgitationPolicy.NOCTURNAL_TAP_WINDOW_MS } >= AgitationPolicy.NOCTURNAL_TAP_THRESHOLD
            ) {
                clearTapBuffers()
                recordDetection(AgitationTrigger.NOCTURNAL_ACTIVITY, now, hourOfDay)
            }
        }

        return fireIfReady(now)
    }

    /**
     * Records a screen tap and returns the trigger if restlessness threshold was crossed.
     */
    @Synchronized
    fun onTap(now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? =
        onTouch(
            TouchSample(
                downAt = now,
                upAt = now,
                xDp = -1f,
                yDp = -1f,
                screenWidthDp = 0f,
                screen = "",
                isTap = true,
                outcome = TouchOutcome.NEITHER
            ),
            hourOfDay
        )

    /**
     * Records an outgoing call attempt to [target] (name or phone number).
     */
    @Synchronized
    fun onCallAttempt(target: String, now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        val cleanTarget = target.trim().lowercase()
        if (cleanTarget.isEmpty()) return null

        callAttempts.addLast(cleanTarget to now)
        val cutoff = now - AgitationPolicy.CALL_RETRY_WINDOW_MS
        while (callAttempts.isNotEmpty() && callAttempts.first().second < cutoff) {
            callAttempts.removeFirst()
        }

        val retriesToTarget = callAttempts.count { it.first == cleanTarget }
        if (retriesToTarget >= AgitationPolicy.CALL_RETRY_THRESHOLD) {
            callAttempts.clear()
            recordDetection(AgitationTrigger.CALL_RETRIES, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    /**
     * Records that the support dialog was opened and closed/timed out without confirming.
     */
    @Synchronized
    fun onSupportOpenedNotConfirmed(now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        supportTimestamps.addLast(now)
        pruneOld(supportTimestamps, now - AgitationPolicy.SUPPORT_LOOP_WINDOW_MS)

        if (supportTimestamps.size >= AgitationPolicy.SUPPORT_LOOP_THRESHOLD) {
            supportTimestamps.clear()
            recordDetection(AgitationTrigger.UNCONFIRMED_SUPPORT, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    /**
     * Records a touch interaction that produced no effect (no screen change, no click, no scroll).
     * Triggers [AgitationTrigger.UNANSWERED_TOUCHES] if [AgitationPolicy.UNANSWERED_TOUCH_THRESHOLD]
     * touches occur within [AgitationPolicy.UNANSWERED_TOUCH_WINDOW_MS].
     */
    @Synchronized
    fun onUnansweredTouch(now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? =
        onTouch(
            TouchSample(
                downAt = now,
                upAt = now,
                xDp = -1f,
                yDp = -1f,
                screenWidthDp = 0f,
                screen = "",
                isTap = false,
                outcome = TouchOutcome.UNANSWERED
            ),
            hourOfDay
        )

    /**
     * A touch produced a real effect (screen change, click handled, or scroll).
     * Resets the unanswered touch streak since the telephone is responding to her.
     */
    @Synchronized
    fun onAnsweredTouch(now: Long) {
        unansweredTimestamps.clear()
    }

    @Synchronized
    fun onScreenChange(screen: String, now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        lastScreenChangeAt = now
        if (screenChangeTimestamps.isNotEmpty() && now - screenChangeTimestamps.last() > AgitationPolicy.LOOP_DWELL_MS) {
            screenChangeTimestamps.clear()
        }
        screenChangeTimestamps.addLast(now)
        if (screenChangeTimestamps.size >= AgitationPolicy.NAVIGATION_LOOP_THRESHOLD) {
            screenChangeTimestamps.clear()
            recordDetection(AgitationTrigger.NAVIGATION_LOOP, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    @Synchronized
    fun onBackWithoutEffect(now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        exitAttempts.addLast(now)
        pruneOld(exitAttempts, now - AgitationPolicy.EXIT_HUNTING_WINDOW_MS)
        if (exitAttempts.size >= AgitationPolicy.EXIT_HUNTING_THRESHOLD) {
            exitAttempts.clear()
            recordDetection(AgitationTrigger.EXIT_HUNTING, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    @Synchronized
    fun onHinge(closed: Boolean, now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        val was = lastHingeClosed
        lastHingeClosed = closed
        if (was == null || was == closed) return null
        lastHingeChangeAt = now
        hingeChangeTimestamps.addLast(now)
        pruneOld(hingeChangeTimestamps, now - AgitationPolicy.FOLD_CYCLE_WINDOW_MS)
        if (hingeChangeTimestamps.size >= AgitationPolicy.FOLD_CYCLE_THRESHOLD) {
            hingeChangeTimestamps.clear()
            recordDetection(AgitationTrigger.FOLD_CYCLES, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    @Synchronized
    fun onScreenOn(now: Long, hourOfDay: Int = currentHour(now)): AgitationTrigger? {
        val hingeAt = lastHingeChangeAt
        if (hingeAt != null && now - hingeAt in 0L..AgitationPolicy.SCREEN_ON_AFTER_HINGE_IGNORE_MS) return null
        screenOnTimestamps.addLast(now)
        pruneOld(screenOnTimestamps, now - AgitationPolicy.SCREEN_CYCLE_WINDOW_MS)
        if (screenOnTimestamps.size >= AgitationPolicy.SCREEN_CYCLE_THRESHOLD) {
            screenOnTimestamps.clear()
            recordDetection(AgitationTrigger.SCREEN_CYCLES, now, hourOfDay)
            return fireIfReady(now)
        }
        return null
    }

    /**
     * She got what she was after (call connected, message sent). True when a fire within
     * [AgitationPolicy.RESOLVE_MS] = 15 s before [now] is thereby resolved. Always clears the
     * restless state like [clear].
     */
    @Synchronized
    fun onGoal(now: Long): Boolean {
        val resolved = hasFired && (now - lastFireAt in 0L..AgitationPolicy.RESOLVE_MS)
        clear()
        return resolved
    }

    /**
     * Human connection or relief established (e.g. call connected or support claimed).
     * Clears restlessness immediately.
     */
    @Synchronized
    fun clear() {
        activeUntil = 0L
        lastFireAt = 0L
        hasFired = false
        detections.clear()
        tapTimestamps.clear()
        callAttempts.clear()
        supportTimestamps.clear()
        unansweredTimestamps.clear()
        deadTaps.clear()
        exitAttempts.clear()
        screenChangeTimestamps.clear()
        hingeChangeTimestamps.clear()
        screenOnTimestamps.clear()
        lastTap = null
        lastScreenChangeAt = null
    }

    fun isRestless(now: Long): Boolean = now < activeUntil

    fun remainingRestlessMs(now: Long): Long {
        val remaining = activeUntil - now
        return if (remaining > 0) remaining else 0L
    }

    @Synchronized
    fun shouldLogToCaregiver(now: Long): Boolean {
        if (now - lastLogAt >= AgitationPolicy.LOG_THROTTLE_MS) {
            lastLogAt = now
            return true
        }
        return false
    }

    private fun recordDetection(trigger: AgitationTrigger, now: Long, hourOfDay: Int) {
        val pts = AgitationPolicy.pointsFor(trigger, quietHours.isQuiet(hourOfDay))
        detections.addLast(Detection(now, trigger, pts))
    }

    private fun fireIfReady(now: Long): AgitationTrigger? {
        while (detections.isNotEmpty() && detections.first().at < now - AgitationPolicy.SCORE_WINDOW_MS) {
            detections.removeFirst()
        }
        if (detections.sumOf { it.points } >= AgitationPolicy.FIRE_SCORE) {
            var best: Detection? = null
            for (d in detections) {
                if (best == null || d.points >= best.points) {
                    best = d
                }
            }
            val winningTrigger = best!!.trigger
            markActive(now)
            lastFireAt = now
            hasFired = true
            detections.clear()
            return winningTrigger
        }
        return null
    }

    private fun clearTapBuffers() {
        tapTimestamps.clear()
        deadTaps.clear()
    }

    private fun markActive(now: Long) {
        activeUntil = now + AgitationPolicy.ACTIVE_DURATION_MS
    }

    private fun isEdgeGrip(sample: TouchSample): Boolean =
        sample.xDp >= 0f && sample.yDp >= 0f && !sample.xDp.isNaN() && !sample.yDp.isNaN() &&
            (sample.xDp <= AgitationPolicy.EDGE_GRIP_DP ||
                (sample.screenWidthDp > 0f && sample.xDp >= sample.screenWidthDp - AgitationPolicy.EDGE_GRIP_DP))

    private fun isTopCorner(sample: TouchSample): Boolean =
        sample.xDp >= 0f && sample.yDp >= 0f && !sample.xDp.isNaN() && !sample.yDp.isNaN() &&
            sample.yDp <= AgitationPolicy.EXIT_CORNER_HEIGHT_DP &&
            (sample.xDp <= AgitationPolicy.EXIT_CORNER_WIDTH_DP ||
                (sample.screenWidthDp > 0f && sample.xDp >= sample.screenWidthDp - AgitationPolicy.EXIT_CORNER_WIDTH_DP))

    private fun pruneOld(deque: ArrayDeque<Long>, cutoff: Long) {
        while (deque.isNotEmpty() && deque.first() < cutoff) {
            deque.removeFirst()
        }
    }

    private fun currentHour(timestamp: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(Calendar.HOUR_OF_DAY)
    }
}
