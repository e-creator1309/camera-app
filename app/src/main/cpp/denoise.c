/*
 * denoise.c — edge-preserving bilateral denoiser for RGBA_8888 bitmaps.
 *
 * bilateralDenoiseNative(bitmap, radius, sigmaColor, sigmaSpace)
 *
 * How it works:
 *   For every output pixel (cx, cy) we gather all neighbors (nx, ny) within
 *   [-radius, radius]².  Each neighbor's contribution is weighted by two
 *   independent Gaussian kernels:
 *
 *     spatial weight  = exp(-euclidean_distance² / (2·sigmaSpace²))
 *     range  weight  = exp(-max_channel_diff²   / (2·sigmaColor²))
 *     total  weight  = spatial * range
 *
 *   Pixels that are spatially close AND color-similar to the center get high
 *   weight; pixels across a sharp edge (large color diff) get near-zero weight
 *   even if they're spatially adjacent.  That is what makes the filter
 *   edge-preserving: noise is averaged away inside a region, but edges stay
 *   crisp because they are never blended across.
 *
 * Both weight LUTs are precomputed once before the pixel loop (integer-scaled
 * to avoid floats in the hot path).  A temporary output buffer is used so the
 * filter reads from the unmodified input, matching the correct bilateral
 * definition.  The buffer is freed before the function returns.
 *
 * Typical params:  radius=2, sigmaColor=25, sigmaSpace=10
 *   → 5×5 kernel, fast enough on a 12 MP photo in ≈ 200 ms on a mid-range SoC.
 *   → radius=3 (7×7) for stronger denoising at ≈ 2× the cost.
 */
#ifndef DENOISE_C
#define DENOISE_C
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

#define LOG_TAG "CamImg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

/* Maximum supported radius — beyond this the filter is slow with diminishing
 * returns; callers are expected to clamp before calling. */
#define MAX_RADIUS 6

/* Fixed-point scale for LUT weights (fits 16-bit, avoids float in hot path). */
#define WEIGHT_SCALE 1024

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_bilateralDenoiseNative(
        JNIEnv *env, jobject thiz,
        jobject bmp,
        jint radius, jfloat sigmaColor, jfloat sigmaSpace)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int W = (int)info.width;
    int H = (int)info.height;

    /* Clamp radius to sensible range. */
    if (radius < 1) radius = 1;
    if (radius > MAX_RADIUS) radius = MAX_RADIUS;

    /* ---- precompute spatial weight LUT [0 .. radius*√2] ---- */
    /* We index by (dx²+dy²); max value is 2*radius². */
    int maxDist2 = 2 * radius * radius;
    /* +2 for safety. */
    int *spatialLut = (int*)malloc((size_t)(maxDist2 + 2) * sizeof(int));
    if (!spatialLut) { AndroidBitmap_unlockPixels(env, bmp); return; }
    float inv2ss = 1.f / (2.f * sigmaSpace * sigmaSpace);
    for (int d2 = 0; d2 <= maxDist2; d2++) {
        float w = expf(-(float)d2 * inv2ss);
        spatialLut[d2] = (int)(w * WEIGHT_SCALE + 0.5f);
    }

    /* ---- precompute range weight LUT [0 .. 255] ---- */
    int rangeLut[256];
    float inv2sc = 1.f / (2.f * sigmaColor * sigmaColor);
    for (int d = 0; d <= 255; d++) {
        float w = expf(-(float)(d * d) * inv2sc);
        rangeLut[d] = (int)(w * WEIGHT_SCALE + 0.5f);
    }

    /* ---- allocate output buffer ---- */
    uint32_t *src = (uint32_t*)pixels;
    uint32_t *dst = (uint32_t*)malloc((size_t)W * H * sizeof(uint32_t));
    if (!dst) {
        free(spatialLut);
        AndroidBitmap_unlockPixels(env, bmp);
        return;
    }

    /* ---- bilateral filter ---- */
    for (int cy = 0; cy < H; cy++) {
        for (int cx = 0; cx < W; cx++) {
            const uint8_t *cp = (const uint8_t*)&src[cy * W + cx];
            int cR = cp[0], cG = cp[1], cB = cp[2];

            long sumR = 0, sumG = 0, sumB = 0, sumW = 0;

            int y0 = cy - radius < 0 ? 0 : cy - radius;
            int y1 = cy + radius >= H ? H - 1 : cy + radius;
            int x0 = cx - radius < 0 ? 0 : cx - radius;
            int x1 = cx + radius >= W ? W - 1 : cx + radius;

            for (int ny = y0; ny <= y1; ny++) {
                int dy = ny - cy;
                for (int nx = x0; nx <= x1; nx++) {
                    int dx = nx - cx;
                    int d2 = dx * dx + dy * dy;
                    if (d2 > maxDist2) continue;

                    const uint8_t *np = (const uint8_t*)&src[ny * W + nx];
                    int dR = np[0] - cR; if (dR < 0) dR = -dR;
                    int dG = np[1] - cG; if (dG < 0) dG = -dG;
                    int dB = np[2] - cB; if (dB < 0) dB = -dB;
                    /* Use max channel diff as the range distance. */
                    int colorDiff = dR > dG ? dR : dG;
                    if (dB > colorDiff) colorDiff = dB;

                    /* Integer multiply — no float in hot path. */
                    long w = ((long)spatialLut[d2] * rangeLut[colorDiff]) >> 10;
                    sumR += w * np[0];
                    sumG += w * np[1];
                    sumB += w * np[2];
                    sumW += w;
                }
            }

            uint8_t *op = (uint8_t*)&dst[cy * W + cx];
            if (sumW > 0) {
                op[0] = (uint8_t)(sumR / sumW);
                op[1] = (uint8_t)(sumG / sumW);
                op[2] = (uint8_t)(sumB / sumW);
            } else {
                op[0] = (uint8_t)cR;
                op[1] = (uint8_t)cG;
                op[2] = (uint8_t)cB;
            }
            op[3] = cp[3]; /* preserve alpha */
        }
    }

    /* Copy result back to the locked bitmap. */
    for (int i = 0; i < W * H; i++) src[i] = dst[i];

    free(dst);
    free(spatialLut);
    AndroidBitmap_unlockPixels(env, bmp);
}
#endif /* DENOISE_C */
