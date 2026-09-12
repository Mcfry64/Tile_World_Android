/* androidoshw.c: Top-level Android management (no SDL).
 *
 * Event loop: the game thread blocks here waiting for key/button events
 * delivered from the Kotlin side via android_send_key().
 * Timing: clock_gettime(CLOCK_MONOTONIC) replaces SDL_GetTicks().
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <android/log.h>

#include "androidgen.h"
#include "../defs.h"
#include "../err.h"

#define LOG_TAG "tworld"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

oshwglobals andg;

/* ---------------------------------------------------------------------------
 * Key event queue — written from JNI thread, read from game thread.
 * --------------------------------------------------------------------------- */

static pthread_mutex_t key_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  key_cond  = PTHREAD_COND_INITIALIZER;

static int  pending_count = 0;

static int key_is_down[TWK_LAST];

/* Typed character ring buffer — used for soft keyboard input so that
 * a down+up pair for the same key is processed in sequence rather than
 * colliding in key_pending[]. */
#define TYPED_QUEUE_CAP 64
static struct { int twk; int down; } typed_queue[TYPED_QUEUE_CAP];
static int typed_head = 0;
static int typed_tail = 0;

/* Keyboard show/hide request: +1=show, -1=hide, 0=no change. Written by
 * android_request_keyboard (game thread), read+cleared by
 * android_get_keyboard_request (render thread). */
static volatile int s_keyboard_requested = 0;

/* Called from JNI (Kotlin) thread. */
void android_send_key(int twk, int down)
{
    if (twk < 0 || twk >= TWK_LAST) return;
    pthread_mutex_lock(&key_mutex);

    key_is_down[twk] = down;

    int n1 = (typed_tail + 1) % TYPED_QUEUE_CAP;
    if (n1 != typed_head) {   /* room in queue */
        typed_queue[typed_tail].twk  = twk;
        typed_queue[typed_tail].down = down ? 1 : 0;
        typed_tail = n1;
        pending_count++;
        pthread_cond_signal(&key_cond);
    }

    pthread_mutex_unlock(&key_mutex);
}

/* Queue a typed character as a down+up pair so both events are delivered in
 * the same _eventupdate cycle and produce KS_STRUCK in the key state machine.
 * Called from JNI (render/Kotlin) thread. */
void android_type_char(int twk)
{
    if (twk < 0 || twk >= TWK_LAST) return;
    pthread_mutex_lock(&key_mutex);
    int n1 = (typed_tail + 1) % TYPED_QUEUE_CAP;
    int n2 = (n1 + 1) % TYPED_QUEUE_CAP;
    if (n2 != typed_head) {   /* room for two entries */
        typed_queue[typed_tail].twk  = twk;
        typed_queue[typed_tail].down = 1;
        typed_tail = n1;
        typed_queue[typed_tail].twk  = twk;
        typed_queue[typed_tail].down = 0;
        typed_tail = n2;
        pending_count++;
        pthread_cond_signal(&key_cond);
    }
    pthread_mutex_unlock(&key_mutex);
}

void android_request_keyboard(int show)
{
    s_keyboard_requested = show ? 1 : -1;
}

int android_get_keyboard_request(void)
{
    int r = s_keyboard_requested;
    if (r) s_keyboard_requested = 0;
    return r;
}

static void _eventupdate(int wait)
{
    pthread_mutex_lock(&key_mutex);

    if (wait && pending_count == 0 && typed_head == typed_tail) {
        /* Wait up to 50 ms (for animation/timer ticks). */
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        long ns = ts.tv_nsec + 50L * 1000000L;
        ts.tv_sec  += ns / 1000000000L;
        ts.tv_nsec  = ns % 1000000000L;
        pthread_cond_timedwait(&key_cond, &key_mutex, &ts);
    }

    while (typed_head != typed_tail) {
        int twk  = typed_queue[typed_head].twk;
        int down = typed_queue[typed_head].down;
        typed_head = (typed_head + 1) % TYPED_QUEUE_CAP;

        keyeventcallback(twk, down);
    }
    pending_count = 0;

    pthread_mutex_unlock(&key_mutex);
}

/* ---------------------------------------------------------------------------
 * Required oshw callbacks.
 * --------------------------------------------------------------------------- */

void setsubtitle(char const *subtitle)
{
    /* No window title on Android. */
    (void)subtitle;
}

int getselectedruleset(void)
{
    return Ruleset_Lynx;
}

void readextensions(struct gameseries *series)
{
    (void)series;
}

int getreplaysecondstoskip(void)
{
    return -1;
}

void copytoclipboard(char const *text)
{
    (void)text;
}

/* ---------------------------------------------------------------------------
 * Initialization.
 * --------------------------------------------------------------------------- */

int setkeyboardrepeat(int enable)
{
    if (enable) {
        memset(key_is_down, 0, sizeof key_is_down);
        /* Reset joystickstyle and flush keystates so gameplay key state
         * (held directions left in KS_DOWN) doesn't bleed into menu input. */
        setkeyboardarrowsrepeat(FALSE);
    }
    return TRUE;
}

int _androidinputinitialize(void)
{
    return TRUE;
}

extern int _sdlsfxinitialize(int silence, int soundbufsize);

int oshwinitialize(int silence, int soundbufsize,
                   int showhistogram, int fullscreen)
{
    geng.eventupdatefunc = _eventupdate;

    return _generictimerinitialize(showhistogram)
        && _androidtextinitialize()
        && _generictileinitialize()
        && _genericinputinitialize()
        && _androidinputinitialize()
        && _androidoutputinitialize(fullscreen)
        && _sdlsfxinitialize(silence, soundbufsize);
}

#ifndef __ANDROID__
int main(int argc, char *argv[])
{
    return tworld(argc, argv);
}
#endif
