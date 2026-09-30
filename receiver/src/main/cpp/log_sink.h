#ifndef LOG_SINK_H
#define LOG_SINK_H

#include <android/log.h>
#include <cstdarg>
#include <cstdio>

#define LOG_SINK_TAG "AirRelayNative"

/*
 * Log sink of the audio engine. Upstream (jqssun) also forwarded every line to an in-app
 * log screen over JNI; this receiver has no log screen, so it only writes to logcat.
 */
struct LogSink {
public:
    void info(const char *fmt, ...)  __attribute__((format(printf, 2, 3))) {
        va_list ap; va_start(ap, fmt); vemit(ANDROID_LOG_INFO, fmt, ap); va_end(ap);
    }
    void warn(const char *fmt, ...)  __attribute__((format(printf, 2, 3))) {
        va_list ap; va_start(ap, fmt); vemit(ANDROID_LOG_WARN, fmt, ap); va_end(ap);
    }
    void error(const char *fmt, ...) __attribute__((format(printf, 2, 3))) {
        va_list ap; va_start(ap, fmt); vemit(ANDROID_LOG_ERROR, fmt, ap); va_end(ap);
    }

private:
    static void vemit(int prio, const char *fmt, va_list ap) {
        char buf[256];
        vsnprintf(buf, sizeof(buf), fmt, ap);
        __android_log_print(prio, LOG_SINK_TAG, "%s", buf);
    }
};

#endif  // LOG_SINK_H
