/*
 * sharpen.c — unsharp masking (USM) for RGBA_8888 bitmaps.
 *
 * unsharpMaskNative(bitmap, radius, strength)
 *
 * Algorithm:
 *   1. Box-blur the input into a temporary buffer (3 passes ≈ Gaussian,
 *      reusing the same separable approach as portrait_blur.c).
 *   2. For every pixel channel c:
 *        detail   = original[c] - blurred[c]
 *        if |detail| > threshold:  out[c] = clamp(original[c] + strength * detail)
 *        else:                      out[c] = original[c]       (don't sharpen noise)
 *
 * The threshold prevents noise from being amplified in flat regions.  A value
 * of 8 (out of 255) works well for camera JPEGs; callers should not need to
 * change it, so it is a compile-time constant here.
 *
 * Typical params: radius=1, strength=0.6  → subtle crispness recovery.
 *                 radius=1, strength=1.2  → visible sharpening for soft lenses.
 *                 radius=2, strength=0.5  → wider-radius "clarity"-style boost.
 */
#ifndef SHARPEN_C
#define SHARPEN_C
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdint.h>

#define LOG_TAG "CamImg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

/* Minimum per-channel difference required before sharpening is applied.
 * Keeps noise flat in low-contrast regions. */
#define USM_THRESHOLD 8

/* Internal separable box-blur used solely for USM.  Same algorithm as
 * portrait_blur.c but operates on a separate src/dst pair. */
static void usm_box_h(const uint32_t *src, uint32_t *dst, int W, int H, int r) {
    int ks = 2 * r + 1;
    for (int y = 0; y < H; y++) {
        int s[3] = {0, 0, 0};
        for (int x = -r; x <= r; x++) {
            int cx = x < 0 ? 0 : (x >= W ? W - 1 : x);
            const uint8_t *p = (const uint8_t*)&src[y * W + cx];
            s[0] += p[0]; s[1] += p[1]; s[2] += p[2];
        }
        for (int x = 0; x < W; x++) {
            uint8_t *o = (uint8_t*)&dst[y * W + x];
            o[0] = (uint8_t)(s[0] / ks);
            o[1] = (uint8_t)(s[1] / ks);
            o[2] = (uint8_t)(s[2] / ks);
            o[3] = ((const uint8_t*)&src[y * W + x])[3]; /* copy alpha */
            int ox = x - r < 0 ? 0 : x - r;
            int ix = x + r + 1 >= W ? W - 1 : x + r + 1;
            const uint8_t *po = (const uint8_t*)&src[y * W + ox];
            const uint8_t *pi = (const uint8_t*)&src[y * W + ix];
            s[0] += pi[0] - po[0];
            s[1] += pi[1] - po[1];
            s[2] += pi[2] - po[2];
        }
    }
}

static void usm_box_v(const uint32_t *src, uint32_t *dst, int W, int H, int r) {
    int ks = 2 * r + 1;
    for (int x = 0; x < W; x++) {
        int s[3] = {0, 0, 0};
        for (int y = -r; y <= r; y++) {
            int cy = y < 0 ? 0 : (y >= H ? H - 1 : y);
            const uint8_t *p = (const uint8_t*)&src[cy * W + x];
            s[0] += p[0]; s[1] += p[1]; s[2] += p[2];
        }
        for (int y = 0; y < H; y++) {
            uint8_t *o = (uint8_t*)&dst[y * W + x];
            o[0] = (uint8_t)(s[0] / ks);
            o[1] = (uint8_t)(s[1] / ks);
            o[2] = (uint8_t)(s[2] / ks);
            o[3] = ((const uint8_t*)&src[y * W + x])[3];
            int oy = y - r < 0 ? 0 : y - r;
            int iy = y + r + 1 >= H ? H - 1 : y + r + 1;
            const uint8_t *po = (const uint8_t*)&src[oy * W + x];
            const uint8_t *pi = (const uint8_t*)&src[iy * W + x];
            s[0] += pi[0] - po[0];
            s[1] += pi[1] - po[1];
            s[2] += pi[2] - po[2];
        }
    }
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_unsharpMaskNative(
        JNIEnv *env, jobject thiz,
        jobject bmp,
        jint radius, jfloat strength)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int W = (int)info.width;
    int H = (int)info.height;
    if (radius < 1) radius = 1;
    if (radius > 8) radius = 8;

    size_t sz = (size_t)W * H * sizeof(uint32_t);
    uint32_t *orig = (uint32_t*)pixels;
    uint32_t *blur = (uint32_t*)malloc(sz);
    uint32_t *tmp  = (uint32_t*)malloc(sz);
    if (!blur || !tmp) { free(blur); free(tmp); AndroidBitmap_unlockPixels(env, bmp); return; }

    /* 3-pass box blur in blur[] (orig → tmp → blur). */
    for (int pass = 0; pass < 3; pass++) {
        const uint32_t *s = (pass == 0) ? orig : (pass % 2 == 1 ? blur : tmp);
        uint32_t       *d = (pass % 2 == 0) ? tmp : blur;
        usm_box_h(s, d, W, H, radius);

        const uint32_t *s2 = d;
        uint32_t       *d2 = (d == tmp) ? blur : tmp;
        usm_box_v(s2, d2, W, H, radius);

        /* After vertical pass the result is in d2; alias blur to it. */
        if (d2 != blur) {
            uint32_t *swap = blur; blur = tmp; tmp = swap;
        }
    }
    /* blur[] now holds the 3-pass blurred version. */

    /* Apply unsharp mask: orig + strength*(orig - blur), clamped. */
    int str_fixed = (int)(strength * 256.f + 0.5f); /* Q8 fixed-point */
    for (int i = 0; i < W * H; i++) {
        uint8_t *o = (uint8_t*)&orig[i];
        const uint8_t *b = (const uint8_t*)&blur[i];
        for (int c = 0; c < 3; c++) {
            int detail = (int)o[c] - (int)b[c];
            if (detail < 0 ? -detail : detail) {
                if ((detail < -USM_THRESHOLD) || (detail > USM_THRESHOLD)) {
                    int v = (int)o[c] + ((str_fixed * detail) >> 8);
                    o[c] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
                }
            }
        }
        /* alpha unchanged */
    }

    free(blur);
    free(tmp);
    AndroidBitmap_unlockPixels(env, bmp);
}
#endif /* SHARPEN_C */
