/*
 * JNI bridge between de.merzkevin.airrelay.bridge.NativeBridge and UxPlay.
 * Based on native_bridge.cpp from jqssun/android-airplay-server (GPL-3.0), reduced to one
 * fixed configuration: screen mirroring (H.264 only) plus sound, no PIN, no persistent key
 * file. HLS video is off unless nativeSetHlsEnabled switches it on (ReceiverService, HlsPolicy).
 *
 * The package name in the function names is the Kotlin code package (namespace), not the
 * applicationId, so renaming the app in Gradle does not touch this file.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <memory>
#include <android/log.h>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "android_raop_callbacks.h"
#include "android_dnssd_shim.h"
}

#include "audio_engine.h"
#include "log_sink.h"

#define TAG "AirRelayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* AirPlay feature bits (dns-sd "features") this receiver switches away from UxPlay's
   defaults: 0 = Video (HLS), 4 = VideoHTTPLiveStreams, 9 = Audio, 42 = ScreenMultiCodec
   (H.265). */
static const int FEATURE_VIDEO = 0;
static const int FEATURE_HLS = 4;
static const int FEATURE_AUDIO = 9;
static const int FEATURE_MULTICODEC = 42;

typedef struct {
    raop_t *raop;
    dnssd_t *dnssd;
    android_callback_ctx_t cb_ctx;
    raop_callbacks_t callbacks;
    char hw_addr[6];
    /* raop_set_lang keeps the pointer, so the system language list lives here;
       a plain buffer because the struct is cleared with memset */
    char lang_system[128];
} server_ctx_t;

static void _log_callback(void *cls, int level, const char *msg) {
    (void)cls;
    int prio = ANDROID_LOG_DEBUG;
    if (level >= 5) prio = ANDROID_LOG_ERROR;
    else if (level >= 4) prio = ANDROID_LOG_WARN;
    else if (level >= 3) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", msg);
}

static void _destroy(JNIEnv *env, server_ctx_t *ctx) {
    if (ctx->raop) {
        raop_destroy(ctx->raop);
        ctx->raop = NULL;
    }
    /* safe after raop_destroy: no decode call can be in flight any more */
    AudioEngine *engine = ctx->cb_ctx.audio_engine;
    ctx->cb_ctx.audio_engine = NULL;
    if (engine) audio_engine_destroy(engine);
    if (ctx->dnssd) {
        dnssd_destroy(ctx->dnssd);
        ctx->dnssd = NULL;
    }
    android_callbacks_destroy(&ctx->cb_ctx, env);
    delete ctx;
}

/*
 * Creates the server. hwAddr must be 6 bytes; it is the AirPlay device id and seeds the
 * pairing key (UxPlay derives the Ed25519 key from it when no key file is given), so a
 * stable hwAddr gives a stable identity without storing anything on the device.
 * Returns 0 on failure.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeInit(
        JNIEnv *env, jobject thiz, jobject callback, jbyteArray hwAddr, jstring name,
        jint width, jint height, jint fps, jboolean advertiseAlac) {
    (void)thiz;
    if (env->GetArrayLength(hwAddr) != 6) {
        LOGE("hwAddr must be 6 bytes");
        return 0;
    }
    server_ctx_t *ctx = new server_ctx_t();
    memset(ctx, 0, sizeof(*ctx));
    env->GetByteArrayRegion(hwAddr, 0, 6, (jbyte *)ctx->hw_addr);

    if (android_callbacks_init(&ctx->cb_ctx, env, callback) != 0) {
        android_callbacks_destroy(&ctx->cb_ctx, env);
        delete ctx;
        return 0;
    }
    android_callbacks_fill(&ctx->callbacks, &ctx->cb_ctx);

    ntp_global_init();
    ctx->raop = raop_init(&ctx->callbacks);
    if (!ctx->raop) {
        LOGE("raop_init failed");
        _destroy(env, ctx);
        return 0;
    }
    ctx->cb_ctx.raop = ctx->raop;
    raop_set_log_level(ctx->raop, LOGGER_ERR);
    raop_set_log_callback(ctx->raop, _log_callback, NULL);

    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char)ctx->hw_addr[0], (unsigned char)ctx->hw_addr[1],
             (unsigned char)ctx->hw_addr[2], (unsigned char)ctx->hw_addr[3],
             (unsigned char)ctx->hw_addr[4], (unsigned char)ctx->hw_addr[5]);

    /* nohold=1: a new sender replaces the current one instead of being refused.
       keyfile "": key derived from device_id, nothing is written to storage. */
    if (raop_init2(ctx->raop, 1, device_id, "") < 0) {
        LOGE("raop_init2 failed");
        /* raop_destroy would call httpd_stop(NULL) on this half-built raop; leaking it
           once is the lesser evil for a failure that ends the server start anyway */
        ctx->raop = NULL;
        _destroy(env, ctx);
        return 0;
    }

    const char *name_c = env->GetStringUTFChars(name, NULL);
    int dns_err = 0;
    ctx->dnssd = dnssd_init(name_c, (int)strlen(name_c), ctx->hw_addr, 6, 0, &dns_err);
    env->ReleaseStringUTFChars(name, name_c);
    if (!ctx->dnssd) {
        LOGE("dnssd_init failed: %d", dns_err);
        _destroy(env, ctx);
        return 0;
    }
    raop_set_dnssd(ctx->raop, ctx->dnssd);

    /* mirroring plus sound; HLS stays off unless nativeSetHlsEnabled turns it on */
    raop_set_plist(ctx->raop, "hls", 0);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_VIDEO, 0);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_HLS, 0);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_AUDIO, 1);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_MULTICODEC, 0);
    /* AAC-LC and AAC-ELD always; ALAC (software, FFmpeg) only if asked for */
    android_dnssd_set_codecs(ctx->dnssd, advertiseAlac ? 1 : 0, 1);

    raop_set_plist(ctx->raop, "width", width);
    raop_set_plist(ctx->raop, "height", height);
    raop_set_plist(ctx->raop, "refreshRate", fps);
    raop_set_plist(ctx->raop, "maxFPS", fps);
    raop_set_plist(ctx->raop, "overscanned", 0);

    ctx->cb_ctx.audio_engine = audio_engine_create(std::make_shared<LogSink>(), 44100, 2);
    if (!ctx->cb_ctx.audio_engine) {
        LOGE("audio engine could not be created");
        _destroy(env, ctx);
        return 0;
    }
    /* adaptive jitter cushion at the 95th percentile, oboe power-saving output,
       software ALAC; same as the upstream defaults */
    audio_engine_configure(ctx->cb_ctx.audio_engine, 0, 95, 0, true, true, false, false);
    return (jlong)(intptr_t)ctx;
}

/* Returns the bound port, or -1 if the socket could not be bound (e.g. port in use). */
extern "C" JNIEXPORT jint JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeStart(
        JNIEnv *env, jobject thiz, jlong handle, jint requestedPort) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return -1;
    unsigned short port = (unsigned short)(requestedPort >= 0 ? requestedPort : 0);
    int ret = raop_start_httpd(ctx->raop, &port);
    if (ret <= 0) {
        LOGE("raop_start_httpd(%d) failed: %d", requestedPort, ret);
        return -1;
    }
    raop_set_port(ctx->raop, port);
    dnssd_register_raop(ctx->dnssd, port);
    dnssd_register_airplay(ctx->dnssd, port);
    LOGI("server listening on port %d", port);
    return (jint)port;
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop) return;
    raop_stop_httpd(ctx->raop);
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (ctx) _destroy(env, ctx);
}

/* Ends the current session: all known connections are closed by the httpd thread. */
extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeDisconnect(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (ctx && ctx->raop) raop_remove_known_connections(ctx->raop);
}

/* dns-sd TXT records as a flat [key, value, key, value, ...] array */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeTxtRecords(
        JNIEnv *env, jobject thiz, jlong handle, jboolean raopRecords) {
    (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    dnssd_t *d = ctx->dnssd;
    const int count = raopRecords ? android_dnssd_get_raop_txt_count(d)
                                  : android_dnssd_get_airplay_txt_count(d);
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(count * 2, strClass, NULL);
    env->DeleteLocalRef(strClass);
    if (!out) return NULL;
    for (int i = 0; i < count; i++) {
        const char *key = raopRecords ? android_dnssd_get_raop_txt_key(d, i)
                                      : android_dnssd_get_airplay_txt_key(d, i);
        const char *val = raopRecords ? android_dnssd_get_raop_txt_val(d, i)
                                      : android_dnssd_get_airplay_txt_val(d, i);
        jstring jk = env->NewStringUTF(key ? key : "");
        jstring jv = env->NewStringUTF(val ? val : "");
        env->SetObjectArrayElement(out, 2 * i, jk);
        env->SetObjectArrayElement(out, 2 * i + 1, jv);
        env->DeleteLocalRef(jk);
        env->DeleteLocalRef(jv);
    }
    return out;
}

/* "<hex mac>@<name>", the service name of the _raop._tcp record */
extern "C" JNIEXPORT jstring JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeRaopServiceName(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->dnssd) return NULL;
    return env->NewStringUTF(android_dnssd_get_raop_servname(ctx->dnssd));
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeSetDefaultStreamValues(
        JNIEnv *env, jobject thiz, jint sampleRate, jint framesPerBurst) {
    (void)env; (void)thiz;
    audio_engine_set_default_stream_values(sampleRate, framesPerBurst);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeAudioStart(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->cb_ctx.audio_engine) return JNI_FALSE;
    return audio_engine_start(ctx->cb_ctx.audio_engine) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeAudioStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (ctx && ctx->cb_ctx.audio_engine) audio_engine_pause(ctx->cb_ctx.audio_engine);
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeAudioFormat(
        JNIEnv *env, jobject thiz, jlong handle, jint ct, jint spf) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (ctx && ctx->cb_ctx.audio_engine) audio_engine_on_format(ctx->cb_ctx.audio_engine, ct, spf);
}

extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeSetGain(
        JNIEnv *env, jobject thiz, jlong handle, jint gainQ15) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (ctx && ctx->cb_ctx.audio_engine) audio_engine_set_gain_q15(ctx->cb_ctx.audio_engine, gainQ15);
}

/*
 * Announces and serves HLS video (YouTube hands over a stream URL instead of mirroring).
 * Must run after nativeInit and before the dns-sd TXT records are read. systemLangs is a
 * colon-separated list such as "de:en"; UxPlay needs at least one entry, so an empty list
 * falls back to "en". Returns false (HLS stays off) if the Kotlin callbacks are missing.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeSetHlsEnabled(
        JNIEnv *env, jobject thiz, jlong handle, jstring systemLangs) {
    (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->raop || !ctx->dnssd) return JNI_FALSE;
    if (android_callbacks_enable_hls(&ctx->cb_ctx) != 0) {
        LOGE("HLS requested but the Kotlin video callbacks are missing");
        return JNI_FALSE;
    }
    const char *langs = systemLangs ? env->GetStringUTFChars(systemLangs, NULL) : NULL;
    snprintf(ctx->lang_system, sizeof(ctx->lang_system), "%s",
             (langs && langs[0]) ? langs : "en");
    if (langs) env->ReleaseStringUTFChars(systemLangs, langs);
    raop_set_lang(ctx->raop, "", "", ctx->lang_system);
    raop_set_plist(ctx->raop, "hls", 1);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_VIDEO, 1);
    dnssd_set_airplay_features(ctx->dnssd, FEATURE_HLS, 1);
    LOGI("HLS video on, system languages %s", ctx->lang_system);
    return JNI_TRUE;
}

/* Player state for /playback-info; duration -1 tells the sender the video has ended. */
extern "C" JNIEXPORT void JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeUpdatePlaybackInfo(
        JNIEnv *env, jobject thiz, jlong handle, jfloat position, jfloat duration, jfloat rate,
        jboolean ready) {
    (void)env; (void)thiz;
    server_ctx_t *ctx = (server_ctx_t *)(intptr_t)handle;
    if (!ctx) return;
    android_callbacks_update_playback_info(&ctx->cb_ctx, (double)position, (double)duration,
                                           (float)rate, ready ? 1 : 0);
}
