/* androidsfx.c: Audio implementation for Android using AAudio.
 *
 * Loads WAV files into 16-bit PCM mono at SAMPLE_RATE Hz.
 * AAudio callback mixes all active sounds into the output stream.
 * Supports one-shot sounds (play once) and continuous sounds (loop
 * while playing flag is set), matching the SDL implementation.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>
#include <android/log.h>
#include <aaudio/AAudio.h>

#include "../gen.h"
#include "../defs.h"
#include "../err.h"
#include "androidgen.h"

#define LOG_TAG "tworld"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

#define SAMPLE_RATE 22050
#define CHANNELS    1

typedef struct {
    int16_t *wave;
    int32_t  len;     /* samples */
    int32_t  pos;     /* playback cursor */
    int      playing;
} sfxinfo;

static sfxinfo          sounds[SND_COUNT];
static pthread_mutex_t  snd_mutex = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream    *g_stream  = NULL;
static int              g_enabled = 0;
static int              g_sfx_on  = 1;
static int              g_volume  = 8; /* 0–10 */

static const char *textsfx[SND_COUNT] = {
    "\"Bummer\"", "Tadaa!", "Clang!", "Ktick!",
    "Bzont!", "Mnphf!", "Chack!", "Slurp!",
    "Flonk!", "Bamff!", "Spang!", "Clack!",
    "Click!", "Whisk!", "Chunk!", "Shunk!",
    "Booom!", "Plash!", "Clink!",
    "Scrrr ...", "Whizz ...", "Whing!", "Drrrr ...",
    "slurp slurp ...", "snick snick ...", "plip plip ...", "crackle crackle ..."
};

extern void android_trigger_sfx_text(const char *text);

static void displaysoundeffects(unsigned long sfx)
{
    for (int i = 0; i < SND_COUNT; ++i) {
        if (sfx & (1UL << i)) {
            if (textsfx[i]) {
                android_trigger_sfx_text(textsfx[i]);
                break;
            }
        }
    }
}

/* AAudio callback: mixes all active sounds into `audioData`. */
static aaudio_data_callback_result_t audio_callback(
        AAudioStream *stream, void *userData,
        void *audioData, int32_t numFrames)
{
    (void)stream; (void)userData;
    int16_t *out = (int16_t *)audioData;
    memset(out, 0, (size_t)numFrames * sizeof(int16_t));

    pthread_mutex_lock(&snd_mutex);
    for (int i = 0; i < SND_COUNT; i++) {
        if (!sounds[i].wave) continue;
        if (!sounds[i].playing && (!sounds[i].pos || i >= SND_ONESHOT_COUNT))
            continue;

        int32_t rem = numFrames;
        int32_t dst = 0;
        while (rem > 0) {
            int32_t avail = sounds[i].len - sounds[i].pos;
            int32_t copy  = avail < rem ? avail : rem;
            for (int32_t j = 0; j < copy; j++) {
                int32_t s = out[dst + j]
                          + (int32_t)sounds[i].wave[sounds[i].pos + j] * g_volume / 10;
                if      (s >  32767) s =  32767;
                else if (s < -32768) s = -32768;
                out[dst + j] = (int16_t)s;
            }
            sounds[i].pos += copy;
            dst += copy;
            rem -= copy;
            if (sounds[i].pos >= sounds[i].len) {
                sounds[i].pos = 0;
                if (i < SND_ONESHOT_COUNT) {
                    sounds[i].playing = 0;
                    break;
                }
                if (!sounds[i].playing) break; /* continuous but stopped */
            }
        }
    }
    pthread_mutex_unlock(&snd_mutex);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* -----------------------------------------------------------------------
 * Minimal WAV loader.
 * Converts to int16 mono at SAMPLE_RATE. Supports 8/16-bit, mono/stereo,
 * any sample rate (nearest-neighbour resample).
 * ----------------------------------------------------------------------- */
static int16_t *load_wav(const char *path, int32_t *out_samples)
{
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;

    uint8_t hdr[12];
    if (fread(hdr, 1, 12, f) != 12 ||
        memcmp(hdr, "RIFF", 4) != 0 ||
        memcmp(hdr + 8, "WAVE", 4) != 0) {
        fclose(f); return NULL;
    }

    uint16_t fmt_audio = 0, fmt_ch = 0, fmt_bits = 0;
    uint32_t fmt_rate  = 0;
    uint8_t *pcm = NULL;
    uint32_t pcm_len = 0;

    uint8_t chunk[8];
    while (fread(chunk, 1, 8, f) == 8) {
        uint32_t sz = (uint32_t)chunk[4]       | ((uint32_t)chunk[5] << 8)
                    | ((uint32_t)chunk[6] << 16) | ((uint32_t)chunk[7] << 24);
        if (memcmp(chunk, "fmt ", 4) == 0) {
            uint8_t fmt[16] = {0};
            uint32_t rd = sz < 16 ? sz : 16;
            if (fread(fmt, 1, rd, f) != rd) { fclose(f); return NULL; }
            if (sz > 16) fseek(f, (long)(sz - 16), SEEK_CUR);
            fmt_audio = (uint16_t)(fmt[0] | (fmt[1] << 8));
            fmt_ch    = (uint16_t)(fmt[2] | (fmt[3] << 8));
            fmt_rate  = (uint32_t)fmt[4] | ((uint32_t)fmt[5]<<8)
                      | ((uint32_t)fmt[6]<<16) | ((uint32_t)fmt[7]<<24);
            fmt_bits  = (uint16_t)(fmt[14] | (fmt[15] << 8));
        } else if (memcmp(chunk, "data", 4) == 0) {
            if (sz > 50 * 1024 * 1024) { fclose(f); return NULL; }
            pcm = malloc(sz);
            if (!pcm) { fclose(f); return NULL; }
            pcm_len = (uint32_t)fread(pcm, 1, sz, f);
        } else {
            fseek(f, (long)((sz + 1) & ~1u), SEEK_CUR);
        }
    }
    fclose(f);

    uint32_t bytes_per_sample = (uint32_t)fmt_ch * (fmt_bits / 8);
    if (!pcm || fmt_audio != 1 || fmt_ch < 1 || fmt_bits < 8 || bytes_per_sample == 0) {
        free(pcm); return NULL;
    }
    int32_t in_samples = (int32_t)(pcm_len / bytes_per_sample);
    int16_t *mono = malloc((size_t)in_samples * sizeof(int16_t));
    if (!mono) { free(pcm); return NULL; }

    for (int32_t i = 0; i < in_samples; i++) {
        int32_t sum = 0;
        for (uint32_t c = 0; c < fmt_ch; c++) {
            if (fmt_bits == 8) {
                uint8_t s8 = pcm[i * fmt_ch + c];
                sum += ((int32_t)s8 - 128) * 256;
            } else {
                uint32_t off = ((uint32_t)i * fmt_ch + c) * 2;
                sum += (int16_t)((uint16_t)pcm[off] | ((uint16_t)pcm[off+1] << 8));
            }
        }
        mono[i] = (int16_t)(sum / (int32_t)fmt_ch);
    }
    free(pcm);

    if (fmt_rate == (uint32_t)SAMPLE_RATE) {
        *out_samples = in_samples;
        return mono;
    }

    /* Nearest-neighbour resample to SAMPLE_RATE. */
    int32_t out_count = (int32_t)((int64_t)in_samples * SAMPLE_RATE / (int64_t)fmt_rate);
    int16_t *out_buf  = malloc((size_t)out_count * sizeof(int16_t));
    if (!out_buf) { free(mono); return NULL; }
    for (int32_t i = 0; i < out_count; i++) {
        int64_t src = (int64_t)i * fmt_rate / SAMPLE_RATE;
        out_buf[i]  = mono[src < in_samples ? src : in_samples - 1];
    }
    free(mono);
    *out_samples = out_count;
    return out_buf;
}

/* -----------------------------------------------------------------------
 * Exported API (matches sdlsfx.c signatures).
 * ----------------------------------------------------------------------- */

int setaudiosystem(int active)
{
    if (!g_enabled) return !active;
    if (!active) {
        if (g_stream) {
            AAudioStream_requestStop(g_stream);
            AAudioStream_close(g_stream);
            g_stream = NULL;
        }
        return TRUE;
    }
    if (g_stream) return TRUE;

    AAudioStreamBuilder *builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGW("AAudio_createStreamBuilder failed");
        return FALSE;
    }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, CHANNELS);
    AAudioStreamBuilder_setSampleRate(builder, SAMPLE_RATE);
    AAudioStreamBuilder_setDataCallback(builder, audio_callback, NULL);
    AAudioStreamBuilder_setPerformanceMode(builder,
                                           AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        LOGW("AAudio openStream: %s", AAudio_convertResultToText(r));
        return FALSE;
    }
    r = AAudioStream_requestStart(g_stream);
    if (r != AAUDIO_OK) {
        LOGW("AAudio requestStart: %s", AAudio_convertResultToText(r));
        AAudioStream_close(g_stream);
        g_stream = NULL;
        return FALSE;
    }
    LOGI("AAudio stream started: %d Hz, ch=%d, PCM_I16",
         AAudioStream_getSampleRate(g_stream),
         AAudioStream_getChannelCount(g_stream));
    return TRUE;
}

int loadsfxfromfile(int index, const char *filename)
{
    if (!filename) { freesfx(index); return TRUE; }
    if (!g_enabled) return FALSE;
    if (!g_stream && !setaudiosystem(TRUE)) return FALSE;

    int32_t n = 0;
    int16_t *wave = load_wav(filename, &n);
    if (!wave) {
        LOGW("loadsfxfromfile: cannot load %s", filename);
        return FALSE;
    }
    freesfx(index);
    pthread_mutex_lock(&snd_mutex);
    sounds[index].wave    = wave;
    sounds[index].len     = n;
    sounds[index].pos     = 0;
    sounds[index].playing = 0;
    pthread_mutex_unlock(&snd_mutex);
    return TRUE;
}

int sfx_loaded(int index)
{
    if (index < 0 || index >= SND_COUNT) return FALSE;
    pthread_mutex_lock(&snd_mutex);
    int loaded = (sounds[index].wave != NULL);
    pthread_mutex_unlock(&snd_mutex);
    return loaded;
}

extern int get_stop_bgm_on_level_complete(void);
extern void android_stop_bgm_on_win(void);
extern void android_set_level_end_state(int state);

void playsoundeffects(unsigned long sfx)
{
    displaysoundeffects(sfx);

    if (sfx & (1UL << SND_CHIP_WINS)) {
        android_set_level_end_state(1);
        if (get_stop_bgm_on_level_complete()) {
            android_stop_bgm_on_win();
        }
    }
    if (sfx & ((1UL << SND_CHIP_LOSES) | (1UL << SND_TIME_OUT) | (1UL << SND_DEREZZ))) {
        android_set_level_end_state(-1);
    }

    if (g_enabled && !g_stream) {
        setaudiosystem(TRUE);
    }
    if (!g_stream || !g_volume || !g_sfx_on) return;
    pthread_mutex_lock(&snd_mutex);
    for (int i = 0; i < SND_COUNT; i++) {
        unsigned long flag = 1UL << i;
        if (sfx & flag) {
            sounds[i].playing = 1;
            if (sounds[i].pos && i < SND_ONESHOT_COUNT)
                sounds[i].pos = 0;
        } else {
            if (i >= SND_ONESHOT_COUNT)
                sounds[i].playing = 0;
        }
    }
    pthread_mutex_unlock(&snd_mutex);
}

void setsoundeffects(int action)
{
    if (!g_stream) return;
    if (action < 0) {
        pthread_mutex_lock(&snd_mutex);
        for (int i = 0; i < SND_COUNT; i++) {
            sounds[i].playing = 0;
            sounds[i].pos     = 0;
        }
        pthread_mutex_unlock(&snd_mutex);
    } else if (!action) {
        pthread_mutex_lock(&snd_mutex);
        for (int i = 0; i < SND_COUNT; i++)
            sounds[i].playing = 0;
        pthread_mutex_unlock(&snd_mutex);
    }
}

void freesfx(int index)
{
    pthread_mutex_lock(&snd_mutex);
    free(sounds[index].wave);
    sounds[index].wave    = NULL;
    sounds[index].pos     = 0;
    sounds[index].playing = 0;
    pthread_mutex_unlock(&snd_mutex);
}

int setvolume(int v, int display)
{
    if (v < 0) v = 0; else if (v > 10) v = 10;
    g_volume = v;
    if (display) {
        char buf[32];
        sprintf(buf, "Volume: %d", v);
        setdisplaymsg(buf, 1000, 1000);
    }
    return TRUE;
}

int changevolume(int delta, int display)
{
    return setvolume(g_volume + delta, display);
}

int _sdlsfxinitialize(int silence, int soundbufsize)
{
    (void)soundbufsize;
    g_enabled = !silence;
    if (g_enabled)
        setaudiosystem(TRUE);
    return TRUE;
}

/* Called from JNI on Activity.onPause — closes stream so Android can
 * fully release the audio device during sleep. */
void android_audio_pause(void)
{
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = NULL;
    }
}

/* Called from JNI on Activity.onResume — reopens the stream. */
void android_audio_resume(void)
{
    if (g_enabled && !g_stream)
        setaudiosystem(TRUE);
}

void android_set_sfx_enabled(int enabled)
{
    g_sfx_on = enabled;
    if (!enabled) {
        setsoundeffects(-1); /* Stop all current SFX */
    }
}
