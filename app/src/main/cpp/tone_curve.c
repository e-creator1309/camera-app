/*
 * tone_curve.c — 256-entry LUT tone curve and auto-contrast for RGBA_8888.
 *
 * applyToneCurveNative(bitmap, lut[256])
 *   Applies a caller-supplied 256-entry byte lookup table to the R, G, B
 *   channels of every pixel.  Alpha is untouched.
 *   The LUT is built in Kotlin (or wherever) from any shape of curve —
 *   S-curve, linear, custom points — and passed down as a raw byte[256].
 *   This makes the native side a pure, branchless table-lookup loop with
 *   no knowledge of the curve shape.
 *
 * autoContrastNative(bitmap)
 *   Builds and applies a luminance-based S-curve automatically:
 *     1. Compute perceived luminance L = 0.299R + 0.587G + 0.114B for each pixel.
 *     2. Build a 256-bin luminance histogram.
 *     3. Compute the cumulative histogram → equalisation map E[v] (0-255).
 *     4. Blend: adjusted = 0.4*E[v] + 0.6*v  (partial equalisation).
 *        Full equalisation over-processes; blending 40/60 gives a natural
 *        contrast boost without the flat-sky / blown-highlight artefacts of
 *        pure histogram equalisation.
 *     5. Apply the blended map as a unified RGB LUT.
 *   This produces the "punch" that makes a flat shot look immediately better
 *   without touching colour (it moves all three channels by the same amount).
 */
#ifndef TONE_CURVE_C
#define TONE_CURVE_C
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "CamImg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_applyToneCurveNative(
        JNIEnv *env, jobject thiz,
        jobject bmp, jbyteArray lut_jni)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if ((*env)->GetArrayLength(env, lut_jni) < 256)      return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    jbyte *lut = (*env)->GetByteArrayElements(env, lut_jni, NULL);
    if (!lut) { AndroidBitmap_unlockPixels(env, bmp); return; }

    /* Cast lut to unsigned so indexing by value works correctly.
     * Java byte is signed; reinterpret as uint8_t. */
    const uint8_t *ulut = (const uint8_t*)lut;

    int N = (int)info.width * (int)info.height;
    uint32_t *pix = (uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&pix[i];
        p[0] = ulut[p[0]];
        p[1] = ulut[p[1]];
        p[2] = ulut[p[2]];
        /* alpha untouched */
    }

    (*env)->ReleaseByteArrayElements(env, lut_jni, lut, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bmp);
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_autoContrastNative(
        JNIEnv *env, jobject thiz, jobject bmp)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int W = (int)info.width;
    int H = (int)info.height;
    int N = W * H;
    const uint32_t *pix = (const uint32_t*)pixels;

    /* Step 1-2: luminance histogram (integer weights: 299/587/114 → sum 1000). */
    int hist[256];
    memset(hist, 0, sizeof(hist));
    for (int i = 0; i < N; i++) {
        const uint8_t *p = (const uint8_t*)&pix[i];
        int lum = (299 * (int)p[0] + 587 * (int)p[1] + 114 * (int)p[2]) / 1000;
        if (lum > 255) lum = 255;
        hist[lum]++;
    }

    /* Step 3: cumulative histogram → equalisation map E[v] in [0,255]. */
    uint8_t eq[256];
    {
        long cum = 0;
        long scale = (long)255 * 256; /* avoid divide inside loop */
        for (int v = 0; v < 256; v++) {
            cum += hist[v];
            long mapped = (cum * 255L) / N;
            eq[v] = (uint8_t)(mapped > 255 ? 255 : mapped);
        }
    }

    /* Step 4: blend 40 % equalised + 60 % linear.
     * unified_lut[v] = round(0.4 * eq[v] + 0.6 * v) */
    uint8_t lut[256];
    for (int v = 0; v < 256; v++) {
        int blended = (int)(409 * (int)eq[v] + 614 * v + 512) >> 10; /* Q10 */
        lut[v] = (uint8_t)(blended > 255 ? 255 : blended);
    }

    /* Step 5: apply unified RGB LUT in-place. */
    uint32_t *wpix = (uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&wpix[i];
        p[0] = lut[p[0]];
        p[1] = lut[p[1]];
        p[2] = lut[p[2]];
    }

    AndroidBitmap_unlockPixels(env, bmp);
}
#endif /* TONE_CURVE_C */
