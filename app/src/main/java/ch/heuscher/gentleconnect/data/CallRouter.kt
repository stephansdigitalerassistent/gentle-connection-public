package ch.heuscher.gentleconnect.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

enum class CallChannel { CELLULAR, THREEMA_IN_APP }

data class CallRoutingDecision(
    val channel: CallChannel,
    val target: String,      // phone number, or Threema ID
    val reason: String       // for logging only, never shown to her
)

object CallRouter {
    /**
     * Pure decision. No Android imports in this function.
     *
     * The telephone first, whenever it can ring. Maintainer's rule (2026-09-16): "always use normal
     * phone if signal is there". Threema is for when there is no mobile voice service, or no
     * number to ring.
     *
     * Never route to the external Threema application. An elderly, cognitively impaired person
     * gets confused when dumped into a third-party app with an unfamiliar UI. If in-app Threema
     * is unavailable, we either route over the telephone network or fail cleanly.
     *
     * Roaming is the exception, and a caregiver switch ([threemaOnlyWhenRoaming], on by default,
     * the 2026-09-16 rule): abroad a telephone call costs money per minute, and a Threema call over
     * Wi-Fi costs nothing. With the switch on and the phone roaming, a call goes over in-app
     * Threema or not at all — no quiet fallback to the expensive cellular network. Emergency
     * numbers never reach this function; [CallPlacer] dials them before asking.
     *
     * With neither cellular service nor in-app Threema, a number is still dialled: Wi-Fi calling
     * may carry it, and if not, the telephone system says so rather than a tap doing nothing.
     */
    fun decide(
        phoneNumber: String?,
        threemaId: String?,
        hasCellularService: Boolean,
        canCallThreemaInApp: Boolean = false,
        isRoaming: Boolean = false,
        threemaOnlyWhenRoaming: Boolean = false
    ): CallRoutingDecision? {
        val cleanThreemaId = threemaId?.trim()?.ifEmpty { null }
        val cleanPhoneNumber = phoneNumber?.trim()?.ifEmpty { null }

        if (isRoaming && threemaOnlyWhenRoaming) {
            return when {
                cleanThreemaId != null && canCallThreemaInApp ->
                    CallRoutingDecision(CallChannel.THREEMA_IN_APP, cleanThreemaId, "roaming")
                else -> null
            }
        }

        val threemaReason = if (cleanPhoneNumber == null) "no_phone_number" else "no_cellular_service"

        return when {
            cleanPhoneNumber != null && hasCellularService ->
                CallRoutingDecision(CallChannel.CELLULAR, cleanPhoneNumber, "cellular_service")
            cleanThreemaId != null && canCallThreemaInApp ->
                CallRoutingDecision(CallChannel.THREEMA_IN_APP, cleanThreemaId, threemaReason)
            cleanPhoneNumber != null ->
                CallRoutingDecision(CallChannel.CELLULAR, cleanPhoneNumber, "cellular_without_service")
            else -> null
        }
    }

    /**
     * Whether the mobile network can carry a voice call right now — registered for voice, which
     * is what "signal is there" means for ringing somebody. Mobile internet is irrelevant: it is
     * switched off whenever Wi-Fi works.
     *
     * Anything that cannot be read counts as service, so a failure here errs towards the
     * ordinary telephone, never away from it. The location permission [TelephonyManager.getServiceState]
     * also wants is held for the family map; without it Android only strips the cell identity.
     */
    @SuppressLint("MissingPermission")
    fun hasCellularVoiceService(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        if (granted != PackageManager.PERMISSION_GRANTED) return true
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return true
            val serviceState = telephonyManager.serviceState ?: return true
            serviceState.state == ServiceState.STATE_IN_SERVICE
        } catch (e: SecurityException) {
            true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Whether any network with working internet is up right now — Wi-Fi or mobile data, validated
     * by Android. Anything that cannot be read counts as internet, so a failure here never stops
     * a call from being tried.
     */
    fun hasInternet(context: Context): Boolean {
        return try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Whether the phone is registered on a network abroad — a foreign one, the case that costs
     * money per minute. Needs no permission. Anything that cannot be read counts as not roaming,
     * so a failure here leaves the ordinary telephone working rather than cutting it off.
     */
    fun isRoaming(context: Context): Boolean {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
            telephonyManager.isNetworkRoaming
        } catch (e: Exception) {
            false
        }
    }
}
