cmake_minimum_required(VERSION 3.22.1)
project("camimg" LANGUAGES C)

# jni_bridge.c #includes all translation units — list only the aggregator here
# so each JNI symbol is compiled exactly once.
add_library(camimg SHARED jni_bridge.c)
target_compile_options(camimg PRIVATE -O2 -Wall -Wno-unused-parameter)

find_library(log-lib      log)
find_library(jnigraphics-lib jnigraphics)
find_library(math-lib     m)   # required by denoise.c (expf) and color_grade.c (fabsf)

target_link_libraries(camimg ${log-lib} ${jnigraphics-lib} ${math-lib})
