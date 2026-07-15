/*
 * color_grade.c — HSL adjustment, vibrance, and white balance for RGBA_8888.
 *
 * hslAdjustNative(bitmap, hueShiftDeg, satScale, lightAdd)
 *   Converts each pixel to HSL, applies:
 *     H += hueShiftDeg / 360  (wrapped)
 *     S  = clamp(S * satScale, 0, 1)
 *     L  = clamp(L + lightAdd, 0, 1)
 *   then converts back to RGB.  All-channel adjustment — great for correcting
 *   colour temperature in post (e.g. lightAdd=-0.05 to pull down blown highlights,
 *   satScale=1.2 for a subtle pop, hueShiftDeg=5 to warm a cool-toned shot).
 *
 * vibranceNative(bitmap, strength)
 *   Smart saturation: boosts each pixel's saturation by strength*(1-S), so
 *   already-vivid pixels are left nearly unchanged while dull/grey pixels get
 *   the most lift.  Unlike a uniform satScale this does not blow out skin tones
 *   or skies.  strength in [0,1]; 0.3 is a subtle, natural-looking boost.
 *
 * whiteBalanceNative(bitmap, rScale, gScale, bScale)
 *   Per-channel linear multiplier applied before clamping.  Correct a warm
 *   (orange-tinted) indoor shot with rScale<1, bScale>1; counteract a cool
 *   (blue) shade with rScale>1, bScale<1.  1.0 / 1.0 / 1.0 is a no-op.
 */
#ifndef COLOR_GRADE_C
#define COLOR_GRADE_C
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <math.h>

#define LOG_TAG "CamImg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

/* ---- HSL conversion helpers (all values normalised to [0,1]) ---- */

static void rgb_to_hsl(float r, float g, float b,
                        float *h, float *s, float *l)
{
    float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
    float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
    float delta = mx - mn;

    *l = (mx + mn) * 0.5f;

    if (delta < 1e-6f) {
        *h = 0.f;
        *s = 0.f;
        return;
    }

    *s = delta / (1.f - fabsf(2.f * (*l) - 1.f));

    if (mx == r)
        *h = (g - b) / delta + (g < b ? 6.f : 0.f);
    else if (mx == g)
        *h = (b - r) / delta + 2.f;
    else
        *h = (r - g) / delta + 4.f;
    *h /= 6.f;
}

static float hue_channel(float p, float q, float t) {
    if (t < 0.f) t += 1.f;
    if (t > 1.f) t -= 1.f;
    if (t < 1.f/6.f) return p + (q - p) * 6.f * t;
    if (t < 0.5f)    return q;
    if (t < 2.f/3.f) return p + (q - p) * (2.f/3.f - t) * 6.f;
    return p;
}

static void hsl_to_rgb(float h, float s, float l,
                        float *r, float *g, float *b)
{
    if (s < 1e-6f) {
        *r = *g = *b = l;
        return;
    }
    float q = l < 0.5f ? l * (1.f + s) : l + s - l * s;
    float p = 2.f * l - q;
    *r = hue_channel(p, q, h + 1.f/3.f);
    *g = hue_channel(p, q, h);
    *b = hue_channel(p, q, h - 1.f/3.f);
}

static float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }

/* ---- JNI exports ---- */

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_hslAdjustNative(
        JNIEnv *env, jobject thiz, jobject bmp,
        jfloat hueShiftDeg, jfloat satScale, jfloat lightAdd)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int N = (int)info.width * (int)info.height;
    float hueShift = hueShiftDeg / 360.f;
    uint32_t *pix = (uint32_t*)pixels;

    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&pix[i];
        float r = p[0] / 255.f, g = p[1] / 255.f, b = p[2] / 255.f;
        float h, s, l;
        rgb_to_hsl(r, g, b, &h, &s, &l);
        h = h + hueShift;
        if (h < 0.f) h += 1.f; else if (h > 1.f) h -= 1.f;
        s = clamp01(s * satScale);
        l = clamp01(l + lightAdd);
        hsl_to_rgb(h, s, l, &r, &g, &b);
        p[0] = (uint8_t)(r * 255.f + 0.5f);
        p[1] = (uint8_t)(g * 255.f + 0.5f);
        p[2] = (uint8_t)(b * 255.f + 0.5f);
        /* alpha unchanged */
    }

    AndroidBitmap_unlockPixels(env, bmp);
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_vibranceNative(
        JNIEnv *env, jobject thiz, jobject bmp, jfloat strength)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int N = (int)info.width * (int)info.height;
    if (strength < -1.f) strength = -1.f;
    if (strength >  1.f) strength =  1.f;
    uint32_t *pix = (uint32_t*)pixels;

    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&pix[i];
        float r = p[0] / 255.f, g = p[1] / 255.f, b = p[2] / 255.f;
        float h, s, l;
        rgb_to_hsl(r, g, b, &h, &s, &l);
        /* Vibrance boost scales inversely with current saturation. */
        float boost = strength * (1.f - s);
        s = clamp01(s + boost);
        hsl_to_rgb(h, s, l, &r, &g, &b);
        p[0] = (uint8_t)(r * 255.f + 0.5f);
        p[1] = (uint8_t)(g * 255.f + 0.5f);
        p[2] = (uint8_t)(b * 255.f + 0.5f);
    }

    AndroidBitmap_unlockPixels(env, bmp);
}

JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_whiteBalanceNative(
        JNIEnv *env, jobject thiz, jobject bmp,
        jfloat rScale, jfloat gScale, jfloat bScale)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    int N = (int)info.width * (int)info.height;
    /* Pre-scale to Q15 fixed-point for a branch-free hot path. */
    int rs = (int)(rScale * 32768.f + 0.5f);
    int gs = (int)(gScale * 32768.f + 0.5f);
    int bs = (int)(bScale * 32768.f + 0.5f);

    uint32_t *pix = (uint32_t*)pixels;
    for (int i = 0; i < N; i++) {
        uint8_t *p = (uint8_t*)&pix[i];
        int r = ((int)p[0] * rs) >> 15;
        int g = ((int)p[1] * gs) >> 15;
        int b = ((int)p[2] * bs) >> 15;
        p[0] = (uint8_t)(r > 255 ? 255 : r);
        p[1] = (uint8_t)(g > 255 ? 255 : g);
        p[2] = (uint8_t)(b > 255 ? 255 : b);
    }

    AndroidBitmap_unlockPixels(env, bmp);
}
#endif /* COLOR_GRADE_C */
