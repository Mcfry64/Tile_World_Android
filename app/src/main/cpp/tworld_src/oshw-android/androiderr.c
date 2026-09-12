/* androiderr.c: Error output stubs for Android.
 *
 * The generic err.c handles most error display via errmsg/die. These
 * stubs silence the SDL-specific ding/usermessage from sdlerr.c.
 */

#include <stdio.h>
#include <stdarg.h>
#include <android/log.h>
#define LOG_TAG "tworld"

void ding(void)
{
    /* No audio beep. */
}

void usermessage(int action, char const *prefix,
                 char const *cfile, unsigned long lineno,
                 char const *fmt, va_list args)
{
    char buf[1024];
    int  pos = 0;
    if (prefix) pos += snprintf(buf + pos, sizeof buf - pos, "%s: ", prefix);
    if (fmt)    pos += vsnprintf(buf + pos, sizeof buf - pos, fmt, args);
    if (cfile)  pos += snprintf(buf + pos, sizeof buf - pos, " [%s:%lu]", cfile, lineno);
    __android_log_print(action == 0 /* NOTIFY_DIE */ ? ANDROID_LOG_FATAL : ANDROID_LOG_ERROR,
                        LOG_TAG, "%s", buf);
}
