cmake_minimum_required(VERSION 3.22.1)
project("tworld")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 11)

set(TWORLD_SRC ${CMAKE_CURRENT_SOURCE_DIR}/tworld_src)

# ── Generate comptime.h (needed by help.c) ─────────────────────────────────
string(TIMESTAMP COMPILE_NOW)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/comptime.h"
     "#define COMPILE_TIME \"${COMPILE_NOW}\"\n")

# ── tworld game library (no SDL) ────────────────────────────────────────────
add_library(tworld SHARED
    # JNI bridge
    android_jni.c

    # Core game
    ${TWORLD_SRC}/tworld.c
    ${TWORLD_SRC}/cmdline.c
    ${TWORLD_SRC}/encoding.c
    ${TWORLD_SRC}/err.c
    ${TWORLD_SRC}/fileio.c
    ${TWORLD_SRC}/help.c
    ${TWORLD_SRC}/lxlogic.c
    ${TWORLD_SRC}/mslogic.c
    ${TWORLD_SRC}/play.c
    ${TWORLD_SRC}/random.c
    ${TWORLD_SRC}/res.c
    ${TWORLD_SRC}/series.c
    ${TWORLD_SRC}/solution.c
    ${TWORLD_SRC}/unslist.c
    ${TWORLD_SRC}/messages.cpp
    ${TWORLD_SRC}/score.cpp
    ${TWORLD_SRC}/settings.cpp

    # Android oshw layer (no SDL)
    ${TWORLD_SRC}/oshw-android/androidbind.c
    ${TWORLD_SRC}/oshw-android/androidoshw.c
    ${TWORLD_SRC}/oshw-android/androidout.c
    ${TWORLD_SRC}/oshw-android/androidtext.c
    ${TWORLD_SRC}/oshw-android/androidsfx.c
    ${TWORLD_SRC}/oshw-android/androiderr.c

    # Generic layer
    ${TWORLD_SRC}/generic/in.c
    ${TWORLD_SRC}/generic/tile.c
    ${TWORLD_SRC}/generic/timer.c
)

target_include_directories(tworld PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}          # for comptime.h
    ${TWORLD_SRC}
    ${TWORLD_SRC}/oshw-android           # provides oshwbind.h, androidgen.h
    ${TWORLD_SRC}/generic
)

target_compile_definitions(tworld PRIVATE
    TWPLUSPLUS
    SAVEDIR=\".\"
    stricmp=strcasecmp
)

target_link_libraries(tworld
    android
    jnigraphics
    log
    aaudio
)
