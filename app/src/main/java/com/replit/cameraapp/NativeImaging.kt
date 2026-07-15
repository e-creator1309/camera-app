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
    }
    