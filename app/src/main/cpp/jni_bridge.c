/* jni_bridge.c — aggregator: one shared library, all C translation units.
 * doc_scan.cpp is compiled separately by CMake (requires C++ / OpenCV). */
#include "doc_warp.c"
#include "portrait_blur.c"
#include "zoom_smooth.c"
#include "denoise.c"
#include "sharpen.c"
#include "histogram.c"
#include "color_grade.c"
#include "tone_curve.c"
