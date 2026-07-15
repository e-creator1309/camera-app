package com.replit.cameraapp

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A document/paper quadrilateral detected in a single analyzed camera frame.
 *
 * [previewPoints] are already mapped into the [PreviewView]'s own pixel space, ready to be
 * drawn directly on a same-sized Compose overlay. [normalizedCorners] are the same four
 * corners expressed as 0..1 fractions of the *upright* frame (rotation-corrected), used
 * later to crop/straighten the full-resolution captured photo.
 *
 * Both lists are ordered [topLeft, topRight, bottomRight, bottomLeft].
 */
data class DetectedDocument(
    val previewPoints: List<Offset>,
    val normalizedCorners: List<PointF>
)

/**
 * Camera-frame document detector backed entirely by the native [NativeImaging] library
 * (`doc_scan.c` compiled into `libcamimg.so`).
 *
 * ## Why previous versions detected everything
 * The old Kotlin-only implementation used Otsu brightness segmentation: it thresholded the
 * luma plane into two classes and flood-filled the largest connected *brightness* region.
 * Any large, uniformly-lit object — a round plate, a circular table top, a wall — looks
 * identical to a document under that test, because neither the flood-fill nor the corner
 * extraction ever checks whether the shape actually has four straight sides.
 *
 * ## What this version does instead
 * All heavy math runs in `doc_scan.c` through three native JNI calls:
 *
 *  1. **[NativeImaging.docScanFindQuadNative]** — the core detector:
 *     - Subsamples the Y plane to a 160×214 work grid.
 *     - Runs Sobel edge detection and Otsu-thresholds the *gradient magnitude* (not luma)
 *       to get a binary edge map.
 *     - BFS-labels connected edge-pixel components.
 *     - For each candidate component it extracts four corner candidates using the
 *       diagonal-extreme-point method.
 *     - **Edge-fit quality test** (the anti-circle filter): walks each of the four candidate
 *       sides pixel-by-pixel with Bresenham's algorithm and measures what fraction of those
 *       pixels are actual gradient-edge pixels.  For a rectangle the sides *are* edges, so
 *       the fraction is high.  For a circle or organic blob the straight lines between the
 *       four extreme points miss almost all the actual curved edges → low score → rejected.
 *     - Interior-angle check (45°–135°), minimum side length, area fraction, convexity.
 *
 *  2. **[NativeImaging.docScanSmoothCornersNative]** — EMA blend between frames.
 *     The blend speed adapts: slow (α=0.12) when a large jump is detected (new document
 *     came into frame), normal (α=0.38) once locked on.
 *
 *  3. **[NativeImaging.docScanIsValidQuadNative]** — re-validates the *smoothed* corners
 *     each frame so EMA drift never causes an invalid shape to reach the UI.
 *
 * ## Temporal stability
 * After losing a detection the overlay is held for [MAX_MISS_FRAMES] frames before being
 * cleared.  This prevents flickering when the camera briefly looks away or loses focus,
 * while still clearing promptly when the document is genuinely gone.
 */
class DocumentEdgeAnalyzer(
    private val previewView: PreviewView,
    private val onResult: (DetectedDocument?) -> Unit
) : ImageAnalysis.Analyzer {

    // ── Tuning ────────────────────────────────────────────────────────────────

    private companion object {
        /** Hold the last detection for this many frames before clearing the overlay. */
        const val MAX_MISS_FRAMES = 7

        /** Normal EMA blend speed — tracks the document while damping per-frame jitter. */
        const val ALPHA_TRACK = 0.38f

        /** Slow blend used when corners jump far in one frame (new document appeared). */
        const val ALPHA_JUMP = 0.12f

        /**
         * If the max per-corner displacement between frames (in [0..1] normalised units)
         * exceeds this, we treat it as a new document rather than continuing to track.
         */
        const val JUMP_THRESHOLD = 0.13f
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Smoothed corners in normalised [0..1] coordinates (same layout as the native output). */
    private val smoothed = FloatArray(8)
    private val raw      = FloatArray(8)
    private val tmp      = FloatArray(8)

    private var hasLocked   = false   // true once we have a valid starting point for smoothing
    private var missFrames  = 0
    private var lastAt      = 0L

    // ── Analyzer entry point ──────────────────────────────────────────────────

    override fun analyze(image: ImageProxy) {
        // Throttle to ~10 fps — more than enough for a live outline, avoids wasting CPU.
        val now = System.currentTimeMillis()
        if (now - lastAt < 100) { image.close(); return }
        lastAt = now

        try {
            processFrame(image)
        } catch (_: Throwable) {
            onResult(null)
        } finally {
            image.close()
        }
    }

    private fun processFrame(image: ImageProxy) {
        val cropRect  = image.cropRect
        val plane     = image.planes[0]
        val rowStride = plane.rowStride
        val cropW     = cropRect.width()
        val cropH     = cropRect.height()
        if (cropW <= 0 || cropH <= 0) { reset(); return }

        // Copy the Y-plane buffer into a byte array so JNI can read it.
        val buf   = plane.buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)

        val found = NativeImaging.docScanFindQuadNative(
            bytes, rowStride,
            cropRect.left, cropRect.top, cropW, cropH,
            raw
        )

        if (!found) {
            missFrames++
            if (missFrames >= MAX_MISS_FRAMES) reset()
            // Otherwise hold the last smoothed result — prevents flicker.
            return
        }

        missFrames = 0

        // First detection: snap immediately instead of blending from (0,0).
        val alpha = when {
            !hasLocked -> { hasLocked = true; 1.0f }
            maxCornerMove(smoothed, raw) > JUMP_THRESHOLD -> ALPHA_JUMP
            else -> ALPHA_TRACK
        }

        NativeImaging.docScanSmoothCornersNative(smoothed, raw, alpha, tmp)
        tmp.copyInto(smoothed)

        // Re-validate after smoothing — EMA can drift corners into invalid shape.
        if (!NativeImaging.docScanIsValidQuadNative(smoothed, cropW, cropH)) return

        // Build preview-space points and normalised upright corners.
        val corners = floatToPointFList(smoothed, cropRect)
        val previewPoints = mapToPreviewSpace(corners, cropRect, image.imageInfo.rotationDegrees)
            ?: return
        val normalizedCorners = sortToTlTrBrBl(
            normalizeUpright(corners, cropRect, image.imageInfo.rotationDegrees)
        )

        onResult(DetectedDocument(previewPoints, normalizedCorners))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun reset() {
        hasLocked  = false
        missFrames = 0
        smoothed.fill(0f)
        onResult(null)
    }

    /** Maximum Euclidean displacement (in [0..1] units) across the four corners. */
    private fun maxCornerMove(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in 0 until 4) {
            val dx = a[i * 2] - b[i * 2]
            val dy = a[i * 2 + 1] - b[i * 2 + 1]
            val d  = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (d > max) max = d
        }
        return max
    }

    /** Convert the native [0..1] float array to PointF list in *buffer* pixel space. */
    private fun floatToPointFList(corners: FloatArray, cropRect: Rect): List<PointF> {
        val w = cropRect.width().toFloat()
        val h = cropRect.height().toFloat()
        return (0 until 4).map { i ->
            PointF(
                cropRect.left + corners[i * 2]     * w,
                cropRect.top  + corners[i * 2 + 1] * h
            )
        }
    }

    /**
     * Maps points from the analysis buffer's (cropped) coordinate space into the
     * [PreviewView]'s pixel space using CameraX's recommended poly-to-poly matrix recipe:
     * the source quad is the crop rect, the destination quad is the view bounds shifted by
     * rotation, so the matrix captures both the crop-to-view scale and the rotation alignment.
     */
    private fun mapToPreviewSpace(
        points: List<PointF>, cropRect: Rect, rotationDegrees: Int
    ): List<Offset>? {
        val viewW = previewView.width
        val viewH = previewView.height
        if (viewW <= 0 || viewH <= 0) return null

        val source = floatArrayOf(
            cropRect.left.toFloat(),  cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.bottom.toFloat(),
            cropRect.left.toFloat(),  cropRect.bottom.toFloat()
        )
        val destination = floatArrayOf(
            0f,          0f,
            viewW.toFloat(), 0f,
            viewW.toFloat(), viewH.toFloat(),
            0f,          viewH.toFloat()
        )
        // Shift the destination vertices by rotation to account for sensor orientation.
        val shift  = (rotationDegrees / 90) * 2
        val orig   = destination.clone()
        for (i in destination.indices) destination[i] = orig[(i + shift) % destination.size]

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return null

        val coords = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> coords[i * 2] = p.x; coords[i * 2 + 1] = p.y }
        matrix.mapPoints(coords)
        return points.indices.map { i -> Offset(coords[i * 2], coords[i * 2 + 1]) }
    }

    /**
     * Rotates and normalises points from buffer pixel space into [0..1] fractions of the
     * *upright* frame (rotation-corrected), ready for the perspective-warp crop step.
     */
    private fun normalizeUpright(
        points: List<PointF>, cropRect: Rect, rotationDegrees: Int
    ): List<PointF> {
        val w = cropRect.width().toFloat()
        val h = cropRect.height().toFloat()
        val rot = ((rotationDegrees % 360) + 360) % 360
        return points.map { p ->
            val x = p.x - cropRect.left
            val y = p.y - cropRect.top
            when (rot) {
                90  -> PointF(((h - y) / h).coerceIn(0f, 1f), (x / w).coerceIn(0f, 1f))
                180 -> PointF(((w - x) / w).coerceIn(0f, 1f), ((h - y) / h).coerceIn(0f, 1f))
                270 -> PointF((y / h).coerceIn(0f, 1f), ((w - x) / w).coerceIn(0f, 1f))
                else -> PointF((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
            }
        }
    }

    /**
     * Re-sorts four normalised (0..1) upright-space points into strict [TL, TR, BR, BL]
     * visual order so the perspective-warp crop always receives corners in the right sequence.
     *   TL = min(x+y),  BR = max(x+y),  TR = max(x−y),  BL = min(x−y)
     */
    private fun sortToTlTrBrBl(corners: List<PointF>): List<PointF> {
        if (corners.size != 4) return corners
        val bySum = corners.sortedBy { it.x + it.y }
        val tl = bySum[0]; val br = bySum[3]
        val mid = listOf(bySum[1], bySum[2])
        val tr = mid.maxByOrNull { it.x - it.y }!!
        val bl = mid.minByOrNull { it.x - it.y }!!
        return listOf(tl, tr, br, bl)
    }
}
