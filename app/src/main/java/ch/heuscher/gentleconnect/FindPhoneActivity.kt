package ch.heuscher.gentleconnect

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.ui.theme.GentleTheme

/**
 * The phone, answering "where are you?" from her watch.
 *
 * It is under a cushion, or in the other room, or in the pocket of the coat in the hall. This is
 * the one thing a watch can do that a phone cannot do for itself.
 *
 * Loud on purpose, and over the lock screen on purpose: the point is to be found from another
 * room, and a noise that respects the silent switch is a noise she will not hear. It stops when
 * she presses the button, and stops by itself after a minute if nobody ever finds it — a phone
 * ringing under a cushion all afternoon is worse than a phone that was never found.
 *
 * The screen says what is happening in one sentence. Somebody who picks this phone up has been
 * looking for it and knows why it is making a noise; what they need is the way to stop it, which
 * is the only thing on the screen.
 */
class FindPhoneActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        startMakingANoise()

        // The backstop. Nothing guarantees anybody presses the button — she may have asked the
        // watch from the sofa and then forgotten why.
        window.decorView.postDelayed({ finish() }, RINGS_FOR_MS)

        setContent {
            GentleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.find_phone_title),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { finish() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp)
                                .padding(top = 40.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.find_phone_stop),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startMakingANoise() {
        try {
            // The alarm stream, not the ringtone stream. A telephone left on silent still rings
            // its alarms, and a phone that cannot be found because it was on silent is a phone
            // this feature does nothing for.
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            // A phone with no ringtone at all still buzzes, below.
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(BUZZ_PATTERN, REPEAT_FROM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
        } catch (e: Exception) {
            // Some phones have no vibrator, and a phone found by ear is found.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
    }

    private companion object {
        /** Long enough to walk to the other room, short enough not to become a nuisance. */
        const val RINGS_FOR_MS = 60_000L

        /** Off, on, off, on — a pattern rather than a drone, so it carries through a cushion. */
        val BUZZ_PATTERN = longArrayOf(0, 600, 400)

        /** Which step of the pattern to loop back to, so it repeats until stopped. */
        const val REPEAT_FROM = 0
    }
}
