/* androidbind.c: TW_Surface implementations for Android (no SDL).
 *
 * All surfaces are ARGB8888 (bpp=4) except font surfaces which are
 * 8-bit palette (bpp=1) so that makefontfromsurface() can read them.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#include "../generic/generic.h"
#include "../gen.h"
#include "../err.h"

#define LOG_TAG "tworld"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

genericglobals geng;

static char twerror[256] = "no error";

const char *TW_GetError(void)
{
    return twerror;
}

static void set_error(const char *msg)
{
    strncpy(twerror, msg, sizeof twerror - 1);
    twerror[sizeof twerror - 1] = '\0';
}

/* Allocate a new surface. transparency=TRUE → bpp=4 with alpha.
 * transparency=FALSE → bpp=4 matching screen format (opaque).
 * Special case: bpp=-1 means 8-bit palette surface (for font loading).
 */
TW_Surface *TW_NewSurface(int w, int h, int transparency)
{
    TW_Surface *s = calloc(1, sizeof *s);
    if (!s) { set_error("out of memory"); return NULL; }
    s->w   = w;
    s->h   = h;
    s->bpp = 4;
    s->pitch = w * 4;
    s->pixels = calloc(h, s->pitch);
    if (!s->pixels) { free(s); set_error("out of memory"); return NULL; }
    s->has_alpha = transparency ? 1 : 0;
    return s;
}

/* Internal: create an 8-bit palette surface (for font BMP). */
static TW_Surface *new_surface_8bpp(int w, int h)
{
    TW_Surface *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->w     = w;
    s->h     = h;
    s->bpp   = 1;
    s->pitch = w;
    s->pixels = calloc(h, w);
    if (!s->pixels) { free(s); return NULL; }
    return s;
}

void TW_FreeSurface(TW_Surface *s)
{
    if (!s) return;
    free(s->pixels);
    free(s);
}

int TW_LockSurface(TW_Surface *s)  { (void)s; return 0; }
void TW_UnlockSurface(TW_Surface *s) { (void)s; }

void TW_SetColorKey(TW_Surface *s, uint32_t key)
{
    if (!s) return;
    s->colorkey     = key;
    s->has_colorkey = 1;
}

void TW_ResetColorKey(TW_Surface *s)
{
    if (!s) return;
    s->has_colorkey = 0;
}

void TW_EnableAlpha(TW_Surface *s)
{
    if (s) s->has_alpha = 1;
}

/* Read a pixel from a surface (returns palette index for bpp=1,
 * ARGB value for bpp=4).
 */
uint32_t TW_PixelAt(TW_Surface *s, int x, int y)
{
    if (!s || x < 0 || y < 0 || x >= s->w || y >= s->h)
        return 0;
    if (s->bpp == 1) {
        return ((uint8_t *)s->pixels)[y * s->pitch + x];
    } else {
        return ((uint32_t *)((uint8_t *)s->pixels + y * s->pitch))[x];
    }
}

/* Fill a rectangle on a surface. color is ARGB8888 for bpp=4,
 * or a palette index for bpp=1.
 */
int TW_FillRect(TW_Surface *s, TW_Rect *rect, uint32_t color)
{
    if (!s) return -1;
    int x0 = rect ? rect->x : 0;
    int y0 = rect ? rect->y : 0;
    int w  = rect ? rect->w : s->w;
    int h  = rect ? rect->h : s->h;
    if (x0 < 0) { w += x0; x0 = 0; }
    if (y0 < 0) { h += y0; y0 = 0; }
    if (x0 + w > s->w) w = s->w - x0;
    if (y0 + h > s->h) h = s->h - y0;
    if (w <= 0 || h <= 0) return 0;

    if (s->bpp == 1) {
        for (int y = y0; y < y0 + h; y++)
            memset((uint8_t *)s->pixels + y * s->pitch + x0, (int)color, w);
    } else {
        for (int y = y0; y < y0 + h; y++) {
            uint32_t *row = (uint32_t *)((uint8_t *)s->pixels + y * s->pitch) + x0;
            for (int x = 0; x < w; x++)
                row[x] = color;
        }
    }
    return 0;
}

/* Blit src onto dst. Handles:
 *   8bpp → 32bpp  (palette lookup, colorkey → transparent)
 *   32bpp → 32bpp (direct copy or alpha blend)
 */
int TW_BlitSurface(TW_Surface *src, TW_Rect *srcrect,
                   TW_Surface *dst, TW_Rect *dstrect)
{
    if (!src || !dst) return -1;

    int sx = srcrect ? srcrect->x : 0;
    int sy = srcrect ? srcrect->y : 0;
    int sw = srcrect ? srcrect->w : src->w;
    int sh = srcrect ? srcrect->h : src->h;
    int dx = dstrect ? dstrect->x : 0;
    int dy = dstrect ? dstrect->y : 0;

    /* Clip to source bounds. */
    if (sx < 0) { dx -= sx; sw += sx; sx = 0; }
    if (sy < 0) { dy -= sy; sh += sy; sy = 0; }
    if (sx + sw > src->w) sw = src->w - sx;
    if (sy + sh > src->h) sh = src->h - sy;

    /* Clip to dest bounds. */
    if (dx < 0) { sx -= dx; sw += dx; dx = 0; }
    if (dy < 0) { sy -= dy; sh += dy; dy = 0; }
    if (dx + sw > dst->w) sw = dst->w - dx;
    if (dy + sh > dst->h) sh = dst->h - dy;

    if (sw <= 0 || sh <= 0) return 0;

    if (src->bpp == 1) {
        /* 8bpp palette → 32bpp destination */
        for (int y = 0; y < sh; y++) {
            const uint8_t  *srow = (const uint8_t *)src->pixels + (sy + y) * src->pitch + sx;
            uint32_t       *drow = (uint32_t *)((uint8_t *)dst->pixels + (dy + y) * dst->pitch) + dx;
            for (int x = 0; x < sw; x++) {
                uint8_t idx = srow[x];
                if (src->has_colorkey && (uint32_t)idx == src->colorkey)
                    continue;
                drow[x] = src->palette[idx] | 0xFF000000u;
            }
        }
    } else {
        /* 32bpp → 32bpp */
        if (src->has_alpha) {
            /* Alpha blend. */
            for (int y = 0; y < sh; y++) {
                const uint32_t *srow = (const uint32_t *)((const uint8_t *)src->pixels + (sy + y) * src->pitch) + sx;
                uint32_t       *drow = (uint32_t *)((uint8_t *)dst->pixels + (dy + y) * dst->pitch) + dx;
                for (int x = 0; x < sw; x++) {
                    uint32_t sp = srow[x];
                    uint32_t sa = (sp >> 24) & 0xFF;
                    if (sa == 0) continue;
                    if (sa == 255) { drow[x] = sp; continue; }
                    uint32_t dp = drow[x];
                    uint32_t da = 255 - sa;
                    uint32_t r = ((sp >> 16 & 0xFF) * sa + (dp >> 16 & 0xFF) * da) / 255;
                    uint32_t g = ((sp >>  8 & 0xFF) * sa + (dp >>  8 & 0xFF) * da) / 255;
                    uint32_t b = ((sp       & 0xFF) * sa + (dp       & 0xFF) * da) / 255;
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        } else {
            /* Plain copy, optional color key. */
            for (int y = 0; y < sh; y++) {
                const uint32_t *srow = (const uint32_t *)((const uint8_t *)src->pixels + (sy + y) * src->pitch) + sx;
                uint32_t       *drow = (uint32_t *)((uint8_t *)dst->pixels + (dy + y) * dst->pitch) + dx;
                if (src->has_colorkey) {
                    for (int x = 0; x < sw; x++) {
                        if (srow[x] != src->colorkey)
                            drow[x] = srow[x];
                    }
                } else {
                    memcpy(drow, srow, sw * 4);
                }
            }
        }
    }
    return 0;
}

/* Scale-blit 32bpp surface into dst rectangle (nearest neighbor). */
int TW_BlitSurfaceScaled(TW_Surface *src, TW_Rect *srcrect,
                         TW_Surface *dst, TW_Rect *dstrect)
{
    if (!src || !dst) return -1;

    int sx = srcrect ? srcrect->x : 0;
    int sy = srcrect ? srcrect->y : 0;
    int sw = srcrect ? srcrect->w : src->w;
    int sh = srcrect ? srcrect->h : src->h;

    int dx = dstrect ? dstrect->x : 0;
    int dy = dstrect ? dstrect->y : 0;
    int dw = dstrect ? dstrect->w : dst->w;
    int dh = dstrect ? dstrect->h : dst->h;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return 0;

    for (int y = 0; y < dh; y++) {
        int src_y = sy + (y * sh) / dh;
        if (src_y >= src->h) src_y = src->h - 1;
        int dst_y = dy + y;
        if (dst_y < 0 || dst_y >= dst->h) continue;

        const uint32_t *srow = (const uint32_t *)((const uint8_t *)src->pixels + src_y * src->pitch);
        uint32_t       *drow = (uint32_t *)((uint8_t *)dst->pixels + dst_y * dst->pitch);

        for (int x = 0; x < dw; x++) {
            int src_x = sx + (x * sw) / dw;
            if (src_x >= src->w) src_x = src->w - 1;
            int dst_x = dx + x;
            if (dst_x < 0 || dst_x >= dst->w) continue;

            uint32_t pixel = srow[src_x];
            if (!src->has_colorkey || pixel != src->colorkey) {
                drow[dst_x] = pixel;
            }
        }
    }
    return 0;
}

/* Convert a surface to "screen format" (ARGB8888 opaque).
 * Always allocates a new surface; caller must free the original.
 * (SDL_DisplayFormat semantics: returns new allocation, never frees input.)
 */
TW_Surface *TW_DisplayFormat(TW_Surface *s)
{
    if (!s) return NULL;
    TW_Surface *out = TW_NewSurface(s->w, s->h, 0);
    if (!out) return NULL;
    TW_BlitSurface(s, NULL, out, NULL);
    /* Ensure alpha channel is fully opaque. */
    uint32_t *px = out->pixels;
    for (int i = 0; i < out->w * out->h; i++)
        px[i] |= 0xFF000000u;
    return out;
}

/* Convert to ARGB8888 with alpha channel.
 * Always allocates a new surface; caller must free the original.
 * (SDL_DisplayFormatAlpha semantics: returns new allocation, never frees input.)
 */
TW_Surface *TW_DisplayFormatAlpha(TW_Surface *s)
{
    if (!s) return NULL;
    TW_Surface *out = TW_NewSurface(s->w, s->h, 1);
    if (!out) return NULL;
    /* Copy pixels; transparent pixels (alpha=0) from colorkey blit stay transparent. */
    TW_BlitSurface(s, NULL, out, NULL);
    out->has_alpha = 1;
    return out;
}

/* -----------------------------------------------------------------------
 * Minimal BMP loader — handles 8bpp paletted and 24/32bpp DIB BMPs.
 * ----------------------------------------------------------------------- */
static TW_Surface *load_bmp(const char *filename)
{
    FILE *f = fopen(filename, "rb");
    if (!f) {
        snprintf(twerror, sizeof twerror, "cannot open %s", filename);
        return NULL;
    }

    /* File header (14 bytes). */
    uint8_t fhdr[14];
    if (fread(fhdr, 1, 14, f) != 14 || fhdr[0] != 'B' || fhdr[1] != 'M') {
        set_error("not a BMP file");
        fclose(f); return NULL;
    }
    uint32_t data_offset = fhdr[10] | (fhdr[11]<<8) | (fhdr[12]<<16) | (fhdr[13]<<24);

    /* DIB header — at least 40 bytes. */
    uint8_t dib[40];
    if (fread(dib, 1, 40, f) != 40) {
        set_error("truncated BMP header");
        fclose(f); return NULL;
    }
    int32_t  bmp_w   = (int32_t)(dib[4] | (dib[5]<<8) | (dib[6]<<16) | (dib[7]<<24));
    int32_t  bmp_h   = (int32_t)(dib[8] | (dib[9]<<8) | (dib[10]<<16) | (dib[11]<<24));
    uint16_t bpp_src = dib[14] | (dib[15]<<8);
    uint32_t compress= dib[16] | (dib[17]<<8) | (dib[18]<<16) | (dib[19]<<24);
    uint32_t clr_used= dib[32] | (dib[33]<<8) | (dib[34]<<16) | (dib[35]<<24);

    if (compress != 0) {
        set_error("compressed BMP not supported");
        fclose(f); return NULL;
    }

    int flip = bmp_h > 0;          /* positive height → bottom-up */
    if (bmp_h < 0) bmp_h = -bmp_h;
    if (bmp_w <= 0 || bmp_h <= 0) {
        set_error("invalid BMP dimensions");
        fclose(f); return NULL;
    }

    /* DIB header size tells us if there's extra data before palette. */
    uint32_t dib_size = dib[0] | (dib[1]<<8) | (dib[2]<<16) | (dib[3]<<24);

    /* Palette (for <= 8bpp). */
    uint32_t palette[256] = {0};
    if (bpp_src <= 8) {
        uint32_t ncolors = clr_used ? clr_used : (1u << bpp_src);
        if (ncolors > 256) ncolors = 256;
        /* Seek to palette (just after the DIB header, which starts at offset 14). */
        fseek(f, 14 + (long)dib_size, SEEK_SET);
        uint8_t quad[4];
        for (uint32_t i = 0; i < ncolors; i++) {
            if (fread(quad, 1, 4, f) != 4) break;
            /* BMP palette: BGRA → ARGB8888 */
            palette[i] = 0xFF000000u | ((uint32_t)quad[2]<<16) | ((uint32_t)quad[1]<<8) | quad[0];
        }
    }

    /* Seek to pixel data. */
    fseek(f, (long)data_offset, SEEK_SET);

    TW_Surface *s;
    if (bpp_src == 1) {
        /* 1bpp (monochrome/2-color BMP) → convert to ARGB8888. */
        s = TW_NewSurface(bmp_w, bmp_h, 0);
        if (!s) { fclose(f); return NULL; }

        int row_bytes = ((bmp_w + 31) / 32) * 4;
        uint8_t *row_buf = malloc(row_bytes);
        if (!row_buf) { TW_FreeSurface(s); fclose(f); return NULL; }
        for (int y = 0; y < bmp_h; y++) {
            int dst_y = flip ? (bmp_h - 1 - y) : y;
            fread(row_buf, 1, row_bytes, f);
            uint32_t *dst = (uint32_t *)((uint8_t *)s->pixels + dst_y * s->pitch);
            for (int x = 0; x < bmp_w; x++) {
                int byte_idx = x / 8;
                int bit_idx  = 7 - (x % 8);
                int val      = (row_buf[byte_idx] >> bit_idx) & 1;
                dst[x]       = palette[val];
            }
        }
        free(row_buf);
    } else if (bpp_src == 4 || bpp_src == 8) {
        /* Keep as 8bpp (one byte per pixel = palette index) so
         * makefontfromsurface() can read palette indices directly.
         * 4bpp nibbles are expanded to full bytes. */
        s = new_surface_8bpp(bmp_w, bmp_h);
        if (!s) { fclose(f); return NULL; }
        memcpy(s->palette, palette, sizeof palette);

        int row_bytes;
        if (bpp_src == 4)
            row_bytes = ((bmp_w + 1) / 2 + 3) & ~3;  /* nibbles, padded to 4 bytes */
        else
            row_bytes = (bmp_w + 3) & ~3;
        uint8_t *row_buf = malloc(row_bytes);
        if (!row_buf) { TW_FreeSurface(s); fclose(f); return NULL; }
        for (int y = 0; y < bmp_h; y++) {
            int dst_y = flip ? (bmp_h - 1 - y) : y;
            uint8_t *dst = (uint8_t *)s->pixels + dst_y * s->pitch;
            fread(row_buf, 1, row_bytes, f);
            if (bpp_src == 4) {
                for (int x = 0; x < bmp_w; x++)
                    dst[x] = (x & 1) ? (row_buf[x/2] & 0x0F) : (row_buf[x/2] >> 4);
            } else {
                memcpy(dst, row_buf, bmp_w);
            }
        }
        free(row_buf);
    } else {
        /* 24 or 32bpp → convert to ARGB8888. */
        s = TW_NewSurface(bmp_w, bmp_h, 0);
        if (!s) { fclose(f); return NULL; }

        int bytes_pp = bpp_src / 8;
        int row_bytes = ((bmp_w * bytes_pp) + 3) & ~3;
        uint8_t *row_buf = malloc(row_bytes);
        if (!row_buf) { TW_FreeSurface(s); fclose(f); return NULL; }
        for (int y = 0; y < bmp_h; y++) {
            int dst_y = flip ? (bmp_h - 1 - y) : y;
            fread(row_buf, 1, row_bytes, f);
            uint32_t *dst = (uint32_t *)((uint8_t *)s->pixels + dst_y * s->pitch);
            for (int x = 0; x < bmp_w; x++) {
                uint8_t *p = row_buf + x * bytes_pp;
                /* BMP stores BGR(A). */
                uint8_t b = p[0], g = p[1], r = p[2];
                uint8_t a = (bytes_pp == 4) ? p[3] : 0xFF;
                dst[x] = ((uint32_t)a << 24) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
            }
        }
        free(row_buf);
    }

    fclose(f);
    return s;
}

TW_Surface *TW_LoadBMP(char const *filename, int setscreenpalette)
{
    TW_Surface *s = load_bmp(filename);
    if (!s) {
        LOGE("TW_LoadBMP failed: %s: %s", filename, twerror);
        return NULL;
    }
    /* setscreenpalette is a no-op for us (we always use ARGB8888). */
    (void)setscreenpalette;
    return s;
}

/* Return a zeroed key-state array (no keys pressed at startup). */
uint8_t *TW_GetKeyState(int *numkeys)
{
    static uint8_t ks[TWK_LAST];
    memset(ks, 0, sizeof ks);
    if (numkeys) *numkeys = TWK_LAST;
    return ks;
}
