package ch.heuscher.gentleconnect.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.model.PhoneFix
import ch.heuscher.gentleconnect.model.PhoneLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Where the phone is, asked the cheapest way that answers.
 *
 * Nothing here is on a timer of its own. It is asked once per heartbeat — every fifteen minutes,
 * from [FirestoreSettingsRepository] — and does nothing at all in between. That is the whole
 * design: this is a phone somebody depends on getting through a day on one charge, and a launcher
 * that quietly holds a location subscription open is a launcher that flattens it by the afternoon.
 *
 * Two sources, in this order:
 *
 *  1. **What is already known.** Every provider keeps its last reading, and asking for it costs
 *     nothing — no radio, no satellites, no delay. On a phone sitting in a flat this is usually
 *     the right answer already.
 *  2. **One fresh reading**, and only when the known ones are not current. Preferably from the
 *     network provider, which answers indoors in seconds off wifi and cell towers, to within a
 *     street. GPS is the fallback rather than the choice: it is the accurate one and it is also
 *     the one that needs a view of the sky and a minute of radio to say so.
 *
 * Both are folded through [PhoneLocation.betterOf], which is where the actual judgement lives and
 * the only part of this that CI can test — everything in this file is the Android framework.
 *
 * **Without the location permission this returns null, for ever, silently.** No prompt, no error,
 * no entry in the caregiver log. She is not the person who can grant it and the caregiver reading
 * the portal sees the position line simply not appear, which is the truth: the phone is not
 * saying where it is.
 *
 * Which of the two grants is held decides what this is worth. With plain location it can only look
 * while she has the screen on, so a phone in a coat pocket since breakfast answers with breakfast;
 * with "all the time" — [Manifest.permission.ACCESS_BACKGROUND_LOCATION], asked for separately in
 * `MainActivity` — every heartbeat can go and look. Nothing in this file branches on that. It asks
 * the same question either way and the operating system decides how good an answer it gets, which
 * is why the age of the fix travels with it rather than being assumed.
 */
class PhoneLocationSource(private val context: Context) {

    private val manager: LocationManager?
        get() = ContextCompat.getSystemService(context, LocationManager::class.java)

    /**
     * The best position available now, or null if there is none to be had.
     *
     * Suspends for at most [FRESH_FIX_TIMEOUT_MS], and usually not at all.
     */
    suspend fun current(): PhoneFix? {
        val manager = manager ?: return null
        if (!hasPermission()) return null

        val known = lastKnown(manager)
        if (known != null && System.currentTimeMillis() - known.at <= FRESH_ENOUGH_MS) {
            return known
        }
        return PhoneLocation.betterOf(known, freshFix(manager))
    }

    /**
     * The best of every provider's remembered reading.
     *
     * Asks all of them rather than picking one, because which provider holds the newest reading
     * depends entirely on what the phone happened to be doing: a maps app leaves a GPS fix behind,
     * a wifi scan leaves a network one, and neither is predictable from here.
     *
     * The permission is checked in [current], which is the only caller. Lint cannot follow that,
     * hence the suppression — and the `runCatching` underneath it means that even if the check
     * were somehow wrong, the result is null rather than a crash.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): PhoneFix? =
        runCatching {
            manager.getProviders(true).fold(null as PhoneFix?) { best, provider ->
                val remembered = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                PhoneLocation.betterOf(best, remembered?.toFix())
            }
        }.getOrNull()

    /**
     * Ask a provider to go and look, and give up if it does not answer in time.
     *
     * The timeout is not a formality. Indoors, GPS never answers at all — the request simply stays
     * open, and without a bound this would hold a listener and a radio open until the next
     * heartbeat replaced it with another one.
     *
     * The subscription is removed on the first reading and on cancellation, which includes the
     * timeout expiring. There is no path out of here that leaves it running.
     *
     * Permission-checked by [current], as [lastKnown] is, and wrapped for the same reason.
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshFix(manager: LocationManager): PhoneFix? {
        val provider = preferredProvider(manager) ?: return null
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<PhoneFix?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (continuation.isActive) continuation.resume(location.toFix())
                    }

                    // Empty, and required: these three only gained default implementations in
                    // API 30, and this app runs from 26.
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }

                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }

                val requested = runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
                if (requested.isFailure && continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** The cheap provider if it is switched on, the expensive one if it is the only one left. */
    private fun preferredProvider(manager: LocationManager): String? = runCatching {
        when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }.getOrNull()

    /**
     * Either grant will do.
     *
     * Coarse alone is enough to answer the question this feature exists for — whether the phone is
     * at her flat — and a phone where only coarse was granted should still answer it rather than
     * going silent about a permission it half has.
     */
    private fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * `hasAccuracy` is asked rather than assumed: [Location.getAccuracy] returns 0.0 when nothing
     * was measured, and 0 metres would otherwise read as a perfect fix and win every comparison
     * it entered.
     */
    private fun Location.toFix() = PhoneFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy.roundToInt() else null,
        at = time
    )

    private companion object {
        /**
         * How current a remembered reading has to be to end the question without asking anyone.
         *
         * A shade under the heartbeat interval, so that a phone whose providers are being kept
         * warm by something else on the device never triggers a request of its own.
         */
        const val FRESH_ENOUGH_MS = 10L * 60 * 1000

        /** How long a provider gets to answer before the heartbeat goes without it. */
        const val FRESH_FIX_TIMEOUT_MS = 30L * 1000
    }
}
