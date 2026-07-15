/*
 * doc_scan.c — native document quadrilateral detector for Android camera frames.
 *
 * PURPOSE
 * ───────
 * Detect the four corners of a rectangular document (paper, card, book) in a
 * live camera Y (luma) plane.  The design goal is to accept ONLY genuine
 * quadrilaterals and reject everything else — circles, organic shapes, random
 * blobs — without any ML model or OpenCV dependency.
 *
 * WHY BRIGHTNESS SEGMENTATION FAILS
 * ──────────────────────────────────
 * The previous Kotlin analyzer used Otsu thresholding on raw luma values and
 * flooded-filled the largest bright or dark blob.  Any large solid object —
 * a round bowl, a circular table, a patch of floor — looks exactly like a
 * document to that algorithm: one dominant brightness against the background.
 * There is no shape test at all once the blob is found.
 *
 * THE FIX: EDGE-FIT QUALITY TEST
 * ──────────────────────────────
 * This library works on EDGE pixels (Sobel gradient magnitude), not raw luma:
 *
 *  1. Subsample the Y plane to SCAN_W×SCAN_H for speed.
 *  2. Sobel edge detection → gradient magnitude image.
 *  3. Otsu threshold on gradient magnitude → binary edge map.
 *  4. Find connected components of edge pixels (BFS).
 *  5. For each component, extract 4 candidate corners using the diagonal-
 *     extreme-point method (argmin/max of x±y).
 *  6. EDGE-FIT QUALITY: walk each of the 4 candidate sides pixel-by-pixel with
 *     Bresenham's algorithm and measure what fraction of those pixels are actual
 *     high-gradient edge pixels.  For a real rectangle this fraction is high
 *     (the sides ARE edges).  For a circle, ellipse, or organic shape, drawing
 *     straight lines between the 4 extreme points misses almost all the actual
 *     curved edges → low quality → rejected.
 *  7. Additional validation: interior angles 45°–135°, minimum side length,
 *     area fraction, convexity.
 *  8. The highest-scoring quad (area × fit quality) is returned.
 *
 * EXPORTED JNI FUNCTIONS
 * ──────────────────────
 *   docScanFindQuadNative(yPlane, rowStride, cropL, cropT, cropW, cropH,
 *                          corners[8]) → Boolean
 *     corners = [tlX,tlY, trX,trY, brX,brY, blX,blY] in [0..1] normalised.
 *
 *   docScanSmoothCornersNative(prev[8], curr[8], alpha, out[8])
 *     Single EMA step: out = prev + alpha*(curr−prev).  Call at ~8–12 fps.
 *
 *   docScanIsValidQuadNative(corners[8], frameW, frameH) → Boolean
 *     Re-validates a previously found quad (angle + area + convexity checks).
 *
 * THREAD SAFETY
 * ─────────────
 * The working buffers (luma, edgeMag, binEdge, visited) are file-static.
 * This is safe only when called from a single thread — which is guaranteed
 * because DocumentEdgeAnalyzer runs on a single-thread Executor.  Do not
 * call docScanFindQuadNative concurrently from multiple threads.
 */
#ifndef DOC_SCAN_C
#define DOC_SCAN_C

#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>

#define LOG_TAG "DocScan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* ── Working resolution ─────────────────────────────────────────────────── */
/* Portrait 3:4, kept small for speed.  Enough detail to find corners to
 * within ~1% of the frame size, which is all the live overlay needs.      */
#define SCAN_W 160
#define SCAN_H 214
#define SCAN_N (SCAN_W * SCAN_H)

/* ── Tuning constants ───────────────────────────────────────────────────── */
/* Minimum number of edge pixels in a connected component before we bother
 * evaluating it as a document candidate.                                    */
#define MIN_COMP_PX   55

/* Fraction of Bresenham-traced side pixels that must be real gradient-edge
 * pixels.  The key threshold that rejects circles.  0.45 = 45%.            */
#define EDGE_FIT_MIN  0.45f

/* Quad area as fraction of total scan area: valid range.                   */
#define MIN_AREA_FRAC 0.09f
#define MAX_AREA_FRAC 0.94f

/* Interior angle valid range (degrees).  Documents seen from an angle
 * deviate from 90°; 45°–135° covers realistic tilt.                       */
#define MIN_ANGLE_DEG 45.0f
#define MAX_ANGLE_DEG 135.0f

/* Each side must be ≥ this fraction of the scan diagonal.                 */
#define MIN_SIDE_FRAC 0.07f

/* How many Sobel magnitude points to use for perimeter-relative thresholding.
 * The Otsu multiplier nudges the threshold toward strong-edge-only.        */
#define OTSU_NUDGE    1.25f

/* Maximum components to evaluate (the N largest ones).                     */
#define MAX_CANDS     8

/* ── Data types ─────────────────────────────────────────────────────────── */

typedef struct {
    int pixelCount;
    /* diagonal-direction extreme pixels */
    int tlX, tlY;  /* argmin(x+y) — top-left region  */
    int trX, trY;  /* argmax(x-y) — top-right region */
    int brX, brY;  /* argmax(x+y) — bottom-right     */
    int blX, blY;  /* argmin(x-y) — bottom-left      */
    int tlV, trV, brV, blV; /* best scores so far     */
} ds_comp;

/* ── File-static working buffers (single-threaded use only) ─────────────── */
static uint8_t g_luma  [SCAN_N];
static uint8_t g_edge  [SCAN_N];   /* Sobel magnitude (0-255) */
static uint8_t g_bin   [SCAN_N];   /* thresholded edge map (0/1) */
static uint8_t g_visit [SCAN_N];
static int     g_bfsX  [SCAN_N];
static int     g_bfsY  [SCAN_N];
static ds_comp g_cands [MAX_CANDS];

/* ── Helpers ─────────────────────────────────────────────────────────────── */

static inline float ds_clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}
static inline float ds_lenf(float ax, float ay, float bx, float by) {
    float dx = ax - bx, dy = ay - by;
    return sqrtf(dx*dx + dy*dy);
}

/* ── Step 1: Subsample Y plane → scan grid ─────────────────────────────── */

static void subsample_y(const uint8_t *yp, int rowStride,
                         int cropL, int cropT, int cropW, int cropH) {
    for (int sy = 0; sy < SCAN_H; sy++) {
        int fy = cropT + (int)((long)sy * cropH / SCAN_H);
        const uint8_t *row = yp + (long)fy * rowStride;
        for (int sx = 0; sx < SCAN_W; sx++) {
            int fx = cropL + (int)((long)sx * cropW / SCAN_W);
            g_luma[sy * SCAN_W + sx] = row[fx];
        }
    }
}

/* ── Step 2: Sobel edge detection ─────────────────────────────────────────
 * Magnitude = (|Gx|+|Gy|)/2, stored in g_edge.  Border rows/cols = 0.    */

static void sobel_edges(void) {
    memset(g_edge, 0, SCAN_N);
    for (int y = 1; y < SCAN_H - 1; y++) {
        for (int x = 1; x < SCAN_W - 1; x++) {
            int row0 = (y-1)*SCAN_W, row1 = y*SCAN_W, row2 = (y+1)*SCAN_W;
            int p00 = g_luma[row0+x-1], p01 = g_luma[row0+x], p02 = g_luma[row0+x+1];
            int p10 = g_luma[row1+x-1],                         p12 = g_luma[row1+x+1];
            int p20 = g_luma[row2+x-1], p21 = g_luma[row2+x], p22 = g_luma[row2+x+1];

            int gx = -p00 - 2*p10 - p20 + p02 + 2*p12 + p22;
            int gy = -p00 - 2*p01 - p02 + p20 + 2*p21 + p22;
            int mag = (abs(gx) + abs(gy)) >> 1;
            g_edge[row1 + x] = (uint8_t)(mag > 255 ? 255 : mag);
        }
    }
}

/* ── Step 3: Otsu threshold on g_edge → g_bin ──────────────────────────── */

static void otsu_binarize(void) {
    long hist[256] = {0};
    for (int i = 0; i < SCAN_N; i++) hist[g_edge[i]]++;

    long total = SCAN_N, sum = 0;
    for (int t = 0; t < 256; t++) sum += (long)t * hist[t];

    long sumB = 0, wB = 0;
    double best = 0.0;
    int thresh = 0;
    for (int t = 0; t < 256; t++) {
        wB += hist[t];
        if (!wB) continue;
        long wF = total - wB;
        if (!wF) break;
        sumB += (long)t * hist[t];
        double mB = (double)sumB / wB;
        double mF = (double)(sum - sumB) / wF;
        double s = (double)wB * wF * (mB - mF) * (mB - mF);
        if (s > best) { best = s; thresh = t; }
    }

    /* Nudge threshold up to keep only strong, clear edges. */
    thresh = (int)(thresh * OTSU_NUDGE);
    if (thresh > 220) thresh = 220;
    if (thresh < 6)   thresh = 6;

    for (int i = 0; i < SCAN_N; i++)
        g_bin[i] = g_edge[i] >= thresh ? 1 : 0;
}

/* ── Step 4: BFS connected component on g_bin ───────────────────────────── */

static void bfs_comp(int sx, int sy, ds_comp *c) {
    int head = 0, tail = 0;
    g_bfsX[tail] = sx; g_bfsY[tail] = sy; tail++;
    g_visit[sy * SCAN_W + sx] = 1;

    c->pixelCount = 0;
    c->tlX = sx; c->tlY = sy; c->tlV = sx + sy;
    c->trX = sx; c->trY = sy; c->trV = sx - sy;
    c->brX = sx; c->brY = sy; c->brV = sx + sy;
    c->blX = sx; c->blY = sy; c->blV = sx - sy;

    static const int dx4[4] = {1,-1,0,0};
    static const int dy4[4] = {0,0,1,-1};

    while (head < tail) {
        int cx = g_bfsX[head], cy = g_bfsY[head]; head++;
        c->pixelCount++;

        /* diagonal extremes */
        int s = cx + cy, d = cx - cy;
        if (s < c->tlV) { c->tlV = s; c->tlX = cx; c->tlY = cy; }
        if (d > c->trV) { c->trV = d; c->trX = cx; c->trY = cy; }
        if (s > c->brV) { c->brV = s; c->brX = cx; c->brY = cy; }
        if (d < c->blV) { c->blV = d; c->blX = cx; c->blY = cy; }

        for (int i = 0; i < 4; i++) {
            int nx = cx + dx4[i], ny = cy + dy4[i];
            if ((unsigned)nx >= (unsigned)SCAN_W || (unsigned)ny >= (unsigned)SCAN_H) continue;
            int ni = ny * SCAN_W + nx;
            if (!g_visit[ni] && g_bin[ni]) {
                g_visit[ni] = 1;
                g_bfsX[tail] = nx; g_bfsY[tail] = ny; tail++;
            }
        }
    }
}

/* ── Step 5: Bresenham edge-fit quality ─────────────────────────────────── */
/* Walk a straight line from (x0,y0) to (x1,y1) in scan space and count
 * what fraction of the visited pixels have non-zero g_edge values.        */

static float line_fit(int x0, int y0, int x1, int y1) {
    int dx = abs(x1-x0), sx = (x0 < x1) ? 1 : -1;
    int dy = -abs(y1-y0), sy = (y0 < y1) ? 1 : -1;
    int err = dx + dy;
    int total = 0, hits = 0;
    int cx = x0, cy = y0;
    for (;;) {
        if ((unsigned)cx < (unsigned)SCAN_W && (unsigned)cy < (unsigned)SCAN_H) {
            total++;
            if (g_edge[cy * SCAN_W + cx]) hits++;
        }
        if (cx == x1 && cy == y1) break;
        int e2 = 2 * err;
        if (e2 >= dy) { err += dy; cx += sx; }
        if (e2 <= dx) { err += dx; cy += sy; }
    }
    return total > 0 ? (float)hits / (float)total : 0.f;
}

/* Average fit quality across all 4 sides of the candidate quad. */
static float quad_fit(const ds_comp *c) {
    float q = line_fit(c->tlX, c->tlY, c->trX, c->trY)
            + line_fit(c->trX, c->trY, c->brX, c->brY)
            + line_fit(c->brX, c->brY, c->blX, c->blY)
            + line_fit(c->blX, c->blY, c->tlX, c->tlY);
    return q * 0.25f;
}

/* ── Step 6: Geometry helpers ────────────────────────────────────────────── */

/* Interior angle at vertex B in the triangle A→B→C, in degrees. */
static float interior_angle(int ax,int ay, int bx,int by, int cx,int cy) {
    float ux = (float)(ax-bx), uy = (float)(ay-by);
    float vx = (float)(cx-bx), vy = (float)(cy-by);
    float lu = sqrtf(ux*ux+uy*uy), lv = sqrtf(vx*vx+vy*vy);
    if (lu < 0.5f || lv < 0.5f) return 0.f;
    float c = ds_clampf((ux*vx+uy*vy)/(lu*lv), -1.f, 1.f);
    return acosf(c) * (180.f / 3.14159265f);
}

/* Shoelace area of quad (TL→TR→BR→BL). */
static float quad_area(const ds_comp *c) {
    /* shoelace: |Σ (xi*y(i+1) - x(i+1)*yi)| / 2 */
    float a = (float)(c->tlX*c->trY - c->trX*c->tlY)
            + (float)(c->trX*c->brY - c->brX*c->trY)
            + (float)(c->brX*c->blY - c->blX*c->brY)
            + (float)(c->blX*c->tlY - c->tlX*c->blY);
    return fabsf(a) * 0.5f;
}

/* 2D cross product of vectors AB and BC; sign tells turn direction. */
static float cross2(int ax,int ay, int bx,int by, int cx,int cy) {
    return (float)(bx-ax)*(float)(cy-ay) - (float)(by-ay)*(float)(cx-ax);
}

/* Convexity: all 4 sequential cross products must share the same sign. */
static int is_convex(const ds_comp *c) {
    float crosses[4] = {
        cross2(c->blX,c->blY, c->tlX,c->tlY, c->trX,c->trY),
        cross2(c->tlX,c->tlY, c->trX,c->trY, c->brX,c->brY),
        cross2(c->trX,c->trY, c->brX,c->brY, c->blX,c->blY),
        cross2(c->brX,c->brY, c->blX,c->blY, c->tlX,c->tlY)
    };
    int sign = 0;
    for (int i = 0; i < 4; i++) {
        int s = crosses[i] > 0.5f ? 1 : (crosses[i] < -0.5f ? -1 : 0);
        if (!s) continue;
        if (!sign) sign = s;
        else if (s != sign) return 0;
    }
    return sign != 0;
}

/* Full validity check for a candidate component. */
static int is_valid_doc(const ds_comp *c, float quality) {
    if (!is_convex(c)) return 0;

    float area = quad_area(c);
    float areaFrac = area / (float)SCAN_N;
    if (areaFrac < MIN_AREA_FRAC || areaFrac > MAX_AREA_FRAC) return 0;

    float scanDiag = sqrtf((float)(SCAN_W*SCAN_W + SCAN_H*SCAN_H));
    float minSide = MIN_SIDE_FRAC * scanDiag;
    if (ds_lenf(c->tlX,c->tlY,c->trX,c->trY) < minSide) return 0;
    if (ds_lenf(c->trX,c->trY,c->brX,c->brY) < minSide) return 0;
    if (ds_lenf(c->brX,c->brY,c->blX,c->blY) < minSide) return 0;
    if (ds_lenf(c->blX,c->blY,c->tlX,c->tlY) < minSide) return 0;

    /* Interior angles — the most selective rectangle test. */
    float a0 = interior_angle(c->blX,c->blY, c->tlX,c->tlY, c->trX,c->trY);
    float a1 = interior_angle(c->tlX,c->tlY, c->trX,c->trY, c->brX,c->brY);
    float a2 = interior_angle(c->trX,c->trY, c->brX,c->brY, c->blX,c->blY);
    float a3 = interior_angle(c->brX,c->brY, c->blX,c->blY, c->tlX,c->tlY);
    if (a0<MIN_ANGLE_DEG||a0>MAX_ANGLE_DEG) return 0;
    if (a1<MIN_ANGLE_DEG||a1>MAX_ANGLE_DEG) return 0;
    if (a2<MIN_ANGLE_DEG||a2>MAX_ANGLE_DEG) return 0;
    if (a3<MIN_ANGLE_DEG||a3>MAX_ANGLE_DEG) return 0;

    /* Edge-fit quality — the anti-circle test. */
    if (quality < EDGE_FIT_MIN) return 0;

    return 1;
}

/* ── JNI: docScanFindQuadNative ─────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_replit_cameraapp_NativeImaging_docScanFindQuadNative(
        JNIEnv *env, jobject thiz,
        jbyteArray yPlane_jni, jint rowStride,
        jint cropL, jint cropT, jint cropW, jint cropH,
        jfloatArray corners_jni)
{
    if ((*env)->GetArrayLength(env, corners_jni) < 8) return JNI_FALSE;
    if (cropW <= 0 || cropH <= 0)                     return JNI_FALSE;

    jbyte *yRaw = (*env)->GetByteArrayElements(env, yPlane_jni, NULL);
    if (!yRaw) return JNI_FALSE;

    /* --- pipeline --- */
    subsample_y((const uint8_t*)yRaw, (int)rowStride,
                (int)cropL, (int)cropT, (int)cropW, (int)cropH);
    (*env)->ReleaseByteArrayElements(env, yPlane_jni, yRaw, JNI_ABORT);

    sobel_edges();
    otsu_binarize();

    memset(g_visit, 0, SCAN_N);

    int nCands = 0;
    int minCandPx = 0;

    /* Scan the binary edge map; BFS every unlabelled edge pixel. */
    for (int y = 1; y < SCAN_H - 1; y++) {
        for (int x = 1; x < SCAN_W - 1; x++) {
            int idx = y * SCAN_W + x;
            if (!g_bin[idx] || g_visit[idx]) continue;

            ds_comp c;
            bfs_comp(x, y, &c);

            if (c.pixelCount < MIN_COMP_PX) continue;

            if (nCands < MAX_CANDS) {
                g_cands[nCands++] = c;
                if (!minCandPx || c.pixelCount < minCandPx) minCandPx = c.pixelCount;
            } else if (c.pixelCount > minCandPx) {
                /* Evict the smallest current candidate. */
                int minIdx = 0;
                for (int k = 1; k < MAX_CANDS; k++)
                    if (g_cands[k].pixelCount < g_cands[minIdx].pixelCount) minIdx = k;
                g_cands[minIdx] = c;
                minCandPx = g_cands[0].pixelCount;
                for (int k = 1; k < MAX_CANDS; k++)
                    if (g_cands[k].pixelCount < minCandPx) minCandPx = g_cands[k].pixelCount;
            }
        }
    }

    float bestScore = -1.f;
    int   bestIdx   = -1;

    for (int i = 0; i < nCands; i++) {
        float quality = quad_fit(&g_cands[i]);
        if (!is_valid_doc(&g_cands[i], quality)) continue;
        float score = sqrtf(quad_area(&g_cands[i])) * quality;
        if (score > bestScore) { bestScore = score; bestIdx = i; }
    }

    if (bestIdx < 0) return JNI_FALSE;

    ds_comp *w = &g_cands[bestIdx];
    float corners[8] = {
        (float)w->tlX / SCAN_W, (float)w->tlY / SCAN_H,
        (float)w->trX / SCAN_W, (float)w->trY / SCAN_H,
        (float)w->brX / SCAN_W, (float)w->brY / SCAN_H,
        (float)w->blX / SCAN_W, (float)w->blY / SCAN_H,
    };
    (*env)->SetFloatArrayRegion(env, corners_jni, 0, 8, corners);
    return JNI_TRUE;
}

/* ── JNI: docScanSmoothCornersNative ─────────────────────────────────────── */
/*
 * Single EMA step: out[i] = prev[i] + alpha * (curr[i] - prev[i]).
 * Call at ~8–12 fps.  alpha=0.35 tracks reasonably fast while damping jitter.
 * Pass alpha=1.0 to snap immediately (first detection).
 */
JNIEXPORT void JNICALL
Java_com_replit_cameraapp_NativeImaging_docScanSmoothCornersNative(
        JNIEnv *env, jobject thiz,
        jfloatArray prev_jni, jfloatArray curr_jni,
        jfloat alpha,
        jfloatArray out_jni)
{
    if ((*env)->GetArrayLength(env, prev_jni) < 8 ||
        (*env)->GetArrayLength(env, curr_jni) < 8 ||
        (*env)->GetArrayLength(env, out_jni)  < 8) return;

    float prev[8], curr[8], out[8];
    (*env)->GetFloatArrayRegion(env, prev_jni, 0, 8, prev);
    (*env)->GetFloatArrayRegion(env, curr_jni, 0, 8, curr);
    for (int i = 0; i < 8; i++)
        out[i] = prev[i] + (float)alpha * (curr[i] - prev[i]);
    (*env)->SetFloatArrayRegion(env, out_jni, 0, 8, out);
}

/* ── JNI: docScanIsValidQuadNative ──────────────────────────────────────── */
/*
 * Re-validate a normalized-coord quad after smoothing to confirm it still
 * describes a plausible document shape.  Uses the same angle + area checks
 * as the main detector, but works in frame-pixel space.
 */
JNIEXPORT jboolean JNICALL
Java_com_replit_cameraapp_NativeImaging_docScanIsValidQuadNative(
        JNIEnv *env, jobject thiz,
        jfloatArray corners_jni, jint frameW, jint frameH)
{
    if ((*env)->GetArrayLength(env, corners_jni) < 8) return JNI_FALSE;
    float nc[8];
    (*env)->GetFloatArrayRegion(env, corners_jni, 0, 8, nc);

    /* Scale to pixel space for geometry checks. */
    int px[4], py[4];
    for (int i = 0; i < 4; i++) {
        px[i] = (int)(nc[i*2]   * (float)frameW + 0.5f);
        py[i] = (int)(nc[i*2+1] * (float)frameH + 0.5f);
    }

    /* Build a temporary ds_comp for reuse of is_convex / area helpers. */
    ds_comp t;
    t.tlX=px[0]; t.tlY=py[0];
    t.trX=px[1]; t.trY=py[1];
    t.brX=px[2]; t.brY=py[2];
    t.blX=px[3]; t.blY=py[3];
    t.pixelCount=0; t.tlV=t.trV=t.brV=t.blV=0;

    if (!is_convex(&t)) return JNI_FALSE;

    float area = quad_area(&t);
    float fArea = (float)(frameW * frameH);
    if (area/fArea < MIN_AREA_FRAC || area/fArea > MAX_AREA_FRAC) return JNI_FALSE;

    float a0 = interior_angle(px[3],py[3], px[0],py[0], px[1],py[1]);
    float a1 = interior_angle(px[0],py[0], px[1],py[1], px[2],py[2]);
    float a2 = interior_angle(px[1],py[1], px[2],py[2], px[3],py[3]);
    float a3 = interior_angle(px[2],py[2], px[3],py[3], px[0],py[0]);
    if (a0<MIN_ANGLE_DEG||a0>MAX_ANGLE_DEG||
        a1<MIN_ANGLE_DEG||a1>MAX_ANGLE_DEG||
        a2<MIN_ANGLE_DEG||a2>MAX_ANGLE_DEG||
        a3<MIN_ANGLE_DEG||a3>MAX_ANGLE_DEG) return JNI_FALSE;

    return JNI_TRUE;
}

#endif /* DOC_SCAN_C */
