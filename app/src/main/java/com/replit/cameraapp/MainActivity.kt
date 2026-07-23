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
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RenderEffect as AndroidRenderEffect
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.OrientationEventListener
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
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio as AspectRatioIcon
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoFilter
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Package name of Samsung's stock Gallery app -- opened when the thumbnail is tapped. */
private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"

private val TIMER_OPTIONS = listOf(0, 3, 10)

/** Quick-select zoom levels shown as tap targets, in addition to continuous pinch-to-zoom.
 *  Filtered per-device to whatever range the current camera actually supports. */
private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 5f, 10f)

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

/** Live preview color filters, applied as a real-time [RenderEffect] on the camera feed. */
private enum class PhotoFilter(val label: String) {
    NONE("None"),
    MONO("Mono"),
    WARM("Warm"),
    COOL("Cool"),
    VIVID("Vivid");

    fun next(): PhotoFilter = entries[(ordinal + 1) % entries.size]
}

/**
 * The capture category shown in the row above the shutter. [PHOTO], [VIDEO], and [PORTRAIT] are
 * wired up to real camera behavior; [MORE] is a placeholder reserved for later work -- selecting
 * it is purely cosmetic and falls back to the same behavior as [PHOTO].
 */
private enum class CaptureMode(val label: String) {
    PHOTO("PHOTO"),
    VIDEO("VIDEO"),
    PORTRAIT("PORTRAIT"),
    MORE("MORE"),
    SCAN("SCAN")
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
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Audio permission can be requested separately later (e.g. the first time Video
        // mode is selected), so only touch hasCameraPermission when it was actually asked.
        if (results.containsKey(Manifest.permission.CAMERA)) {
            hasCameraPermission = results[Manifest.permission.CAMERA] == true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    var screen by remember { mutableStateOf(Screen.CAMERA) }
    var scanDocumentsEnabled by remember { mutableStateOf(SettingsPreferences.isScanDocumentsEnabled(context)) }
    var watermarkEnabled by remember { mutableStateOf(SettingsPreferences.isWatermarkEnabled(context)) }
    var smartEnhanceEnabled by remember { mutableStateOf(SettingsPreferences.isSmartEnhanceEnabled(context)) }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.CAMERA }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The camera stays composed (and bound) underneath Settings, so zoom, flash,
                // timer, and the last-photo thumbnail aren't reset just from opening the gear menu.
                CameraContent(
                    scanDocumentsEnabled = scanDocumentsEnabled,
                    watermarkEnabled = watermarkEnabled,
                    smartEnhanceEnabled = smartEnhanceEnabled,
                    onOpenSettings = { screen = Screen.SETTINGS }
                )

                if (screen == Screen.SETTINGS) {
                    SettingsScreen(
                        scanDocumentsEnabled = scanDocumentsEnabled,
                        onScanDocumentsChanged = { enabled ->
                            scanDocumentsEnabled = enabled
                            SettingsPreferences.setScanDocumentsEnabled(context, enabled)
                        },
                        watermarkEnabled = watermarkEnabled,
                        onWatermarkChanged = { enabled ->
                            watermarkEnabled = enabled
                            SettingsPreferences.setWatermarkEnabled(context, enabled)
                        },
                        smartEnhanceEnabled = smartEnhanceEnabled,
                        onSmartEnhanceChanged = { enabled ->
                            smartEnhanceEnabled = enabled
                            SettingsPreferences.setSmartEnhanceEnabled(context, enabled)
                        },
                        onBack = { screen = Screen.CAMERA }
                    )
                }
            }
        } else {
            PermissionRationale(onRequestPermission = {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            })
        }
    }
}

@Composable
private fun CameraContent(
    scanDocumentsEnabled: Boolean,
    watermarkEnabled: Boolean,
    smartEnhanceEnabled: Boolean,
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
    var photoFilter by remember { mutableStateOf(PhotoFilter.NONE) }
    var detectedDocument by remember { mutableStateOf<DetectedDocument?>(null) }
    var zoomIndicatorPulse by remember { mutableIntStateOf(0) }
    var zoomIndicatorVisible by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    // Physical device rotation — updated by OrientationEventListener below.
    // The UI stays portrait-locked (manifest); this drives the capture
    // targetRotation so the EXIF/container angle is correct even when the
    // device is held sideways for a landscape shot or video.
    var deviceRotation by remember { mutableIntStateOf(AndroidSurface.ROTATION_0) }

    // Pinch-gesture target zoom.  The raw gesture updates this immediately;
    // a native EMA smoothing loop (see LaunchedEffect below) steps the live
    // zoomRatio toward it at ~30 fps so the camera HAL is never flooded.
    var targetZoomRatio by remember { mutableFloatStateOf(1f) }

    // Requested the first time Video is selected, in case the initial permission prompt
    // (which also asks for it) was skipped because camera access was already granted from
    // a previous version of the app. Recording still works without it, just silently.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(captureMode) {
        if (captureMode == CaptureMode.VIDEO &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── ML Kit Document Scanner ──────────────────────────────────────────────
    val mlScannerOptions = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .setGalleryImportAllowed(false)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .build()

    val mlScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.forEach { page ->
                saveScanToGallery(context, page.imageUri)
            }
        }
    }

    val launchMlScanner: () -> Unit = {
        val activity = context as Activity
        GmsDocumentScanning.getClient(mlScannerOptions)
            .getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                mlScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.e("CameraApp", "ML Kit scanner failed to start", e)
            }
    }

    // Track physical device rotation so captured images and videos embed the
    // correct orientation metadata even though the UI stays portrait-locked.
    // Ranges: 0° = portrait-up, 90° = landscape-right, 180° = portrait-down,
    // 270° = landscape-left (matching Surface.ROTATION_* conventions).
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    orientation <= 45 || orientation > 315 -> AndroidSurface.ROTATION_0
                    orientation in 46..135                 -> AndroidSurface.ROTATION_270
                    orientation in 136..225                -> AndroidSurface.ROTATION_180
                    else                                    -> AndroidSurface.ROTATION_90
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // Document scanning only runs on the back camera -- the front camera's mirrored preview
    // would need its own coordinate handling, and scanning a document via selfie camera isn't
    // a real use case, so this keeps the feature simple and correct rather than half-right.
    val scanActive = scanDocumentsEnabled && lensFacing == CameraSelector.LENS_FACING_BACK

    val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    // Two-finger pinch drives zoom continuously (no fixed steps) -- the zoom indicator below
    // pops up while pinching and lingers for ~2.5s after the last change before fading out,
    // instead of a permanently docked zoom row. A row of tap-to-select presets (0.5x-10x,
    // see ZoomPresetRow) sits alongside it for quick, discoverable jumps.
    //
    // Pinch events only update targetZoomRatio — the actual setZoomRatio call is made by the
    // EMA smoothing loop below, which caps HAL calls to ~30fps.  Calling setZoomRatio on every
    // raw gesture event (~120fps on most devices) starves the camera pipeline and drops frames.
    val zoomTransformableState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange != 1f) {
            targetZoomRatio = (targetZoomRatio * zoomChange).coerceIn(minZoom, maxZoom)
            zoomIndicatorPulse += 1
        }
    }

    // Native EMA zoom smoother — steps the live zoomRatio toward the pinch target at ~30fps.
    // Each targetZoomRatio change restarts the effect, which is the rate-limiting mechanism:
    // rapid pinch events keep restarting the coroutine, but only one setZoomRatio call goes
    // out per 33ms window.  Once the gesture stops, the loop runs to completion and snaps
    // to the exact target value so the zoom never drifts.
    LaunchedEffect(targetZoomRatio, minZoom, maxZoom) {
        while (true) {
            val curr = zoomRatio
            val target = targetZoomRatio
            if (NativeImaging.isZoomSettledNative(curr, target, 0.005f)) {
                if (curr != target) {
                    zoomRatio = target
                    camera?.cameraControl?.setZoomRatio(target)
                }
                break
            }
            val next = NativeImaging.lerpZoomNative(curr, target, 0.25f, minZoom, maxZoom)
            zoomRatio = next
            camera?.cameraControl?.setZoomRatio(next)
            delay(33L) // ~30fps cap on HAL calls
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

    // Keep the ImageCapture use case informed of device rotation so every JPEG
    // is tagged with the correct EXIF orientation — even when the device is
    // held sideways while the UI stays portrait-locked.
    LaunchedEffect(deviceRotation, imageCapture) {
        imageCapture.targetRotation = deviceRotation
    }

    // Same for VideoCapture: the mp4 container's rotation metadata is set from
    // targetRotation, so landscape-held recordings play back the right way up.
    LaunchedEffect(deviceRotation, videoCapture) {
        videoCapture?.targetRotation = deviceRotation
    }
    // COMPATIBLE (TextureView-backed) mode is required so the live preview actually
    // participates in Compose's own rendering layer -- that's what lets the filter
    // RenderEffect below draw over the real camera pixels instead of being ignored.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val documentAnalysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { documentAnalysisExecutor.shutdown() }
    }

    LaunchedEffect(lensFacing, captureAspect, scanDocumentsEnabled, captureMode) {
        // Switching mode/lens/aspect tears down and rebinds the camera -- stop any in-flight
        // recording first instead of leaving it dangling on an about-to-be-unbound camera.
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        detectedDocument = null

        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val useCaseGroupBuilder = UseCaseGroup.Builder()
        useCaseGroupBuilder.addUseCase(preview)

        var newImageCapture: ImageCapture? = null
        var newVideoCapture: VideoCapture<Recorder>? = null

        if (captureMode == CaptureMode.VIDEO) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            newVideoCapture = VideoCapture.withOutput(recorder)
            useCaseGroupBuilder.addUseCase(newVideoCapture)
        } else {
            // Portrait and More are placeholders for later work -- they fall back to the
            // same photo pipeline as Photo mode rather than doing anything special.
            newImageCapture = buildImageCapture(captureAspect)
            useCaseGroupBuilder.addUseCase(newImageCapture)

            val scanForThisBind = scanDocumentsEnabled && lensFacing == CameraSelector.LENS_FACING_BACK
            if (scanForThisBind) {
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            documentAnalysisExecutor,
                            DocumentEdgeAnalyzer(previewView) { result -> detectedDocument = result }
                        )
                    }
                useCaseGroupBuilder.addUseCase(imageAnalysis)
            }
        }

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
            newImageCapture?.let { imageCapture = it }
            videoCapture = newVideoCapture
            flashOn = false
            zoomRatio = 1f
            targetZoomRatio = 1f
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
                // Self-timer countdown, shown full-screen before the shutter fires. Each
                // second gets its own haptic tick so the countdown is felt, not just seen --
                // makes it unmistakable that the timer button actually did something.
                if (timerSeconds > 0) {
                    for (secondsLeft in timerSeconds downTo 1) {
                        countdownValue = secondsLeft
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                    // Portrait mode blurs everything the segmenter doesn't recognize as a
                    // person, giving the same soft-background look as a real depth camera.
                    if (captureMode == CaptureMode.PORTRAIT) {
                        applyPortraitBackgroundBlur(context, uri)
                    }
                    if (watermarkEnabled) {
                        applyWatermark(context, uri)
                    }
                    // Smart Enhance: native post-processing pipeline applied last so it
                    // works on the final pixel state (after crop, blur, and watermark).
                    if (smartEnhanceEnabled) {
                        applySmartEnhance(context, uri)
                    }
                    lastPhotoUri = uri
                    capturedPop = true
                }
            } finally {
                isBusy = false
            }
        }
    }
    val startOrStopRecording: () -> Unit = record@{
        val activeVideoCapture = videoCapture
        if (activeVideoCapture == null) {
            return@record
        }
        if (isRecording) {
            activeRecording?.stop()
            return@record
        }
        if (isBusy) return@record
        scope.launch {
            isBusy = true
            try {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CameraApp")
                }
                val outputOptions = MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val pendingRecording = activeVideoCapture.output.prepareRecording(context, outputOptions).let {
                    if (hasAudioPermission) it.withAudioEnabled() else it
                }

                activeRecording = pendingRecording.start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            isBusy = false
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            activeRecording = null
                            isBusy = false
                            if (!event.hasError()) {
                                lastPhotoUri = event.outputResults.outputUri
                                capturedPop = true
                            } else {
                                Log.e("CameraApp", "Video recording finished with error: ${event.error}")
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (exc: Exception) {
                Log.e("CameraApp", "Failed to start video recording", exc)
                isBusy = false
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (true) {
                delay(1000)
                recordingSeconds += 1
            }
        }
    }

    val onShutterClick: () -> Unit = {
        when (captureMode) {
            CaptureMode.VIDEO -> startOrStopRecording()
            CaptureMode.SCAN  -> launchMlScanner()
            else              -> performCapture()
        }
    }
    val currentShutterClick by rememberUpdatedState(onShutterClick)

    // Let the hardware volume keys act as a physical shutter button, like Samsung Camera.
    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        activity?.onVolumeKeyPressed = { currentShutterClick() }
        onDispose { activity?.onVolumeKeyPressed = null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = zoomTransformableState)
                .graphicsLayer(
                    compositingStrategy = CompositingStrategy.Offscreen,
                    renderEffect = rememberFilterEffect(photoFilter)
                )
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

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
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                scanActive = scanActive,
                documentDetected = detectedDocument != null
            )
        }

        // Top-left: timer + settings.  Top-right: filter + flash.
        // Splitting the four icons across both sides frees up viewfinder space and
        // keeps the most-used controls (timer on the left, flash on the right) within
        // natural thumb reach for each hand.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timer — left side, first icon.
            PressableIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val currentIndex = TIMER_OPTIONS.indexOf(timerSeconds).coerceAtLeast(0)
                    timerSeconds = TIMER_OPTIONS[(currentIndex + 1) % TIMER_OPTIONS.size]
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (timerSeconds > 0) Icons.Filled.Timer else Icons.Filled.TimerOff,
                        contentDescription = "Self-timer: ${if (timerSeconds > 0) "${timerSeconds}s" else "off"}",
                        tint = if (timerSeconds > 0) Color(0xFFFFD54F) else Color.White
                    )
                    // The armed duration shown right on the icon -- makes it obvious the
                    // timer button actually does something, not just changes color.
                    if (timerSeconds > 0) {
                        Text(
                            text = timerSeconds.toString(),
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 3.dp, y = 3.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD54F))
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Settings gear — left side, second icon.
            PressableIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenSettings()
                }
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        // Top-right: filter + flash.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Filter — right side, first icon.
            PressableIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    photoFilter = photoFilter.next()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoFilter,
                    contentDescription = "Filter: ${photoFilter.label}",
                    tint = if (photoFilter != PhotoFilter.NONE) SamsungBlue else Color.White
                )
            }

            // Flash — right side, second icon. Back camera needs a physical flash
            // unit; front camera always offers simulated screen-flash.
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

        // Recording badge -- a small pulsing dot plus an elapsed-time readout, shown only
        // while actively recording video.
        AnimatedVisibility(
            visible = isRecording,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            RecordingBadge(seconds = recordingSeconds)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tap-to-select zoom presets (0.5x ultra-wide through 10x), filtered to whatever
            // range this device's camera actually supports -- quicker and more discoverable
            // than pinch-only zoom, which is kept as well for fine continuous control.
            ZoomPresetRow(
                current = zoomRatio,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onSelect = { level ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val newZoom = level.coerceIn(minZoom, maxZoom)
                    // Preset taps are instant — bypass smoothing by aligning
                    // both values so the EMA loop sees nothing to animate.
                    targetZoomRatio = newZoom
                    zoomRatio = newZoom
                    camera?.cameraControl?.setZoomRatio(newZoom)
                    zoomIndicatorPulse += 1
                },
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // Aspect ratio only applies to still photos, so it's hidden while Video is the
            // active category instead of showing a control that would do nothing.
            if (captureMode != CaptureMode.VIDEO) {
                AspectRatioSelector(
                    current = captureAspect,
                    onSelect = { captureAspect = it }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Category selector sitting right above the shutter -- Photo and Video switch
            // real camera behavior; Portrait and More are reserved placeholders for later
            // work and are purely cosmetic for now.
            CaptureModeRow(
                current = captureMode,
                onSelect = { mode ->
                    if (!isRecording) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        captureMode = mode
                    }
                },
                modifier = Modifier.padding(bottom = 14.dp)
            )

            CameraControls(
                modifier = Modifier.fillMaxWidth(),
                lastPhotoUri = lastPhotoUri,
                thumbnailPop = capturedPop,
                flipSpins = flipSpins,
                captureMode = captureMode,
                isRecording = isRecording,
                onThumbnailClick = { openGallery(context, lastPhotoUri) },
                onCaptureClick = onShutterClick,
                onFlipClick = {
                    if (!isRecording) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        flipSpins += 1
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
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

/**
 * Builds the live-preview color filter as a real [RenderEffect], applied via
 * `Modifier.graphicsLayer`. `minSdk` for this app is 31, so `RenderEffect` (added in API 31)
 * is always available -- no version gating needed. Filters are preview-only; they aren't
 * baked into the saved JPEG.
 */
@Composable
private fun rememberFilterEffect(filter: PhotoFilter): RenderEffect? {
    if (filter == PhotoFilter.NONE) return null
    return remember(filter) {
        val matrix = when (filter) {
            PhotoFilter.MONO -> AndroidColorMatrix().apply { setSaturation(0f) }
            PhotoFilter.WARM -> AndroidColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 14f,
                    0f, 1.03f, 0f, 0f, 4f,
                    0f, 0f, 0.86f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.COOL -> AndroidColorMatrix(
                floatArrayOf(
                    0.90f, 0f, 0f, 0f, -6f,
                    0f, 0.97f, 0f, 0f, 0f,
                    0f, 0f, 1.16f, 0f, 16f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            PhotoFilter.VIVID -> AndroidColorMatrix().apply { setSaturation(1.55f) }
            PhotoFilter.NONE -> AndroidColorMatrix() // unreachable -- guarded above
        }
        AndroidRenderEffect
            .createColorFilterEffect(ColorMatrixColorFilter(matrix))
            .asComposeRenderEffect()
    }
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
    // No title while it's just quietly scanning -- only speak up once a document is actually
    // found, or to explain why nothing is happening when the front camera is active.
    if (scanActive && !documentDetected) return

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
            text = if (documentDetected) "Document detected" else "Switch to back camera to scan",
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
 * Tap-to-select row of zoom presets (0.5x through 10x), filtered to the levels this device's
 * camera actually supports. Complements continuous pinch-to-zoom with quick, discoverable jumps
 * to a specific lens/zoom level, the way stock camera apps expose their lens switcher.
 */
@Composable
private fun ZoomPresetRow(
    current: Float,
    minZoom: Float,
    maxZoom: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val available = remember(minZoom, maxZoom) {
        val eps = 0.05f
        val levels = ZOOM_PRESETS.filter { it >= minZoom - eps && it <= maxZoom + eps }.toMutableSet()
        levels.add(1f.coerceIn(minZoom, maxZoom))
        levels.filter { it in (minZoom - eps)..(maxZoom + eps) }.sorted()
    }
    if (available.size < 2) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        available.forEach { level ->
            val isActive = kotlin.math.abs(current - level) < 0.15f
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isActive) SamsungBlue else Color.Transparent)
                    .clickable { onSelect(level) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = formatZoomLabel(level),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatZoomLabel(level: Float): String =
    if (level < 1f) String.format(Locale.US, "%.1fx", level) else "${level.roundToInt()}x"

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
    captureMode: CaptureMode,
    isRecording: Boolean,
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
        ShutterButton(mode = captureMode, isRecording = isRecording, onClick = onCaptureClick)
        FlipButton(spins = flipSpins, onClick = onFlipClick)
    }
}

/**
 * The category selector shown above the shutter, e.g. Samsung Camera's Photo/Video/More row.
 * Small, understated text labels -- the current one picked out in accent yellow and bold.
 */
@Composable
private fun CaptureModeRow(
    current: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CaptureMode.entries.forEach { mode ->
            val isSelected = mode == current
            val color by animateColorAsState(
                targetValue = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.6f),
                label = "captureModeColor"
            )
            Text(
                text = mode.label,
                color = color,
                fontSize = if (isSelected) 13.sp else 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.6.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(mode) }
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

/** Small pulsing dot + elapsed-time readout shown while a video is actively recording. */
@Composable
private fun RecordingBadge(seconds: Int) {
    val infinite = rememberInfiniteTransition(label = "recordingPulse")
    val dotAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingDotAlpha"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF3B30).copy(alpha = dotAlpha))
        )
        Text(
            text = "%02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
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

/**
 * Photo mode: a plain white circle, unchanged.
 * Video mode: red instead of white to flag that pressing it records instead of snapping a
 * photo. While recording, it morphs into a smaller red rounded square -- the universal
 * "tap again to stop" shape -- so it's obvious a tap now ends the recording.
 * Portrait/More fall back to the Photo look since they're inert placeholders.
 */
@Composable
private fun ShutterButton(mode: CaptureMode, isRecording: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val outerScale by animateFloatAsState(if (isPressed) 0.9f else 1f, label = "shutterOuterScale")
    val innerPressScale by animateFloatAsState(if (isPressed) 0.8f else 1f, label = "shutterInnerPressScale")
    val innerSizeFraction by animateFloatAsState(
        targetValue = if (isRecording) 0.42f else 1f,
        label = "shutterInnerSize"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isRecording) 8.dp else 40.dp,
        label = "shutterCornerRadius"
    )
    val innerColor by animateColorAsState(
        targetValue = if (mode == CaptureMode.VIDEO) Color(0xFFFF3B30) else Color.White,
        label = "shutterInnerColor"
    )

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
                .fillMaxSize(innerSizeFraction)
                .scale(innerPressScale)
                .clip(RoundedCornerShape(cornerRadius))
                .background(innerColor)
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
        // Decode with EXIF rotation applied so we crop the visually-upright image,
        // not the raw sensor-orientation bytes (which may be landscape for a portrait shot).
        val original = decodeBitmapUpright(context, uri) ?: return

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
        // IMPORTANT: normalizedCorners are in *upright/display* space (rotation-corrected 0..1),
        // computed by DocumentEdgeAnalyzer.normalizeUpright().  BitmapFactory.decodeStream()
        // on its own returns pixels in *sensor-native* orientation (ignoring EXIF), so we must
        // rotate the bitmap to match display orientation before mapping the corners onto it.
        val original = decodeBitmapUpright(context, uri) ?: return

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

        // Forward matrix: source corners -> dest rectangle. Invert so native code
        // maps each dest pixel back to its source coordinate for bilinear sampling.
        val fwdMatrix = Matrix()
        if (!fwdMatrix.setPolyToPoly(source, 0, destination, 0, 4)) return
        val invMatrix = Matrix()
        if (!fwdMatrix.invert(invMatrix)) return
        val invValues = FloatArray(9); invMatrix.getValues(invValues)

        val straightened = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        NativeImaging.warpDocumentNative(original, straightened, invValues)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            straightened.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        fixExifOrientation(context, uri)
    } catch (exc: Exception) {
        Log.e("CameraApp", "Failed to apply document scan crop", exc)
    }
}

/**
 * Stamps a small "CameraApp" + capture-date watermark into the bottom-right corner of the
 * saved image at [uri], in place. A soft drop shadow behind the text keeps it legible over
 * both light and dark backgrounds without needing a solid backing plate.
 */
private fun applyWatermark(context: Context, uri: Uri) {
    try {
        // Decode upright so the watermark lands in the correct visual corner,
        // not the sensor-native corner (which may be a different physical edge).
        val original = decodeBitmapUpright(context, uri) ?: return

        val watermarked = if (original.isMutable && original.config == Bitmap.Config.ARGB_8888) {
            original
        } else {
            original.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = AndroidCanvas(watermarked)

        val timestamp = SimpleDateFormat("MMM d, yyyy \u00b7 HH:mm", Locale.getDefault())
            .format(System.currentTimeMillis())
        val label = "CameraApp  \u2022  $timestamp"

        val textSizePx = watermarked.width / 28f
        val padding = watermarked.width / 45f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = textSizePx
            textAlign = Paint.Align.RIGHT
            setShadowLayer(textSizePx / 6f, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        }

        canvas.drawText(label, watermarked.width - padding, watermarked.height - padding, textPaint)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            watermarked.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        // Bitmap.compress strips EXIF. Without this, MediaStore still serves the original
        // ORIENTATION_ROTATE_90 tag on the newly-upright pixels -> photo appears 90° tilted.
        fixExifOrientation(context, uri)
    } catch (exc: Exception) {
        Log.e("CameraApp", "Failed to apply watermark", exc)
    }
}

/**
 * Blurs everything behind the subject in the saved photo at [uri], in place, giving Portrait
 * mode the same soft-background look as a real depth camera even though this device only has
 * one lens. Uses ML Kit's on-device Selfie Segmenter to tell foreground from background, then
 * blends a blurred copy of the photo back in wherever the person confidence is low -- soft,
 * confidence-weighted blending (rather than a hard cutout) is what keeps the edges around hair
 * and shoulders from looking cut out with scissors.
 */
private suspend fun applyPortraitBackgroundBlur(context: Context, uri: Uri) {
    val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )
    try {
        // Decode upright so ML Kit's segmentation mask aligns with the correct image orientation.
        // InputImage.fromBitmap(bitmap, rotationDegrees=0) assumes the bitmap is already upright.
        val original = decodeBitmapUpright(context, uri) ?: return

        val sharp = if (original.config == Bitmap.Config.ARGB_8888) {
            original
        } else {
            original.copy(Bitmap.Config.ARGB_8888, false)
        }

        val mask = segmentPerson(segmenter, InputImage.fromBitmap(sharp, 0))
        val width = sharp.width
        val height = sharp.height
        if (mask.width != width || mask.height != height) {
            // The segmenter is expected to rescale its mask to the input image size; bail
            // out rather than risk compositing misaligned pixels if that assumption ever changes.
            return
        }

        // Full-resolution 3-pass box blur — sharper background edges than downscale trick.
        val blurred = sharp.copy(Bitmap.Config.ARGB_8888, true)
        NativeImaging.stackBlurNative(blurred, PORTRAIT_BLUR_RADIUS)

        val sharpPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        sharp.getPixels(sharpPixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

        val confidence = mask.buffer
        confidence.rewind()
        val confidenceArr = FloatArray(width * height) { confidence.getFloat().coerceIn(0f, 1f) }
        val outPixels = IntArray(width * height)
        NativeImaging.blendPixelsNative(sharpPixels, blurredPixels, confidenceArr, outPixels, width * height)

        val result = Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            result.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        fixExifOrientation(context, uri)
    } catch (exc: Exception) {
        Log.e("CameraApp", "Failed to apply portrait background blur", exc)
    } finally {
        segmenter.close()
    }
}

/** Wraps ML Kit's listener-based [Segmenter.process] as a suspend call. */
private suspend fun segmentPerson(segmenter: Segmenter, image: InputImage): SegmentationMask =
    suspendCancellableCoroutine { continuation ->
        segmenter.process(image)
            .addOnSuccessListener { mask -> if (continuation.isActive) continuation.resume(mask) }
            .addOnFailureListener { exc -> if (continuation.isActive) continuation.resumeWithException(exc) }
    }

/**
 * Cheap, dependency-free approximation of a gaussian blur: shrink the image way down (which
 * mixes each pixel with its neighbors) and scale it back up with bilinear filtering. Far
 * faster than a per-pixel convolution at full photo resolution and plenty soft-looking for a
 * background blur.
 */
/* downscaleBlur + blendPixel removed — replaced by NativeImaging.stackBlurNative
   and NativeImaging.blendPixelsNative (see portrait_blur.c). */

/**
 * Decodes the JPEG at [uri] and returns a bitmap that is already rotated to the correct
 * display/upright orientation by reading and applying the EXIF [ExifInterface.TAG_ORIENTATION]
 * tag before returning.
 *
 * Why this matters: [BitmapFactory.decodeStream] returns raw sensor-native pixels and ignores
 * EXIF orientation entirely.  When the phone is held portrait and the back camera sensor is
 * landscape (the common case), the returned bitmap is landscape even though the saved file
 * looks portrait in every gallery app (they all honour the EXIF tag).
 *
 * Any post-processing that maps coordinates from the live analysis frame onto the saved
 * bitmap (document crop corners, square crop, watermark position, segmentation mask) must
 * work in the same orientation space.  Using this helper everywhere ensures that.
 *
 * Two [ContentResolver.openInputStream] calls are made: the first reads only the EXIF header
 * (a few KB at most), the second decodes the full pixel data.  Both are closed immediately
 * after use.  The rotation matrix is applied via [Bitmap.createBitmap] so the returned
 * bitmap is a new, correctly-oriented copy; the original raw bitmap is recycled.
 */
/** Stack-blur radius for portrait background blur (pixels, full photo resolution). */
    private const val PORTRAIT_BLUR_RADIUS = 40

    /**
    * Write EXIF ORIENTATION_NORMAL into the JPEG at [uri] after a Bitmap.compress() save.
    * Bitmap.compress strips all EXIF tags — without this, the MediaStore continues to serve
    * the stale ORIENTATION_ROTATE_90 it cached from the original file, causing photos to
    * appear 90° tilted in every gallery app even though the pixels are already upright.
    */
    private fun fixExifOrientation(context: Context, uri: Uri) {
      try {
          context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
              val exif = ExifInterface(pfd.fileDescriptor)
              exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                  ExifInterface.ORIENTATION_NORMAL.toString())
              exif.saveAttributes()
          }
      } catch (e: Exception) {
          Log.w("CameraApp", "Failed to fix EXIF orientation", e)
      }
    }

    private fun decodeBitmapUpright(context: Context, uri: Uri): Bitmap? {
    // Pass 1: read EXIF orientation (reads only the header, not all pixel data).
    val exifOrientation = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    // Pass 2: decode full pixel data in sensor-native orientation.
    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    } ?: return null

    // Build the matrix that transforms sensor orientation → upright display orientation.
    val matrix = Matrix()
    when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90    -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180   -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270   -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            // 90° CCW + horizontal flip
            matrix.postScale(-1f, 1f)
            matrix.postRotate(-90f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            // 90° CW + horizontal flip
            matrix.postScale(-1f, 1f)
            matrix.postRotate(90f)
        }
        else -> return raw // ORIENTATION_NORMAL (1) or unknown — already upright, no work needed
    }

    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    raw.recycle()
    return upright
}

/**
 * Smart Enhance — four-stage native post-processing pipeline applied after capture.
 *
 * Stage order matters:
 *  1. Auto-levels first  — fixes per-channel exposure before we touch anything else.
 *     Running it on a clipped or colour-casted image would distort downstream stages.
 *  2. Bilateral denoise  — edge-preserving noise reduction on the level-corrected pixels.
 *     Done before sharpening so we don't amplify noise that the sharpener would otherwise
 *     dig up and make visible.
 *  3. Unsharp mask       — recovers crispness lost in JPEG compression / sensor AA filter.
 *     Applied after denoise so the detail it amplifies is genuine signal, not noise.
 *  4. Vibrance           — light saturation lift, weighted toward under-saturated pixels.
 *     Last because it should work on the final, spatially-processed values.
 *
 * The whole pipeline runs in compiled ARM C (libcamimg.so) without any cloud round-trip.
 */
private fun applySmartEnhance(context: Context, uri: Uri) {
    try {
        val original = decodeBitmapUpright(context, uri) ?: return
        val bitmap = if (original.isMutable && original.config == Bitmap.Config.ARGB_8888) {
            original
        } else {
            original.copy(Bitmap.Config.ARGB_8888, true)
        }

        NativeImaging.autoLevelsNative(bitmap, 0.005f)
        NativeImaging.bilateralDenoiseNative(bitmap, radius = 2, sigmaColor = 25f, sigmaSpace = 10f)
        NativeImaging.unsharpMaskNative(bitmap, radius = 1, strength = 0.55f)
        NativeImaging.vibranceNative(bitmap, strength = 0.22f)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        fixExifOrientation(context, uri)
    } catch (exc: Exception) {
        Log.e("CameraApp", "Smart Enhance failed", exc)
    }
}

/**
 * Copies a scanned-document JPEG produced by ML Kit into the device's
 * DCIM/Camera folder so it appears in the gallery alongside regular photos.
 */
private fun saveScanToGallery(context: Context, sourceUri: Uri) {
    val displayName = "SCAN_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
    }
    val resolver = context.contentResolver
    val destUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (destUri == null) {
        Log.e("CameraApp", "saveScanToGallery: could not create MediaStore entry")
        return
    }
    try {
        resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(destUri)?.use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        Log.e("CameraApp", "saveScanToGallery: copy failed", e)
        resolver.delete(destUri, null, null)
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
