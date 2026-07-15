/*
 * histogram.c — per-channel histogram and auto-levels for RGBA_8888 bitmaps.
 *
 * autoLevelsNative(bitmap, clampFraction)
 *   Stretches the tonal range of each R, G, B channel independently so that
 *   the brightest clampFraction% of pixels map to 255 and the darkest
 *   clampFraction% map to 0.  Remaining pixels are linearly interpolated.
 *
 *   Effect: dramatically corrects underexposed or overexposed photos and
 *   neutralises colour casts from fluorescent / tungsten lighting, because
 *   each channel is stretched to its own extremes.
 *
 *   clampFraction = 0.005 (0.5 %) is a good default.  Higher values give more
 *   aggressive correction at the cost of clipping more highlight/shadow detail.
 *
 * computeHistogramNative(bitmap, histR, histG, histB)
 *   Fills three caller-provided int[256] arrays with the per-channel pixel
 *   frequency counts.  Useful for displaying an in-app histogram overlay or
 *   for driving other algorithms.
 */
#ifndef HISTOGRAM_C
#define HISTOGRAM_C
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "CamImg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

/* Map input value v in [lo,hi] linearly to [0,255]. */
static int al_stretch(int v, int lo, int hi) {
    if (lo >= hi) return 128;
    int out = (v - lo) * 255 / (hi - lo);
    return out < 0 ? 0 : (out > 255 ? 255 : out);
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_autoLevelsNative(
        JNIEnv *env, jobject thiz,
        jobject bmp, jfloat clampFraction)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int W = (int)info.width;
    int H = (int)info.height;
    int N = W * H;

    if (clampFraction < 0.f) clampFraction = 0.f;
    if (clampFraction > 0.1f) clampFraction = 0.1f;
    int clampCount = (int)(clampFraction * (float)N + 0.5f);

    /* Build per-channel histograms. */
    int histR[256], histG[256], histB[256];
    memset(histR, 0, sizeof(histR));
    memset(histG, 0, sizeof(histG));
    memset(histB, 0, sizeof(histB));

    const uint32_t *pix = (const uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        const uint8_t *p = (const uint8_t*)&pix[i];
        histR[p[0]]++;
        histG[p[1]]++;
        histB[p[2]]++;
    }

    /* Find lo/hi percentile for each channel. */
    int loR = 0, hiR = 255, loG = 0, hiG = 255, loB = 0, hiB = 255;
    {
        int cum = 0; int v;
        for (v = 0;   v < 256; v++) { cum += histR[v]; if (cum > clampCount) { loR = v; break; } }
        cum = 0;
        for (v = 255; v >= 0;  v--) { cum += histR[v]; if (cum > clampCount) { hiR = v; break; } }
        cum = 0;
        for (v = 0;   v < 256; v++) { cum += histG[v]; if (cum > clampCount) { loG = v; break; } }
        cum = 0;
        for (v = 255; v >= 0;  v--) { cum += histG[v]; if (cum > clampCount) { hiG = v; break; } }
        cum = 0;
        for (v = 0;   v < 256; v++) { cum += histB[v]; if (cum > clampCount) { loB = v; break; } }
        cum = 0;
        for (v = 255; v >= 0;  v--) { cum += histB[v]; if (cum > clampCount) { hiB = v; break; } }
    }

    /* Build 256-entry per-channel stretch LUTs. */
    uint8_t lutR[256], lutG[256], lutB[256];
    for (int v = 0; v < 256; v++) {
        lutR[v] = (uint8_t)al_stretch(v, loR, hiR);
        lutG[v] = (uint8_t)al_stretch(v, loG, hiG);
        lutB[v] = (uint8_t)al_stretch(v, loB, hiB);
    }

    /* Apply LUTs in-place; leave alpha untouched. */
    uint32_t *wpix = (uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&wpix[i];
        p[0] = lutR[p[0]];
        p[1] = lutG[p[1]];
        p[2] = lutB[p[2]];
    }

    AndroidBitmap_unlockPixels(env, bmp);
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_computeHistogramNative(
        JNIEnv *env, jobject thiz,
        jobject bmp,
        jintArray histR_jni, jintArray histG_jni, jintArray histB_jni)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int N = (int)info.width * (int)info.height;
    int hR[256], hG[256], hB[256];
    memset(hR, 0, sizeof(hR));
    memset(hG, 0, sizeof(hG));
    memset(hB, 0, sizeof(hB));

    const uint32_t *pix = (const uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        const uint8_t *p = (const uint8_t*)&pix[i];
        hR[p[0]]++; hG[p[1]]++; hB[p[2]]++;
    }

    AndroidBitmap_unlockPixels(env, bmp);

    (*env)->SetIntArrayRegion(env, histR_jni, 0, 256, hR);
    (*env)->SetIntArrayRegion(env, histG_jni, 0, 256, hG);
    (*env)->SetIntArrayRegion(env, histB_jni, 0, 256, hB);
}
#endif /* HISTOGRAM_C */
