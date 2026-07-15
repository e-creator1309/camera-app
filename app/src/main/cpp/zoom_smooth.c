/*
 * zoom_smooth.c — native zoom interpolation helpers for libcamimg.
 *
 * lerpZoomNative:
 *   Single exponential-moving-average (EMA) step.
 *   next = current + alpha * (target - current), clamped to [minZoom, maxZoom].
 *   With alpha=0.25 and a 33 ms step interval (~30fps) the camera settles in
 *   ~5 frames, which feels instant to the user while keeping the HAL call rate
 *   low enough to prevent frame drops during rapid pinch gestures.
 *
 * isZoomSettledNative:
 *   Returns JNI_TRUE when |target - current| < threshold.  The Kotlin caller
 *   uses this to stop looping and avoid unnecessary setZoomRatio calls at rest.
 */
#ifndef ZOOM_SMOOTH_C
#define ZOOM_SMOOTH_C
#include <jni.h>

JNIEXPORT jfloat JNICALL
Java_com_replit_cameraapp_NativeImaging_lerpZoomNative(
        JNIEnv *env, jobject thiz,
        jfloat current, jfloat target, jfloat alpha,
        jfloat minZoom, jfloat maxZoom)
{
    float next = current + alpha * (target - current);
    if (next < minZoom) next = minZoom;
    if (next > maxZoom) next = maxZoom;
    return next;
}

JNIEXPORT jboolean JNICALL
Java_com_replit_cameraapp_NativeImaging_isZoomSettledNative(
        JNIEnv *env, jobject thiz,
        jfloat current, jfloat target, jfloat threshold)
{
    float diff = target - current;
    if (diff < 0.f) diff = -diff;
    return (jboolean)(diff < threshold ? 1 : 0);
}
#endif
