package ch.heuscher.gentleconnect.voip

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.speech.TtsManager
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.theme.GentleTheme
import kotlinx.coroutines.delay

/**
 * Full-screen feedback when a call tap fails immediately.
 *
 * For a cognitively impaired user, silent failure after tapping a contact creates profound confusion:
 * did the phone register the tap? Is it broken? Is somebody answering?
 *
 * This screen displays a clear explanation in large type over the keyguard, displays the contact's
 * picture or initials, and reads the explanation aloud once, at the caregiver's speech rate, if the
 * caregiver switched that on (off by default).
 *
 * Dismisses automatically after 10 seconds, or immediately when touched anywhere.
 */
class CallFailedActivity : ComponentActivity() {

    private var ttsManager: TtsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnAndKeyguardOff()

        val messageResId = intent.getIntExtra(EXTRA_MESSAGE_RES_ID, R.string.call_failed_no_connection)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)?.ifBlank { null }
        val contactPhotoUri = intent.getStringExtra(EXTRA_CONTACT_PHOTO_URI)?.ifBlank { null }

        val displayName = contactName?.let { PersonName.forHer(it) } ?: contactName

        val messageText = if (messageResId == R.string.call_failed_not_from_abroad) {
            val who = displayName ?: getString(R.string.person_unknown)
            getString(messageResId, who)
        } else {
            getString(messageResId)
        }

        // Read aloud only if the caregiver switched it on (RemoteSettings.speakCallFailures, off
        // by default): the card itself is always shown.
        val settings = try {
            Messengers.settings(applicationContext).settings.value
        } catch (e: Exception) {
            null
        }
        if (settings?.speakCallFailures == true) {
            ttsManager = TtsManager(applicationContext).apply {
                setSpeechRate(settings.speechRate)
                speak(messageText)
            }
        }

        setContent {
            GentleTheme {
                LaunchedEffect(Unit) {
                    delay(DISMISS_TIMEOUT_MS)
                    finish()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            finish()
                        }
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val displayName = contactName?.let { PersonName.forHer(it) } ?: contactName
                        if (!displayName.isNullOrBlank()) {
                            ContactAvatar(
                                photoUri = contactPhotoUri,
                                name = displayName,
                                modifier = Modifier.size(140.dp),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                textStyle = MaterialTheme.typography.displayMedium
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }

                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        ttsManager = null
        super.onDestroy()
    }

    private fun turnScreenOnAndKeyguardOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    companion object {
        const val EXTRA_MESSAGE_RES_ID = "EXTRA_MESSAGE_RES_ID"
        const val EXTRA_CONTACT_NAME = "EXTRA_CONTACT_NAME"
        const val EXTRA_CONTACT_PHOTO_URI = "EXTRA_CONTACT_PHOTO_URI"
        private const val DISMISS_TIMEOUT_MS = 10_000L

        fun start(
            context: Context,
            messageResId: Int,
            contactName: String? = null,
            contactPhotoUri: String? = null
        ) {
            val intent = Intent(context, CallFailedActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_RES_ID, messageResId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_PHOTO_URI, contactPhotoUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
