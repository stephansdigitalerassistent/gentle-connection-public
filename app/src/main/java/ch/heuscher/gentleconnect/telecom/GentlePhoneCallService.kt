package ch.heuscher.gentleconnect.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import ch.heuscher.gentleconnect.MainActivity
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.CallLogRepository
import ch.heuscher.gentleconnect.data.CaregiverLogRepository
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.support.SupportManager
import ch.heuscher.gentleconnect.ui.Restlessness
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.Home
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The telephone, once this app is the phone app.
 *
 * Android hands the whole of an ordinary call to whichever app holds the dialler role: the ringing
 * screen, the answering, the hanging up and the screen in between. Before this existed the calls
 * were somebody else's — she pressed a face here and was handed over to another app with another
 * idea of what a call looks like, and the app that knew who she was and how big her text should be
 * had no part in the one moment that matters most.
 *
 * **This service does not ring.** The manifest deliberately does not claim
 * `android.telecom.IN_CALL_SERVICE_RINGING`, which means Android plays her ringtone at her volume
 * with her Do Not Disturb rules, exactly as it does for every other phone app. Owning the ringing
 * would let us be cleverer about it — silencing numbers she does not know, letting a second call
 * from the same person through a quiet hour — and every one of those cleverer behaviours is a new
 * way for the telephone to sit silent while her family try to reach her. The failure is invisible:
 * nothing appears on the screen, no error is logged anywhere she or a caregiver would look, and it
 * would be discovered as "she never picks up any more". Not worth it. If blocking unknown callers
 * is ever wanted, Simple Phone's `DndRingPolicy` is a tested implementation to port, and taking
 * the ringtone on is the decision to make consciously at that point.
 *
 * There is at most one call here. No hold, no second line, no swapping between two conversations —
 * see [OngoingCall].
 */
class GentlePhoneCallService : InCallService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tracked: Call? = null

    /** Fixed when the call arrives. The platform's own direction flag is not on every version. */
    private var arrivedRinging = false
    private var wasAnswered = false

    private val audioRouting by lazy {
        CallAudioRouting(
            context = applicationContext,
            audioState = { if (tracked == null) null else callAudioState },
            setRoute = { setAudioRoute(it.toPlatformRoute()) }
        )
    }

    /**
     * Whether the routing has been let loose on this call yet.
     *
     * Nothing to do with tidiness — see [beginAudioHandlingUnlessRinging] for why a ringing call
     * must be left completely alone.
     */
    private var audioHandlingStarted = false

    private var silenceJob: kotlinx.coroutines.Job? = null

    private var identifyJob: kotlinx.coroutines.Job? = null

    private val controls = object : OngoingCall.Controls {
        override fun answer() {
            val call = tracked ?: return
            if (stageOf(call) == CallStage.RINGING) {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }

        override fun hangUp() {
            val call = tracked ?: return
            // Rejecting and disconnecting are different operations, and using the wrong one on a
            // ringing call leaves it ringing. `reject` is also what tells the network to stop,
            // which is what puts the call in her log as one she turned down rather than one that
            // simply stopped.
            if (stageOf(call) == CallStage.RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
        }
        
        override fun silence() {
            silenceTheRinger()
        }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publish(call)

            if (stageOf(call) == CallStage.TALKING || state == Call.STATE_ACTIVE) {
                if (call === tracked && arrivedRinging && !wasAnswered) {
                    missedCallAnsweredBy(numberOf(call))
                }
                if (!wasAnswered) {
                    Restlessness.shared(applicationContext).recordGoal("call connected")
                }
                wasAnswered = true
                SupportManager.onCallConnected(applicationContext, numberOf(call))
            }
            
            if (stageOf(call) != CallStage.RINGING) {
                silenceJob?.cancel()
                silenceJob = null
            }

            // Also here and not only in onCallAdded, because an incoming call is left alone
            // while it rings and this is the moment it stops ringing. The platform additionally
            // resets the route during state transitions, so re-deciding here is what makes the
            // route stick once she has answered.
            beginAudioHandlingUnlessRinging(call)
        }
    }

    /**
     * She has just picked up a call from [number]. If the missed call on her home screen was from
     * the same telephone, they are talking now and the card has nothing left to say.
     *
     * The counterpart of her ringing them back — see HomeViewModel.markMissedCallAnsweredBy — which
     * was the only way the card used to go: her daughter would ring again, they would talk, and
     * the card went on saying she had missed her. Written to the memory on disk, because the view
     * model may not be running; the launcher reads it again when it comes back to the front.
     */
    private fun missedCallAnsweredBy(number: String) {
        if (number.isBlank()) return
        val context = applicationContext
        scope.launch(Dispatchers.IO) {
            val missed = CallLogRepository(context).fetchLatestMissedCall() ?: return@launch
            if (!Home.answeredBy(missed, number)) return@launch
            AppGraph.nudgeMemory(context).dealtWithMissedCall(missed.timestamp)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        if (endedAsASecondCall(call)) return
        if (endedAsAStranger(call)) return

        tracked = call
        arrivedRinging = stageOf(call) == CallStage.RINGING
        wasAnswered = !arrivedRinging && stageOf(call) == CallStage.TALKING
        call.registerCallback(callback)

        if (arrivedRinging) {
            val timeout = ch.heuscher.gentleconnect.telecom.RingerSettings.getSilenceTimeoutSeconds(applicationContext)
            if (timeout > 0 && !SupportManager.isRingThroughActiveForPhone(applicationContext, numberOf(call))) {
                silenceJob = scope.launch {
                    kotlinx.coroutines.delay(timeout * 1000L)
                    silenceTheRinger()
                }
            }
        }

        // The address book is asked on a deadline, because the ringing screen now waits for the
        // answer before it draws. A content-provider query is normally quick, but "normally" is
        // not good enough for the one screen that must never fail to appear: if the query is slow
        // or the provider is wedged, the deadline settles the question and the phone is drawn
        // with whatever is known. Late is worse than plain here — a call she cannot see is a call
        // she cannot answer.
        identifyJob = scope.launch {
            kotlinx.coroutines.delay(IDENTIFY_DEADLINE_MS)
            val current = tracked ?: return@launch
            if (current !== call) return@launch
            publish(current, identified = true)
        }

        // Published before anybody is looked up, so the screen is on the phone within the frame
        // rather than after a query. The name arrives a moment later and replaces the number.
        publish(call)
        putTheScreenInFront(call)
        lookUpWhoItIs(call)

        beginAudioHandlingUnlessRinging(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)

        // Only the call this service is actually showing takes the telephone down with it.
        //
        // Everything below is written for "the call is over": it clears her screen, stops the
        // screen blanking and the audio routing, cancels the ongoing-call notification and plays
        // the busy tone. Run for a call that was never hers to show, all of that lands on the call
        // that is still going. It happened on 2026-09-17 at 20:21:06: a second call had been
        // answered, the first one then ended, and three seconds into the new conversation the app
        // played a 3.5-second busy tone into her ear, dropped her back to the home screen and
        // cancelled the notification — leaving a live call with no way to end it. The same applies
        // to a call ended the moment it arrived by [endedAsASecondCall] or [endedAsAStranger]:
        // neither was ever published, so neither has anything to tear down.
        if (tracked !== call) return
        tracked = null

        silenceJob?.cancel()
        silenceJob = null
        identifyJob?.cancel()
        identifyJob = null

        // Read before the release below, not after. release() clears OngoingCall.current, and the
        // name and the face are the whole point of the missed-call notification: a bare number is
        // exactly what she cannot act on.
        val missed = OngoingCall.current.value

        OngoingCall.release(controls)
        endAudioHandling()
        NotificationManagerCompat.from(this).cancel(CALL_NOTIFICATION)

        val isOutgoing = !arrivedRinging
        val causeCode = call.details?.disconnectCause?.code
        if ((isOutgoing || wasAnswered) &&
            causeCode != android.telecom.DisconnectCause.LOCAL &&
            causeCode != android.telecom.DisconnectCause.MISSED
        ) {
            ch.heuscher.gentleconnect.voip.BusyTone.playStandalone(CoroutineScope(Dispatchers.Default))
        }

        val number = numberOf(call)
        if (call.details?.disconnectCause?.code == android.telecom.DisconnectCause.MISSED) {
            SupportManager.onCallMissed(applicationContext, number)
            showMissedCallNotification(call, missed)
        } else {
            SupportManager.onCallEnded(applicationContext, number)
        }
    }

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        if (state == null) return
        // Acted on only once the call is hers — see [beginAudioHandlingUnlessRinging].
        audioRouting.onAudioStateChanged(state)
    }

    override fun onDestroy() {
        // The service can go while a screen is still up — Telecom unbinds when the last call
        // ends, and occasionally before the activity has noticed. Clearing here is what stops a
        // dead call screen sitting over the launcher with two circles that do nothing.
        OngoingCall.release(controls)
        endAudioHandling()
        NotificationManagerCompat.from(this).cancel(CALL_NOTIFICATION)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Start deciding where the sound goes — but never while the telephone is still ringing.
     *
     * Two separate things go wrong if this runs on a ringing call, and both of them look to her
     * like a telephone that has stopped working.
     *
     * The first is the ringtone. Asking Telecom for a route while a call is ringing moves where
     * the ringing is played on a good many phones. A paired Bluetooth headset sitting in a drawer
     * is still an available route, and this policy prefers Bluetooth over everything — so the
     * telephone would ring, correctly and at full volume, inside a drawer. Nothing would appear
     * to be wrong; it would be discovered months later as "she never answers any more".
     *
     * The second is the screen. [EarProximity] holds the screen-off wake lock whenever the sound is
     * on the earpiece, and a phone lying face down on a table reads as *near*. The full-screen
     * intent wakes the screen for the incoming call and the proximity lock would put it straight
     * back out again.
     *
     * So a ringing call is left entirely to Android, which is the same reasoning that keeps
     * `IN_CALL_SERVICE_RINGING` out of the manifest. An outgoing call is not ringing at her end —
     * she is listening to it — so it starts immediately.
     *
     * Safe to call repeatedly; only the first call that finds the telephone out of the ringing
     * state does anything.
     */
    private fun beginAudioHandlingUnlessRinging(call: Call) {
        if (audioHandlingStarted) return
        if (stageOf(call) == CallStage.RINGING) return

        audioHandlingStarted = true
        audioRouting.start()
    }

    /** Let the sensors and the wake lock go. Must survive being called when nothing was started. */
    private fun endAudioHandling() {
        audioHandlingStarted = false
        audioRouting.stop()
    }

    /**
     * End a call that arrives while she is already on the telephone.
     *
     * Call waiting is a feature for somebody who can hold two conversations in their head and
     * decide between them under a beeping tone. For her it is the opposite: on 2026-09-17 a second
     * call arrived mid-conversation, she answered it, and the first call — still live, still on
     * the same loudspeaker — was put on hold by the network with nothing on screen to say so. This
     * app has one call slot by design (see [OngoingCall]); the platform having two is precisely
     * where that design stops being true, and the honest fix is to stop the second call reaching
     * her at all rather than to grow a second line she cannot use.
     *
     * Rejected rather than left to ring on: `reject` tells the network to stop, so the caller
     * hears busy and knows to try again, and Android files it as a call she turned down rather
     * than one that mysteriously stopped. She is told afterwards through the ordinary missed-call
     * notice ([tellHerAboutTheSecondCall]), with the caller's name and a Call back button — the
     * whole point being that she rings them back when she is free, which is the difference
     * between this and a stranger's call the switch ends.
     *
     * The decision itself — which calls are second calls and which are not — is [SecondCall], so
     * that it can be checked without a telephone. This method is only the part that needs one.
     *
     * @return whether the call was ended, in which case the caller must do nothing further with it.
     */
    private fun endedAsASecondCall(call: Call): Boolean {
        val ongoing = tracked ?: return false
        // Telecom re-adding the call this service is already showing is not a second call.
        if (ongoing === call) return false

        val details = call.details
        val isEmergency = details != null &&
            (details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) ||
                details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE))

        if (!SecondCall.shouldEnd(stageOf(ongoing), stageOf(call), isEmergency)) return false

        return try {
            call.reject(false, null)
            tellHerAboutTheSecondCall(call)
            true
        } catch (e: Exception) {
            // Rejecting failed, so the call is coming in whatever this method wanted. Falling
            // through is the lesser evil: the old two-call confusion is survivable, a call that
            // rings with no screen behind it is not.
            false
        }
    }

    /**
     * Tell her about the call that was turned away, the same way she is told about any other call
     * she did not take.
     *
     * The missed-call notice and not the blocked-call one: this is somebody she knows, ringing her
     * while she was busy, and everything that notice does — the name, the face, "Call back" — is
     * exactly what she needs. A second call that vanished without trace would be the worse bug of
     * the two, because nothing anywhere would ever tell her Eliane had rung.
     *
     * The address book is asked off the main thread; the notice waits for it rather than posting a
     * bare number first and correcting itself, because a number is what she cannot act on.
     */
    private fun tellHerAboutTheSecondCall(call: Call) {
        val number = numberOf(call)
        SupportManager.onCallMissed(applicationContext, number)
        scope.launch {
            val caller = if (number.isBlank()) null else withContext(Dispatchers.IO) {
                runCatching { CallLogRepository(applicationContext).lookupContact(number) }.getOrNull()
            }
            showMissedCallNotification(
                call,
                PhoneCall(
                    number = number,
                    name = caller?.name,
                    photoUri = caller?.photoUri,
                    stage = CallStage.OVER,
                    isIncoming = true,
                    identified = true
                )
            )
        }
    }

    /**
     * End a call from somebody she does not know, if a caregiver has asked for that.
     *
     * First thing in `onCallAdded`, before anything is published or drawn, so that nothing of the
     * call reaches her: no ring, no screen appearing and vanishing, and no row on her list of
     * calls with a stranger's number and a green telephone beside it.
     *
     * The address-book lookup is synchronous, which is the one place in this file that touches a
     * content provider on the main thread. It is deliberate and it is narrow: this runs only when
     * the switch is on, it is an indexed `PhoneLookup` of a single number, and the alternative —
     * deciding a moment later, off a background thread — is a telephone that rings once before
     * going quiet. Half a ring is worse than either answer.
     *
     * The switch is read from preferences rather than from the website, because this service can
     * be started by the call itself, in a process with no settings loaded and possibly no signal.
     * See [UnknownCallers].
     *
     * @return whether the call was ended, in which case the caller must do nothing further with it.
     */
    private fun endedAsAStranger(call: Call): Boolean {
        // Outgoing calls are never anybody's stranger — she dialled them.
        if (stageOf(call) != CallStage.RINGING) return false
        if (!UnknownCallers.isOn(this)) return false

        val number = numberOf(call)
        if (SupportManager.isRingThroughActiveForPhone(applicationContext, number)) return false

        val inAddressBook = number.isNotBlank() &&
            runCatching { CallLogRepository(applicationContext).lookupContact(number) }
                .getOrNull() != null

        if (!UnknownCallers.shouldEnd(true, number, inAddressBook)) return false

        return try {
            call.reject(false, null)
            noteTheStrangerForWhoeverLooksAfterHer()
            if (UnknownCallers.notifies(this)) {
                showBlockedCallNotification(call)
            }
            true
        } catch (e: Exception) {
            // Rejecting failed, so the call is still coming in. Better a call she did not want
            // than a telephone that neither rings nor hangs up.
            false
        }
    }

    /**
     * Tell her a stranger's call was ended, when a caregiver has asked for that.
     *
     * Deliberately not the missed-call notice, however easy that shape is to copy:
     * - its own id, so it never replaces a real missed call from her family, and a swipe on it
     *   never clears one;
     * - no "Call back" button — ringing that number back is exactly what ending the call prevents —
     *   and nothing written into the missed-call memory, which would take a real missed call's
     *   card off her home screen;
     * - a quiet channel, so a call that never rang does not arrive as a banner over her screen.
     *
     * Pressing it opens her calls only when this call is listed there. Otherwise it has no action:
     * a notice that opens a list without the thing it told her about is worse than none.
     */
    private fun showBlockedCallNotification(call: Call) {
        ensureBlockedChannel()

        val number = numberOf(call)
        val who = number.ifBlank { getString(R.string.call_unknown_caller) }

        val builder = NotificationCompat.Builder(this, CHANNEL_BLOCKED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(getString(R.string.call_blocked_notification_title, who))
            .setContentText(getString(R.string.call_blocked_notification_text))
            .setAutoCancel(true)

        // The list leaves out calls with no number, so a withheld caller is not there to open.
        if (number.isNotBlank() && UnknownCallers.showsInCallLog(this)) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    MISSED_CALL_REQUEST,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_MISSED_CALL, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        try {
            NotificationManagerCompat.from(this).notify(BLOCKED_CALL_NOTIFICATION, builder.build())
        } catch (e: SecurityException) {}
    }

    /**
     * Write the ended call into the caregiver's log.
     *
     * Not optional, and not a debug line. A switch that silently ends calls turns a surgery ringing
     * back from an unsaved number into "we tried and nobody answered", with nothing anywhere to
     * explain it — and the person best placed to understand is the one who turned the switch on
     * months earlier. Carries neither number nor name: this log is published to the caregiver
     * website, and the number belongs on the phone, in the telephone's own list of calls.
     */
    private fun noteTheStrangerForWhoeverLooksAfterHer() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CaregiverLogRepository(
                        AppDatabase.getInstance(applicationContext).caregiverEventDao()
                    ).record(
                        CaregiverEvent(
                            kind = CaregiverEventKind.CALL_FROM_STRANGER_ENDED,
                            at = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                // The call is already ended. Losing the note is bad, but not a reason to take the
                // telephone down with it.
            }
        }
    }

    /** The current state, as one of the four she can tell apart. */
    private fun stageOf(call: Call): CallStage {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        return when (state) {
            Call.STATE_RINGING -> CallStage.RINGING
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING,
            Call.STATE_SELECT_PHONE_ACCOUNT -> CallStage.DIALLING
            // Holding is counted as talking on purpose: this app never puts a call on hold, so
            // the only way to reach that state is another app or the network doing it, and the
            // truthful thing to show her is still "you are on the telephone with Anna".
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> CallStage.TALKING
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallStage.OVER
            else -> if (arrivedRinging) CallStage.RINGING else CallStage.DIALLING
        }
    }

    private fun numberOf(call: Call): String =
        call.details?.handle?.schemeSpecificPart.orEmpty()

    private fun publish(
        call: Call,
        name: String? = null,
        photoUri: String? = null,
        identified: Boolean = false
    ) {
        val previous = OngoingCall.current.value
        val stage = stageOf(call)
        val here = PhoneCall(
            number = numberOf(call),
            // A name once found is kept: the lookup finishes once, and every state change after
            // it must not quietly put the raw number back on the screen mid-call.
            name = name ?: previous?.name,
            photoUri = photoUri ?: previous?.photoUri,
            stage = stage,
            isIncoming = arrivedRinging,
            // Sticky for the same reason the name is. Every other publish on this call — a state
            // change, a route change — passes the default, and none of them may take the screen
            // back to not knowing who is calling.
            identified = identified || previous?.identified == true
        )
        OngoingCall.show(here, controls)

        if (stage == CallStage.OVER) {
            NotificationManagerCompat.from(this).cancel(CALL_NOTIFICATION)
        } else {
            notify(here)
        }
    }

    /**
     * Who is calling, out of her address book.
     *
     * Off the main thread because this is a content-provider query and the main thread at this
     * moment is drawing a ringing telephone. The same lookup the call list uses, so the name on
     * the ringing screen and the name in the list of calls are the same name.
     */
    private fun lookUpWhoItIs(call: Call) {
        val number = numberOf(call)
        if (number.isBlank()) {
            // A withheld number has nothing to look up, and it is not going to become a name
            // later. Settling it here rather than leaving it open is what stops the ringing
            // screen waiting out the whole deadline for an answer that was never coming.
            publish(call, identified = true)
            return
        }
        scope.launch {
            val caller = withContext(Dispatchers.IO) {
                CallLogRepository(applicationContext).lookupContact(number)
            }
            val current = tracked ?: return@launch
            if (current !== call) return@launch
            // Published even when nobody was found. "Not in her address book" is an answer, and
            // the screen is waiting on the question being settled rather than on a name.
            publish(current, name = caller?.name, photoUri = caller?.photoUri, identified = true)
        }
    }

    /**
     * Wake the phone up with the call on it. Both ways, on purpose.
     *
     * The notification carries a full-screen intent, which is the only thing that works when the
     * phone is locked and face down on a table — from Android 10 an app that is not already in
     * front may not simply start an activity.
     *
     * But a full-screen intent is *not* honoured when the screen is already on and she is using
     * the phone: Android shows it as a banner at the top instead. That is the ordinary case for
     * a call she has just placed herself, and a banner is not a call screen — there would be
     * nothing large enough to hang up with. So the activity is also started directly, which is
     * allowed precisely when the banner would have happened, because the app is in front.
     *
     * Whichever of the two lands first, the activity is `singleTop` and both routes carry the
     * same intent, so she gets one screen and not two.
     */
    private fun putTheScreenInFront(call: Call) {
        notify(
            PhoneCall(
                number = numberOf(call),
                name = null,
                photoUri = null,
                stage = stageOf(call),
                isIncoming = arrivedRinging
            )
        )

        try {
            startActivity(
                Intent(this, PhoneCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            // Refused because the app was in the background. Expected, and already covered — the
            // notification above is the path that works from there.
        }
    }

    /**
     * Stop the ringtone, and nothing else. The call goes on ringing in every sense except the
     * noise: it stays on her screen and stays answerable.
     *
     * Lint asks for MODIFY_PHONE_STATE here and cannot be given it — that is a signature
     * permission no ordinary application holds, and adding it to the manifest would be a claim
     * that is never granted. The other half of the platform's rule is the half that applies:
     * the default dialer may silence the call it is already showing, and this app is the default
     * dialer or it is not her telephone at all. One home for the call so that there is one
     * explanation of the suppression rather than a copy of it at every site.
     */
    @SuppressLint("MissingPermission")
    private fun silenceTheRinger() {
        val manager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        manager?.silenceRinger()
    }

    private fun showMissedCallNotification(call: Call, missed: PhoneCall?) {
        // The ringing call will have made the channel already in every ordinary case. Asking again
        // costs nothing and covers the one where it did not: on Android 8 and later a notification
        // posted to a channel that does not exist is dropped without a word.
        ensureChannel()

        val number = numberOf(call)
        // Through [whoToShow], like the ringing screen and the banner over it, rather than off
        // `name` raw. This was the one notice in the app that printed her address book as it is
        // written — `Johann "Papa Opa" Hartmann`, of which a notification title shows about as
        // far as the nickname, so the family name was the part that got cut off. And a withheld
        // number has no name and no number either, which left the sentence ending in nothing at
        // all: "Missed call from ". All three places now say the same thing about the same caller.
        val caller = missed ?: PhoneCall(
            number = number,
            name = null,
            photoUri = null,
            stage = CallStage.OVER,
            isIncoming = true
        )
        val who = caller.whoToShow(getString(R.string.call_unknown_caller))

        val callBackIntent = PendingIntent.getBroadcast(
            this,
            CallActionReceiver.ACTION_CALL_BACK.hashCode(),
            Intent(this, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_CALL_BACK
                putExtra(CallActionReceiver.EXTRA_NUMBER, number)
                // When the call came, so that pressing "Call back" here takes the card off her
                // home screen the way pressing it on the card itself does — see
                // [ch.heuscher.gentleconnect.model.MissedCallNotice]. This moment, not the moment
                // of the press: a second call that arrives while the notice is sitting there is
                // still news, and must not be swept away with the one she has just answered.
                //
                // The same number the call log will file this call under: Android writes its DATE
                // column from exactly this clock reading.
                putExtra(CallActionReceiver.EXTRA_MISSED_AT, call.details?.creationTimeMillis ?: 0L)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            this,
            CallActionReceiver.ACTION_DISMISS_MISSED_CALL.hashCode(),
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DISMISS_MISSED_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // What "Tap for details" means. There was no such intent until now: the notice told her
        // to press it and pressing it did nothing but take it away. A phone that asks her to press
        // something and then answers with nothing is a phone she learns to stop pressing. It opens
        // the list of calls, which is where this call and the time it came actually are.
        val details = PendingIntent.getActivity(
            this,
            MISSED_CALL_REQUEST,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_MISSED_CALL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(getString(R.string.call_missed_notification_title, who))
            .setContentText(getString(R.string.call_missed_notification_text))
            .setAutoCancel(true)
            .setContentIntent(details)
            .setDeleteIntent(dismissIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.sym_action_call,
                    getString(R.string.call_missed_action_callback),
                    callBackIntent
                ).build()
            )

        val photoUri = missed?.photoUri
        if (!photoUri.isNullOrBlank()) {
            try {
                val stream = contentResolver.openInputStream(android.net.Uri.parse(photoUri))
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                builder.setLargeIcon(bitmap)
            } catch (e: Exception) {}
        }

        try {
            NotificationManagerCompat.from(this).notify(CallActionReceiver.MISSED_CALL_NOTIFICATION, builder.build())
        } catch (e: SecurityException) {}
    }

    private fun notify(here: PhoneCall) {
        ensureChannel()

        val screen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PhoneCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val who = here.whoToShow(getString(R.string.call_unknown_caller))
        val person = Person.Builder().setName(who).setImportant(true).build()

        val style = if (here.canAnswer) {
            NotificationCompat.CallStyle.forIncomingCall(
                person,
                action(CallActionReceiver.ACTION_HANG_UP),
                action(CallActionReceiver.ACTION_ANSWER)
            )
        } else {
            NotificationCompat.CallStyle.forOngoingCall(
                person,
                action(CallActionReceiver.ACTION_HANG_UP)
            )
        }

        // A ringing call and a call already in progress are not the same notification, and posting
        // them the same way is what put a second Hang Up on her screen: the ongoing one arrived as
        // a banner over this app's own call screen, which already has a red circle the size of a
        // thumb. Two ways to end one call, one of them floating over the other.
        //
        // Ringing keeps everything — the banner and the full-screen intent are how her phone shows
        // the call at all, and that intent is the one the app-op on this device was granted for.
        // An ongoing call keeps only the entry in the shade: she is already looking at the screen
        // it would have taken her to.
        val builder = NotificationCompat.Builder(this, if (here.canAnswer) CHANNEL else CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(screen)

        if (here.canAnswer) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(screen, true)
        } else {
            // The intent is here for the platform, not for her. A CallStyle notification is
            // refused outright unless it comes from a foreground service or carries one of these,
            // and the refusal is thrown, not returned — it took the whole phone app down in the
            // middle of dialling. Passing false is what keeps it out of her way: a low-priority
            // full-screen intent only fires on a screen that is off, so the banner this branch
            // was written to remove stays removed, and the call she is already looking at is not
            // interrupted by a second way to end it.
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
                .setFullScreenIntent(screen, false)
        }

        val notification = builder.build()

        try {
            NotificationManagerCompat.from(this).notify(CALL_NOTIFICATION, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS refused. Named on its own rather than folded into the catch
            // below because lint reads this line as the proof that the permission behind notify()
            // has been thought about, and fails the build without it.
        } catch (e: Exception) {
            // And everything else the platform can refuse. The call still rings and the platform
            // still knows about it; what is lost is a line in the shade. Never a reason to crash
            // the phone app — this service dying takes her call screen with it.
        }
    }

    private fun action(what: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        what.hashCode(),
        Intent(this, CallActionReceiver::class.java).setAction(what),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * The channel, made silent on purpose.
     *
     * Android is ringing her ringtone for this call already. A channel with a sound of its own
     * would put a notification chime on top of it — two noises for one event, and the quieter one
     * arriving first.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(
                CHANNEL,
                getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
        // A second channel purely so the call she is already on stops announcing itself. From
        // Android 8 onwards it is the channel's importance, not the notification's priority, that
        // decides whether something appears as a banner over what she is looking at — so an
        // ongoing call cannot be quietened on a channel shared with the ringing one, however it is
        // built. IMPORTANCE_LOW still puts it in the shade and still keeps the Hang Up within
        // reach; it simply stops it arriving on top of the screen that already offers one.
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            val ongoing = NotificationChannel(
                CHANNEL_ONGOING,
                getString(R.string.call_channel_ongoing_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(ongoing)
        }
    }

    /** Low importance: in the shade, never a banner, no sound. See [showBlockedCallNotification]. */
    private fun ensureBlockedChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_BLOCKED) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BLOCKED,
                getString(R.string.call_channel_blocked_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private companion object {
        /**
         * How long the ringing screen will wait for the address book before drawing anyway.
         *
         * Long enough that the lookup wins on any ordinary call, short enough that nobody
         * experiences it as the phone being slow to ring.
         */
        private const val IDENTIFY_DEADLINE_MS = 700L

        const val CHANNEL = "gentle_connection_calls"

        /** The call she is already on. Separate from [CHANNEL] only to be quieter than it. */
        const val CHANNEL_ONGOING = "gentle_connection_calls_ongoing"

        /** Strangers' calls the switch ended. Separate so it can be quiet — and stay quiet. */
        const val CHANNEL_BLOCKED = "gentle_connection_calls_blocked"

        // Not 4711, and not 4712. Android files a notification by its id alone, so this is the
        // third in a series that must stay distinct: 4711 belongs to MessengerService and 4712 to
        // Attention. Sharing 4711 was tried on a real phone on 2026-08-16 and is worse than it
        // sounds — MessengerService holds 4711 as its *foreground service* notification, so posting
        // a call under that number replaced it, and the cancel in onCallRemoved was then refused,
        // because Android does not let anybody take down a running foreground service's notice.
        // What she was left with was a "Hang Up" button sitting on her phone with no call behind
        // it, flagged NO_CLEAR so she could not even swipe it away — the exact thing this app
        // exists to keep off her screen.
        const val CALL_NOTIFICATION = 4713

        /**
         * The request code behind the missed call's "details" intent.
         *
         * Its own, not 0, which the ringing call's screen intent already uses. They name different
         * activities and so would not in fact collide, but a second pending intent quietly
         * depending on that is not a thing to leave lying next to a telephone.
         */
        const val MISSED_CALL_REQUEST = 4715

        /**
         * The notice for a stranger's call that was ended. Not 4714, the missed call's: sharing it
         * would let a stranger's call replace her daughter's missed-call notice.
         */
        const val BLOCKED_CALL_NOTIFICATION = 4716
    }
}
