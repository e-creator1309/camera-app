package com.replit.cameraapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.Surface as AndroidSurface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AspectRatio as AspectRatioIcon
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CropFree
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Package name of Samsung's stock Gallery app -- opened when the thumbnail is tapped. */
private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"

private val TIMER_OPTIONS = listOf(0, 3, 10)

/** How long the front camera's simulated screen-flash stays lit before the shutter fires. */
private const val FRONT_FLASH_DURATION_MS = 1000L

/** The photo aspect ratios the "sizes" control cycles through. */
private enum class CaptureAspect(val label: String) {
    FULL("Full"),
    RATIO_4_3("4:3"),
    RATIO_1_1("1:1"),
    RATIO_16_9("16:9");

    fun next(): CaptureAspect = entries[(ordinal + 1) % entries.size]
}

/** Which full-screen page is currently shown over the camera preview. */
private enum class Screen { CAMERA, SETTINGS }

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

    var screen by remember { mutableStateOf(Screen.CAMERA) }
    var scanDocumentsEnabled by remember { mutableStateOf(SettingsPreferences.isScanDocumentsEnabled(context)) }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.CAMERA }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The camera stays composed (and bound) underneath Settings, so zoom, flash,
                // timer, and the last-photo thumbnail aren't reset just from opening the gear menu.
                CameraContent(
                    scanDocumentsEnabled = scanDocumentsEnabled,
                    onOpenSettings = { screen = Screen.SETTINGS }
                )

                if (screen == Screen.SETTINGS) {
                    SettingsScreen(
                        scanDocumentsEnabled = scanDocumentsEnabled,
                        onScanDocumentsChanged = { enabled ->
                            scanDocumentsEnabled = enabled
                            SettingsPreferences.setScanDocumentsEnabled(context, enabled)
                        },
                        onBack = { screen = Screen.CAMERA }
                    )
                }
            }
        } else {
            PermissionRationale(onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            })
        }
    }
}

@Composable
private fun CameraContent(
    scanDocumentsEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()

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
    var isBusy by remember { mutableStateOf(false) }
    var frontFlashActive by remember { mutableStateOf(false) }
    var captureAspect by remember { mutableStateOf(CaptureAspect.FULL) }
    var detectedDocument by remember { mutableStateOf<DetectedDocument?>(null) }
    var zoomIndicatorPulse by remember { mutableIntStateOf(0) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }

    // Document scanning only runs on the back camera -- the front camera's mirrored preview
    // would need its own coordinate handling, and scanning a document via selfie camera isn't
    // a real use case, so this keeps the feature simple and correct rather than half-right.
    val scanActive = scanDocumentsEnabled && lensFacing == CameraSelector.LENS_FACING_BACK

    val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    // Two-finger pinch drives zoom continuously (no fixed steps) -- the zoom indicator below
    // pops up while pinching and lingers for ~2.5s after the last change before fading out,
    // instead of a permanently docked zoom row.
    val zoomTransformableState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange != 1f) {
            val newZoom = (zoomRatio * zoomChange).coerceIn(minZoom, maxZoom)
            zoomRatio = newZoom
            camera?.cameraControl?.setZoomRatio(newZoom)
            zoomIndicatorPulse += 1
        }
    }

    LaunchedEffect(zoomIndicatorPulse) {
        if (zoomIndicatorPulse > 0) {
            zoomIndicatorVisible = true
            delay(2500)
            zoomIndicatorVisible = false
        }
    }

    var imageCapture by remember {
        mutableStateOf(buildImageCapture(CaptureAspect.FULL))
    }
    val previewView = remember { PreviewView(context) }
    val documentAnalysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { documentAnalysisExecutor.shutdown() }
    }

    LaunchedEffect(lensFacing, captureAspect, scanDocumentsEnabled) {
        detectedDocument = null

        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val newImageCapture = buildImageCapture(captureAspect)

        val scanForThisBind = scanDocumentsEnabled && lensFacing == CameraSelector.LENS_FACING_BACK
        val imageAnalysis = if (scanForThisBind) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(
                        documentAnalysisExecutor,
                        DocumentEdgeAnalyzer(previewView) { result -> detectedDocument = result }
                    )
                }
        } else {
            null
        }

        val useCaseGroupBuilder = UseCaseGroup.Builder()
        useCaseGroupBuilder.addUseCase(preview)
        useCaseGroupBuilder.addUseCase(newImageCapture)
        imageAnalysis?.let { useCaseGroupBuilder.addUseCase(it) }

        // A shared ViewPort keeps the analysis, preview, and capture crop rects aligned to the
        // same field of view, which is what makes the live document outline line up correctly.
        if (previewView.width > 0 && previewView.height > 0) {
            val rotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
            useCaseGroupBuilder.setViewPort(
                ViewPort.Builder(Rational(previewView.width, previewView.height), rotation).build()
            )
        }

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroupBuilder.build())
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

    LaunchedEffect(freezeFrame) {
        if (freezeFrame != null) {
            delay(500)
            freezeFrame = null
        }
    }

    val performCapture: () -> Unit = capture@{
        if (isBusy) return@capture
        scope.launch {
            isBusy = true
            try {
                // Self-timer countdown, shown full-screen before the shutter fires.
                if (timerSeconds > 0) {
                    for (secondsLeft in timerSeconds downTo 1) {
                        countdownValue = secondsLeft
                        delay(1000)
                    }
                    countdownValue = 0
                }

                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                // Front cameras usually have no physical flash, so simulate one by
                // lighting up the whole screen for a second before the photo is taken.
                val useFrontScreenFlash = lensFacing == CameraSelector.LENS_FACING_FRONT && flashOn
                if (useFrontScreenFlash) {
                    frontFlashActive = true
                    delay(FRONT_FLASH_DURATION_MS)
                } else {
                    // Freeze the last preview frame instead of a white pulse, so the
                    // user gets clear feedback that a photo was taken without a flash.
                    freezeFrame = previewView.bitmap
                }

                imageCapture.flashMode = if (!useFrontScreenFlash && flashOn) {
                    ImageCapture.FLASH_MODE_ON
                } else {
                    ImageCapture.FLASH_MODE_OFF
                }

                val documentCorners = detectedDocument?.normalizedCorners
                val uri = capturePhoto(context, imageCapture, mainExecutor, captureAspect)

                if (useFrontScreenFlash) {
                    frontFlashActive = false
                }

                if (uri != null) {
                    // If a document was framed at the moment of capture, straighten and crop
                    // to it -- "Full" only, so it doesn't fight with the 4:3/1:1/16:9 crops.
                    if (scanActive && captureAspect == CaptureAspect.FULL && documentCorners != null) {
                        applyDocumentScanCrop(context, uri, documentCorners)
                    }
                    lastPhotoUri = uri
                    capturedPop = true
                }
            } finally {
                isBusy = false
            }
        }
    }
    val currentPerformCapture by rememberUpdatedState(performCapture)

    // Let the hardware volume keys act as a physical shutter button, like Samsung Camera.
    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        activity?.onVolumeKeyPressed = { currentPerformCapture() }
        onDispose { activity?.onVolumeKeyPressed = null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = zoomTransformableState)
        )

        DocumentScanOverlay(
            document = if (scanActive) detectedDocument else null,
            modifier = Modifier.fillMaxSize()
        )

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

        // Front-camera screen flash: lights the whole screen white as a makeshift flash.
        if (frontFlashActive) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        if (scanDocumentsEnabled) {
            DocumentScanBadge(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                scanActive = scanActive,
                documentDetected = detectedDocument != null
            )
        }

        // Top-right: settings, timer, and flash toggle all together in one row, beside the flash icon.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PressableIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenSettings()
                }
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }

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

            // Back camera needs a physical flash unit; front camera always offers the
            // simulated screen-flash, so its button is never gated on hasFlashUnit().
            val flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true ||
                lensFacing == CameraSelector.LENS_FACING_FRONT
            AnimatedVisibility(
                visible = flashAvailable,
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
        if (countdownValue > 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = countdownValue.toString(),
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Pinch-to-zoom indicator: hidden by default, pops up beside the preview while the
        // user pinches with two fingers and fades out ~2.5s after the last change.
        PinchZoomIndicator(
            visible = zoomIndicatorVisible,
            zoomRatio = zoomRatio,
            minZoom = minZoom,
            maxZoom = maxZoom,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
        )

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

/** Draws a live outline over a document/paper detected by [DocumentEdgeAnalyzer], if any. */
@Composable
private fun DocumentScanOverlay(document: DetectedDocument?, modifier: Modifier = Modifier) {
    val points = document?.previewPoints ?: return
    if (points.size != 4) return

    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            lineTo(points[1].x, points[1].y)
            lineTo(points[2].x, points[2].y)
            lineTo(points[3].x, points[3].y)
            close()
        }
        drawPath(path, color = SamsungBlue.copy(alpha = 0.16f), style = Fill)
        drawPath(path, color = SamsungBlue, style = Stroke(width = 4.dp.toPx()))
    }
}

/** Small pill near the top of the screen confirming Scan documents mode and its live state. */
@Composable
private fun DocumentScanBadge(
    modifier: Modifier = Modifier,
    scanActive: Boolean,
    documentDetected: Boolean
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CropFree,
            contentDescription = null,
            tint = if (documentDetected) SamsungBlue else Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = when {
                !scanActive -> "Switch to back camera to scan"
                documentDetected -> "Document detected"
                else -> "Scanning for documents"
            },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
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
            Icons.Filled.AspectRatioIcon,
            contentDescription = "Photo size",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(text = current.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Transient pinch-to-zoom readout: a vertical track that fills up to the current zoom
 * level, plus a numeric label. Only shown for a couple of seconds around an active pinch
 * gesture -- there's no permanently docked zoom row anymore, matching how a two-finger pinch
 * behaves in most stock camera apps.
 */
@Composable
private fun PinchZoomIndicator(
    visible: Boolean,
    zoomRatio: Float,
    minZoom: Float,
    maxZoom: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val range = (maxZoom - minZoom).coerceAtLeast(0.01f)
        val fraction = ((zoomRatio - minZoom) / range).coerceIn(0f, 1f)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "%.1fx", zoomRatio),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SamsungBlue)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(200.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(50))
                        .background(SamsungBlue)
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

/** Takes a photo and suspends until it is saved, returning the saved [Uri] or null on failure. */
private suspend fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    aspect: CaptureAspect
): Uri? = suspendCancellableCoroutine { continuation ->
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
                val uri = output.savedUri
                if (uri != null && aspect == CaptureAspect.RATIO_1_1) {
                    cropToSquare(context, uri)
                }
                if (continuation.isActive) continuation.resume(uri)
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraApp", "Photo capture failed", exc)
                if (continuation.isActive) continuation.resume(null)
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
 * Straightens and crops the saved image at [uri] to the document quadrilateral described by
 * [normalizedCorners] (0..1 fractions, ordered [topLeft, topRight, bottomRight, bottomLeft]),
 * using a perspective warp -- the same "photo scanner" effect apps like this are modeled on,
 * done here with plain [android.graphics.Matrix] instead of a native computer-vision dependency
 * that can't be verified in this build environment.
 */
private fun applyDocumentScanCrop(context: Context, uri: Uri, normalizedCorners: List<PointF>) {
    if (normalizedCorners.size != 4) return
    try {
        val original = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return

        val w = original.width.toFloat()
        val h = original.height.toFloat()
        val tl = PointF(normalizedCorners[0].x * w, normalizedCorners[0].y * h)
        val tr = PointF(normalizedCorners[1].x * w, normalizedCorners[1].y * h)
        val br = PointF(normalizedCorners[2].x * w, normalizedCorners[2].y * h)
        val bl = PointF(normalizedCorners[3].x * w, normalizedCorners[3].y * h)

        fun dist(a: PointF, b: PointF): Float {
            val dx = a.x - b.x; val dy = a.y - b.y
            return kotlin.math.hypot(dx, dy)
        }

        val outWidth = ((dist(tl, tr) + dist(bl, br)) / 2f).roundToInt().coerceIn(200, original.width)
        val outHeight = ((dist(tl, bl) + dist(tr, br)) / 2f).roundToInt().coerceIn(200, original.height)

        val source = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val destination = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat()
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return // degenerate quad -- keep original

        val straightened = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        AndroidCanvas(straightened).drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            straightened.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
    } catch (exc: Exception) {
        Log.e("CameraApp", "Failed to apply document scan crop", exc)
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
