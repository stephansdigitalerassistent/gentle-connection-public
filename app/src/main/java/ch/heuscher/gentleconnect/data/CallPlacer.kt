package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.voip.CallState
import ch.heuscher.gentleconnect.voip.GentleConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The specific reason a call tap could not be placed immediately.
 */
enum class CallFailure {
    /** The phone is roaming abroad, and this contact has no in-app Threema connection. */
    NOT_FROM_ABROAD,
    /** No cellular service and no reachable data / Threema connection. */
    NO_CONNECTION,
    /** The contact has neither a phone number nor a Threema ID. */
    NOTHING_TO_CALL
}

/**
 * Actually ringing somebody, once [CallRouter] has decided how.
 *
 * Split out of the view model when the watch arrived. A tap on her wrist has to place exactly
 * the call a tap on the same face on the phone would place — over the telephone network whenever
 * it can ring, over in-app Threema when it cannot or when the phone is roaming.
 *
 * We never route to the external Threema application: an elderly, cognitively impaired user cannot
 * handle being dumped into a third-party app with an unfamiliar interface. If in-app Threema is
 * unavailable, we route over the telephone network or fail cleanly with clear feedback.
 */
object CallPlacer {

    /**
     * @param onFailed called when the call cannot be placed immediately, providing the exact failure reason.
     */
    fun place(
        context: Context,
        phoneNumber: String?,
        threemaId: String?,
        onFailed: (CallFailure) -> Unit
    ) {
        val manager = GentleConnectionService.activeManager
        if (manager != null && manager.currentCallState.value != CallState.IDLE) {
            Log.w(TAG, "Call tap ignored: active VoIP call already in state ${manager.currentCallState.value}")
            return
        }

        // An emergency number is dialled on the telephone, always and before any routing: no
        // setting about cost may stand between her and 112 abroad.
        val cleanNumber = phoneNumber?.trim()?.ifEmpty { null }
        if (cleanNumber != null && isEmergencyNumber(context, cleanNumber)) {
            Log.i(TAG, "Placing call: channel=CELLULAR reason=emergency_number")
            placeCellularCall(context, cleanNumber) { onFailed(CallFailure.NO_CONNECTION) }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var effectiveThreemaId = threemaId?.trim()?.ifEmpty { null }

            // If no Threema ID was explicitly provided, look up if this phone number matches a known Threema contact.
            if (effectiveThreemaId == null && cleanNumber != null) {
                try {
                    val db = AppDatabase.getInstance(context)
                    val normalized = PhoneNumberNormalizer.matchable(null, cleanNumber, "41")
                    val match = if (normalized != null) {
                        db.threemaMatchDao().getMatchByPhoneNumber(normalized)
                    } else null
                    val directMatch = if (match == null) {
                        db.threemaMatchDao().getMatchByPhoneNumber(cleanNumber)
                    } else null

                    effectiveThreemaId = match?.identity ?: directMatch?.identity
                } catch (e: Exception) {
                    Log.w(TAG, "Error looking up Threema match by phone number", e)
                }
            }

            val canCallThreemaInApp = manager != null
            val isRoaming = CallRouter.isRoaming(context)
            val threemaOnlyWhenRoaming = threemaOnlyWhenRoaming(context)
            val hasCellularService = CallRouter.hasCellularVoiceService(context)
            val hasInternet = CallRouter.hasInternet(context)

            // No mobile network and no internet at all: nothing can carry any call, so say so at
            // once instead of starting a call that can only end in a busy tone.
            if (!hasCellularService && !hasInternet) {
                Log.w(TAG, "No way to call: no cellular service and no internet")
                recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, cleanNumber)
                withContext(Dispatchers.Main) { onFailed(CallFailure.NO_CONNECTION) }
                return@launch
            }

            val decision = CallRouter.decide(
                phoneNumber = cleanNumber,
                threemaId = effectiveThreemaId,
                hasCellularService = hasCellularService,
                canCallThreemaInApp = canCallThreemaInApp,
                isRoaming = isRoaming,
                threemaOnlyWhenRoaming = threemaOnlyWhenRoaming
            )

            // Fixed for this call: a Threema call that will not start while roaming must not fall back
            // to the network the switch exists to keep her off.
            val mayFallBackToCellular = !(isRoaming && threemaOnlyWhenRoaming)

            if (decision == null) {
                val failure = if (!mayFallBackToCellular) {
                    Log.w(TAG, "No way to call: roaming, and Threema only while roaming, with no Threema for this person")
                    recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD, cleanNumber)
                    CallFailure.NOT_FROM_ABROAD
                } else if (cleanNumber == null && effectiveThreemaId == null) {
                    Log.w(TAG, "No way to call: no usable number or Threema ID")
                    CallFailure.NOTHING_TO_CALL
                } else {
                    Log.w(TAG, "No way to call: no cellular service and no in-app Threema")
                    recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, cleanNumber)
                    CallFailure.NO_CONNECTION
                }
                withContext(Dispatchers.Main) {
                    onFailed(failure)
                }
                return@launch
            }

            // Abroad, Threema is the only way, and without internet it cannot work either.
            if (decision.channel == CallChannel.THREEMA_IN_APP && !mayFallBackToCellular && !hasInternet) {
                Log.w(TAG, "No way to call: roaming, Threema only, and no internet")
                recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, cleanNumber)
                withContext(Dispatchers.Main) { onFailed(CallFailure.NO_CONNECTION) }
                return@launch
            }

            Log.i(TAG, "Placing call: channel=${decision.channel} reason=${decision.reason}")

            when (decision.channel) {
                CallChannel.THREEMA_IN_APP -> {
                    val started = manager?.startCall(decision.target) ?: false
                    if (!started) {
                        if (mayFallBackToCellular && cleanNumber != null) {
                            Log.w(TAG, "Threema call could not start; falling back to the telephone")
                            placeCellularCall(context, cleanNumber) {
                                recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, cleanNumber)
                                CoroutineScope(Dispatchers.Main).launch { onFailed(CallFailure.NO_CONNECTION) }
                            }
                        } else {
                            val failure = if (!mayFallBackToCellular) {
                                recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD, cleanNumber)
                                CallFailure.NOT_FROM_ABROAD
                            } else {
                                recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, cleanNumber)
                                CallFailure.NO_CONNECTION
                            }
                            withContext(Dispatchers.Main) {
                                onFailed(failure)
                            }
                        }
                    }
                }
                CallChannel.CELLULAR -> {
                    placeCellularCall(context, decision.target) {
                        recordCaregiverFault(context, CaregiverEventKind.CALL_FAILED_NO_CONNECTION, decision.target)
                        CoroutineScope(Dispatchers.Main).launch { onFailed(CallFailure.NO_CONNECTION) }
                    }
                }
            }
        }
    }

    private const val TAG = "CallPlacer"

    private fun recordCaregiverFault(context: Context, kind: CaregiverEventKind, subject: String?) {
        try {
            val repo = CaregiverLogRepository(AppDatabase.getInstance(context).caregiverEventDao())
            CoroutineScope(Dispatchers.IO).launch {
                repo.record(CaregiverEvent(kind = kind, at = System.currentTimeMillis(), subject = subject))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record caregiver fault", e)
        }
    }

    /**
     * The caregiver's switch, read from the settings as they stand. Before the settings document
     * has arrived this is the default, which is on — the cheap way round.
     */
    private fun threemaOnlyWhenRoaming(context: Context): Boolean =
        try {
            Messengers.settings(context).settings.value.threemaOnlyWhenRoaming
        } catch (e: Exception) {
            RemoteSettings().threemaOnlyWhenRoaming
        }

    private fun isEmergencyNumber(context: Context, number: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephony?.isEmergencyNumber(dialableNumber(number)) ?: false
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(dialableNumber(number))
            }
        } catch (e: Exception) {
            // Unreadable counts as an emergency number: it only means the telephone is used.
            true
        }

    private fun placeCellularCall(context: Context, rawNumber: String, onFailed: () -> Unit) {
        val phoneUri = Uri.parse("tel:${dialableNumber(rawNumber)}")

        if (placeThroughTelecom(context, phoneUri)) return
        placeThroughAnotherApp(context, phoneUri, onFailed)
    }

    /**
     * The number as `tel:` wants it. Blank once cleaned means the raw text was all punctuation, and
     * handing that on unchanged is better than dialling nothing: it reaches the dialler, where it
     * is visible and wrong, rather than failing silently here.
     */
    internal fun dialableNumber(rawNumber: String): String {
        val cleanNumber = rawNumber.replace(Regex("[^0-9+]"), "")
        return cleanNumber.ifEmpty { rawNumber }
    }

    /**
     * Ringing her family ourselves, rather than asking another app to do it.
     *
     * `ACTION_CALL` is not a system call — it is a request to whichever app claims the filter, and
     * this app deliberately claims none (an app that rings on another app's say-so can be made to
     * ring anybody). On her phone the claim belongs to Simple Phone, so the moment
     * gentle-connection takes the dialler role the two deadlock: Simple Phone receives the intent,
     * sees it is no longer the default dialler, and asks for the role back instead of dialling.
     * Pressing a face did nothing at all, with no error anywhere — measured on the test phone
     * 2026-08-17, five taps and not one call placed.
     *
     * `TelecomManager.placeCall` goes straight to the platform with no app in between, so this
     * works whichever app holds the role, and keeps working once gentle-connection holds it.
     *
     * @return whether the call was handed to the platform. False means "not from here" and never
     *   that the call failed — the caller still has [placeThroughAnotherApp] to try.
     */
    private fun placeThroughTelecom(context: Context, phoneUri: Uri): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
        if (granted != PackageManager.PERMISSION_GRANTED) return false

        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            telecom.placeCall(phoneUri, Bundle())
            true
        } catch (e: Exception) {
            // A device with no telephony throws here rather than answering the question.
            false
        }
    }

    /** The old road: ask whoever holds the filter. Still right when we may not place calls. */
    private fun placeThroughAnotherApp(context: Context, phoneUri: Uri, onFailed: () -> Unit) {
        val callIntent = Intent(Intent.ACTION_CALL, phoneUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(callIntent)
        } catch (e: Exception) {
            // No permission to dial, or no telephony at all. The dialler with the number already
            // in it is the next best thing: somebody sitting with her can press the green button.
            val dialIntent = Intent(Intent.ACTION_DIAL, phoneUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(dialIntent)
            } catch (e2: Exception) {
                onFailed()
            }
        }
    }
}
