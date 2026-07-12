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
import kotlin.math.max
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
 * heuristic, hardened against the false positives a single-pixel version is prone to:
 *
 *  - a 3x3 box blur runs before edge detection, so sensor noise doesn't get picked up as an
 *    "edge" in the first place;
 *  - each corner is the *average* of several of the strongest nearby edge points rather than
 *    a single most-extreme pixel, so one stray speck can't drag a corner out to the wrong spot;
 *  - candidate quads are rejected unless they're convex and reasonably rectangular (no side
 *    wildly longer than another), which throws out the random slivers a naive corner pick can
 *    otherwise report as a "document"; and
 *  - accepted corners are eased toward the previous frame's corners (light temporal smoothing)
 *    so the outline doesn't jitter or snap between frames.
 *
 * That's enough to reliably lock onto a high-contrast rectangular object such as a sheet of
 * paper or a book cover on a desk, without pulling in a heavy native CV dependency that can't
 * be verified in this build environment.
 */
class DocumentEdgeAnalyzer(
    private val previewView: PreviewView,
    private val onResult: (DetectedDocument?) -> Unit
) : ImageAnalysis.Analyzer {

    private val gridWidth = 120
    private val gridHeight = 160
    private var lastAnalyzedAt = 0L

    // Corners (in analysis-buffer pixel space) from the last accepted detection, used to ease
    // each new detection toward instead of snapping straight to it.
    private var smoothedCorners: List<PointF>? = null

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
        if (cropW <= 0 || cropH <= 0) return fail()

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

        // Light denoise before edge detection -- without this, isolated noisy pixels near the
        // frame edge were the main cause of the outline snapping to the wrong place.
        val blurred = boxBlur3x3(gray)

        // Simple gradient-magnitude edge map (difference vs. right/below neighbor).
        val edgeThreshold = 24
        val margin = 4 // ignore a thin border, where lens vignetting/noise causes false edges
        val edgeXs = ArrayList<Int>()
        val edgeYs = ArrayList<Int>()
        for (gy in margin until gridHeight - margin - 1) {
            for (gx in margin until gridWidth - margin - 1) {
                val here = blurred[gy * gridWidth + gx].toInt() and 0xFF
                val right = blurred[gy * gridWidth + gx + 1].toInt() and 0xFF
                val below = blurred[(gy + 1) * gridWidth + gx].toInt() and 0xFF
                val gradient = abs(here - right) + abs(here - below)
                if (gradient > edgeThreshold) {
                    edgeXs.add(gx)
                    edgeYs.add(gy)
                }
            }
        }

        // Too few edges -- nothing resembling a document in frame.
        val minEdgePixels = (gridWidth * gridHeight) / 60
        if (edgeXs.size < minEdgePixels) return fail()

        // For each frame corner, average the strongest handful of edge points that push
        // furthest toward it, rather than trusting a single most-extreme pixel.
        val topK = 8
        val topLeftGrid = averageExtremePoint(edgeXs, edgeYs, topK) { x, y -> -(x + y) }
        val topRightGrid = averageExtremePoint(edgeXs, edgeYs, topK) { x, y -> x - y }
        val bottomRightGrid = averageExtremePoint(edgeXs, edgeYs, topK) { x, y -> x + y }
        val bottomLeftGrid = averageExtremePoint(edgeXs, edgeYs, topK) { x, y -> y - x }
        if (topLeftGrid == null || topRightGrid == null || bottomRightGrid == null || bottomLeftGrid == null) {
            return fail()
        }

        fun toBufferPoint(grid: PointF): PointF = PointF(
            cropRect.left + grid.x * cropW / gridWidth.toFloat(),
            cropRect.top + grid.y * cropH / gridHeight.toFloat()
        )

        val topLeft = toBufferPoint(topLeftGrid)
        val topRight = toBufferPoint(topRightGrid)
        val bottomRight = toBufferPoint(bottomRightGrid)
        val bottomLeft = toBufferPoint(bottomLeftGrid)

        // Sanity checks: the four corners must form a reasonably sized, convex,
        // roughly-rectangular quad, otherwise noisy scenes with no real document would
        // constantly "detect" garbage (this is what used to produce a stray rectangle
        // nowhere near the actual paper/book).
        val quadArea = quadrilateralArea(topLeft, topRight, bottomRight, bottomLeft)
        val frameArea = cropW.toFloat() * cropH.toFloat()
        val areaRatio = quadArea / frameArea
        if (areaRatio < 0.12f || areaRatio > 0.97f) return fail()

        val topSide = distance(topLeft, topRight)
        val rightSide = distance(topRight, bottomRight)
        val bottomSide = distance(bottomRight, bottomLeft)
        val leftSide = distance(bottomLeft, topLeft)
        val minSide = min(min(topSide, rightSide), min(bottomSide, leftSide))
        val maxSide = max(max(topSide, rightSide), max(bottomSide, leftSide))
        if (minSide < cropW * 0.08f) return fail()
        // A real sheet of paper or book cover doesn't have one side wildly longer than
        // another -- reject thin slivers this heuristic would otherwise happily box up.
        if (maxSide / minSide > 3.2f) return fail()
        if (!isConvexQuad(topLeft, topRight, bottomRight, bottomLeft)) return fail()

        val rawCorners = listOf(topLeft, topRight, bottomRight, bottomLeft)
        val previous = smoothedCorners
        val corners = if (previous != null && previous.size == 4) {
            rawCorners.mapIndexed { i, p ->
                PointF(
                    previous[i].x + (p.x - previous[i].x) * 0.4f,
                    previous[i].y + (p.y - previous[i].y) * 0.4f
                )
            }
        } else {
            rawCorners
        }
        smoothedCorners = corners

        val previewPoints = mapToPreviewSpace(corners, cropRect, image.imageInfo.rotationDegrees) ?: return fail()
        val normalizedCorners = normalizeUpright(corners, cropRect, image.imageInfo.rotationDegrees)

        return DetectedDocument(previewPoints, normalizedCorners)
    }

    /** Clears the smoothing baseline so the next real detection doesn't ease in from a stale spot. */
    private fun fail(): DetectedDocument? {
        smoothedCorners = null
        return null
    }

    /** 3x3 box blur over the coarse luma grid, used to suppress single-pixel noise before edge detection. */
    private fun boxBlur3x3(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        for (gy in 0 until gridHeight) {
            for (gx in 0 until gridWidth) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val ny = gy + dy
                    if (ny < 0 || ny >= gridHeight) continue
                    for (dx in -1..1) {
                        val nx = gx + dx
                        if (nx < 0 || nx >= gridWidth) continue
                        sum += src[ny * gridWidth + nx].toInt() and 0xFF
                        count++
                    }
                }
                out[gy * gridWidth + gx] = (sum / count).toByte()
            }
        }
        return out
    }

    /**
     * Averages the coordinates of the [topK] edge points that score highest under [score],
     * in grid space. Used instead of a single argmax pick so one noisy outlier pixel can't
     * single-handedly relocate a corner.
     */
    private fun averageExtremePoint(
        xs: List<Int>,
        ys: List<Int>,
        topK: Int,
        score: (Int, Int) -> Int
    ): PointF? {
        if (xs.isEmpty()) return null
        val indices = xs.indices.sortedByDescending { score(xs[it], ys[it]) }
        val take = indices.take(topK.coerceAtMost(indices.size))
        var sumX = 0f
        var sumY = 0f
        for (i in take) {
            sumX += xs[i]
            sumY += ys[i]
        }
        return PointF(sumX / take.size, sumY / take.size)
    }

    /** Convex-polygon test via cross-product sign consistency around the four vertices in order. */
    private fun isConvexQuad(a: PointF, b: PointF, c: PointF, d: PointF): Boolean {
        val pts = listOf(a, b, c, d)
        var sign = 0
        for (i in pts.indices) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % pts.size]
            val p2 = pts[(i + 2) % pts.size]
            val cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
            val current = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (current == 0) continue
            if (sign == 0) sign = current else if (current != sign) return false
        }
        return sign != 0
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
