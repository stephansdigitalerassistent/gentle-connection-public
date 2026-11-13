package ch.heuscher.gentleconnect.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import ch.heuscher.gentleconnect.ui.components.GentleKey
import ch.heuscher.gentleconnect.ui.components.ReadAloudKey
import ch.heuscher.gentleconnect.ui.components.GentlePrimaryKey
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.model.Attachment
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.SpokenWords
import ch.heuscher.gentleconnect.model.StatusContent
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.WideScreenGripMargin
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import ch.heuscher.gentleconnect.model.ViewerNavigation
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.components.DecodedPhoto
import ch.heuscher.gentleconnect.ui.components.InlineFilm
import ch.heuscher.gentleconnect.ui.components.StatusWords
import ch.heuscher.gentleconnect.ui.components.SupportButton
import ch.heuscher.gentleconnect.ui.components.fitToWidth
import ch.heuscher.gentleconnect.ui.components.formatTimeInWords
import ch.heuscher.gentleconnect.ui.components.rememberZoomAtTap
import kotlinx.coroutines.launch

/**
 * Everything one person put up in one go, opened on the whole screen, one picture at a time.
 *
 * The album card in her post shows one picture large and three small, which is a way of saying
 * "there is more here" and not a way of looking at it. This is where she looks: the picture as
 * large as the glass allows, uncropped, and the same three ways on to the next one — a swipe, the
 * arrow buttons, or a press on the right or left third of the picture. Three ways because each
 * of them fails somebody: a swipe is the gesture her hand least reliably makes, the buttons are
 * at the bottom where her thumb is not, and the thirds are invisible until someone shows her.
 * Nothing wraps round. On the first picture the back arrow closes the viewer (she reads <- as
 * "go back"); on the last picture the forward arrow closes the viewer too ("I have seen them all,
 * I am done"). Neither button is ever disabled: a disabled, unresponsive button is a silent
 * trap that invites repeated pressing and triggers agitation alarms (evidence from her Galaxy
 * Fold, where tapping a disabled <- five times triggered BURST_TAPS).
 *
 * ## The top row
 *
 * The top-left corner is the round green back arrow and the top-right is the help button ("?"),
 * matching where they sit on every other screen. The album viewer used to have neither: the
 * top-left showed the poster's face, which did nothing when tapped, and there was no help button
 * at all. Her Galaxy Fold recorded a burst of taps on this screen on 1 Oct at 12:29:59; what she
 * was pressing is not known, but it was the one screen where the corner she goes back from held
 * something else. The face now stands beside the arrow, still touching the name it belongs to.
 *
 * A film is a page like any other, with a Play button on its first frame; see [InlineFilm] for
 * why it plays here rather than in the full-screen player a conversation uses.
 *
 * Silent about her, as the whole Look screen is: turning a page marks that picture seen on this
 * phone only — see [ch.heuscher.gentleconnect.data.SeenPictures] — and nobody who posted learns
 * anything.
 */
@Composable
fun BurstViewer(
    burst: LookItem.Picture,
    startIndex: Int,
    onPictureSeen: (String) -> Unit,
    onClose: () -> Unit,
    /** Read a status's words aloud — the same voice a letter uses. */
    onSpeakRequested: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onForwardRequested: ((Message) -> Unit)?,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    modifier: Modifier = Modifier
) {
    val count = burst.messages.size
    val pagerState = rememberPagerState(initialPage = startIndex) { count }
    var playingPage by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    val current = burst.messages[pagerState.currentPage]

    BackHandler { onClose() }

    // Light icons in the phone's own bars while this is up. The glass behind them is black here,
    // and the app's usual dark icons on black are a clock she cannot read — the same repair
    // [FilmPlayer] makes for its window. Put back exactly as they were on the way out.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) bars?.isAppearanceLightStatusBars = lightStatus
            if (lightNavigation != null) bars?.isAppearanceLightNavigationBars = lightNavigation
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (playingPage != pagerState.currentPage) playingPage = null
    }

    LaunchedEffect(pagerState.settledPage) {
        onPictureSeen(burst.messages[pagerState.settledPage].id)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top row
        Row(
            modifier = Modifier
                // Above the viewer's pager: the support button's "?" hangs below this row.
                .zIndex(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GentleKey(
                onClick = onClose,
                modifier = Modifier.width(56.dp),
                face = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                minHeight = 56.dp,
                contentPadding = PaddingValues(0.dp),
                contentAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_back),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            ContactAvatar(
                photoUri = burst.photoUri,
                name = personName(current.senderName),
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val name = personName(current.senderName)
                // The name must never be cut on the folded cover screen (the window is 334dp wide
                // there and her system font scale is 1.3, leaving roughly 115dp for the name).
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = name,
                        color = Color.White,
                        style = fitToWidth(
                            texts = listOf(name),
                            available = maxWidth,
                            base = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            minSize = 16.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val time = formatTimeInWords(current.timestamp)
                val subtitle = if (count > 1) {
                    val position = stringResource(R.string.viewer_position, pagerState.currentPage + 1, count)
                    "$time · $position"
                } else {
                    time
                }
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
            }
            Spacer(Modifier.width(8.dp))
            val gripMargin = if (isWideWindow()) WideScreenGripMargin else 0.dp
            SupportButton(
                state = support,
                onClick = onSupportPressed,
                isRestless = isRestless
            )
            if (gripMargin > 0.dp) {
                Spacer(Modifier.width(gripMargin))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Middle
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { burst.messages[it].id },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val message = burst.messages[page]
            val kind = StatusContent.of(message)
            val words = messageInWords(message.text)
            // Words written on a picture go under it, never across it. Words that are the whole
            // status are the page, and have no caption because they are nothing but caption.
            val caption = if (kind == StatusContent.WORDS) "" else Attachment.captionIn(message.text)
            val readable = if (kind == StatusContent.WORDS) words else caption
            val spoken = stringResource(
                R.string.status_read_aloud_format,
                personName(message.senderName),
                readable
            ).trim()

            // A press on the left third goes back, on the right third goes on, and in the middle
            // does nothing. Laid on the page, and laid again over a playing film, whose view would
            // otherwise take the touch for itself.
            // Per page: each page is its own place on the screen while the pager slides.
            val zoomAtTap = rememberZoomAtTap()
            val turnByThirds = Modifier.pointerInput(pagerState, count) {
                detectTapGestures(
                    // Magnifies like everywhere else; it never closes the album, so a double touch
                    // from her tremor cannot take the pictures away. The page no longer turns on the
                    // first tap of a double one, which is what makes the two compatible.
                    onDoubleTap = zoomAtTap::zoomAt,
                    onTap = { offset ->
                        val zone = ViewerNavigation.zoneOf(offset.x, size.width.toFloat())
                        val target = ViewerNavigation.target(pagerState.currentPage, zone, count)
                        if (target != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(target) }
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize().then(zoomAtTap.modifier).then(turnByThirds)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (message.videoPath == null || playingPage != page) {
                            if (kind == StatusContent.WORDS) {
                                // Scrolled rather than cut off when somebody wrote a paragraph.
                                StatusWords(
                                    text = words,
                                    large = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .verticalScroll(rememberScrollState())
                                )
                            } else {
                                DecodedPhoto(
                                    path = message.photoPath,
                                    videoPath = message.videoPath,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (message.videoPath != null) {
                                GentlePrimaryKey(
                                    onClick = {
                                        onStopSpeaking()
                                        playingPage = page
                                    },
                                    modifier = Modifier.align(Alignment.Center),
                                    shape = RoundedCornerShape(16.dp),
                                    minHeight = 72.dp
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.viewer_play),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        } else {
                            InlineFilm(
                                path = message.videoPath,
                                onEnded = { playingPage = null },
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(modifier = Modifier.fillMaxSize().then(turnByThirds))
                        }
                    }

                    if (caption.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        // A band under the picture, and scrolled when it is long: the picture
                        // keeps most of the glass however much somebody wrote under it.
                        Text(
                            text = caption,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    // The voice for whatever there is to read on this page, and only where
                    // there are words to read: a picture with no caption, or one captioned with a
                    // face and nothing else, gets no button — see [SpokenWords].
                    if (SpokenWords.worthSaying(readable)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ReadAloudKey(
                            text = spoken,
                            onSpeakRequested = { text ->
                                // One voice at a time: a film on this page stops for the reading.
                                playingPage = null
                                onSpeakRequested(text)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Forward button
        if (onForwardRequested != null && Forwarding.mediaOf(current) != null) {
            GentleKey(
                onClick = { onForwardRequested(current) },
                // The gap first, outside the button: padding after the height would be taken out of
                // the button itself and leave it 40dp tall.
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .fillMaxWidth(),
                face = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp),
                minHeight = 56.dp,
                fillWidth = true
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.forward_label),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        // Bottom row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (count == 1) {
                GentlePrimaryKey(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 72.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                val isFirst = pagerState.currentPage == 0
                val isLast = pagerState.currentPage == count - 1

                GentlePrimaryKey(
                    onClick = {
                        if (isFirst) {
                            onClose()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 72.dp,
                    fillWidth = true
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(
                            if (isFirst) R.string.viewer_back else R.string.viewer_previous
                        ),
                        modifier = Modifier.size(40.dp)
                    )
                }

                GentlePrimaryKey(
                    onClick = onClose,
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 72.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                GentlePrimaryKey(
                    onClick = {
                        if (isLast) {
                            onClose()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 72.dp,
                    fillWidth = true
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(
                            if (isLast) R.string.viewer_done else R.string.viewer_next
                        ),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

