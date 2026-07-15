cmake_minimum_required(VERSION 3.22.1)
    project("camimg" LANGUAGES C)

    add_library(camimg SHARED jni_bridge.c doc_warp.c portrait_blur.c)
    target_compile_options(camimg PRIVATE -O2 -Wall -Wextra)
    find_library(log-lib log)
    find_library(jnigraphics-lib jnigraphics)
    target_link_libraries(camimg ${log-lib} ${jnigraphics-lib})
    