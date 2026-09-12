/* androidout.c: Creating the program's displays (Android, no SDL).
 *
 * Adapted from oshw-sdl/sdlout.c. Replaces SDL types with TW_Rect/TW_Surface
 * and SDL_GetTicks with TW_GetTicks. Screen updating notifies the JNI layer.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <android/log.h>

#include "androidgen.h"
#include "../err.h"
#include "../state.h"
#include "../messages.h"
#include "../defs.h"
#include "../res.h"

extern int isoutoftime(void);

#define LOG_TAG "tworld"

static void updateWindowSurface(void)
{
    android_update_screen();
}

#define MARGINW     8
#define MARGINH     8
#define PROMPTICONW 16
#define PROMPTICONH 10

#define BUTTON_AREA_H 0

#define fillrect(r)  (puttext((r), NULL, 0, PT_MULTILINE))

typedef struct msgdisplayinfo {
    char         msg[64];
    unsigned int msglen;
    unsigned long until;
    unsigned long bolduntil;
    int          priority;
} msgdisplayinfo;

static msgdisplayinfo msgdisplay;
static int            is_paused_title = 0;
static int            hint_active = 0;
static TW_Surface    *prompticons = NULL;
static int            fullscreen_mode = 0;
static int            screenw, screenh;
static TW_Rect        locrects[8];

#define displayloc  (locrects[0])
#define titleloc    (locrects[1])
#define infoloc     (locrects[2])
#define invloc      (locrects[3])
#define hintloc     (locrects[4])
#define rscoreloc   (locrects[5])
#define messageloc  (locrects[6])
#define promptloc   (locrects[7])

static int fullredraw = 1;

static void draw_recessed_box(int x, int y, int w, int h);
static void draw_text_in_black_box(TW_Rect const *box, char const *msg, int flags);

static fontcolors makefontcolors(int rbk, int gbk, int bbk,
                                  int rtx, int gtx, int btx)
{
    fontcolors fc;
    fc.c[0] = TW_MapRGB(geng.screen, rbk, gbk, bbk);
    fc.c[2] = TW_MapRGB(geng.screen, rtx, gtx, btx);
    fc.c[1] = TW_MapRGB(geng.screen, (rbk+rtx)/2, (gbk+gtx)/2, (bbk+btx)/2);
    return fc;
}

static int createprompticons(void)
{
    if (!prompticons) {
        /* Create 8bpp surface from raw index data; palette set below. */
        TW_Surface *s = TW_NewSurface(PROMPTICONW, 3 * PROMPTICONH, 0);
        if (!s) return FALSE;
        prompticons = s;
    }

    static uint8_t iconpx[] = {
        0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,
        0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0,
        0,0,0,0,1,2,2,2,2,1,0,0,0,0,0,0,
        0,0,1,2,2,2,2,2,2,1,0,0,0,0,0,0,
        1,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,
        1,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,
        0,0,1,2,2,2,2,2,2,1,0,0,0,0,0,0,
        0,0,0,0,1,2,2,2,2,1,0,0,0,0,0,0,
        0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,
        0,0,0,0,0,2,2,2,2,2,2,0,0,0,0,0,
        0,0,0,0,2,2,2,2,2,2,2,2,0,0,0,0,
        0,0,0,2,2,2,2,2,2,2,2,2,2,0,0,0,
        0,0,0,2,2,2,2,2,2,2,2,2,2,0,0,0,
        0,0,0,2,2,2,2,2,2,2,2,2,2,0,0,0,
        0,0,0,2,2,2,2,2,2,2,2,2,2,0,0,0,
        0,0,0,2,2,2,2,2,2,2,2,2,2,0,0,0,
        0,0,0,0,2,2,2,2,2,2,2,2,0,0,0,0,
        0,0,0,0,0,2,2,2,2,2,2,0,0,0,0,0,
        0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0,
        0,0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,
        0,0,0,0,0,0,1,2,2,2,2,2,2,1,0,0,
        2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,1,
        2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,1,
        0,0,0,0,0,0,1,2,2,2,2,2,2,1,0,0,
        0,0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,
        0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0,
        0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0
    };
    uint32_t clr[3] = {
        bkgndcolor(andg.dimtextclr),
        halfcolor(andg.dimtextclr),
        textcolor(andg.dimtextclr)
    };
    uint32_t *dst = (uint32_t *)prompticons->pixels;
    int total = PROMPTICONW * 3 * PROMPTICONH;
    for (int i = 0; i < total; i++)
        dst[i] = clr[iconpx[i] < 3 ? iconpx[i] : 0];
    return TRUE;
}

static int get_tile_scale(void)
{
    int tile_w = geng.wtile > 0 ? geng.wtile : 32;
    int scale = tile_w / 32;
    return scale > 0 ? scale : 1;
}

int android_get_tile_scale(void)
{
    return get_tile_scale();
}

#define SVAL(x) (geng.wtile == 48 ? ((x) * 3) / 2 : (x) * scale)

static int layoutscreen(void)
{
    static char const *scoretext  = "888  DRAWN AND QUARTERED"
                                    "   88,888  8,888,888  8,888,888";
    static char const *hinttext   = "Total Score  ";
    static char const *rscoretext = "88888888";
    static char const *chipstext  = "Chips";
    static char const *timertext  = " 88888";

    int fullw, infow, rscorew, texth;

    if (geng.wtile <= 0 || geng.htile <= 0)
        return FALSE;

    int scale = get_tile_scale();
    if (geng.wtile == 48) {
        set_font_scale_frac(3, 2);
    } else {
        set_font_scale(scale);
    }

    int margin_w = SVAL(MARGINW);
    int margin_h = SVAL(MARGINH);
    int top_margin = SVAL(6);

    screenw = NXTILES * geng.wtile;

    puttext(&displayloc, scoretext, -1, PT_CALCSIZE);
    fullw = displayloc.w;
    texth = displayloc.h;
    puttext(&displayloc, hinttext, -1, PT_CALCSIZE);
    infow = displayloc.w;
    puttext(&displayloc, rscoretext, -1, PT_CALCSIZE);
    rscorew = displayloc.w;
    infow += rscorew;

    // Row 0: Level Title (HIDDEN)
    titleloc.x = 0;
    titleloc.y = 0;
    titleloc.w = 0;
    titleloc.h = 0;

    // Row 1: Game Map (at the top, 9x9 tiles at native resolution)
    displayloc.x = 0;
    displayloc.y = 0;
    displayloc.w = NXTILES * geng.wtile;
    displayloc.h = NYTILES * geng.htile;
    geng.maploc  = displayloc;

    // Row 2: Stats (LVL, TIME, CHIPS displays on 1 line) - Below Map
    infoloc.x = margin_w;
    infoloc.y = displayloc.y + displayloc.h + top_margin;
    infoloc.w = screenw - 2 * margin_w;
    infoloc.h = SVAL(28);

    // Row 3: 1x8 Inventory Grid (Centered) - Below Stats
    invloc.w = 8 * geng.wtile;
    invloc.h = geng.htile;
    invloc.x = (screenw - invloc.w) / 2;
    invloc.y = infoloc.y + infoloc.h + margin_h;

    // Row 4: Transient messages (like "Paused") - Below Inventory
    messageloc.x = margin_w;
    messageloc.y = invloc.y + invloc.h + margin_h;
    messageloc.w = screenw - 2 * margin_w;
    messageloc.h = texth;

    // Row 5: Message/Hint text at the bottom - Below Messages
    hintloc.x = margin_w;
    hintloc.y = messageloc.y + messageloc.h + margin_h;
    hintloc.w = screenw - 2 * margin_w;
    hintloc.h = 6 * texth;

    // rscoreloc for score numbers on the right of the hint box
    rscoreloc.x = hintloc.x + hintloc.w / 2;
    rscoreloc.y = hintloc.y + texth;
    rscoreloc.w = hintloc.w / 2;
    rscoreloc.h = texth;

    int prompt_w = SVAL(PROMPTICONW);
    int prompt_h = SVAL(PROMPTICONH);
    promptloc.x = screenw - margin_w - prompt_w;
    promptloc.y = hintloc.y + hintloc.h - prompt_h;
    promptloc.w = prompt_w;
    promptloc.h = prompt_h;

    screenh = hintloc.y + hintloc.h + margin_h + SVAL(12);
    int target_screenh = (int)(screenw * 2.2f);
    if (screenh < target_screenh) {
        screenh = target_screenh;
    }

    return TRUE;
}

static int createdisplay(void)
{
    if (geng.screen) {
        TW_FreeSurface(geng.screen);
    }
    geng.screen = TW_NewSurface(screenw, screenh, 0);
    if (!geng.screen) {
        errmsg(NULL, "cannot create display surface\n");
        return FALSE;
    }
    return TRUE;
}

void cleardisplay(void)
{
    if (!geng.screen) return;

    TW_FillRect(geng.screen, NULL, bkgndcolor(andg.textclr));

    /* A and B buttons removed */

    fullredraw = 1;
    geng.mapvieworigin = -1;
}

static TW_Rect get_item_grid_aligned_box(char const *msg, int extra_line)
{
    int scale = get_tile_scale();
    int line_h = andg.font.h * scale + 2;
    int needed_h = 0;

    if (msg && *msg) {
        needed_h = measuremltext((unsigned char const*)msg, -1, invloc.w - 8);
    }

    int box_h = needed_h + (extra_line * line_h) + 6 * scale;
    if (box_h < invloc.h + 4 * scale) box_h = invloc.h + 4 * scale;

    TW_Rect box;
    box.x = invloc.x;
    box.w = invloc.w;
    box.y = invloc.y - 2 * scale;
    box.h = box_h;
    return box;
}

static void displaymsg(int update)
{
    int f;
    if (msgdisplay.until < TW_GetTicks()) {
        *msgdisplay.msg  = '\0';
        msgdisplay.msglen = 0;
        msgdisplay.priority = 0;
        f = 0;
        return;
    } else {
        if (!msgdisplay.msglen) return;
        f = PT_CENTER;
        if (msgdisplay.bolduntil < TW_GetTicks()) f |= PT_DIM;
    }
    TW_Rect text_box;
    if (msgdisplay.priority == 0) {
        text_box = messageloc;
        text_box.x = invloc.x;
        text_box.w = invloc.w;
        int scale = get_tile_scale();
        text_box.h = andg.font.h * scale + 10;
        if (geng.wtile == 48 || geng.wtile == 96) {
            text_box.h += 6;
        }
    }
    draw_recessed_box(text_box.x, text_box.y, text_box.w, text_box.h);
    draw_text_in_black_box(&text_box, msgdisplay.msg, f);
    if (update) updateWindowSurface();
}

int setdisplaymsg(char const *msg, int msecs, int bold)
{
    if (!msg || !*msg) {
        *msgdisplay.msg  = '\0';
        msgdisplay.msglen = 0;
        msgdisplay.until  = 0;
        msgdisplay.bolduntil = 0;
        msgdisplay.priority = 0;
    } else {
        msgdisplay.msglen = strlen(msg);
        if (msgdisplay.msglen >= sizeof msgdisplay.msg)
            msgdisplay.msglen = sizeof msgdisplay.msg - 1;
        memcpy(msgdisplay.msg, msg, msgdisplay.msglen);
        msgdisplay.msg[msgdisplay.msglen] = '\0';
        msgdisplay.until     = TW_GetTicks() + msecs;
        msgdisplay.bolduntil = TW_GetTicks() + bold;
        msgdisplay.priority  = 1;
    }
    displaymsg(TRUE);
    return TRUE;
}

int setsfxmsg(char const *msg, int msecs, int bold)
{
    unsigned long now = TW_GetTicks();
    if (hint_active || (msgdisplay.until >= now && msgdisplay.priority >= 1)) {
        return FALSE;
    }
    if (!msg || !*msg) {
        *msgdisplay.msg  = '\0';
        msgdisplay.msglen = 0;
        msgdisplay.until  = 0;
        msgdisplay.bolduntil = 0;
        msgdisplay.priority = 0;
    } else {
        msgdisplay.msglen = strlen(msg);
        if (msgdisplay.msglen >= sizeof msgdisplay.msg)
            msgdisplay.msglen = sizeof msgdisplay.msg - 1;
        memcpy(msgdisplay.msg, msg, msgdisplay.msglen);
        msgdisplay.msg[msgdisplay.msglen] = '\0';
        msgdisplay.until     = now + msecs;
        msgdisplay.bolduntil = now + bold;
        msgdisplay.priority  = 0;
    }
    displaymsg(TRUE);
    return TRUE;
}

void android_set_paused_title(int paused)
{
    is_paused_title = paused;
}

int android_is_paused(void)
{
    return is_paused_title;
}

static char const *decimal(long number, int places)
{
    static char buf[32];
    char *dest = buf + sizeof buf;
    unsigned long n;
    n = number >= 0 ? (unsigned long)number : (unsigned long)-(number+1)+1;
    *--dest = '\0';
    do { *--dest = CHAR_MZERO + n % 10; n /= 10; } while (n);
    while (buf + sizeof buf - dest < places + 1) *--dest = CHAR_MZERO;
    if (number < 0) *--dest = '-';
    return dest;
}

static void displayshutter(void)
{
    TW_Rect rect = displayloc;
    TW_FillRect(geng.screen, &rect, halfcolor(andg.dimtextclr));
    rect.x++; rect.y++; rect.w -= 2; rect.h -= 2;
    TW_FillRect(geng.screen, &rect, textcolor(andg.dimtextclr));
    rect.x++; rect.y++; rect.w -= 2; rect.h -= 2;
    TW_FillRect(geng.screen, &rect, halfcolor(andg.dimtextclr));
    rect.x++; rect.y++; rect.w -= 2; rect.h -= 2;
    TW_FillRect(geng.screen, &rect, bkgndcolor(andg.dimtextclr));
}

static void convert_to_monospaced_digits(char *buf) {
    for (; *buf; buf++) {
        if (*buf >= '0' && *buf <= '9') {
            *buf = (char)(*buf - '0' + 144);
        }
    }
}

static void draw_recessed_box(int x, int y, int w, int h)
{
    uint32_t black_shadow  = TW_MapRGB(NULL, 0, 0, 0);          // Pitch black top/left shadow
    uint32_t dark_shadow   = TW_MapRGB(NULL, 28, 28, 28);        // Inner top/left shadow
    uint32_t white_hl      = TW_MapRGB(NULL, 255, 255, 255);    // Bright bottom/right highlight
    uint32_t soft_hl       = TW_MapRGB(NULL, 160, 160, 160);    // Inner bottom/right highlight
    uint32_t black_bg      = TW_MapRGB(NULL, 0, 0, 0);          // Display interior

    // Level 1: Outer shadow (top & left)
    TW_Rect box = { x, y, w, h };
    TW_FillRect(geng.screen, &box, black_shadow);

    // Level 1: Outer highlight (bottom & right)
    TW_Rect b1 = { x, y + h - 1, w, 1 };
    TW_FillRect(geng.screen, &b1, white_hl);
    TW_Rect r1 = { x + w - 1, y, 1, h };
    TW_FillRect(geng.screen, &r1, white_hl);

    // Level 2: Inner shadow (top & left)
    TW_Rect box2 = { x + 1, y + 1, w - 2, h - 2 };
    TW_FillRect(geng.screen, &box2, dark_shadow);

    // Level 2: Inner highlight (bottom & right)
    TW_Rect b2 = { x + 1, y + h - 2, w - 2, 1 };
    TW_FillRect(geng.screen, &b2, soft_hl);
    TW_Rect r2 = { x + w - 2, y + 1, 1, h - 2 };
    TW_FillRect(geng.screen, &r2, soft_hl);

    // Inner black display area
    TW_Rect inner = { x + 2, y + 2, w - 4, h - 4 };
    TW_FillRect(geng.screen, &inner, black_bg);
}

static void draw_3d_panel(int x, int y, int w, int h, uint32_t bg_color)
{
    uint32_t c_black     = TW_MapRGB(NULL, 0, 0, 0);          // Black outer border
    uint32_t c_highlight = TW_MapRGB(NULL, 120, 120, 120);    // Outer 3D highlight
    uint32_t c_soft_hl   = TW_MapRGB(NULL, 80, 80, 80);       // Inner 3D highlight
    uint32_t c_shadow    = TW_MapRGB(NULL, 24, 24, 24);       // Inner 3D shadow

    // Level 1: Outer black box
    TW_Rect box = { x, y, w, h };
    TW_FillRect(geng.screen, &box, c_black);

    // Level 1: Top & Left highlight (3D raised look)
    TW_Rect t1 = { x + 1, y + 1, w - 2, 1 }; TW_FillRect(geng.screen, &t1, c_highlight);
    TW_Rect l1 = { x + 1, y + 1, 1, h - 2 }; TW_FillRect(geng.screen, &l1, c_highlight);

    // Level 2: Top & Left soft highlight
    TW_Rect t2 = { x + 2, y + 2, w - 4, 1 }; TW_FillRect(geng.screen, &t2, c_soft_hl);
    TW_Rect l2 = { x + 2, y + 2, 1, h - 4 }; TW_FillRect(geng.screen, &l2, c_soft_hl);

    // Level 2: Bottom & Right shadow
    TW_Rect b1 = { x + 1, y + h - 2, w - 2, 1 }; TW_FillRect(geng.screen, &b1, c_shadow);
    TW_Rect r1 = { x + w - 2, y + 1, 1, h - 2 }; TW_FillRect(geng.screen, &r1, c_shadow);

    // Level 1: Bottom & Right outer black border
    TW_Rect b2 = { x, y + h - 1, w, 1 }; TW_FillRect(geng.screen, &b2, c_black);
    TW_Rect r2 = { x + w - 1, y, 1, h }; TW_FillRect(geng.screen, &r2, c_black);

    // Gray panel interior
    TW_Rect inner = { x + 3, y + 3, w - 6, h - 6 };
    TW_FillRect(geng.screen, &inner, bg_color);
}

static TW_Surface *bg_tile_surface = NULL;

static void load_background_tile(void)
{
    if (!bg_tile_surface && resdir) {
        char bg_path[1024];
        snprintf(bg_path, sizeof(bg_path), "%s/background.bmp", resdir);
        bg_tile_surface = TW_LoadBMP(bg_path, FALSE);
    }
}

static void draw_tiled_background(TW_Rect const *area)
{
    load_background_tile();

    if (!bg_tile_surface) {
        uint32_t black_bg = TW_MapRGB(NULL, 0, 0, 0);
        TW_Rect rect = *area;
        TW_FillRect(geng.screen, &rect, black_bg);
        return;
    }

    int tile_w = bg_tile_surface->w;
    int tile_h = bg_tile_surface->h;

    if (tile_w <= 0 || tile_h <= 0) return;

    int start_x = area->x;
    int start_y = area->y;
    int end_x   = area->x + area->w;
    int end_y   = area->y + area->h;

    for (int y = start_y; y < end_y; y += tile_h) {
        for (int x = start_x; x < end_x; x += tile_w) {
            TW_Rect dst = { x, y, tile_w, tile_h };
            TW_BlitSurface(bg_tile_surface, NULL, geng.screen, &dst);
        }
    }
}

static TW_Surface *chip_icon_temp = NULL;

static void draw_chip_icon(int x, int y, int size)
{
    if (!chip_icon_temp || chip_icon_temp->w != geng.wtile || chip_icon_temp->h != geng.htile) {
        if (chip_icon_temp) TW_FreeSurface(chip_icon_temp);
        chip_icon_temp = TW_NewSurface(geng.wtile, geng.htile, 0);
    }
    if (chip_icon_temp) {
        TW_FillRect(chip_icon_temp, NULL, TW_MapRGBA(chip_icon_temp, 0, 0, 0, 0));
        drawfulltileid(chip_icon_temp, 0, 0, ICChip);
        TW_Rect dst = { x, y, size, size };
        TW_BlitSurfaceScaled(chip_icon_temp, NULL, geng.screen, &dst);
    }
}

static void draw_text_in_black_box(TW_Rect const *box, char const *msg, int flags)
{
    if (!msg || !*msg) return;

    TW_Rect draw_r = *box;
    draw_r.x = box->x + 4;
    draw_r.w = box->w - 8;
    draw_r.y = box->y + 4;
    draw_r.h = box->h - 6;

    fontcolors text_box_fc;
    text_box_fc.c[0] = TW_MapRGB(NULL, 0, 0, 0);       // Pitch black
    text_box_fc.c[2] = TW_MapRGB(NULL, 255, 255, 255); // White text
    text_box_fc.c[1] = TW_MapRGB(NULL, 48, 48, 48);    // Shadow

    fontcolors old_fc = andg.textclr;
    andg.textclr = text_box_fc;
    puttext(&draw_r, msg, -1, flags | PT_MULTILINE | PT_CENTER);
    andg.textclr = old_fc;
}

static void displayinfo(gamestate const *state, int timeleft, int besttime)
{
    TW_Rect rect, col;
    char    buf[512];
    int     n, texth, col_w;
    fontcolors  orig_fc, red_fc, green_fc, yellow_fc;

    int scale = get_tile_scale();
    texth = andg.font.h;
    col_w = infoloc.w / 3;

    uint32_t black_bg = TW_MapRGB(NULL, 0, 0, 0);
    uint32_t dark_bg  = TW_MapRGB(NULL, 60, 60, 60);

    // Fill entire screen below map with tiled background pattern from background.bmp
    TW_Rect bg_fill = { 0, displayloc.h, screenw, screenh - displayloc.h };
    draw_tiled_background(&bg_fill);

    // 3D Window Frame around Playfield Map (Closing window directly under playfield)
    uint32_t c_black = TW_MapRGB(NULL, 0, 0, 0);       // #000000
    uint32_t c_dark  = TW_MapRGB(NULL, 60, 60, 60);     // #3c3c3c
    uint32_t c_gray  = TW_MapRGB(NULL, 102, 102, 102);  // #666666

    // Bottom border under playfield map
    TW_Rect B1 = { 0, displayloc.h - 1, screenw, 1 };    TW_FillRect(geng.screen, &B1, c_black);
    TW_Rect B2 = { 0, displayloc.h - 2, screenw, 1 };    TW_FillRect(geng.screen, &B2, c_dark);
    TW_Rect B3 = { 0, displayloc.h - 3, screenw, 1 };    TW_FillRect(geng.screen, &B3, c_gray);

    // Draw 3D Gray Panel Box for info controls below map (10px omhoog verplaatst)
    int panel_x = SVAL(3);
    int panel_y = displayloc.h + SVAL(4);
    int panel_w = screenw - 2 * panel_x;
    int panel_h = SVAL(190);

    draw_3d_panel(panel_x, panel_y, panel_w, panel_h, dark_bg);

    orig_fc.c[0] = dark_bg;
    orig_fc.c[2] = TW_MapRGB(NULL, 255, 255, 255); // White text
    orig_fc.c[1] = TW_MapRGB(NULL, 24, 24, 24);    // Shadow

    red_fc = orig_fc;
    red_fc.c[2] = TW_MapRGB(NULL, 255, 0, 0);     // Bright red for labels
    red_fc.c[1] = TW_MapRGB(NULL, 128, 0, 0);

    green_fc.c[0] = TW_MapRGB(NULL, 0, 0, 0);
    green_fc.c[2] = TW_MapRGB(NULL, 0, 255, 0);
    green_fc.c[1] = TW_MapRGB(NULL, 0, 128, 0);

    yellow_fc.c[0] = TW_MapRGB(NULL, 0, 0, 0);
    yellow_fc.c[2] = TW_MapRGB(NULL, 255, 255, 0);
    yellow_fc.c[1] = TW_MapRGB(NULL, 128, 128, 0);

    // Clear the areas with dark background
    rect = infoloc;  TW_FillRect(geng.screen, &rect, dark_bg);
    rect = invloc;   TW_FillRect(geng.screen, &rect, dark_bg);
    rect = titleloc; TW_FillRect(geng.screen, &rect, dark_bg);

    TW_Rect shadow, r_lbl, r_num, draw_rect;
    int pad_x = SVAL(3), pad_y = SVAL(2), box_w, box_h = SVAL(22), box_x, box_y;
    fontcolors digit_fc;

    box_y = infoloc.y + SVAL(6);

    // Column 1: LEVEL ("LVL" label + recessed black display box)
    snprintf(buf, sizeof(buf), "%03d", (int)state->game->number);
    convert_to_monospaced_digits(buf);

    r_num.w = r_num.h = 0; puttext(&r_num, buf, -1, PT_CALCSIZE);
    r_lbl.w = r_lbl.h = 0; puttext(&r_lbl, "LVL", 3, PT_CALCSIZE);

    box_w = r_num.w + 2 * pad_x;

    int col1_total_w = r_lbl.w + SVAL(3) + box_w;
    int col1_start_x = infoloc.x + (col_w - col1_total_w) / 2;

    draw_rect.x = col1_start_x;
    draw_rect.y = box_y + (box_h - r_lbl.h) / 2;
    draw_rect.w = r_lbl.w;
    draw_rect.h = r_lbl.h;
    andg.textclr = red_fc;
    puttext(&draw_rect, "LVL", 3, 0);
    shadow = draw_rect; shadow.x++; puttext(&shadow, "LVL", 3, 0);

    box_x = col1_start_x + r_lbl.w + SVAL(3);
    draw_recessed_box(box_x, box_y, box_w, box_h);

    draw_rect.x = box_x + pad_x - 1;
    draw_rect.y = box_y + pad_y;
    draw_rect.w = r_num.w;
    draw_rect.h = r_num.h;
    digit_fc = green_fc;
    andg.textclr = digit_fc;
    puttext(&draw_rect, buf, -1, 0);
    shadow = draw_rect; shadow.x++; puttext(&shadow, buf, -1, 0);

    // Column 2: TIME ("TIME" label + recessed black display box)
    if (timeleft == TIME_NIL) {
        snprintf(buf, sizeof(buf), "---");
    } else {
        snprintf(buf, sizeof(buf), "%03d", timeleft);
        convert_to_monospaced_digits(buf);
    }

    r_num.w = r_num.h = 0; puttext(&r_num, buf, -1, PT_CALCSIZE);
    r_lbl.w = r_lbl.h = 0; puttext(&r_lbl, "TIME", 4, PT_CALCSIZE);

    box_w = r_num.w + 2 * pad_x;

    int col2_total_w = r_lbl.w + SVAL(3) + box_w;
    int col2_start_x = infoloc.x + col_w + (col_w - col2_total_w) / 2;

    draw_rect.x = col2_start_x;
    draw_rect.y = box_y + (box_h - r_lbl.h) / 2;
    draw_rect.w = r_lbl.w;
    draw_rect.h = r_lbl.h;
    andg.textclr = red_fc;
    puttext(&draw_rect, "TIME", 4, 0);
    shadow = draw_rect; shadow.x++; puttext(&shadow, "TIME", 4, 0);

    box_x = col2_start_x + r_lbl.w + SVAL(3);
    draw_recessed_box(box_x, box_y, box_w, box_h);

    draw_rect.x = box_x + pad_x - 1;
    draw_rect.y = box_y + pad_y;
    draw_rect.w = r_num.w;
    draw_rect.h = r_num.h;
    digit_fc = (timeleft != TIME_NIL && timeleft < 15) ? yellow_fc : green_fc;
    andg.textclr = digit_fc;
    puttext(&draw_rect, buf, -1, 0);
    shadow = draw_rect; shadow.x++; puttext(&shadow, buf, -1, 0);

    // Column 3: CHIPS (Recessed black display box + Chip Tile next to it)
    snprintf(buf, sizeof(buf), "%03d", (int)state->chipsneeded);
    convert_to_monospaced_digits(buf);

    r_num.w = r_num.h = 0; puttext(&r_num, buf, -1, PT_CALCSIZE);

    box_w = r_num.w + 2 * pad_x;

    int chip_icon_size = SVAL(24);
    int chip_tile_x = invloc.x + 7 * geng.wtile + (geng.wtile - chip_icon_size) / 2;
    int chip_tile_y = box_y + (box_h - chip_icon_size) / 2;

    box_x = chip_tile_x - SVAL(3) - box_w;

    draw_recessed_box(box_x, box_y, box_w, box_h);

    draw_rect.x = box_x + pad_x - 1;
    draw_rect.y = box_y + pad_y;
    draw_rect.w = r_num.w;
    draw_rect.h = r_num.h;
    digit_fc = (state->chipsneeded == 0) ? yellow_fc : green_fc;
    andg.textclr = digit_fc;
    puttext(&draw_rect, buf, -1, 0);
    shadow = draw_rect; shadow.x++; puttext(&shadow, buf, -1, 0);

    draw_chip_icon(chip_tile_x, chip_tile_y, chip_icon_size);

    andg.textclr = orig_fc;

    int has_msg = (msgdisplay.msglen > 0 && msgdisplay.until > TW_GetTicks() && msgdisplay.priority >= 1);
    int has_hint = ((state->statusflags & SF_SHOWHINT) && *state->hinttext);
    hint_active = has_hint ? 1 : 0;
    int has_invalid = (state->statusflags & SF_INVALID) || (state->currenttime < 0 && state->game->unsolvable);
    int has_text = has_msg || has_hint || has_invalid;

    if (!has_text) {
        // Inventory: 1x8 row when NO text is active
        for (n = 0; n < 4; n++) {
            int id_key  = state->keys[n]  ? Key_Red  + n : Empty;
            int id_boot = state->boots[n] ? Boots_Ice + n : Empty;

            drawfulltileid(geng.screen, invloc.x + n * geng.wtile, invloc.y, id_key);
            drawfulltileid(geng.screen, invloc.x + (n + 4) * geng.wtile, invloc.y, id_boot);
        }
    } else {
        // Draw 3D recessed black text box replacing item grid (width matches invloc)
        char const *active_text = NULL;
        if (has_msg) {
            active_text = msgdisplay.msg;
        } else if (has_hint) {
            active_text = state->hinttext;
        } else if (has_invalid) {
            if (state->statusflags & SF_INVALID) {
                active_text = "This level cannot be played.";
            } else if (state->game->unsolvable && *state->game->unsolvable) {
                sprintf(buf, "Unsolvable level: %s", state->game->unsolvable);
                active_text = buf;
            } else {
                active_text = "Unsolvable level.";
            }
        }

        TW_Rect text_box = get_item_grid_aligned_box(active_text, 0);
        draw_recessed_box(text_box.x, text_box.y, text_box.w, text_box.h);
        if (active_text) {
            draw_text_in_black_box(&text_box, active_text, 0);
        }
    }

    // fillrect(&promptloc);
    andg.textclr = orig_fc;
}

static int displayprompticon(int completed)
{
    (void)completed;
    return TRUE;
}

void setcolors(long bkgnd, long text, long bold, long dim)
{
    int bkgndr, bkgndg, bkgndb;
    if (bkgnd <= 0) bkgnd = 0x484848; // Default to Gray (#484848 = 72, 72, 72)
    if (text  < 0) text  = 0xFFFFFF;
    if (bold  < 0) bold  = 0xFFFF00;
    if (dim   < 0) dim   = 0xC0C0C0;
    if (bkgnd == text || bkgnd == bold || bkgnd == dim) {
        errmsg(NULL, "one or more text colors matches the background color; "
                     "color scheme left unchanged.");
        return;
    }
    bkgndr = (bkgnd >> 16) & 255;
    bkgndg = (bkgnd >>  8) & 255;
    bkgndb =  bkgnd        & 255;
    andg.textclr    = makefontcolors(bkgndr, bkgndg, bkgndb,
                        (text>>16)&255, (text>>8)&255, text&255);
    andg.dimtextclr = makefontcolors(bkgndr, bkgndg, bkgndb,
                        (dim>>16)&255,  (dim>>8)&255,  dim&255);
    andg.hilightclr = makefontcolors(bkgndr, bkgndg, bkgndb,
                        (bold>>16)&255, (bold>>8)&255, bold&255);
    createprompticons();
}

static TW_Surface *map_temp_surface = NULL;

static void draw_map_view_scaled(gamestate const *state, TW_Rect loc)
{
    int actual_map_w = NXTILES * geng.wtile;
    int actual_map_h = NYTILES * geng.htile;

    if (actual_map_w == loc.w && actual_map_h == loc.h) {
        displaymapview(state, loc);
        return;
    }

    if (!map_temp_surface || map_temp_surface->w != actual_map_w || map_temp_surface->h != actual_map_h) {
        if (map_temp_surface) TW_FreeSurface(map_temp_surface);
        map_temp_surface = TW_NewSurface(actual_map_w, actual_map_h, 0);
    }

    if (!map_temp_surface) {
        displaymapview(state, loc);
        return;
    }

    TW_Rect temp_loc = { 0, 0, actual_map_w, actual_map_h };
    TW_Surface *saved_screen = geng.screen;
    geng.screen = map_temp_surface;
    TW_FillRect(map_temp_surface, NULL, TW_MapRGB(map_temp_surface, 0, 0, 0));
    displaymapview(state, temp_loc);
    geng.screen = saved_screen;

    TW_BlitSurfaceScaled(map_temp_surface, NULL, geng.screen, &loc);
}

static void draw_playfield_window_border(void)
{
    // Border removed
}

int displaygame(gamestate const *state, int timeleft, int besttime,
                int showinitstate)
{
    draw_map_view_scaled(state, displayloc);
    draw_playfield_window_border();
    displayinfo(state, timeleft, besttime);
    displaymsg(FALSE);
    updateWindowSurface();
    fullredraw = 0;
    return TRUE;
}

int displayendmessage(int basescore, int timescore, long totalscore,
                      int completed)
{
    if (completed == -2) {
        return CmdQuitLevel;
    }

    char end_msg_buf[1024];

    if (completed > 0) {
        int fullscore = timescore + basescore;
        char const *win_msg = getmessage(MessageWin);

        if (win_msg && *win_msg) {
            snprintf(end_msg_buf, sizeof(end_msg_buf),
                     "LEVEL COMPLETED!\nTime Bonus: %d\nLevel Bonus: %d\nLevel Score: %d\nTotal Score: %ld\n%s\n\nTap anywhere to continue...",
                     timescore, basescore, fullscore, totalscore, win_msg);
        } else {
            snprintf(end_msg_buf, sizeof(end_msg_buf),
                     "LEVEL COMPLETED!\nTime Bonus: %d\nLevel Bonus: %d\nLevel Score: %d\nTotal Score: %ld\n\nTap anywhere to continue...",
                     timescore, basescore, fullscore, totalscore);
        }
    } else {
        int timedOut = isoutoftime();
        char const *title = timedOut ? "TIME OUT" : "LEVEL FAILED";
        char const *msg = getmessage(timedOut ? MessageTime : MessageDie);
        if (!msg) msg = getmessage(MessageDie);

        if (msg && *msg) {
            snprintf(end_msg_buf, sizeof(end_msg_buf), "%s\n%s\n\nTap anywhere to continue...", title, msg);
        } else {
            snprintf(end_msg_buf, sizeof(end_msg_buf), "%s\n\nTap anywhere to continue...", title);
        }
    }

    int extra_lines = (completed > 0) ? ((geng.wtile == 48 || geng.wtile == 96) ? 4 : 3) : ((geng.wtile == 48 || geng.wtile == 96) ? 3 : 2);
    TW_Rect text_box = get_item_grid_aligned_box(end_msg_buf, extra_lines);
    draw_recessed_box(text_box.x, text_box.y, text_box.w, text_box.h);
    draw_text_in_black_box(&text_box, end_msg_buf, 0);

    updateWindowSurface();
    displayprompticon(completed);
    return CmdNone;
}

int displaytable(char const *title, tablespec const *table, int completed)
{
    TW_Rect  area;
    TW_Rect *cols;
    int      i, n;

    cleardisplay();
    area.x = MARGINW;
    area.y = screenh - MARGINH - andg.font.h;
    area.w = screenw - 2 * MARGINW;
    area.h = andg.font.h;
    puttext(&area, title, -1, 0);
    area.h = area.y - (MARGINH + BUTTON_AREA_H);
    area.y = MARGINH + BUTTON_AREA_H;

    cols = measuretable(&area, table);
    for (i = table->rows, n = 0; i; --i)
        drawtablerow(table, cols, &n, 0);
    free(cols);

    displayprompticon(completed);
    updateWindowSurface();
    return TRUE;
}

int displaytiletable(char const *title,
                     tiletablerow const *rows, int count, int completed)
{
    TW_Rect left, right;
    int     col, id, i;

    cleardisplay();
    left.x = MARGINW;
    left.y = screenh - MARGINH - andg.font.h;
    left.w = screenw - 2 * MARGINW;
    left.h = andg.font.h;
    puttext(&left, title, -1, 0);
    left.h = left.y - (MARGINH + BUTTON_AREA_H);
    left.y = MARGINH + BUTTON_AREA_H;
    right  = left;
    col    = geng.wtile * 2 + MARGINW;
    right.x += col;
    right.w -= col;

    for (i = 0; i < count; i++) {
        if (rows[i].isfloor) id = rows[i].item1;
        else                 id = crtile(rows[i].item1, EAST);
        drawfulltileid(geng.screen, left.x + geng.wtile, left.y, id);
        if (rows[i].item2) {
            if (rows[i].isfloor) id = rows[i].item2;
            else                 id = crtile(rows[i].item2, EAST);
            drawfulltileid(geng.screen, left.x, left.y, id);
        }
        left.y += geng.htile;
        left.h -= geng.htile;
        puttext(&right, rows[i].desc, -1, PT_MULTILINE | PT_UPDATERECT);
        if (left.y < right.y) { left.y = right.y; left.h = right.h; }
        else                  { right.y = left.y;  right.h = left.h; }
    }
    displayprompticon(completed);
    updateWindowSurface();
    return TRUE;
}

int displaylist(char const *title, tablespec const *table, int *idx,
                DisplayListType listtype, int (*inputcallback)(int*))
{
    TW_Rect  area;
    TW_Rect *cols;
    TW_Rect *colstmp;
    int      linecount, itemcount, topitem, index;
    int      j, n;

    cleardisplay();
    area.x = MARGINW;
    area.y = screenh - MARGINH - andg.font.h;
    area.w = screenw - 2 * MARGINW;
    area.h = andg.font.h;
    puttext(&area, title, -1, 0);
    area.h = area.y - (MARGINH + BUTTON_AREA_H);
    area.y = MARGINH + BUTTON_AREA_H;
    cols = measuretable(&area, table);
    if (!(colstmp = malloc(table->cols * sizeof *colstmp)))
        memerrexit();

    itemcount = table->rows - 1;
    topitem   = 0;
    linecount = area.h / andg.font.h - 1;
    index     = *idx;
    n         = SCROLL_NOP;

    do {
        switch (n) {
          case SCROLL_NOP:                                          break;
          case SCROLL_UP:           --index;                        break;
          case SCROLL_DN:           ++index;                        break;
          case SCROLL_HALFPAGE_UP:  index -= (linecount + 1) / 2;  break;
          case SCROLL_HALFPAGE_DN:  index += (linecount + 1) / 2;  break;
          case SCROLL_PAGE_UP:      index -= linecount;             break;
          case SCROLL_PAGE_DN:      index += linecount;             break;
          case SCROLL_ALLTHEWAY_UP: index = 0;                      break;
          case SCROLL_ALLTHEWAY_DN: index = itemcount - 1;          break;
          default:                  index = n;                      break;
        }
        if (index < 0)            index = 0;
        else if (index >= itemcount) index = itemcount - 1;
        if (linecount < itemcount) {
            n = linecount / 2;
            if      (index < n)                topitem = 0;
            else if (index >= itemcount - n)   topitem = itemcount - linecount;
            else                               topitem = index - n;
        }

        n = 0;
        TW_FillRect(geng.screen, &area, bkgndcolor(andg.textclr));
        memcpy(colstmp, cols, table->cols * sizeof *colstmp);
        drawtablerow(table, colstmp, &n, 0);
        for (j = 0; j < topitem; j++)
            drawtablerow(table, NULL, &n, 0);
        for ( ; j < topitem + linecount && j < itemcount; j++)
            drawtablerow(table, colstmp, &n, j == index ? PT_HILIGHT : 0);
        updateWindowSurface();

        n = SCROLL_NOP;
        if (!inputcallback) {
            /* No callback — display once, wait for any keypress, then close. */
            input(TRUE);
            break;
        }
    } while ((*inputcallback)(&n));
    if (n) *idx = index;

    free(cols);
    free(colstmp);
    cleardisplay();
    return n;
}

int displayinputprompt(char const *prompt, char *input, int maxlen,
                       InputPromptType inputtype, int (*inputcallback)(void))
{
    TW_Rect area, promptrect, inputrect;
    int     len, ch;

    puttext(&inputrect, "W", 1, PT_CALCSIZE);
    inputrect.w *= maxlen + 1;
    puttext(&promptrect, prompt, -1, PT_CALCSIZE);
    area.h = inputrect.h + promptrect.h + 2 * MARGINH;
    area.w = (inputrect.w > promptrect.w ? inputrect.w : promptrect.w) + 2 * MARGINW;
    area.x = (screenw - area.w) / 2;
    area.y = (screenh - area.h) / 2;
    promptrect.x = area.x + MARGINW;
    promptrect.y = area.y + MARGINH;
    promptrect.w = area.w - 2 * MARGINW;
    inputrect.x  = promptrect.x;
    inputrect.y  = promptrect.y + promptrect.h;
    inputrect.w  = promptrect.w;

    len = strlen(input);
    if (len > maxlen) len = maxlen;
    android_request_keyboard(1);
    for (;;) {
        TW_FillRect(geng.screen, &area, textcolor(andg.textclr));
        TW_Rect inner = { area.x+1, area.y+1, area.w-2, area.h-2 };
        TW_FillRect(geng.screen, &inner, bkgndcolor(andg.textclr));
        puttext(&promptrect, prompt, -1, PT_CENTER);
        input[len] = '_';
        puttext(&inputrect, input, len + 1, PT_CENTER);
        input[len] = '\0';
        updateWindowSurface();
        ch = (*inputcallback)();
        if (ch == '\n' || ch < 0) break;
        if (isprint(ch)) {
            input[len] = ch;
            if (len < maxlen) ++len;
            input[len] = '\0';
        } else if (ch == '\b') {
            if (len) --len;
            input[len] = '\0';
        } else if (ch == '\f') {
            len = 0; input[0] = '\0';
        }
    }
    android_request_keyboard(0);
    cleardisplay();
    return ch == '\n';
}

int creategamedisplay(void)
{
    if (!layoutscreen() || !createdisplay())
        return FALSE;
    cleardisplay();
    return TRUE;
}

int _androidoutputinitialize(int _fullscreen)
{
    fullscreen_mode = _fullscreen;
    screenw = 640;
    screenh = 480;
    promptloc.x = screenw - MARGINW - PROMPTICONW;
    promptloc.y = screenh - MARGINH - PROMPTICONH;
    promptloc.w = PROMPTICONW;
    promptloc.h = PROMPTICONH;
    geng.screen = NULL;
    createdisplay();

    andg.textclr    = makefontcolors(32, 32, 32, 255, 255, 255);
    andg.dimtextclr = makefontcolors(32, 32, 32, 192, 192, 192);
    andg.hilightclr = makefontcolors(32, 32, 32, 255, 255,   0);

    cleardisplay();
    return createprompticons();
}
