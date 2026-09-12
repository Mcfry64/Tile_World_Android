/* oshwbind.h: Binds the generic module to the Android OS/hardware layer.
 *
 * Replaces oshw-sdl/oshwbind.h. No SDL dependency.
 */

#ifndef HEADER_android_oshwbind_h_
#define HEADER_android_oshwbind_h_

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Alpha constants matching SDL values. */
enum {
    TW_ALPHA_TRANSPARENT = 0,
    TW_ALPHA_OPAQUE      = 255
};

/* Key index constants (same layout as SDL version — must match generic/in.c). */
enum {
    TWK_BACKSPACE  = '\b',
    TWK_TAB        = '\t',
    TWK_RETURN     = '\r',
    TWK_ESCAPE     = 27,
    TWK_CTRL_C     = '\003',

#ifdef TWPLUSPLUS
    TWC_SEESCORES       = 200,
    TWC_SEESOLUTIONFILES= 201,
    TWC_TIMESCLIPBOARD  = 202,
    TWC_QUITLEVEL       = 203,
    TWC_QUIT            = 204,
    TWC_PROCEED         = 205,
    TWC_PAUSEGAME       = 206,
    TWC_SAMELEVEL       = 207,
    TWC_NEXTLEVEL       = 208,
    TWC_PREVLEVEL       = 209,
    TWC_GOTOLEVEL       = 210,
    TWC_PLAYBACK        = 211,
    TWC_CHECKSOLUTION   = 212,
    TWC_REPLSOLUTION    = 213,
    TWC_KILLSOLUTION    = 214,
    TWC_SEEK            = 215,
    TWC_HELP            = 216,
    TWC_KEYS            = 217,
    TWC_TILESET         = 218,
#endif

    TWK_UP         = 256,
    TWK_DOWN       = 257,
    TWK_LEFT       = 258,
    TWK_RIGHT      = 259,
    TWK_KP8        = 260,
    TWK_KP4        = 261,
    TWK_KP2        = 262,
    TWK_KP6        = 263,
    TWK_KP_ENTER   = 264,
    TWK_INSERT     = 265,
    TWK_DELETE     = 266,
    TWK_HOME       = 267,
    TWK_END        = 268,
    TWK_PAGEUP     = 269,
    TWK_PAGEDOWN   = 270,
    TWK_F1         = 271,
    TWK_F2         = 272,
    TWK_F3         = 273,
    TWK_F4         = 274,
    TWK_F5         = 275,
    TWK_F6         = 276,
    TWK_F7         = 277,
    TWK_F8         = 278,
    TWK_F9         = 279,
    TWK_F10        = 280,
    TWK_LSHIFT     = 281,
    TWK_RSHIFT     = 282,
    TWK_LCTRL      = 283,
    TWK_RCTRL      = 284,
    TWK_LALT       = 285,
    TWK_RALT       = 286,
    TWK_LMETA      = 287,
    TWK_RMETA      = 288,
    TWK_CAPSLOCK   = 289,
    TWK_NUMLOCK    = 290,
    TWK_SCROLLLOCK = 291,
    TWK_MODE       = 292,
    TWK_LAST       = 320
};

enum {
    TW_BUTTON_LEFT      = 1,
    TW_BUTTON_RIGHT     = 3,
    TW_BUTTON_MIDDLE    = 2,
    TW_BUTTON_WHEELUP   = 4,
    TW_BUTTON_WHEELDOWN = 5
};

/* Surface — supports bpp=1 (palette, for font) and bpp=4 (ARGB8888, for screen/tiles). */
typedef struct TW_Surface {
    int      w, h;
    int      pitch;          /* bytes per row */
    int      bpp;            /* bytes per pixel: 1 or 4 */
    void    *pixels;
    uint32_t colorkey;       /* palette index (bpp=1) or ARGB value (bpp=4) */
    int      has_colorkey;
    int      has_alpha;      /* bpp=4 only: alpha-blend when used as source */
    uint32_t palette[256];   /* bpp=1 only: ARGB8888 palette */
} TW_Surface;

typedef struct TW_Rect {
    int x, y, w, h;
} TW_Rect;

/* Surface operations. */
extern TW_Surface *TW_NewSurface(int w, int h, int transparency);
extern void        TW_FreeSurface(TW_Surface *s);
extern int         TW_BlitSurface(TW_Surface *src, TW_Rect *srcrect,
                                   TW_Surface *dst, TW_Rect *dstrect);
extern int         TW_BlitSurfaceScaled(TW_Surface *src, TW_Rect *srcrect,
                                         TW_Surface *dst, TW_Rect *dstrect);
extern int         TW_FillRect(TW_Surface *s, TW_Rect *rect, uint32_t color);
extern int         TW_LockSurface(TW_Surface *s);
extern void        TW_UnlockSurface(TW_Surface *s);
extern void        TW_SetColorKey(TW_Surface *s, uint32_t key);
extern void        TW_ResetColorKey(TW_Surface *s);
extern void        TW_EnableAlpha(TW_Surface *s);
extern TW_Surface *TW_DisplayFormat(TW_Surface *s);
extern TW_Surface *TW_DisplayFormatAlpha(TW_Surface *s);
extern uint32_t    TW_PixelAt(TW_Surface *s, int x, int y);
extern TW_Surface *TW_LoadBMP(char const *filename, int setscreenpalette);
extern uint8_t    *TW_GetKeyState(int *numkeys);

#define TW_MUSTLOCK(s)       (0)
#define TW_BytesPerPixel(s)  ((s)->bpp)

/* MapRGB: always returns ARGB8888. Surface argument kept for API compat. */
static inline uint32_t TW_MapRGB_fn(TW_Surface *s, uint8_t r, uint8_t g, uint8_t b)
{
    (void)s;
    return (uint32_t)(0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b);
}
static inline uint32_t TW_MapRGBA_fn(TW_Surface *s, uint8_t r, uint8_t g, uint8_t b, uint8_t a)
{
    (void)s;
    return (uint32_t)(((uint32_t)a << 24) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b);
}
#define TW_MapRGB(s,r,g,b)     TW_MapRGB_fn(s,(uint8_t)(r),(uint8_t)(g),(uint8_t)(b))
#define TW_MapRGBA(s,r,g,b,a)  TW_MapRGBA_fn(s,(uint8_t)(r),(uint8_t)(g),(uint8_t)(b),(uint8_t)(a))

/* Timing. */
static inline uint32_t TW_GetTicks(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}
static inline void TW_Delay(uint32_t ms)
{
    struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
    nanosleep(&ts, NULL);
}

extern const char *TW_GetError(void);

#endif /* HEADER_android_oshwbind_h_ */
