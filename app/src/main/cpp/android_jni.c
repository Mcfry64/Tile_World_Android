/* android_jni.c: JNI bridge between Kotlin GameEngine and the tworld C engine.
 *
 * Game lifecycle:
 *   Kotlin calls nativeInit(paths)  → stores paths
 *   Kotlin calls nativeStart()      → spawns game thread running tworld()
 *   Kotlin calls nativeSendKey()    → feeds input to game thread
 *   Kotlin calls nativeCopyPixels() → copies current frame to Android Bitmap
 *   Kotlin calls nativeStop()       → signals game to exit
 */

#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "tworld"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Pull in the full type definitions (TW_Surface, genericglobals, etc.). */
#include "tworld_src/oshw-android/oshwbind.h"
#include "tworld_src/generic/generic.h"
#include "tworld_src/play.h"

/* External functions declared across tworld engine C modules */
extern int  tworld(int argc, char *argv[]);
extern void android_send_key(int twk, int down);
extern void android_type_char(int twk);
extern int  android_get_keyboard_request(void);
extern int  setsfxmsg(char const *msg, int msecs, int bold);
extern void android_audio_pause(void);
extern void android_audio_resume(void);
extern void android_set_sfx_enabled(int enabled);
extern int  setdisplaymsg(char const *msg, int msecs, int bold);
extern int  setvolume(int v, int display);
extern int  android_is_paused(void);
extern void android_set_game_speed(int percent);
extern void setsfxtheme(const char *theme_name);
extern int  android_get_tile_scale(void);

static JavaVM   *g_vm = NULL;
static jclass    g_music_cls = NULL;
static jmethodID g_stop_bgm_mid = NULL;
static jclass    g_engine_cls = NULL;
static jmethodID g_sfx_text_mid = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK && env) {
        jclass localCls = (*env)->FindClass(env, "dev/mcfry64/tworld/MusicManager");
        if (localCls) {
            g_music_cls = (jclass)(*env)->NewGlobalRef(env, localCls);
            g_stop_bgm_mid = (*env)->GetStaticMethodID(env, g_music_cls, "stopMusicFromNative", "()V");
            (*env)->DeleteLocalRef(env, localCls);
        } else if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }

        jclass engCls = (*env)->FindClass(env, "dev/mcfry64/tworld/GameEngine");
        if (engCls) {
            g_engine_cls = (jclass)(*env)->NewGlobalRef(env, engCls);
            g_sfx_text_mid = (*env)->GetStaticMethodID(env, g_engine_cls, "onSfxTextFromNative", "(Ljava/lang/String;)V");
            (*env)->DeleteLocalRef(env, engCls);
        } else if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    return JNI_VERSION_1_6;
}

void android_trigger_sfx_text(const char *text)
{
    if (!text || !*text || !g_vm || !g_engine_cls || !g_sfx_text_mid) return;
    JNIEnv *env = NULL;
    jint res = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    int need_detach = 0;
    if (res == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return;
        need_detach = 1;
    }
    if (env) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        jstring jText = (*env)->NewStringUTF(env, text);
        if (jText) {
            (*env)->CallStaticVoidMethod(env, g_engine_cls, g_sfx_text_mid, jText);
            (*env)->DeleteLocalRef(env, jText);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (need_detach) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

void android_stop_bgm_on_win(void)
{
    if (!g_vm || !g_music_cls || !g_stop_bgm_mid) return;
    JNIEnv *env = NULL;
    jint res = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    int need_detach = 0;
    if (res == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return;
        need_detach = 1;
    }
    if (env) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->CallStaticVoidMethod(env, g_music_cls, g_stop_bgm_mid);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (need_detach) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

/* ---------------------------------------------------------------------------
 * Framebuffer — game writes to geng.screen; we copy to a shared buffer.
 * Kotlin reads the shared buffer via nativeCopyPixels().
 * --------------------------------------------------------------------------- */
static pthread_mutex_t fb_mutex = PTHREAD_MUTEX_INITIALIZER;
static uint32_t *framebuffer  = NULL;
static int       fb_width     = 0;
static int       fb_height    = 0;

static int       s_cached_level_num = 0;
static int       s_cached_end_state = 0;
static char      s_cached_name[256] = "";
static char      s_cached_author[256] = "";

void android_set_level_end_state(int state)
{
    pthread_mutex_lock(&fb_mutex);
    s_cached_end_state = state;
    pthread_mutex_unlock(&fb_mutex);
}

/* Called from androidout.c:android_update_screen(). */
void android_update_screen(void)
{
    if (!geng.screen) return;
    int w = geng.screen->w;
    int h = geng.screen->h;

    pthread_mutex_lock(&fb_mutex);
    if (!framebuffer || fb_width != w || fb_height != h) {
        free(framebuffer);
        framebuffer = malloc((size_t)w * h * 4);
        fb_width    = w;
        fb_height   = h;
    }
    if (framebuffer)
        memcpy(framebuffer, geng.screen->pixels, (size_t)w * h * 4);

    /* Cache level metadata while we have the mutex */
    s_cached_level_num = get_current_level_number();
    const char *name = get_current_level_name();
    if (name) {
        strncpy(s_cached_name, name, sizeof(s_cached_name) - 1);
        s_cached_name[sizeof(s_cached_name) - 1] = '\0';
    } else {
        s_cached_name[0] = '\0';
    }
    const char *author = get_current_level_author();
    if (author) {
        strncpy(s_cached_author, author, sizeof(s_cached_author) - 1);
        s_cached_author[sizeof(s_cached_author) - 1] = '\0';
    } else {
        s_cached_author[0] = '\0';
    }

    pthread_mutex_unlock(&fb_mutex);
}

/* ---------------------------------------------------------------------------
 * Game thread.
 * --------------------------------------------------------------------------- */
static char s_data_dir[512];
static char s_res_dir[512];
static char s_sets_dir[512];
static char s_save_dir[512];

static volatile int s_running = 0;
static pthread_t    s_game_thread;

static char s_start_set[256] = "";
static int  s_start_level = 0;

static void *game_thread_func(void *arg)
{
    char lvl_str[16];
    snprintf(lvl_str, sizeof(lvl_str), "%d", s_start_level);

    /* Construct argv: tworld -D data -S save -R res -L sets [-T tileset] [set] [lvl] */
    char *argv[16];
    int i = 0;
    argv[i++] = "tworld";
    argv[i++] = "-D"; argv[i++] = s_data_dir;
    argv[i++] = "-S"; argv[i++] = s_save_dir;
    argv[i++] = "-R"; argv[i++] = s_res_dir;
    argv[i++] = "-L"; argv[i++] = s_sets_dir;

    char *tileset_file = (char*)arg;
    if (tileset_file && tileset_file[0]) {
        argv[i++] = "-T";
        argv[i++] = tileset_file;
    }

    if (s_start_set[0]) {
        argv[i++] = s_start_set;
        if (s_start_level > 0) {
            argv[i++] = lvl_str;
        }
    }
    argv[i] = NULL;
    int argc = i;

    LOGI("game thread starting tworld() with argc=%d", argc);
    tworld(argc, argv);
    LOGI("game thread: tworld() returned, setting s_running = 0");
    s_running = 0;
    return NULL;
}

/* ---------------------------------------------------------------------------
 * JNI functions — package dev.mcfry64.tworld, class GameEngine.
 * --------------------------------------------------------------------------- */

#define JNI_FN(name) \
    Java_dev_mcfry64_tworld_GameEngine_##name

JNIEXPORT jboolean JNICALL
JNI_FN(nativeInit)(JNIEnv *env, jclass cls,
                   jstring jDataDir, jstring jResDir,
                   jstring jSetsDir, jstring jSaveDir)
{
    (void)cls;
    const char *d = jDataDir ? (*env)->GetStringUTFChars(env, jDataDir, NULL) : NULL;
    const char *r = jResDir  ? (*env)->GetStringUTFChars(env, jResDir,  NULL) : NULL;
    const char *l = jSetsDir ? (*env)->GetStringUTFChars(env, jSetsDir, NULL) : NULL;
    const char *s = jSaveDir ? (*env)->GetStringUTFChars(env, jSaveDir, NULL) : NULL;

    snprintf(s_data_dir, sizeof(s_data_dir), "%s", d ? d : "");
    snprintf(s_res_dir,  sizeof(s_res_dir),  "%s", r ? r : "");
    snprintf(s_sets_dir, sizeof(s_sets_dir), "%s", l ? l : "");
    snprintf(s_save_dir, sizeof(s_save_dir), "%s", s ? s : "");

    if (d) (*env)->ReleaseStringUTFChars(env, jDataDir, d);
    if (r) (*env)->ReleaseStringUTFChars(env, jResDir,  r);
    if (l) (*env)->ReleaseStringUTFChars(env, jSetsDir, l);
    if (s) (*env)->ReleaseStringUTFChars(env, jSaveDir, s);

    LOGI("nativeInit: data=%s res=%s sets=%s save=%s",
         s_data_dir, s_res_dir, s_sets_dir, s_save_dir);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeStart)(JNIEnv *env, jclass cls, jstring jSetName, jint jLevelNum, jstring jTileset)
{
    (void)cls;
    /* If a previous game session thread is still shutting down, wait for it */
    for (int k = 0; k < 50 && s_running; k++) {
        android_send_key(27 /* TWK_ESCAPE */, 1);
        android_send_key(27, 0);
        usleep(20000); // 20ms * 50 = max 1 second wait
    }
    if (s_running) {
        LOGE("nativeStart: Engine is still running after wait");
        return JNI_FALSE;
    }

    const char *setName = jSetName ? (*env)->GetStringUTFChars(env, jSetName, NULL) : NULL;
    if (setName) {
        snprintf(s_start_set, sizeof(s_start_set), "%s", setName);
        (*env)->ReleaseStringUTFChars(env, jSetName, setName);
    } else {
        s_start_set[0] = '\0';
    }
    s_start_level = (int)jLevelNum;

    const char *tileset = jTileset ? (*env)->GetStringUTFChars(env, jTileset, NULL) : NULL;
    char tileset_arg[256];
    if (tileset) {
        snprintf(tileset_arg, sizeof(tileset_arg), "%s", tileset);
        (*env)->ReleaseStringUTFChars(env, jTileset, tileset);
    } else {
        tileset_arg[0] = '\0';
    }

    LOGI("nativeStart: Starting engine for set=%s level=%d tileset=%s",
         s_start_set, s_start_level, tileset_arg);

    s_running = 1;
    static char s_tileset_file[256];
    snprintf(s_tileset_file, sizeof(s_tileset_file), "%s", tileset_arg);

    if (pthread_create(&s_game_thread, NULL, game_thread_func, s_tileset_file) != 0) {
        LOGE("failed to create game thread");
        s_running = 0;
        return JNI_FALSE;
    }
    pthread_detach(s_game_thread);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
JNI_FN(nativeSendKey)(JNIEnv *env, jclass cls, jint twk, jboolean down)
{
    (void)env; (void)cls;
    android_send_key((int)twk, (int)down);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSendTouch)(JNIEnv *env, jclass cls, jint x, jint y, jboolean down)
{
    (void)env; (void)cls;
    if (geng.mouseeventcallbackfunc) {
        geng.mouseeventcallbackfunc((int)x, (int)y, 1, down ? 1 : 0);
    }
}

JNIEXPORT void JNICALL
JNI_FN(nativeCopyPixels)(JNIEnv *env, jclass cls, jobject bitmap)
{
    (void)cls;
    if (!bitmap) return;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)  return;

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    pthread_mutex_lock(&fb_mutex);
    if (framebuffer && fb_width > 0 && fb_height > 0 && pixels) {
        int copy_w = (int)info.width  < fb_width  ? (int)info.width  : fb_width;
        int copy_h = (int)info.height < fb_height ? (int)info.height : fb_height;
        for (int y = 0; y < copy_h; y++) {
            uint32_t       *dst = (uint32_t *)((uint8_t *)pixels + y * info.stride);
            const uint32_t *src = framebuffer + y * fb_width;
            /* Convert ARGB8888 (our format) to RGBA8888 (Android Bitmap).
             * ANDROID_BITMAP_FORMAT_RGBA_8888 in little-endian memory is
             * [R, G, B, A] at bytes 0-3, i.e. uint32 = 0xAABBGGRR.
             * Source ARGB is 0xAARRGGBB → target: (a<<24)|(b<<16)|(g<<8)|r */
            for (int x = 0; x < copy_w; x++) {
                uint32_t p = src[x];
                uint8_t  a = (p >> 24) & 0xFF;
                uint8_t  r = (p >> 16) & 0xFF;
                uint8_t  g = (p >>  8) & 0xFF;
                uint8_t  b =  p        & 0xFF;
                dst[x] = ((uint32_t)a << 24) | ((uint32_t)b << 16)
                        | ((uint32_t)g <<  8) |  (uint32_t)r;
            }
        }
    }
    pthread_mutex_unlock(&fb_mutex);

    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetScreenWidth)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    pthread_mutex_lock(&fb_mutex);
    int w = fb_width;
    pthread_mutex_unlock(&fb_mutex);
    return w;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetScreenHeight)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    pthread_mutex_lock(&fb_mutex);
    int h = fb_height;
    pthread_mutex_unlock(&fb_mutex);
    return h;
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeIsRunning)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return s_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetKeyboardRequest)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)android_get_keyboard_request();
}

JNIEXPORT void JNICALL
JNI_FN(nativeTypeChar)(JNIEnv *env, jclass cls, jint twk)
{
    (void)env; (void)cls;
    android_type_char((int)twk);
}

JNIEXPORT void JNICALL
JNI_FN(nativeAudioPause)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    android_audio_pause();
}

JNIEXPORT void JNICALL
JNI_FN(nativeAudioResume)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    android_audio_resume();
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetSfxEnabled)(JNIEnv *env, jclass cls, jboolean enabled)
{
    (void)env; (void)cls;
    android_set_sfx_enabled(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetGameSpeed)(JNIEnv *env, jclass cls, jint percent)
{
    (void)env; (void)cls;
    android_set_game_speed((int)percent);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetUnlimitedTime)(JNIEnv *env, jclass cls, jboolean unlimited)
{
    (void)env; (void)cls;
    g_unlimited_time = unlimited ? 1 : 0;
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetSfxVolume)(JNIEnv *env, jclass cls, jint volume)
{
    (void)env; (void)cls;
    setvolume(volume, 0);
}

JNIEXPORT void JNICALL
JNI_FN(nativeSetSfxTheme)(JNIEnv *env, jclass cls, jstring jTheme)
{
    (void)cls;
    if (!jTheme) {
        setsfxtheme("");
        return;
    }
    const char *theme = (*env)->GetStringUTFChars(env, jTheme, NULL);
    if (theme) {
        setsfxtheme(theme);
        (*env)->ReleaseStringUTFChars(env, jTheme, theme);
    } else {
        setsfxtheme("");
    }
}

JNIEXPORT void JNICALL
JNI_FN(nativeShowMessage)(JNIEnv *env, jclass cls, jstring jMsg)
{
    (void)cls;
    if (!jMsg) {
        setsfxmsg(NULL, 0, 0);
        return;
    }
    const char *msg = (*env)->GetStringUTFChars(env, jMsg, NULL);
    if (msg) {
        setsfxmsg(msg, 800, 200);
        (*env)->ReleaseStringUTFChars(env, jMsg, msg);
    } else {
        setsfxmsg(NULL, 0, 0);
    }
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeIsGamePaused)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return android_is_paused() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetCurrentLevelNumber)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    pthread_mutex_lock(&fb_mutex);
    int num = s_cached_level_num;
    pthread_mutex_unlock(&fb_mutex);
    return (jint)num;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetLevelEndState)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    pthread_mutex_lock(&fb_mutex);
    int st = s_cached_end_state;
    pthread_mutex_unlock(&fb_mutex);
    return (jint)st;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeGetTileScale)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jint)android_get_tile_scale();
}

JNIEXPORT jstring JNICALL
JNI_FN(nativeGetCurrentLevelName)(JNIEnv *env, jclass cls)
{
    (void)cls;
    pthread_mutex_lock(&fb_mutex);
    jstring s = (*env)->NewStringUTF(env, s_cached_name);
    pthread_mutex_unlock(&fb_mutex);
    return s;
}

JNIEXPORT jstring JNICALL
JNI_FN(nativeGetCurrentLevelAuthor)(JNIEnv *env, jclass cls)
{
    (void)cls;
    pthread_mutex_lock(&fb_mutex);
    jstring s = (*env)->NewStringUTF(env, s_cached_author);
    pthread_mutex_unlock(&fb_mutex);
    return s;
}

JNIEXPORT void JNICALL
JNI_FN(nativeStop)(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    /* Send Escape to cause tworld to exit gracefully. */
    android_send_key(27 /* TWK_ESCAPE */, 1);
    android_send_key(27, 0);
}
