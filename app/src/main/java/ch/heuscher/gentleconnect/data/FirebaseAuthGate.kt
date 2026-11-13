package ch.heuscher.gentleconnect.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Gates Firestore work on the device being signed in.
 *
 * The device signs in anonymously, and that happens asynchronously in
 * [FirestoreSettingsRepository]. Anything that touches Firestore before it completes is
 * rejected by the security rules — and a snapshot listener that has been denied is NOT
 * retried by the SDK, it just stays silent forever. That failure is invisible: no crash,
 * no error on screen, simply no messages and no people, permanently.
 *
 * So repositories wait here instead of attaching at construction time.
 *
 * This deliberately never calls `signInAnonymously()` itself. Sign-in is owned by
 * [FirestoreSettingsRepository]; racing it could mint a second anonymous account and leave
 * the device document pointing at the wrong `deviceUid`.
 */
object FirebaseAuthGate {

    /**
     * Runs [block] once a user is present, and again on every later sign-in. Calls
     * [onSignedOut] when the user goes away so callers can drop stale listeners.
     */
    fun whenSignedIn(onSignedOut: () -> Unit = {}, block: () -> Unit) {
        Firebase.auth.addAuthStateListener { auth ->
            if (auth.currentUser != null) block() else onSignedOut()
        }
    }

    /**
     * Suspends until the device is signed in. Returns false if that has not happened within
     * [timeoutMs], so a caller can report failure rather than hanging on a tap.
     */
    suspend fun awaitSignedIn(timeoutMs: Long = 10_000L): Boolean {
        val auth = Firebase.auth
        if (auth.currentUser != null) return true

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : FirebaseAuth.AuthStateListener {
                    override fun onAuthStateChanged(updated: FirebaseAuth) {
                        if (updated.currentUser != null) {
                            updated.removeAuthStateListener(this)
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
                }
                auth.addAuthStateListener(listener)
                continuation.invokeOnCancellation { auth.removeAuthStateListener(listener) }
            }
        } ?: false
    }
}
