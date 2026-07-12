package com.replit.cameraapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio as AspectRatioIcon
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Package name of Samsung's stock Gallery app -- opened when the thumbnail is tapped. */
private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"

private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 10f)
private val TIMER_OPTIONS = listOf(0, 3, 10)

/** The photo aspect ratios the "sizes" control cycles through. */
private enum class CaptureAspect(val label: String) {
    FULL("Full"),
    RATIO_4_3("4:3"),
    RATIO_1_1("1:1"),
    RATIO_16_9("16:9");

    fun next(): CaptureAspect = entries[(ordinal + 1) % entries.size]
}

class MainActivity : ComponentActivity() {

    /**
     * Set by [CameraContent] to the current shutter action so the hardware volume
     * keys can trigger a capture, mirroring Samsung Camera's "volume key to shoot" behavior.
     */
    var onVolumeKeyPressed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraScreen()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            event?.repeatCount == 0
        ) {
            onVolumeKeyPressed?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // Consume the key-up too, otherwise the system still raises/lowers volume on release.
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        if (hasCameraPermission) {
            CameraContent()
        } else {
            PermissionRationale(onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            })
        }
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashOn by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var flipSpins by remember { mutableStateOf(0) }
    var capturedPop by remember { mutableStateOf(false) }
    var freezeFrame by remember { mutableStateOf<Bitmap?>(null) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var isCounting by remember { mutableStateOf(false) }
    var captureAspect by remember { mutableStateOf(CaptureAspect.FULL) }

    var imageCapture by remember {
        mutableStateOf(buildImageCapture(CaptureAspect.FULL))
    }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lensFacing, captureAspect) {
        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val newImageCapture = buildImageCapture(captureAspect)

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, newImageCapture)
            imageCapture = newImageCapture
            flashOn = false
            zoomRatio = 1f
        } catch (exc: Exception) {
            Log.e("CameraApp", "Failed to bind camera use cases", exc)
        }
    }

    LaunchedEffect(capturedPop) {
        if (capturedPop) {
            delay(220)
            capturedPop = false
        }
    }

    val runCapture: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        // Freeze the last preview frame instead of flashing the screen white, so the
        // user gets clear feedback that a photo was taken without a jarring white pulse.
        freezeFrame = previewView.bitmap
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        takePhoto(context, imageCapture, mainExecutor, captureAspect) { uri ->
            lastPhotoUri = uri
            capturedPop = true
        }
    }
    val currentRunCapture by rememberUpdatedState(runCapture)

    val performCapture: () -> Unit = {
        if (isCounting) {
            // Ignore extra presses while a countdown is already running.
        } else if (timerSeconds > 0) {
            isCounting = true
            countdownValue = timerSeconds
        } else {
            currentRunCapture()
        }
    }
    val currentPerformCapture by rememberUpdatedState(performCapture)

    LaunchedEffect(isCounting, countdownValue) {
        if (isCounting) {
            if (countdownValue > 0) {
                delay(1000)
                countdownValue -= 1
            } else {
                isCounting = false
                currentRunCapture()
            }
        }
    }

    LaunchedEffect(freezeFrame) {
        if (freezeFrame != null) {
            delay(500)
            freezeFrame = null
        }
    }

    // Let the hardware volume keys act as a physical shutter button, like Samsung Camera.
    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        activity?.onVolumeKeyPressed = { currentPerformCapture() }
        onDispose { activity?.onVolumeKeyPressed = null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Frozen still of the last preview frame, shown briefly after a capture so the
        // user can tell a photo was taken -- no white flash, just a short, calm pause.
        freezeFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top-left: settings gear, reserved for future options.
        PressableIconButton(
            modifier = Modifier.align(Alignment.TopStart).padding(top = 20.dp, start = 20.dp),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // Placeholder for now -- settings menu content comes in a later pass.
            }
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
        }

        // Top-right: timer + flash toggle, side by side.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PressableIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val currentIndex = TIMER_OPTIONS.indexOf(timerSeconds).coerceAtLeast(0)
                    timerSeconds = TIMER_OPTIONS[(currentIndex + 1) % TIMER_OPTIONS.size]
                }
            ) {
                Icon(
                    imageVector = if (timerSeconds > 0) Icons.Filled.Timer else Icons.Filled.TimerOff,
                    contentDescription = "Self-timer: ${if (timerSeconds > 0) "${timerSeconds}s" else "off"}",
                    tint = if (timerSeconds > 0) Color(0xFFFFD54F) else Color.White
                )
            }

            AnimatedVisibility(
                visible = camera?.cameraInfo?.hasFlashUnit() == true,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PressableIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // Flash no longer stays lit as a torch -- it only fires for the
                        // instant a photo is captured, like a normal camera flash.
                        flashOn = !flashOn
                    }
                ) {
                    Icon(
                        imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (flashOn) "Turn flash off" else "Turn flash on",
                        tint = if (flashOn) Color(0xFFFFD54F) else Color.White
                    )
                }
            }
        }

        // Countdown overlay while a timed capture is pending.
        if (isCounting && countdownValue > 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = countdownValue.toString(),
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AspectRatioSelector(
                current = captureAspect,
                onSelect = { captureAspect = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
            ZoomSelector(
                current = zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onSelect = { ratio ->
                    zoomRatio = ratio
                    camera?.cameraControl?.setZoomRatio(ratio)
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            CameraControls(
                modifier = Modifier.fillMaxWidth(),
                lastPhotoUri = lastPhotoUri,
                thumbnailPop = capturedPop,
                flipSpins = flipSpins,
                onThumbnailClick = { openGallery(context, lastPhotoUri) },
                onCaptureClick = performCapture,
                onFlipClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    flipSpins += 1
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }
            )
        }
    }
}

/** Builds an [ImageCapture] use case targeting the given aspect ratio ("Full" leaves it unset). */
private fun buildImageCapture(aspect: CaptureAspect): ImageCapture {
    val builder = ImageCapture.Builder()
    when (aspect) {
        CaptureAspect.RATIO_4_3 -> builder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
        CaptureAspect.RATIO_16_9 -> builder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
        // 1:1 has no native CameraX aspect ratio -- captured at 4:3 then cropped to a
        // square after saving. "Full" leaves the sensor's default ratio untouched.
        CaptureAspect.RATIO_1_1 -> builder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
        CaptureAspect.FULL -> Unit
    }
    return builder.build()
}

@Composable
private fun AspectRatioSelector(current: CaptureAspect, onSelect: (CaptureAspect) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { onSelect(current.next()) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            AspectRatioIcon,
            contentDescription = "Photo size",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(text = current.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ZoomSelector(current: Float, minZoom: Float, maxZoom: Float, onSelect: (Float) -> Unit) {
    val available = ZOOM_PRESETS.filter { it in minZoom..maxZoom || it == 1f }
    if (available.size <= 1) return

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        available.forEach { preset ->
            val selected = kotlin.math.abs(current - preset) < 0.05f
            val scale by animateFloatAsState(if (selected) 1.08f else 1f, label = "zoomScale")

            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onSelect(preset) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val label = if (preset < 1f) "${preset}x" else "${preset.roundToInt()}x"
                Text(
                    text = label,
                    color = if (selected) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun CameraControls(
    modifier: Modifier = Modifier,
    lastPhotoUri: Uri?,
    thumbnailPop: Boolean,
    flipSpins: Int,
    onThumbnailClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFlipClick: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbnailButton(uri = lastPhotoUri, pop = thumbnailPop, onClick = onThumbnailClick)
        ShutterButton(onClick = onCaptureClick)
        FlipButton(spins = flipSpins, onClick = onFlipClick)
    }
}

/** A round icon button that visibly scales down while pressed. */
@Composable
private fun PressableIconButton(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.85f else 1f, label = "iconButtonScale")

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun ThumbnailButton(uri: Uri?, pop: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else if (pop) 1.15f else 1f,
        animationSpec = tween(durationMillis = if (pop) 140 else 100),
        label = "thumbnailScale"
    )

    Box(
        modifier = Modifier
            .size(52.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = "Open gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Open gallery", tint = Color.White)
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val outerScale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "shutterOuterScale")
    val innerScale by animateFloatAsState(if (isPressed) 0.8f else 1f, label = "shutterInnerScale")

    Box(
        modifier = Modifier
            .size(78.dp)
            .scale(outerScale)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(innerScale)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun FlipButton(spins: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (isPressed) 0.85f else 1f, label = "flipPressScale")
    val rotation by animateFloatAsState(
        targetValue = spins * 180f,
        animationSpec = tween(durationMillis = 350),
        label = "flipRotation"
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Cameraswitch,
            contentDescription = "Switch camera",
            tint = Color.White,
            modifier = Modifier.rotate(rotation)
        )
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access is needed to take photos.", color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) { Text("Grant permission") }
        }
    }
}

/** Awaits [ProcessCameraProvider.getInstance] without blocking the calling thread. */
private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    aspect: CaptureAspect,
    onSaved: (Uri) -> Unit
) {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CameraApp")
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri ?: return
                if (aspect == CaptureAspect.RATIO_1_1) {
                    Thread {
                        cropToSquare(context, uri)
                        executor.execute { onSaved(uri) }
                    }.start()
                } else {
                    onSaved(uri)
                }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraApp", "Photo capture failed", exc)
            }
        }
    )
}

/** Center-crops the saved image at [uri] down to a square, in place. */
private fun cropToSquare(context: Context, uri: Uri) {
    try {
        val original = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return

        val side = minOf(original.width, original.height)
        val x = (original.width - side) / 2
        val y = (original.height - side) / 2
        val cropped = Bitmap.createBitmap(original, x, y, side, side)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
    } catch (exc: Exception) {
        Log.e("CameraApp", "Failed to crop photo to 1:1", exc)
    }
}

/**
 * Opens the small thumbnail's target gallery app. Tries the Samsung Gallery
 * package ([SAMSUNG_GALLERY_PACKAGE]) first, since that's the icon this app mirrors;
 * falls back to whatever gallery/photos app is available on the device.
 */
private fun openGallery(context: Context, lastPhotoUri: Uri?) {
    val samsungGalleryIntent = context.packageManager.getLaunchIntentForPackage(SAMSUNG_GALLERY_PACKAGE)

    val intent = samsungGalleryIntent ?: Intent(Intent.ACTION_VIEW).apply {
        type = "image/*"
        lastPhotoUri?.let { data = it }
    }

    try {
        context.startActivity(intent)
    } catch (exc: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        } catch (inner: ActivityNotFoundException) {
            Log.e("CameraApp", "No gallery app available on this device", inner)
        }
    }
}
