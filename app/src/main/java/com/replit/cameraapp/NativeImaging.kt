package com.replit.cameraapp

import android.graphics.Bitmap

/** JNI bridge to libcamimg.so — native C image processing. Loaded on first access. */
object NativeImaging {
    init { System.loadLibrary("camimg") }

    /** Perspective-warp [srcBitmap] into [dstBitmap] using 9-float inverse homography from Matrix.getValues(). */
    external fun warpDocumentNative(srcBitmap: Bitmap, dstBitmap: Bitmap, invMatrix: FloatArray): Boolean

    /** 3-pass separable box blur (~Gaussian) at [radius] pixels, in place. Bitmap must be ARGB_8888. */
    external fun stackBlurNative(bitmap: Bitmap, radius: Int)

    /** Blend sharp+blurred Java-ARGB (0xAARRGGBB) int arrays using ML Kit confidence weights. */
    external fun blendPixelsNative(sharpPixels: IntArray, blurredPixels: IntArray,
                                   confidence: FloatArray, outPixels: IntArray, count: Int)

    /**
     * Single EMA zoom step: next = current + alpha*(target-current), clamped to [minZoom, maxZoom].
     * Call at ~30fps to smoothly drive the live zoom toward a pinch-gesture target without
     * flooding the camera HAL (which causes frame drops when called at raw gesture rate ~120fps).
     */
    external fun lerpZoomNative(current: Float, target: Float, alpha: Float,
                                minZoom: Float, maxZoom: Float): Float

    /**
     * Returns true when |target - current| < [threshold].
     * Used by the zoom-smoothing loop to know when to stop calling setZoomRatio.
     */
    external fun isZoomSettledNative(current: Float, target: Float, threshold: Float): Boolean
}
