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
 * Lightweight, dependency-free document/paper detector for [ImageAnalysis].
 *
 * There's no OpenCV or ML model here. Earlier versions of this analyzer picked out raw
 * high-contrast *edge pixels* and took the most extreme one in each corner direction --
 * that fell apart on anything with texture, because dense handwriting/text or a cluttered,
 * grainy background produces edge pixels everywhere, not just at the paper's boundary, so
 * the "corners" ended up scattered across whatever was busiest in frame rather than the
 * actual sheet of paper.
 *
 * This version instead segments the frame into two brightness classes with an automatic
 * (Otsu) threshold, finds the single largest *connected* region of one class, and only then
 * takes that region's corners. A real sheet of paper is a solid, contiguous blob of roughly
 * one brightness against a differently-lit background -- it stays one blob no matter how much
 * handwriting or texture is on it, whereas a busy/cluttered background breaks into many small,
 * disconnected regions that lose out to the paper on size. Concretely:
 *
 *  - a 3x3 box blur runs before thresholding, so sensor noise doesn't fragment the blob;
 *  - Otsu's method picks the brightness split automatically per-frame instead of a fixed
 *    threshold, so it adapts to the actual lighting instead of assuming a fixed exposure;
 *  - both the brighter-than-threshold and darker-than-threshold classes are tried (light
 *    paper on a dark desk, or a dark cover on a light desk both work);
 *  - a flood fill finds the largest connected component of that class, and corners are the
 *    *average* of several of that component's most extreme points in each direction, so one
 *    jagged pixel at the mask boundary can't drag a corner out to the wrong spot;
 *  - the region has to actually fill most of its own bounding quad ("fill ratio"), which
 *    rejects sparse, scattered regions that happen to have the right bounding box but aren't
 *    really a solid rectangle; and
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

    private companion object {
        // 4-connectivity neighbor offsets (right, left, down, up), used by [findLargestComponent].
        val NEIGHBOR_DX = intArrayOf(1, -1, 0, 0)
        val NEIGHBOR_DY = intArrayOf(0, 0, 1, -1)
    }

    private val gridWidth = 160
    private val gridHeight = 214
    private val margin = 6 // ignore a thin border, where lens vignetting/noise causes false regions
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

        // Light denoise before thresholding -- without this, isolated noisy pixels fragment
        // the paper's blob into smaller pieces that then lose out to the background on size.
        val blurred = boxBlur3x3(gray)

        // Automatic brightness split (Otsu's method) over the interior of the frame, so the
        // threshold adapts to actual lighting instead of assuming a fixed exposure.
        val histogram = IntArray(256)
        var sampleCount = 0
        for (gy in margin until gridHeight - margin) {
            for (gx in margin until gridWidth - margin) {
                histogram[blurred[gy * gridWidth + gx].toInt() and 0xFF]++
                sampleCount++
            }
        }
        if (sampleCount == 0) return fail()
        val threshold = otsuThreshold(histogram, sampleCount)

        val brightMask = BooleanArray(gridWidth * gridHeight) { i -> (blurred[i].toInt() and 0xFF) > threshold }
        val darkMask = BooleanArray(gridWidth * gridHeight) { i -> !brightMask[i] }

        // Try the brighter class first -- the common case of light paper on a darker
        // desk/background -- then fall back to the darker class (e.g. a dark cover on a
        // light desk). Whichever one actually forms a solid, rectangular blob wins.
        val document = buildDocument(brightMask, cropRect, cropW, cropH, image.imageInfo.rotationDegrees)
            ?: buildDocument(darkMask, cropRect, cropW, cropH, image.imageInfo.rotationDegrees)

        return document ?: fail()
    }

    /**
     * Attempts to find a document-shaped quadrilateral from the largest connected component
     * of [mask], returning `null` (without touching [smoothedCorners]) if this particular
     * mask doesn't produce a convincing one -- the caller tries the opposite mask next.
     */
    private fun buildDocument(
        mask: BooleanArray,
        cropRect: Rect,
        cropW: Int,
        cropH: Int,
        rotationDegrees: Int
    ): DetectedDocument? {
        val component = findLargestComponent(mask) ?: return null
        val (compXs, compYs) = component

        // Too small to be a document filling any meaningful part of the frame.
        val minComponentCells = (gridWidth * gridHeight) / 50
        if (compXs.size < minComponentCells) return null

        // For each frame corner, average the strongest handful of this component's points
        // that push furthest toward it, rather than trusting a single most-extreme pixel.
        val topK = 6
        val xsList = compXs.toList()
        val ysList = compYs.toList()
        val topLeftGrid = averageExtremePoint(xsList, ysList, topK) { x, y -> -(x + y) }
        val topRightGrid = averageExtremePoint(xsList, ysList, topK) { x, y -> x - y }
        val bottomRightGrid = averageExtremePoint(xsList, ysList, topK) { x, y -> x + y }
        val bottomLeftGrid = averageExtremePoint(xsList, ysList, topK) { x, y -> y - x }
        if (topLeftGrid == null || topRightGrid == null || bottomRightGrid == null || bottomLeftGrid == null) {
            return null
        }

        // The component has to actually fill most of its own bounding quad -- this is what
        // separates a solid sheet of paper from a scattered, oddly-shaped patch of background
        // that happens to share a brightness class but isn't really a rectangle.
        val quadAreaGrid = quadrilateralArea(topLeftGrid, topRightGrid, bottomRightGrid, bottomLeftGrid)
        val fillRatio = compXs.size.toFloat() / quadAreaGrid.coerceAtLeast(1f)
        if (fillRatio < 0.55f) return null

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
        // constantly "detect" garbage.
        val quadArea = quadrilateralArea(topLeft, topRight, bottomRight, bottomLeft)
        val frameArea = cropW.toFloat() * cropH.toFloat()
        val areaRatio = quadArea / frameArea
        if (areaRatio < 0.20f || areaRatio > 0.92f) return null

        val topSide = distance(topLeft, topRight)
        val rightSide = distance(topRight, bottomRight)
        val bottomSide = distance(bottomRight, bottomLeft)
        val leftSide = distance(bottomLeft, topLeft)
        val minSide = min(min(topSide, rightSide), min(bottomSide, leftSide))
        val maxSide = max(max(topSide, rightSide), max(bottomSide, leftSide))
        if (minSide < cropW * 0.08f) return null
        // A real sheet of paper or book cover doesn't have one side wildly longer than
        // another -- reject thin slivers this heuristic would otherwise happily box up.
        if (maxSide / minSide > 2.5f) return null
        if (!isConvexQuad(topLeft, topRight, bottomRight, bottomLeft)) return null

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

        val previewPoints = mapToPreviewSpace(corners, cropRect, rotationDegrees) ?: return null
        // Re-sort into true [TL, TR, BR, BL] visual order in upright space.
        // normalizeUpright transforms coords but does NOT reorder — buffer-space
        // "topLeft" is the visual top-right after a 90° phone rotation, causing
        // the 180° document-crop flip reported by users.
        val normalizedCorners = sortToTlTrBrBl(normalizeUpright(corners, cropRect, rotationDegrees))

        return DetectedDocument(previewPoints, normalizedCorners)
    }

    /** Clears the smoothing baseline so the next real detection doesn't ease in from a stale spot. */
    private fun fail(): DetectedDocument? {
        smoothedCorners = null
        return null
    }

    /** 3x3 box blur over the coarse luma grid, used to suppress single-pixel noise before thresholding. */
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
     * Otsu's method: picks the brightness value that best splits [histogram] (a 256-bin luma
     * histogram covering [totalSamples] pixels) into two classes by maximizing the variance
     * *between* the two classes' means -- the standard automatic-thresholding technique for
     * separating a bright object from a dark background (or vice versa) regardless of exposure.
     */
    private fun otsuThreshold(histogram: IntArray, totalSamples: Int): Int {
        var sumAll = 0L
        for (level in histogram.indices) sumAll += level.toLong() * histogram[level]

        var weightBelow = 0L
        var sumBelow = 0L
        var bestVariance = -1.0
        var bestThreshold = 127
        for (level in 0..255) {
            weightBelow += histogram[level]
            if (weightBelow == 0L) continue
            val weightAbove = totalSamples - weightBelow
            if (weightAbove == 0L) break
            sumBelow += level.toLong() * histogram[level]

            val meanBelow = sumBelow.toDouble() / weightBelow
            val meanAbove = (sumAll - sumBelow).toDouble() / weightAbove
            val meanDelta = meanBelow - meanAbove
            val betweenClassVariance = weightBelow.toDouble() * weightAbove.toDouble() * meanDelta * meanDelta
            if (betweenClassVariance > bestVariance) {
                bestVariance = betweenClassVariance
                bestThreshold = level
            }
        }
        return bestThreshold
    }

    /**
     * Flood-fills [mask] (grid-space, `true` = this class) within the margin bounds and
     * returns the grid coordinates of its largest 4-connected component, or `null` if [mask]
     * has no set pixels there at all.
     */
    private fun findLargestComponent(mask: BooleanArray): Pair<IntArray, IntArray>? {
        val visited = BooleanArray(gridWidth * gridHeight)
        val stackX = IntArray(gridWidth * gridHeight)
        val stackY = IntArray(gridWidth * gridHeight)
        var bestXs: IntArray? = null
        var bestYs: IntArray? = null
        var bestSize = 0

        for (startY in margin until gridHeight - margin) {
            for (startX in margin until gridWidth - margin) {
                val startIndex = startY * gridWidth + startX
                if (visited[startIndex] || !mask[startIndex]) continue

                var stackSize = 1
                stackX[0] = startX
                stackY[0] = startY
                visited[startIndex] = true
                val compXs = ArrayList<Int>()
                val compYs = ArrayList<Int>()

                while (stackSize > 0) {
                    stackSize--
                    val x = stackX[stackSize]
                    val y = stackY[stackSize]
                    compXs.add(x)
                    compYs.add(y)

                    for (dir in 0 until 4) {
                        val nx = x + NEIGHBOR_DX[dir]
                        val ny = y + NEIGHBOR_DY[dir]
                        if (nx < margin || nx >= gridWidth - margin || ny < margin || ny >= gridHeight - margin) continue
                        val nIndex = ny * gridWidth + nx
                        if (visited[nIndex] || !mask[nIndex]) continue
                        visited[nIndex] = true
                        stackX[stackSize] = nx
                        stackY[stackSize] = ny
                        stackSize++
                    }
                }

                if (compXs.size > bestSize) {
                    bestSize = compXs.size
                    bestXs = compXs.toIntArray()
                    bestYs = compYs.toIntArray()
                }
            }
        }

        val xs = bestXs ?: return null
        val ys = bestYs ?: return null
        return xs to ys
    }

    /**
     * Averages the coordinates of the [topK] points that score highest under [score], in grid
     * space. Used instead of a single argmax pick so one jagged outlier pixel at the mask
     * boundary can't single-handedly relocate a corner.
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

    /** Re-sorts four normalized (0..1) upright-space points into [TL, TR, BR, BL] visual order.
     *  TL = min(x+y), BR = max(x+y), TR = max(x-y), BL = min(x-y). */
    private fun sortToTlTrBrBl(corners: List<PointF>): List<PointF> {
        if (corners.size != 4) return corners
        val bySum = corners.sortedBy { it.x + it.y }
        val tl = bySum[0]; val br = bySum[3]
        val mid = listOf(bySum[1], bySum[2])
        val tr = mid.maxByOrNull { it.x - it.y }!!
        val bl = mid.minByOrNull { it.x - it.y }!!
        return listOf(tl, tr, br, bl)
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
