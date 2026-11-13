package ch.heuscher.gentleconnect.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportMember
import ch.heuscher.gentleconnect.ui.components.LocalReadingAloud
import ch.heuscher.gentleconnect.model.SupportNames
import ch.heuscher.gentleconnect.model.SupportRequest
import ch.heuscher.gentleconnect.model.SupportRequestCooldown
import ch.heuscher.gentleconnect.model.SupportStatus
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.theme.PaleSage
import ch.heuscher.gentleconnect.ui.theme.SupportBlue
import ch.heuscher.gentleconnect.ui.theme.SupportBlueOnDark
import ch.heuscher.gentleconnect.ui.theme.WarmForestGreen
import kotlinx.coroutines.delay

/**
 * Full-screen status view shown after a support request is confirmed or when tapping
 * an active support button.
 *
 * It answers one question — who is coming — and moves only to answer it:
 * - Waiting: the faces a message reached, each with a slow breathing ring (the message is out
 *   there); faces it could not reach dimmed, so the faces and the names in the headline agree.
 *   Under the headline: "Wer zuerst kann, meldet sich bei dir."
 * - Claimed: the ring stops, the claimer's face grows to the middle with a green tick, the others
 *   shrink and fade, and the phone gives one short buzz in case she is not looking.
 * - Headline, one step at a time and only ever good news:
 *     "Nachricht geht an Anna und Johann …" → "Anna und Johann wissen Bescheid" →
 *     "Anna hat es gelesen" → "Anna schaut es mit dir an";
 *     "Das Telefon versucht es weiter" when nothing got through. Never "ich": in the family's
 *     messages "ich" is her, and a screen saying "ich" in the phone's voice is a second speaker.
 * - No call button (removed 2026-09-18): dialling is what she finds hard. They call her.
 * - "Vorlesen", "Noch einmal Bescheid geben" (after 5 min) and "Zurück".
 * - With the system's "remove animations" setting, the final state without the motion.
 */
@Composable
fun SupportStatusScreen(
    request: SupportRequest?,
    members: List<SupportMember>,
    onSpeakRequested: (String) -> Unit,
    onReAlert: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Gone from under her — closed on the portal, or two hours up. Back to where she came from
    // rather than a screen that says nothing is happening.
    var hadRequest by remember { mutableStateOf(false) }
    LaunchedEffect(request == null) {
        if (request != null) hadRequest = true
        else if (hadRequest) onBack()
    }

    val claimant = request?.claimedBy?.let { id -> members.firstOrNull { it.id == id } }
    val claimantName = claimant?.name ?: request?.claimedByName
    val claimed = request?.status == SupportStatus.CLAIMED && claimantName != null
    val reader = request?.deliveries
        ?.firstOrNull { it.state == "read" }
        ?.let { d -> members.firstOrNull { it.id == d.memberId } }
    val reachedIds = request?.deliveries
        ?.filter { it.state == "sent" || it.state == "read" }
        ?.map { it.memberId }
        ?.toSet()
        .orEmpty()
    // Everything tried for this member failed, and nothing is still on its way.
    val unreachableIds = request?.deliveries
        ?.groupBy { it.memberId }
        ?.filterValues { tries -> tries.all { it.state == "failed" } }
        ?.keys
        .orEmpty()
    // By name, and only the ones something actually went to. "Your family has been told" was on
    // the screen while one of three SMS had failed; the names she sees are the people who know.
    val reached = members.filter { it.id in reachedIds }.map { SupportNames.shortName(it.name) }
    val allFailed = request != null && request.deliveries.isNotEmpty() && reached.isEmpty()
    val andWord = stringResource(R.string.support_names_and)
    val everyone = SupportNames.join(members.map { SupportNames.shortName(it.name) }, andWord)

    // Only ever good news, one step at a time; never an error. See docs/support-button.md, 3.3.
    val headline = when {
        claimed -> stringResource(R.string.support_status_claimed, SupportNames.shortName(claimantName!!))
        reader != null -> stringResource(R.string.support_status_read, SupportNames.shortName(reader.name))
        reached.size == 1 -> stringResource(R.string.support_status_sent, reached.first())
        reached.size > 1 -> stringResource(R.string.support_status_sent_many, SupportNames.join(reached, andWord))
        allFailed -> stringResource(R.string.support_status_trying)
        members.isNotEmpty() -> stringResource(R.string.support_status_sending_to, everyone)
        else -> stringResource(R.string.support_status_sending)
    }
    // Who is coming, before anybody has said so: whoever can first. Not "Anna meldet sich
    // gleich" after a read receipt — reading it is not a promise.
    val subline = if (!claimed && reached.isNotEmpty()) stringResource(R.string.support_status_waiting_hint) else null

    // One buzz when somebody takes it while she has the screen open — she may not be looking.
    val haptics = LocalHapticFeedback.current
    var sawUnclaimed by remember { mutableStateOf(false) }
    LaunchedEffect(claimed) {
        if (!claimed) sawUnclaimed = true
        else if (sawUnclaimed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val context = LocalContext.current
    val motion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ringBlue = if (isDark) SupportBlueOnDark else SupportBlue
    val ringGreen = if (isDark) PaleSage else WarmForestGreen

    // Slow and faint: about three seconds a breath. Not a spinner and not ticking dots — those
    // read as "loading" or as a clock, and she is already anxious.
    val breathing = rememberInfiniteTransition(label = "SupportWaiting")
    val breath by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SupportWaitingBreath"
    )

    val lastAlertAt = request?.repeats?.lastOrNull() ?: request?.createdAt ?: 0L
    // Counted from the last time anybody was told, and simply not offered inside the five minutes:
    // a greyed-out button with a countdown is a clock ticking at somebody who is already anxious.
    val canReAlert = request != null && SupportRequestCooldown.canReAlert(lastAlertAt, nowMillis)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            // Above the system navigation bar: without the inset "Zurück" sat half under it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canReAlert) {
                    OutlinedButton(
                        onClick = onReAlert,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.support_status_re_alert),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.support_status_back),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Faces, centred while they fit and sliding sideways once they do not. The short name
            // under each: the address book's full "Johann "Papa Opa" Hartmann" ran off the edge.
            if (members.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        // The padding is inside the scroll, which clips at its edges: without it
                        // the breathing halo of the first and last face was cut off at the side.
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The one who is coming in the middle of the row, not wherever the
                        // portal happened to list them.
                        val ordered = if (claimant == null) members else {
                            val others = members.filter { it.id != claimant.id }
                            val half = others.size / 2
                            others.take(half) + claimant + others.drop(half)
                        }
                        ordered.forEach { member ->
                            SupportFace(
                                member = member,
                                role = when {
                                    claimed && member.id == claimant?.id -> FaceRole.COMING
                                    claimed -> FaceRole.STOOD_DOWN
                                    member.id in reachedIds -> FaceRole.REACHED
                                    member.id in unreachableIds -> FaceRole.UNREACHABLE
                                    else -> FaceRole.PENDING
                                },
                                breath = if (motion) breath else 0.5f,
                                animate = motion,
                                ringBlue = ringBlue,
                                ringGreen = ringGreen
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            if (subline != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = subline,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // The same size, shape and weight as "Zurück": as a thin outlined button it did not
            // look like something to press.
            val spoken = if (subline != null) "$headline. $subline" else headline
            // While the voice is reading this, the button says so — see [ReadAloudKey].
            val reading = LocalReadingAloud.current == spoken
            Button(
                onClick = { onSpeakRequested(spoken) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (reading) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            ) {
                if (!reading) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }
                Text(
                    text = stringResource(
                        if (reading) R.string.read_aloud_stop else R.string.support_status_read_aloud
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private enum class FaceRole { PENDING, REACHED, UNREACHABLE, COMING, STOOD_DOWN }

/** One member's face and short name, sized and ringed by what they are in this request. */
@Composable
private fun SupportFace(
    member: SupportMember,
    role: FaceRole,
    breath: Float,
    animate: Boolean,
    ringBlue: Color,
    ringGreen: Color
) {
    val targetSize: Dp = when (role) {
        FaceRole.COMING -> 160.dp
        FaceRole.STOOD_DOWN -> 56.dp
        else -> 88.dp
    }
    val targetAlpha = when (role) {
        FaceRole.UNREACHABLE -> 0.4f
        FaceRole.STOOD_DOWN -> 0.5f
        else -> 1f
    }
    val size by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(if (animate) 600 else 0, easing = FastOutSlowInEasing),
        label = "SupportFaceSize"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(if (animate) 600 else 0),
        label = "SupportFaceAlpha"
    )

    Column(
        modifier = Modifier.alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            ContactAvatar(
                photoUri = member.buttonPhotoUri,
                name = member.name,
                modifier = Modifier
                    .size(size)
                    .drawBehind {
                        if (role == FaceRole.REACHED) {
                            // The breath: a halo swelling a few dp out from the face and back.
                            val grow = (4 + 8 * breath).dp.toPx()
                            drawCircle(
                                color = ringBlue.copy(alpha = 0.18f + 0.22f * (1f - breath)),
                                radius = this.size.minDimension / 2 + grow
                            )
                        }
                    }
                    .then(
                        when (role) {
                            FaceRole.REACHED -> Modifier.border(4.dp, ringBlue, CircleShape)
                            FaceRole.COMING -> Modifier.border(6.dp, ringGreen, CircleShape)
                            else -> Modifier
                        }
                    )
            )
            if (role == FaceRole.COMING) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(48.dp)
                        .background(ringGreen, CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        Text(
            text = SupportNames.shortName(member.name),
            style = if (role == FaceRole.STOOD_DOWN) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
