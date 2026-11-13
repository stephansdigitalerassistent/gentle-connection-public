package ch.heuscher.gentleconnect.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch

/**
 * Her people, on her wrist.
 *
 * The whole app. A list of the faces the phone sent, one question, and a call — plus one line at
 * the bottom that makes the phone shout so she can find it.
 *
 * It is not a small copy of the phone app and must never become one. No messages, no mail, no
 * settings, no address book: a wrist is a glance and a press. Everything the watch cannot do in
 * one press belongs on the phone in her pocket, which is the thing this is a remote control for.
 *
 * The question before the call is the same question the phone asks and exists for the same
 * reason, only more so: a watch is worn, and a sleeve pulled over it presses whatever is under
 * the sleeve.
 */
class WatchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchApp() }
    }
}

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WatchFaces(context) }
    val phone = remember { WatchPhone(context) }

    var faces by remember { mutableStateOf<List<WatchFace>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<WatchFace?>(null) }
    var unreachable by remember { mutableStateOf(false) }

    // What the phone has already sent. There may be no new delivery to wait for — the phone
    // could have sent this list a week ago — so what is stored has to be read outright.
    LaunchedEffect(Unit) {
        faces = repository.load()
        loaded = true
    }

    // And a change while she is looking at it. Without this, starring somebody new on the phone
    // left the watch showing yesterday's people until it was closed and opened again.
    DisposableEffect(Unit) {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events ->
            events.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach
                if (event.dataItem.uri.path != WearMessages.FACES_PATH) return@forEach
                // Frozen because the buffer is recycled the moment this callback returns, and
                // the read below happens on another thread afterwards.
                val item = DataMapItem.fromDataItem(event.dataItem.freeze())
                scope.launch { faces = repository.read(item) }
            }
        }
        client.addListener(listener)
        onDispose { client.removeListener(listener) }
    }

    MaterialTheme {
        val asked = asking
        when {
            asked != null -> AskFirst(
                face = asked,
                onYes = {
                    asking = null
                    scope.launch { unreachable = !phone.ring(asked) }
                },
                onNo = { asking = null }
            )
            unreachable -> PhoneNotThere(onClose = { unreachable = false })
            else -> Faces(
                faces = faces,
                loaded = loaded,
                onFace = { asking = it },
                onFindPhone = { scope.launch { unreachable = !phone.findPhone() } }
            )
        }
    }
}

@Composable
private fun Faces(
    faces: List<WatchFace>,
    loaded: Boolean,
    onFace: (WatchFace) -> Unit,
    onFindPhone: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(faces) { face ->
                FaceChip(face = face, onClick = { onFace(face) })
            }

            // Last, under the people, because it is about the phone and not about anybody. It is
            // here at all because it is the one thing a watch can do that a phone cannot do for
            // itself.
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onFindPhone,
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.watch_find_phone),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }

            // Only once the phone has been asked. Saying "nobody yet" while the list is still
            // being read would be the watch stating something it does not know.
            if (loaded && faces.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.watch_nobody),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/** One person: their face if there is one, their name, and nothing else. */
@Composable
private fun FaceChip(face: WatchFace, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
        icon = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.surface),
                contentAlignment = Alignment.Center
            ) {
                val photo = face.photo
                if (photo != null) {
                    Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        label = {
            Text(
                text = face.name,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/**
 * "Shall I ring Karolina?" — the phone's question, on a smaller screen.
 *
 * A screen rather than a dialog over the list: at this size a dialog is a box covering almost
 * everything with the list showing round its edges, which reads as two things at once.
 */
@Composable
private fun AskFirst(face: WatchFace, onYes: () -> Unit, onNo: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.watch_call_question, face.name),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // "No" first, on the left, where a thumb sliding off the small screen lands. The
                // wrong one of these two costs her a call to somebody at an odd hour; the other
                // costs one more press.
                Button(
                    onClick = onNo,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.watch_no)
                    )
                }
                Button(onClick = onYes) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.watch_yes)
                    )
                }
            }
        }
    }
}

/**
 * The phone is not answering the watch.
 *
 * Said plainly, because it is the one failure the watch genuinely knows about and the one that
 * pressing again will not fix. Everything else — a call that could not be routed, a number that
 * turned out to be nothing — happens over at the phone and is not reported back: the watch would
 * be telling her about a thing she is not looking at.
 */
@Composable
private fun PhoneNotThere(onClose: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.watch_phone_not_there),
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onClose,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.watch_ok)
                )
            }
        }
    }
}
