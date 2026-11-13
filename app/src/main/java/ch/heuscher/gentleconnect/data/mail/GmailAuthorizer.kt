package ch.heuscher.gentleconnect.data.mail

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * The `gmail.readonly` grant her post is read with.
 *
 * No password is stored anywhere. The caregiver grants access once, on her phone, for the Google
 * account already signed in there; from then on Play services hands out fresh access tokens on
 * request, in the background, for as long as the grant stands. The OAuth client is the Android
 * one in the gentle-connection project, matched by package name and signing certificate.
 */
object GmailAuthorizer {

    const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"

    fun request(address: String): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_READONLY)))
            .setAccount(Account(address.trim(), GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE))
            .build()

    /**
     * An access token without asking anybody anything, or null when the grant is missing and
     * only the consent screen could produce one. Never shows UI: safe from a worker.
     */
    suspend fun silentToken(context: Context, address: String): String? {
        val result = Identity.getAuthorizationClient(context.applicationContext)
            .authorize(request(address))
            .await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /**
     * Drops a token Gmail rejected, so the next [silentToken] mints a new one rather than
     * handing back the same dead one from the cache. Blocking; call off the main thread.
     */
    fun clearToken(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context.applicationContext, token) }
    }
}
