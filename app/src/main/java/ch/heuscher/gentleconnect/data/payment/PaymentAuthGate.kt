package ch.heuscher.gentleconnect.data.payment

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import ch.heuscher.gentleconnect.model.PaymentButtonState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Answers one question: would a card payment be taken if she held the phone to the terminal now?
 *
 * Asked, not assumed. Nothing here counts down and nothing here remembers that she typed a PIN
 * a moment ago — every answer is the phone's own answer, read fresh.
 */
interface PaymentAuthGate {
    /**
     * Suspending, and the thread is the implementation's business: the real one talks to the
     * keystore and takes itself off the main thread to do it, a test's does not have to pretend to.
     */
    suspend fun state(): PaymentButtonState
}

/**
 * The real gate, standing on the Android keystore.
 *
 * It keeps one key that is useless unless the phone has been strongly unlocked within
 * [WINDOW_SECONDS] — strong meaning the device credential or a Class 3 biometric, the same bar
 * Google Wallet's payment key is held to, and the bar that 2D face unlock does not clear. Trying
 * to use that key is the whole test: it works, or it throws [UserNotAuthenticatedException], and
 * those two outcomes are [PaymentButtonState.Ready] and [PaymentButtonState.Idle].
 *
 * Reading the state this way rather than running our own timer is what makes the card honest
 * across the cases a timer gets wrong: the phone sleeping in her pocket on the way to the shop
 * (a handler-based countdown does not run while the device is suspended, the keystore's clock
 * does), and her unlocking the phone with her PIN at the till without touching our button at all
 * — which arms the key, and so lights the card, with nothing for her to remember.
 */
class KeystorePaymentAuthGate(context: Context) : PaymentAuthGate {

    private val appContext = context.applicationContext

    private val keyguardManager: KeyguardManager?
        get() = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    override suspend fun state(): PaymentButtonState = withContext(Dispatchers.IO) {
        // No PIN, pattern or password on the phone: there is no strong unlock for a payment key
        // to stand on, and Wallet could not pay from this phone either. Say so, once, rather
        // than offering her a button that would fail at the till.
        if (keyguardManager?.isDeviceSecure != true) return@withContext PaymentButtonState.Unavailable

        try {
            val key = loadOrCreateKey()
            Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key)
            PaymentButtonState.Ready
        } catch (e: UserNotAuthenticatedException) {
            // The ordinary "not now": the last strong unlock is too old. Her PIN fixes it.
            PaymentButtonState.Idle
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Somebody changed the lock screen. The old key is scrap; the next read makes a new
            // one, and until she has authenticated once more the answer is the same anyway.
            deleteKey()
            PaymentButtonState.Idle
        } catch (e: InvalidAlgorithmParameterException) {
            // The keystore refuses a user-authentication key on a phone with no secure lock.
            // isDeviceSecure above should have caught that; this is the same answer by another road.
            PaymentButtonState.Unavailable
        } catch (e: Exception) {
            // Anything else — a keystore that is busy, a vendor quirk — is treated as "ask her
            // for the PIN", never as "ready". Wrong in the safe direction: the worst it costs is
            // a PIN entry she did not strictly need.
            PaymentButtonState.Idle
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Spelled out on 30 and up: the device credential or a strong biometric, and nothing
            // weaker. A Class 2 face unlock leaves this key locked, which is precisely the case
            // the card exists to make visible.
            builder.setUserAuthenticationParameters(
                WINDOW_SECONDS,
                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(WINDOW_SECONDS)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gentleconnect.payment.freshness"
        private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"

        /**
         * How long a strong unlock keeps the card lit, in seconds.
         *
         * Ours to choose — Wallet does not publish its own window — but chosen once, here, and
         * enforced by the keystore against the phone's real clock rather than by a counter in the
         * app. Five minutes is a walk from the queue to the till with the phone asleep in between.
         */
        private const val WINDOW_SECONDS = 300
    }
}
