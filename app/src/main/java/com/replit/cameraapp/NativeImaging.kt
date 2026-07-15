package com.replit.cameraapp

import android.graphics.Bitmap

/** JNI bridge to libcamimg.so — native C image processing. Loaded on first access. */
object NativeImaging {
    init { System.loadLibrary("camimg") }

    // ── doc_warp.c ────────────────────────────────────────────────────────────

    /** Perspective-warp [srcBitmap] into [dstBitmap] using a 9-float inverse homography. */
    external fun warpDocumentNative(srcBitmap: Bitmap, dstBitmap: Bitmap, invMatrix: FloatArray): Boolean

    // ── portrait_blur.c ───────────────────────────────────────────────────────

    /** 3-pass separable box blur (~Gaussian) at [radius] pixels, in place. Bitmap must be ARGB_8888. */
    external fun stackBlurNative(bitmap: Bitmap, radius: Int)

    /** Blend sharp+blurred ARGB int arrays using ML Kit confidence weights. */
    external fun blendPixelsNative(sharpPixels: IntArray, blurredPixels: IntArray,
                                   confidence: FloatArray, outPixels: IntArray, count: Int)

    // ── zoom_smooth.c ─────────────────────────────────────────────────────────

    /**
     * Single EMA zoom step: next = current + alpha*(target−current), clamped to [minZoom, maxZoom].
     * Call at ~30 fps to drive the live zoom toward a pinch target without flooding the HAL.
     */
    external fun lerpZoomNative(current: Float, target: Float, alpha: Float,
                                minZoom: Float, maxZoom: Float): Float

    /** Returns true when |target − current| < [threshold]; used to stop the zoom animation loop. */
    external fun isZoomSettledNative(current: Float, target: Float, threshold: Float): Boolean

    // ── denoise.c ─────────────────────────────────────────────────────────────

    /**
     * Edge-preserving bilateral denoiser, in place.
     *
     * @param bitmap     ARGB_8888 bitmap to denoise.
     * @param radius     Neighbourhood half-size in pixels (1–6). Typical: 2 (5×5 kernel).
     * @param sigmaColor Range bandwidth — how much colour difference is allowed before a
     *                   neighbouring pixel is excluded.  25 is a sensible default; lower
     *                   values preserve edges more aggressively.
     * @param sigmaSpace Spatial falloff.  10 keeps the filter local; increase for smoother
     *                   results at the cost of bleeding across fine edges.
     */
    external fun bilateralDenoiseNative(bitmap: Bitmap, radius: Int,
                                        sigmaColor: Float, sigmaSpace: Float)

    // ── sharpen.c ─────────────────────────────────────────────────────────────

    /**
     * Unsharp masking, in place.
     *
     * @param bitmap   ARGB_8888 bitmap to sharpen.
     * @param radius   Blur radius used to compute the mask (1–8). Typical: 1.
     * @param strength Amount of detail to add back (0 = no-op, 1 = full USM). Typical: 0.55.
     */
    external fun unsharpMaskNative(bitmap: Bitmap, radius: Int, strength: Float)

    // ── histogram.c ───────────────────────────────────────────────────────────

    /**
     * Per-channel auto-levels stretch, in place.
     *
     * Finds the [clampFraction] darkest/brightest pixels per channel and stretches the
     * remaining range to [0, 255].  Corrects exposure and colour casts simultaneously.
     * Typical [clampFraction]: 0.005 (0.5 %).
     */
    external fun autoLevelsNative(bitmap: Bitmap, clampFraction: Float)

    /**
     * Compute per-channel 256-bin histograms.
     * Arrays [histR], [histG], [histB] must each have length ≥ 256.
     */
    external fun computeHistogramNative(bitmap: Bitmap,
                                        histR: IntArray, histG: IntArray, histB: IntArray)

    // ── color_grade.c ─────────────────────────────────────────────────────────

    /**
     * HSL adjustment, in place.
     *
     * @param hueShiftDeg Hue rotation in degrees (−180 … +180). 0 = no shift.
     * @param satScale    Saturation multiplier (0 = greyscale, 1 = unchanged, 1.3 = vivid).
     * @param lightAdd    Lightness addend in [−1, 1] (−0.05 darkens, +0.05 brightens).
     */
    external fun hslAdjustNative(bitmap: Bitmap,
                                 hueShiftDeg: Float, satScale: Float, lightAdd: Float)

    /**
     * Smart saturation boost (vibrance), in place.
     *
     * Boosts the saturation of each pixel by [strength]×(1−currentSaturation), so dull
     * colours get the most lift while vivid colours (skin, sky) are left nearly untouched.
     * @param strength 0 = no-op, 0.3 = subtle, 1.0 = strong.
     */
    external fun vibranceNative(bitmap: Bitmap, strength: Float)

    /**
     * Per-channel white-balance correction via linear scaling, in place.
     *
     * @param rScale Red   multiplier (< 1 cools, > 1 warms).
     * @param gScale Green multiplier (usually near 1.0).
     * @param bScale Blue  multiplier (> 1 cools, < 1 warms).
     */
    external fun whiteBalanceNative(bitmap: Bitmap, rScale: Float, gScale: Float, bScale: Float)

    // ── tone_curve.c ──────────────────────────────────────────────────────────

    /**
     * Apply a 256-entry byte LUT to R, G, B channels, in place.  Alpha unchanged.
     * Build any curve shape in Kotlin and pass it down; the native side is a pure
     * table-lookup loop with no curve knowledge.
     */
    external fun applyToneCurveNative(bitmap: Bitmap, lut: ByteArray)

    /**
     * Automatic contrast enhancement via partial luminance equalisation, in place.
     *
     * Computes a luminance histogram, derives a cumulative-distribution equalisation map,
     * blends it 40 % with the identity, and applies the result as a unified RGB LUT.
     * Equivalent to a gentle auto-contrast that punches up flat shots without the harsh
     * artefacts of full histogram equalisation.
     */
    external fun autoContrastNative(bitmap: Bitmap)
}
