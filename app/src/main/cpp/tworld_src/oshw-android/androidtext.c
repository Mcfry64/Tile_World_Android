/* androidtext.c: Font rendering for Android (no SDL).
 *
 * Adapted from oshw-sdl/sdltext.c. Surface is always bpp=4 (ARGB8888);
 * font BMP is bpp=1 (palette) and handled by makefontfromsurface.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include "androidgen.h"
#include "../err.h"

/* Extract the glyph data from an 8-bit paletted font surface.
 * Layout documented in the Tile World distribution.
 */
static int makefontfromsurface(fontinfo *pf, TW_Surface *surface)
{
    char          brk[267];
    unsigned char *p;
    unsigned char *dest;
    uint8_t        foregnd, bkgnd;
    int            pitch, wsum;
    int            ch;
    int            x, y, x0, y0, w;

    if (!surface || surface->bpp != 1)
        return FALSE;

    pitch  = surface->pitch;
    p      = (unsigned char *)surface->pixels;
    foregnd = p[0];
    bkgnd   = p[pitch];

    for (y = 1, p += pitch; y < surface->h && *p == bkgnd; ++y, p += pitch) ;
    pf->h = (signed char)(y - 1);

    wsum = 0;
    ch   = 32;
    memset(pf->w,   0, sizeof pf->w);
    memset(brk,     0, sizeof brk);

    for (y = 0; y + pf->h < surface->h && ch < 256; y += pf->h + 1) {
        p  = (unsigned char *)surface->pixels;
        p += y * pitch;
        x0 = 1;
        for (x = 1; x < surface->w; ++x) {
            if (p[x] == bkgnd) continue;
            w    = x - x0;
            x0   = x + 1;
            pf->w[ch] = (signed char)w;
            wsum += w;
            ++ch;
            if      (ch == 127) ch = 144;
            else if (ch == 154) ch = 160;
            else if (ch == 256) break;
        }
        brk[ch] = 1;
    }

    if (!(pf->memory = calloc(wsum, pf->h)))
        memerrexit();

    x0   = 1;
    y0   = 1;
    dest = pf->memory;
    for (ch = 0; ch < 256; ++ch) {
        pf->bits[ch] = dest;
        if (pf->w[ch] == 0) continue;
        if (brk[ch]) { x0 = 1; y0 += pf->h + 1; }
        p  = (unsigned char *)surface->pixels;
        p += y0 * pitch + x0;
        for (y = 0; y < pf->h; ++y, p += pitch)
            for (x = 0; x < pf->w[ch]; ++x, ++dest)
                *dest = (p[x] == bkgnd) ? 0 : (p[x] == foregnd) ? 2 : 1;
        x0 += pf->w[ch] + 1;
    }
    return TRUE;
}

static int g_font_scale_num = 1;
static int g_font_scale_den = 1;

void set_font_scale_frac(int num, int den)
{
    g_font_scale_num = num > 0 ? num : 1;
    g_font_scale_den = den > 0 ? den : 1;
}

void set_font_scale(int scale)
{
    set_font_scale_frac(scale > 0 ? scale : 1, 1);
}

static inline int scale_val(int val)
{
    return (val * g_font_scale_num) / g_font_scale_den;
}

int measuremltext(unsigned char const *text, int len, int maxwidth)
{
    int brk, w, h, n;
    if (len < 0) len = (int)strlen((char const*)text);
    h = brk = 0;
    int font_h = scale_val(andg.font.h);
    for (n = 0, w = 0; n < len; ++n) {
        int char_w = scale_val(andg.font.w[text[n]]);
        w += char_w;
        if (isspace(text[n])) {
            brk = w;
        } else if (w > maxwidth) {
            h += font_h;
            if (brk) { w -= brk; brk = 0; }
            else     { w  = char_w; brk = 0; }
        }
    }
    if (w) h += font_h;
    return h;
}

/* Render one scanline of text (32bpp). */
static void drawtextscanline32(uint32_t *scanline, int w, int y,
                                uint32_t const *clr,
                                unsigned char const *text, int len)
{
    if (w <= 0 || !scanline || !clr) return;

    unsigned char const *glyph;
    int                  n, x, s;
    int                  src_y = (y * g_font_scale_den) / g_font_scale_num;
    if (src_y >= andg.font.h) src_y = andg.font.h - 1;

    for (n = 0; n < len && w > 0 && text; ++n) {
        unsigned char ch = text[n];
        glyph = andg.font.bits[ch];
        if (!glyph) continue;
        int char_w = (unsigned char)andg.font.w[(unsigned char)ch];
        glyph += src_y * char_w;
        for (x = 0; x < char_w && w > 0; ++x) {
            uint32_t c = clr[glyph[x]];
            int px_count = ((x + 1) * g_font_scale_num) / g_font_scale_den - (x * g_font_scale_num) / g_font_scale_den;
            for (s = 0; s < px_count && w > 0; ++s) {
                *scanline++ = c;
                --w;
            }
        }
    }
    while (w > 0) {
        *scanline++ = clr[0];
        --w;
    }
}

static void drawtext(TW_Rect *rect, unsigned char const *text,
                     int len, int flags)
{
    uint32_t *clr;
    int       l, r;
    int       y, w;

    if (!geng.screen || !geng.screen->pixels) return;

    if (len < 0) len = text ? (int)strlen((char const*)text) : 0;

    w = 0;
    if (text) {
        for (int n = 0; n < len; ++n)
            w += scale_val(andg.font.w[text[n]]);
    }

    if (flags & PT_CALCSIZE) {
        rect->h = scale_val(andg.font.h);
        rect->w = w;
        return;
    }

    if (w >= rect->w) {
        w = rect->w; l = r = 0;
    } else if (flags & PT_RIGHT) {
        l = rect->w - w; r = 0;
    } else if (flags & PT_CENTER) {
        l = (rect->w - w) / 2;
        r = (rect->w - w) - l;
    } else {
        l = 0; r = rect->w - w;
    }

    if      (flags & PT_DIM)    clr = andg.dimtextclr.c;
    else if (flags & PT_HILIGHT) clr = andg.hilightclr.c;
    else                         clr = andg.textclr.c;

    int text_h = scale_val(andg.font.h);
    int rx = rect->x;
    int ry = rect->y;
    int rw = rect->w;
    int rh = rect->h;

    if (rx >= geng.screen->w || ry >= geng.screen->h || rw <= 0 || rh <= 0) {
        if (flags & PT_UPDATERECT) {
            rect->y += text_h;
            rect->h -= text_h;
        }
        return;
    }

    int max_y = text_h < rh ? text_h : rh;
    if (ry + max_y > geng.screen->h) {
        max_y = geng.screen->h - ry;
    }

    int draw_l = l, draw_w = w, draw_r = r;
    int draw_x = rx;

    if (draw_x < 0) {
        int clip_left = -draw_x;
        draw_x = 0;
        if (draw_l >= clip_left) {
            draw_l -= clip_left;
        } else {
            clip_left -= draw_l;
            draw_l = 0;
            if (draw_w >= clip_left) {
                draw_w -= clip_left;
            } else {
                clip_left -= draw_w;
                draw_w = 0;
                draw_r -= clip_left;
                if (draw_r < 0) draw_r = 0;
            }
        }
    }

    if (draw_x + draw_l + draw_w + draw_r > geng.screen->w) {
        int overflow = (draw_x + draw_l + draw_w + draw_r) - geng.screen->w;
        if (draw_r >= overflow) {
            draw_r -= overflow;
        } else {
            overflow -= draw_r;
            draw_r = 0;
            if (draw_w >= overflow) {
                draw_w -= overflow;
            } else {
                overflow -= draw_w;
                draw_w = 0;
                draw_l -= overflow;
                if (draw_l < 0) draw_l = 0;
            }
        }
    }

    if (max_y > 0 && ry >= 0) {
        int pitch = geng.screen->pitch;
        uint8_t *base = (uint8_t *)geng.screen->pixels + ry * pitch + draw_x * 4;

        for (y = 0; y < max_y; ++y) {
            uint32_t *row = (uint32_t *)(base + y * pitch);
            if (draw_l > 0) {
                drawtextscanline32(row, draw_l, y, clr, NULL, 0);
                row += draw_l;
            }
            if (draw_w > 0 && text) {
                drawtextscanline32(row, draw_w, y, clr, text, len);
                row += draw_w;
            }
            if (draw_r > 0) {
                drawtextscanline32(row, draw_r, y, clr, NULL, 0);
            }
        }
    }

    if (flags & PT_UPDATERECT) {
        rect->y += text_h;
        rect->h -= text_h;
    }
}

static void drawmultilinetext(TW_Rect *rect, unsigned char const *text,
                               int len, int flags)
{
    TW_Rect area;
    int     index, brkw, brkn;
    int     w, n;

    if (flags & PT_CALCSIZE) {
        rect->h = measuremltext(text, len, rect->w);
        return;
    }
    if (len < 0) len = (int)strlen((char const*)text);

    area = *rect;
    brkw = brkn = index = 0;
    for (n = 0, w = 0; n < len; ++n) {
        if (text[n] == '\n') {
            drawtext(&area, text + index, n - index, flags | PT_UPDATERECT);
            index = n + 1;
            w = 0;
            brkw = 0;
            continue;
        }
        int char_w = scale_val(andg.font.w[text[n]]);
        w += char_w;
        if (isspace(text[n])) {
            brkn = n; brkw = w;
        } else if (w > rect->w) {
            if (brkw) {
                drawtext(&area, text + index, brkn - index, flags | PT_UPDATERECT);
                index = brkn + 1;
                w    -= brkw;
            } else {
                drawtext(&area, text + index, n - index, flags | PT_UPDATERECT);
                index = n;
                w     = char_w;
            }
            brkw = 0;
        }
    }
    if (w) drawtext(&area, text + index, len - index, flags | PT_UPDATERECT);

    if (flags & PT_UPDATERECT) {
        *rect = area;
    } else {
        while (area.h > 0 && area.y < geng.screen->h) drawtext(&area, NULL, 0, PT_UPDATERECT);
    }
}

static void puttext_impl(TW_Rect *rect, char const *text, int len, int flags)
{
    if (!andg.font.h)
        die("no font available!");
    if (len < 0) len = text ? (int)strlen(text) : 0;

    if (flags & PT_MULTILINE)
        drawmultilinetext(rect, (unsigned char const*)text, len, flags);
    else
        drawtext(rect, (unsigned char const*)text, len, flags);
}

static TW_Rect *measuretable_impl(TW_Rect const *area, tablespec const *table)
{
    TW_Rect               *colsizes;
    unsigned char const   *p;
    int                    sep, mlindex, mlwidth, diff;
    int                    i, j, n, i0, c, w, x;

    if (!(colsizes = malloc(table->cols * sizeof *colsizes)))
        memerrexit();
    for (i = 0; i < table->cols; ++i) {
        colsizes[i].x = 0;
        colsizes[i].y = area->y;
        colsizes[i].w = 0;
        colsizes[i].h = area->h;
    }

    mlindex = -1; mlwidth = 0; n = 0;
    for (j = 0; j < table->rows; ++j) {
        for (i = 0; i < table->cols; ++n) {
            c = table->items[n][0] - '0';
            if (c == 1) {
                w = 0;
                p = (unsigned char const*)table->items[n];
                for (p += 2; *p; ++p) w += scale_val(andg.font.w[*p]);
                if (table->items[n][1] == '!') {
                    if (w > mlwidth || mlindex != i) mlwidth = w;
                    mlindex = i;
                } else {
                    if (w > colsizes[i].w) colsizes[i].w = w;
                }
            }
            i += c;
        }
    }

    sep  = scale_val(andg.font.w[' ']) * table->sep;
    w    = -sep;
    for (i = 0; i < table->cols; ++i) w += colsizes[i].w + sep;
    diff = area->w - w;
    if (diff < 0 && table->collapse >= 0) {
        w = -diff;
        if (colsizes[table->collapse].w < w)
            w = colsizes[table->collapse].w - scale_val(andg.font.w[' ']);
        colsizes[table->collapse].w -= w;
        diff += w;
    }

    if (diff > 0) {
        n = 0;
        for (j = 0; j < table->rows && diff > 0; ++j) {
            for (i = 0; i < table->cols; ++n) {
                c = table->items[n][0] - '0';
                if (c > 1 && table->items[n][1] != '!') {
                    w = sep;
                    p = (unsigned char const*)table->items[n];
                    for (p += 2; *p; ++p) w += scale_val(andg.font.w[*p]);
                    for (i0 = i; i0 < i + c; ++i0) w -= colsizes[i0].w + sep;
                    if (w > 0) {
                        if      (table->collapse >= i && table->collapse < i+c) i0 = table->collapse;
                        else if (mlindex >= i && mlindex < i+c)                  i0 = mlindex;
                        else                                                      i0 = i + c - 1;
                        if (w > diff) w = diff;
                        colsizes[i0].w += w;
                        diff -= w;
                        if (diff == 0) break;
                    }
                }
                i += c;
            }
        }
    }
    if (diff > 0 && mlindex >= 0 && colsizes[mlindex].w < mlwidth) {
        mlwidth -= colsizes[mlindex].w;
        w = (mlwidth < diff) ? mlwidth : diff;
        colsizes[mlindex].w += w;
    }

    x = 0;
    for (i = 0; i < table->cols && x < area->w; ++i) {
        colsizes[i].x = area->x + x;
        x += colsizes[i].w + sep;
        if (x >= area->w)
            colsizes[i].w = area->x + area->w - colsizes[i].x;
    }
    for (; i < table->cols; ++i) {
        colsizes[i].x = area->x + area->w;
        colsizes[i].w = 0;
    }
    return colsizes;
}

static int drawtablerow_impl(tablespec const *table, TW_Rect *cols,
                          int *row, int flags)
{
    TW_Rect                rect;
    unsigned char const   *p;
    int                    c, f, n, i, y;

    if (!cols) {
        for (i = 0; i < table->cols; i += table->items[(*row)++][0] - '0') ;
        return TRUE;
    }

    y = cols[0].y;
    n = *row;
    for (i = 0; i < table->cols; ++n) {
        p = (unsigned char const*)table->items[n];
        c = p[0] - '0';
        rect = cols[i];
        i += c;
        if (c > 1) rect.w = cols[i-1].x + cols[i-1].w - rect.x;
        f = flags | PT_UPDATERECT;
        if      (p[1] == '+') f |= PT_RIGHT;
        else if (p[1] == '.') f |= PT_CENTER;
        if      (p[1] == '!') drawmultilinetext(&rect, p+2, -1, f);
        else                  drawtext(&rect, p+2, -1, f);
        if (rect.y > y) y = rect.y;
    }

    *row = n;
    for (i = 0; i < table->cols; ++i) {
        cols[i].h -= y - cols[i].y;
        cols[i].y  = y;
    }
    return TRUE;
}

void freefont(void)
{
    if (andg.font.h) {
        free(andg.font.memory);
        andg.font.memory = NULL;
        andg.font.h      = 0;
    }
}

int loadfontfromfile(char const *filename, int complain)
{
    TW_Surface *bmp = TW_LoadBMP(filename, FALSE);
    if (!bmp) {
        if (complain) errmsg(filename, "can't load font bitmap: %s", TW_GetError());
        return FALSE;
    }
    fontinfo font;
    if (!makefontfromsurface(&font, bmp)) {
        if (complain) errmsg(filename, "invalid font file");
        TW_FreeSurface(bmp);
        return FALSE;
    }
    TW_FreeSurface(bmp);
    freefont();
    andg.font = font;
    return TRUE;
}

int _androidtextinitialize(void)
{
    andg.font.h          = 0;
    andg.puttextfunc     = puttext_impl;
    andg.measuretablefunc = measuretable_impl;
    andg.drawtablerowfunc = drawtablerow_impl;
    return TRUE;
}
