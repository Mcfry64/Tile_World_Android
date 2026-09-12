/* androidgen.h: Internal shared definitions for the Android OS/hardware layer.
 *
 * Replaces oshw-sdl/sdlgen.h. No SDL dependency.
 */

#ifndef HEADER_androidgen_h_
#define HEADER_androidgen_h_

#include "oshwbind.h"
#include "../gen.h"
#include "../oshw.h"
#include "../generic/generic.h"

/* Font glyph data. */
typedef struct fontinfo {
    signed char  h;
    signed char  w[256];
    void        *memory;
    unsigned char *bits[256];
} fontinfo;

/* A set of three pre-mapped colors for text rendering. */
typedef struct fontcolors { uint32_t c[3]; } fontcolors;

#define bkgndcolor(fc)  ((fc).c[0])
#define halfcolor(fc)   ((fc).c[1])
#define textcolor(fc)   ((fc).c[2])

/* Flags for puttext. */
#define PT_CENTER     0x0001
#define PT_RIGHT      0x0002
#define PT_MULTILINE  0x0004
#define PT_UPDATERECT 0x0008
#define PT_CALCSIZE   0x0010
#define PT_DIM        0x0020
#define PT_HILIGHT    0x0040

typedef struct oshwglobals {
    fontcolors  textclr;
    fontcolors  dimtextclr;
    fontcolors  hilightclr;
    fontinfo    font;

    void      (*puttextfunc)(TW_Rect *area, char const *text, int len, int flags);
    TW_Rect  *(*measuretablefunc)(TW_Rect const *area, tablespec const *table);
    int       (*drawtablerowfunc)(tablespec const *table, TW_Rect *cols,
                                  int *row, int flags);
} oshwglobals;

extern oshwglobals andg;

#define puttext         (*andg.puttextfunc)
#define measuretable    (*andg.measuretablefunc)
#define drawtablerow    (*andg.drawtablerowfunc)

extern int _androidtextinitialize(void);
extern int measuremltext(unsigned char const *text, int len, int maxwidth);
extern void set_font_scale(int scale);
extern void set_font_scale_frac(int num, int den);
extern int _androidoutputinitialize(int fullscreen);
extern int _androidinputinitialize(void);
/* generic/in.c */
extern int _genericinputinitialize(void);

/* Called by androidout.c whenever the game frame should be pushed to screen. */
extern void android_update_screen(void);
extern void android_set_paused_title(int paused);

/* Soft keyboard control — called from androidout.c. */
extern void android_request_keyboard(int show);
extern int  android_get_keyboard_request(void);

/* Typed char input from soft keyboard — called from JNI. */
extern void android_type_char(int twk);

#endif /* HEADER_androidgen_h_ */
