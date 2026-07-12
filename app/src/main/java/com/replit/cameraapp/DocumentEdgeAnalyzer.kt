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
import kotlin.math.min

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
 * Lightweight, dependency-free document/paper edge detector for [ImageAnalysis].
 *
 * There's no OpenCV or ML model here -- just a coarse luma edge map plus a corner-extremity
 * heuristic: for each of the four frame corners, find the strongest edge pixel that lies
 * furthest toward it. That's enough to reliably lock onto a high-contrast rectangular object
 * such as a sheet of paper on a desk, without pulling in a heavy native CV dependency that
 * can't be verified in this build environment.
 */
class DocumentEdgeAnalyzer(
    private val previewView: PreviewView,
    private val onResult: (DetectedDocument?) -> Unit
) : ImageAnalysis.Analyzer {

    private val gridWidth = 120
    private val gridHeight = 160
    private var lastAnalyzedAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        // Throttle to ~6 fps -- plenty for a live outline, far cheaper than every frame.
        if (now - lastAnalyzedAt < 160) {
            image.close()
            return
        }
        lastAnalyzedAt = now

        try {
            onResult(detectDocument(image))
        } catch (t: Throwable) {
            onResult(null)
        } finally {
            image.close()
        }
    }

    private fun detectDocument(image: ImageProxy): DetectedDocument? {
        val cropRect = image.cropRect
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val cropW = cropRect.width()
        val cropH = cropRect.height()
        if (cropW <= 0 || cropH <= 0) return null

        // Sample a coarse grid of luma values directly from the analysis buffer's Y plane.
        val gray = ByteArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            val srcY = cropRect.top + gy * cropH / gridHeight
            val rowOffset = srcY * rowStride
            for (gx in 0 until gridWidth) {
                val srcX = cropRect.left + gx * cropW / gridWidth
                val index = rowOffset + srcX * pixelStride
                gray[gy * gridWidth + gx] = if (index in 0 until buffer.capacity()) buffer.get(index) else 0
            }
        }

        // Simple gradient-magnitude edge map (difference vs. right/below neighbor).
        val edgeThreshold = 28
        val margin = 4 // ignore a thin border, where lens vignetting/noise causes false edges
        val edgeXs = ArrayList<Int>()
        val edgeYs = ArrayList<Int>()
        for (gy in margin until gridHeight - margin - 1) {
            for (gx in margin until gridWidth - margin - 1) {
                val here = gray[gy * gridWidth + gx].toInt() and 0xFF
                val right = gray[gy * gridWidth + gx + 1].toInt() and 0xFF
                val below = gray[(gy + 1) * gridWidth + gx].toInt() and 0xFF
                val gradient = abs(here - right) + abs(here - below)
                if (gradient > edgeThreshold) {
                    edgeXs.add(gx)
                    edgeYs.add(gy)
                }
            }
        }

        // Too few edges -- nothing resembling a document in frame.
        val minEdgePixels = (gridWidth * gridHeight) / 60
        if (edgeXs.size < minEdgePixels) return null

        // For each frame corner, pick the edge pixel that pushes furthest toward it.
        var bestTL = -1; var bestTLScore = Int.MAX_VALUE
        var bestTR = -1; var bestTRScore = Int.MIN_VALUE
        var bestBL = -1; var bestBLScore = Int.MIN_VALUE
        var bestBR = -1; var bestBRScore = Int.MIN_VALUE

        for (i in edgeXs.indices) {
            val x = edgeXs[i]; val y = edgeYs[i]
            val sumScore = x + y   // minimized at top-left, maximized at bottom-right
            val diffScore = x - y  // maximized at top-right, minimized at bottom-left

            if (sumScore < bestTLScore) { bestTLScore = sumScore; bestTL = i }
            if (sumScore > bestBRScore) { bestBRScore = sumScore; bestBR = i }
            if (diffScore > bestTRScore) { bestTRScore = diffScore; bestTR = i }
            if (diffScore < bestBLScore) { bestBLScore = diffScore; bestBL = i }
        }
        if (bestTL < 0 || bestTR < 0 || bestBL < 0 || bestBR < 0) return null

        // Map a grid index back to real pixel coordinates in the analysis buffer's own
        // (cropped) coordinate space -- the same space image.cropRect lives in.
        fun toBufferPoint(index: Int): PointF {
            val gx = edgeXs[index]; val gy = edgeYs[index]
            return PointF(
                cropRect.left + gx * cropW / gridWidth.toFloat(),
                cropRect.top + gy * cropH / gridHeight.toFloat()
            )
        }

        val topLeft = toBufferPoint(bestTL)
        val topRight = toBufferPoint(bestTR)
        val bottomRight = toBufferPoint(bestBR)
        val bottomLeft = toBufferPoint(bestBL)

        // Sanity checks: the four corners must form a reasonably sized, non-degenerate quad,
        // otherwise noisy scenes with no real document would constantly "detect" garbage.
        val quadArea = quadrilateralArea(topLeft, topRight, bottomRight, bottomLeft)
        val frameArea = cropW.toFloat() * cropH.toFloat()
        val areaRatio = quadArea / frameArea
        if (areaRatio < 0.12f || areaRatio > 0.97f) return null

        val minSide = min(
            min(distance(topLeft, topRight), distance(topRight, bottomRight)),
            min(distance(bottomRight, bottomLeft), distance(bottomLeft, topLeft))
        )
        if (minSide < cropW * 0.08f) return null

        val corners = listOf(topLeft, topRight, bottomRight, bottomLeft)
        val previewPoints = mapToPreviewSpace(corners, cropRect, image.imageInfo.rotationDegrees) ?: return null
        val normalizedCorners = normalizeUpright(corners, cropRect, image.imageInfo.rotationDegrees)

        return DetectedDocument(previewPoints, normalizedCorners)
    }

    /**
     * Maps points from the analysis buffer's (cropped) coordinate space into the
     * [PreviewView]'s pixel space, following CameraX's recommended recipe for aligning
     * ImageAnalysis coordinates with what's on screen: build a matrix from the crop rect to
     * the view bounds, with the destination vertices shifted to account for rotation.
     */
    private fun mapToPreviewSpace(points: List<PointF>, cropRect: Rect, rotationDegrees: Int): List<Offset>? {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return null

        val source = floatArrayOf(
            cropRect.left.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.bottom.toFloat(),
            cropRect.left.toFloat(), cropRect.bottom.toFloat()
        )
        val destination = floatArrayOf(
            0f, 0f,
            viewWidth.toFloat(), 0f,
            viewWidth.toFloat(), viewHeight.toFloat(),
            0f, viewHeight.toFloat()
        )
        val vertexSize = 2
        val shiftOffset = (rotationDegrees / 90) * vertexSize
        val original = destination.clone()
        for (toIndex in source.indices) {
            val fromIndex = (toIndex + shiftOffset) % source.size
            destination[toIndex] = original[fromIndex]
        }

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return null

        val coords = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> coords[i * 2] = p.x; coords[i * 2 + 1] = p.y }
        matrix.mapPoints(coords)
        return points.indices.map { i -> Offset(coords[i * 2], coords[i * 2 + 1]) }
    }

    /** Rotates and normalizes points from buffer space into 0..1 fractions of the upright frame. */
    private fun normalizeUpright(points: List<PointF>, cropRect: Rect, rotationDegrees: Int): List<PointF> {
        val w = cropRect.width().toFloat()
        val h = cropRect.height().toFloat()
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        return points.map { p ->
            val x = p.x - cropRect.left
            val y = p.y - cropRect.top
            when (normalizedRotation) {
                90 -> PointF(((h - y) / h).coerceIn(0f, 1f), (x / w).coerceIn(0f, 1f))
                180 -> PointF(((w - x) / w).coerceIn(0f, 1f), ((h - y) / h).coerceIn(0f, 1f))
                270 -> PointF((y / h).coerceIn(0f, 1f), ((w - x) / w).coerceIn(0f, 1f))
                else -> PointF((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
            }
        }
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    /** Shoelace formula for a simple quadrilateral's area. */
    private fun quadrilateralArea(a: PointF, b: PointF, c: PointF, d: PointF): Float {
        val pts = listOf(a, b, c, d)
        var sum = 0f
        for (i in pts.indices) {
            val p1 = pts[i]; val p2 = pts[(i + 1) % pts.size]
            sum += p1.x * p2.y - p2.x * p1.y
        }
        return abs(sum) / 2f
    }
}
