package ch.heuscher.gentleconnect.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.OwnPhotoStore
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.components.GentleKey
import ch.heuscher.gentleconnect.ui.components.GentlePrimaryKey
import ch.heuscher.gentleconnect.ui.components.GentleSecondaryKey
import ch.heuscher.gentleconnect.ui.components.gentleDialogOpacity
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import ch.heuscher.gentleconnect.util.traceTaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How long she can walk away from the camera screen before it dismisses itself (2 minutes). */
private const val CAMERA_TIMEOUT_MILLIS = 120_000

/**
 * Full-screen camera interface for taking and sending a photograph directly to a contact.
 *
 * ## Design for cognitive decline
 * The screen presents one decision at a time with huge touch targets:
 * - **Taking (live)**: Who this photo is for, a live camera preview, a prominent round shutter
 *   button in the centre, a "Zurück" key to cancel, and an optional camera-flip button if allowed
 *   and available. Back camera is always selected by default.
 * - **Reviewing**: Freezes the prepared photo in place (the exact EXIF-stripped image that will be
 *   sent) with two clear choices: "Nochmals" (discard and retake) and "Schicken" (send).
 *
 * If she puts the phone down and walks away for 2 minutes, [gentleDialogOpacity] gently fades out
 * and dismisses the screen, ensuring the phone never remains stuck in camera mode indefinitely.
 */
@Composable
fun CameraScreen(
    recipient: Recipient? = null,
    cameraSwitchEnabled: Boolean,
    onSend: suspend (path: String) -> Boolean = { false },
    onSent: () -> Unit = {},
    onPhotoReady: ((path: String) -> Unit)? = null,
    /**
     * Whether the photo handed on by [onPhotoReady] has gone, asked when the screen closes. A
     * question rather than a value: the send that makes it true is also what closes the screen,
     * so the screen is never drawn again to be told, and a sent photo is the one her own copy of
     * the message still shows.
     */
    isSent: () -> Boolean = { false },
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission handling
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Whether Android's own question has been answered yet. Until it has, the screen stays blank:
    // showing "not allowed" behind the system dialog would tell her no before she has said yes.
    var permissionAnswered by remember { mutableStateOf(hasCameraPermission) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        permissionAnswered = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Prepared photo path: null = Taking state; non-null = Reviewing state
    var preparedPhotoPath by remember { mutableStateOf<String?>(null) }
    var preparedBitmap by remember(preparedPhotoPath) { mutableStateOf<ImageBitmap?>(null) }
    var sentSuccessfully by remember { mutableStateOf(false) }

    // Interactivity timer: 2 minutes of idle dismisses the screen
    var timerKey by remember { mutableIntStateOf(0) }
    val resetTimer = { timerKey++ }

    // Clean up temporary photo if dismissed without sending
    val sentElsewhere by rememberUpdatedState(isSent)
    DisposableEffect(Unit) {
        onDispose {
            if (!sentSuccessfully && !sentElsewhere()) {
                preparedPhotoPath?.let { File(it).delete() }
            }
        }
    }

    BackHandler {
        preparedPhotoPath?.let { File(it).delete() }
        preparedPhotoPath = null
        onClose()
    }

    // Load prepared bitmap for previewing
    LaunchedEffect(preparedPhotoPath) {
        val path = preparedPhotoPath
        if (path != null) {
            preparedBitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        } else {
            preparedBitmap = null
        }
    }

    if (!hasCameraPermission && !permissionAnswered) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .traceTaps("camera")
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_denied),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                GentleSecondaryKey(
                    onClick = onClose,
                    minHeight = 56.dp
                ) {
                    Text(
                        text = stringResource(R.string.camera_back),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        return
    }

    // CameraX lifecycle and provider
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var hasBothCameras by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    // awaitInstance rather than getInstance: the future getInstance returns is a Guava type this
    // module cannot see at compile time, and a suspend call needs no listener anyway.
    LaunchedEffect(Unit) {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        cameraProvider = provider
        hasBothCameras = try {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        } catch (_: Exception) {
            false
        }
    }

    DisposableEffect(lifecycleOwner, cameraProvider) {
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    LaunchedEffect(cameraProvider, previewView, lensFacing) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pView = previewView ?: return@LaunchedEffect

        try {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        } catch (_: Exception) {}
    }

    val canSwitchCamera = cameraSwitchEnabled && hasBothCameras

    val takePhoto = {
        val capture = imageCapture
        if (!isCapturing && capture != null) {
            isCapturing = true
            try {
                val rawFile = File.createTempFile("camera_raw_", ".jpg", context.cacheDir)
                val outputOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()
                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            scope.launch {
                                try {
                                    val path = OwnPhotoStore.prepare(context, rawFile)
                                    preparedPhotoPath = path
                                } catch (e: Exception) {
                                    rawFile.delete()
                                    Toast.makeText(context, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                                } finally {
                                    isCapturing = false
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            rawFile.delete()
                            isCapturing = false
                            Toast.makeText(context, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } catch (e: Exception) {
                isCapturing = false
                Toast.makeText(context, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val retakeAction = {
        preparedPhotoPath?.let { File(it).delete() }
        preparedPhotoPath = null
    }

    val sendAction = {
        val path = preparedPhotoPath
        if (path != null && !isSending) {
            isSending = true
            scope.launch {
                val success = onSend(path)
                isSending = false
                if (success) {
                    sentSuccessfully = true
                    onSent()
                } else {
                    Toast.makeText(context, R.string.camera_send_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val switchCameraAction = {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    // Only the wait restarts on a press. Keying the whole screen would rebuild the PreviewView,
    // rebind the camera and kill a picture that is still being taken.
    val opacity = key(timerKey) { gentleDialogOpacity(CAMERA_TIMEOUT_MILLIS, onClose) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .graphicsLayer { alpha = opacity }
            .traceTaps("camera")
    ) {
        if (isWideWindow()) {
            // Wide window layout: preview and header on left, action buttons as vertical column on right
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Title row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (recipient != null) {
                            ContactAvatar(
                                photoUri = recipient.photoUri,
                                name = recipient.name,
                                modifier = Modifier.size(52.dp)
                            )
                            val name = personName(recipient.name)
                            val title = if (preparedPhotoPath == null) {
                                stringResource(R.string.camera_photo_for, name)
                            } else {
                                stringResource(R.string.camera_send_to_question, name)
                            }
                            Text(
                                text = title,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        } else {
                            val title = if (preparedPhotoPath == null) {
                                stringResource(R.string.camera_take_photo_title)
                            } else {
                                stringResource(R.string.camera_photo_is_good)
                            }
                            Text(
                                text = title,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    // Preview / photo container
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (preparedPhotoPath == null) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).apply {
                                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                        scaleType = PreviewView.ScaleType.FILL_CENTER
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { view -> previewView = view }
                            )
                        } else {
                            preparedBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }

                // Right column: keys
                if (preparedPhotoPath == null) {
                    Column(
                        modifier = Modifier
                            .width(160.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GentleSecondaryKey(
                            onClick = {
                                resetTimer()
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minHeight = 56.dp
                        ) {
                            Text(
                                text = stringResource(R.string.camera_back),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        GentleKey(
                            onClick = {
                                resetTimer()
                                takePhoto()
                            },
                            modifier = Modifier.size(88.dp),
                            face = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            minHeight = 88.dp,
                            contentPadding = PaddingValues(0.dp),
                            enabled = !isCapturing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.camera_take_photo),
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        if (canSwitchCamera) {
                            GentleSecondaryKey(
                                onClick = {
                                    resetTimer()
                                    switchCameraAction()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minHeight = 56.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cameraswitch,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.camera_switch),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(56.dp))
                        }
                    }
                } else {
                    // Reviewing: Schicken/Weiter above Nochmals
                    Column(
                        modifier = Modifier
                            .width(160.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (recipient != null) {
                            GentlePrimaryKey(
                                onClick = {
                                    resetTimer()
                                    sendAction()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minHeight = 64.dp,
                                enabled = !isSending
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.camera_send),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            GentlePrimaryKey(
                                onClick = {
                                    resetTimer()
                                    preparedPhotoPath?.let { path ->
                                        onPhotoReady?.invoke(path)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minHeight = 64.dp
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_next),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        GentleSecondaryKey(
                            onClick = {
                                resetTimer()
                                retakeAction()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minHeight = 64.dp
                        ) {
                            Text(
                                text = stringResource(R.string.camera_retake),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            // Narrow layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp)
            ) {
                // Title row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (recipient != null) {
                        ContactAvatar(
                            photoUri = recipient.photoUri,
                            name = recipient.name,
                            modifier = Modifier.size(52.dp)
                        )
                        val name = personName(recipient.name)
                        val title = if (preparedPhotoPath == null) {
                            stringResource(R.string.camera_photo_for, name)
                        } else {
                            stringResource(R.string.camera_send_to_question, name)
                        }
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    } else {
                        val title = if (preparedPhotoPath == null) {
                            stringResource(R.string.camera_take_photo_title)
                        } else {
                            stringResource(R.string.camera_photo_is_good)
                        }
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Preview / photo container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (preparedPhotoPath == null) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view -> previewView = view }
                        )
                    } else {
                        preparedBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }

                // Bottom controls
                if (preparedPhotoPath == null) {
                    // Taking state: [Zurück]   ( ◉ )   [⟲]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            GentleSecondaryKey(
                                onClick = {
                                    resetTimer()
                                    onClose()
                                },
                                minHeight = 56.dp
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_back),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        GentleKey(
                            onClick = {
                                resetTimer()
                                takePhoto()
                            },
                            modifier = Modifier.size(88.dp),
                            face = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            minHeight = 88.dp,
                            contentPadding = PaddingValues(0.dp),
                            enabled = !isCapturing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.camera_take_photo),
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (canSwitchCamera) {
                                GentleSecondaryKey(
                                    onClick = {
                                        resetTimer()
                                        switchCameraAction()
                                    },
                                    minHeight = 56.dp
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Cameraswitch,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.camera_switch),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.width(56.dp))
                            }
                        }
                    }
                } else {
                    // Reviewing state: [ Nochmals ]   [ Schicken / Weiter ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        GentleSecondaryKey(
                            onClick = {
                                resetTimer()
                                retakeAction()
                            },
                            modifier = Modifier.weight(1f),
                            minHeight = 64.dp
                        ) {
                            Text(
                                text = stringResource(R.string.camera_retake),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (recipient != null) {
                            GentlePrimaryKey(
                                onClick = {
                                    resetTimer()
                                    sendAction()
                                },
                                modifier = Modifier.weight(1f),
                                minHeight = 64.dp,
                                enabled = !isSending
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.camera_send),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            GentlePrimaryKey(
                                onClick = {
                                    resetTimer()
                                    preparedPhotoPath?.let { path ->
                                        onPhotoReady?.invoke(path)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                minHeight = 64.dp
                            ) {
                                Text(
                                    text = stringResource(R.string.camera_next),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
