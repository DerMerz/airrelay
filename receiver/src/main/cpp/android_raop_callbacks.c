/*
 * Implements raop_callbacks_t for a mirroring-only receiver.
 * Based on android_raop_callbacks.c from jqssun/android-airplay-server (GPL-3.0),
 * reduced to screen mirroring plus its sound; metadata, cover art, DACP, PIN pairing and
 * HLS video are answered natively without calling into Java.
 *
 * All callbacks fire from RAOP's internal pthreads. A thread is attached to the JVM on its
 * first callback and detached by a pthread key destructor when it exits, so no thread ever
 * leaves the VM attached.
 */

#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include "android_raop_callbacks.h"
#include "audio_engine.h"

#define TAG "AirRelayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;
static pthread_key_t g_detach_key;
static pthread_once_t g_detach_once = PTHREAD_ONCE_INIT;

static void _detach_thread(void *unused) {
    (void)unused;
    if (g_jvm) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void _make_detach_key(void) {
    pthread_key_create(&g_detach_key, _detach_thread);
}

static JNIEnv *_get_env(android_callback_ctx_t *ctx) {
    JNIEnv *env = NULL;
    int status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) return NULL;
        pthread_once(&g_detach_once, _make_detach_key);
        /* any non-NULL value makes the destructor run at thread exit */
        pthread_setspecific(g_detach_key, (void *)1);
    }
    /* a pending exception from an earlier callback would make the next JNI call abort */
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    return env;
}

static void _clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

int android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj) {
    memset(ctx, 0, sizeof(*ctx));
    (*env)->GetJavaVM(env, &ctx->jvm);
    g_jvm = ctx->jvm;
    ctx->callback_obj = (*env)->NewGlobalRef(env, callback_obj);

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    ctx->on_video_data = (*env)->GetMethodID(env, cls, "onVideoData", "([BJ)V");
    ctx->on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZ)V");
    ctx->on_volume = (*env)->GetMethodID(env, cls, "onVolume", "(F)V");
    ctx->on_client_volume = (*env)->GetMethodID(env, cls, "onClientVolume", "()F");
    ctx->on_audio_teardown = (*env)->GetMethodID(env, cls, "onAudioTeardown", "()V");
    ctx->on_conn_init = (*env)->GetMethodID(env, cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy", "()V");
    ctx->on_mirror_running = (*env)->GetMethodID(env, cls, "onMirrorRunning", "(Z)V");
    ctx->on_client_request = (*env)->GetMethodID(env, cls, "onClientRequest", "()Z");
    (*env)->DeleteLocalRef(env, cls);

    if (!ctx->on_video_data || !ctx->on_audio_format || !ctx->on_volume ||
        !ctx->on_client_volume || !ctx->on_audio_teardown || !ctx->on_conn_init ||
        !ctx->on_conn_destroy || !ctx->on_mirror_running || !ctx->on_client_request) {
        /* GetMethodID leaves a NoSuchMethodError pending */
        _clear_exception(env);
        LOGE("callback method missing, check the R8 keep rules for NativeCallbacks");
        return -1;
    }
    return 0;
}

void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (ctx->callback_obj) {
        (*env)->DeleteGlobalRef(env, ctx->callback_obj);
        ctx->callback_obj = NULL;
    }
}

/* --- forwarded callbacks --- */

static void _audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void)ntp;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    if (!ctx->audio_engine || !data->data || data->data_len <= 0) return;
    audio_engine_decode(ctx->audio_engine, data->data, data->data_len,
                        (int)data->ct, (int64_t)data->ntp_time_local);
}

static void _video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void)ntp;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    if (!data->data || data->data_len <= 0) return;
    /* raop_rtp_mirror marks undecryptable packets by setting byte 0 (start code) to 1 */
    if (data->data[0]) {
        LOGE("video packet decryption failed, dropping %d bytes", data->data_len);
        return;
    }
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    if (!arr) { _clear_exception(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
                           arr, (jlong)data->ntp_time_local);
    _clear_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

static void _call_void(android_callback_ctx_t *ctx, jmethodID m) {
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, m);
    _clear_exception(env);
}

static void _conn_init(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _call_void(ctx, ctx->on_conn_init);
}

static void _conn_destroy(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _call_void(ctx, ctx->on_conn_destroy);
}

static void _conn_reset(void *cls, int reason) {
    (void)cls;
    LOGI("connection reset: %d", reason);
}

static void _audio_set_volume(void *cls, float volume) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_volume, (jfloat)volume);
    _clear_exception(env);
}

static double _audio_set_client_volume(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return 0.0;
    jfloat db = (*env)->CallFloatMethod(env, ctx->callback_obj, ctx->on_client_volume);
    if ((*env)->ExceptionCheck(env)) {
        _clear_exception(env);
        return 0.0;
    }
    return (double)db;
}

static void _audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                              bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void)isMedia; (void)audioFormat;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_format,
                           (jint)*ct, (jint)*spf, (jboolean)*usingScreen);
    _clear_exception(env);
}

static void _audio_stop_coverart_rendering(void *cls) {
    /* UxPlay fires this on audio TEARDOWN */
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    _call_void(ctx, ctx->on_audio_teardown);
}

static void _mirror_video_running(void *cls, bool running) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("mirror running: %d", running);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_mirror_running, (jboolean)running);
    _clear_exception(env);
}

/*
 * First SETUP of a session, after pairing and FairPlay, before any stream is set up.
 * Kotlin decides whether this sender may start (subscription). A refused sender gets
 * "403 Forbidden" (patch 0007) and no stream is ever created, so the iPhone shows its
 * own "could not connect" message instead of a half picture. Fails closed: without an
 * answer from Kotlin the sender is refused.
 */
static void _report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    /* sender strings stay native: an iPhone name with emoji is not valid modified UTF-8 */
    (void)deviceid; (void)name;
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    *admit = false;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jboolean ok = (*env)->CallBooleanMethod(env, ctx->callback_obj, ctx->on_client_request);
    if ((*env)->ExceptionCheck(env)) {
        _clear_exception(env);
        ok = JNI_FALSE;
    }
    *admit = ok == JNI_TRUE;
    LOGI("sender %s (%s)", *admit ? "admitted" : "refused, no subscription", model ? model : "?");
}

/* only H.264 is announced (feature bit 42 off); refuse H.265 if a sender tries anyway */
static int _video_set_codec(void *cls, video_codec_t codec) {
    (void)cls;
    LOGI("video_set_codec: %d", codec);
    return codec == VIDEO_CODEC_H265 ? -1 : 0;
}

/* --- answered natively --- */

static void _video_pause(void *cls) { (void)cls; }
static void _video_resume(void *cls) { (void)cls; }
static void _conn_feedback(void *cls) { (void)cls; }
static void _audio_flush(void *cls) { (void)cls; }
static void _video_flush(void *cls) { (void)cls; }
static void _audio_set_metadata(void *cls, const void *buf, int len) { (void)cls; (void)buf; (void)len; }
static void _audio_set_coverart(void *cls, const void *buf, int len) { (void)cls; (void)buf; (void)len; }
static void _audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) {
    (void)cls; (void)dacp_id; (void)active_remote;
}
static void _audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void)cls; (void)start; (void)curr; (void)end;
}
static void _video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {
    (void)cls;
    LOGI("video size %.0fx%.0f -> %.0fx%.0f", *w_src, *h_src, *w, *h);
}
static void _display_pin(void *cls, char *pin) { (void)cls; (void)pin; }
static void _video_reset(void *cls, reset_type_t t) { (void)cls; LOGI("video_reset %d", t); }

/* HLS video is never announced ("hls" plist 0, feature bits 0 and 4 off); these only
   exist so a misbehaving sender cannot reach a NULL function pointer */
static void _video_play(void *cls, const char *location, const float start_position) {
    (void)cls; (void)location; (void)start_position;
}
static void _video_scrub(void *cls, const float position) { (void)cls; (void)position; }
static void _video_rate(void *cls, const float rate) { (void)cls; (void)rate; }
static void _video_stop(void *cls) { (void)cls; }
static void _video_acquire_playback_info(void *cls, playback_info_t *info) {
    (void)cls;
    memset(info, 0, sizeof(*info));
    info->playback_buffer_empty = true;
}
static float _video_playlist_remove(void *cls) { (void)cls; return 0.0f; }

void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx) {
    memset(cbs, 0, sizeof(raop_callbacks_t));
    cbs->cls = ctx;

    cbs->audio_process = _audio_process;
    cbs->video_process = _video_process;
    cbs->video_pause = _video_pause;
    cbs->video_resume = _video_resume;
    cbs->conn_feedback = _conn_feedback;
    cbs->conn_reset = _conn_reset;
    cbs->video_reset = _video_reset;
    cbs->conn_init = _conn_init;
    cbs->conn_destroy = _conn_destroy;
    cbs->audio_flush = _audio_flush;
    cbs->video_flush = _video_flush;
    cbs->audio_set_client_volume = _audio_set_client_volume;
    cbs->audio_set_volume = _audio_set_volume;
    cbs->audio_set_metadata = _audio_set_metadata;
    cbs->audio_set_coverart = _audio_set_coverart;
    cbs->audio_stop_coverart_rendering = _audio_stop_coverart_rendering;
    cbs->audio_remote_control_id = _audio_remote_control_id;
    cbs->audio_set_progress = _audio_set_progress;
    cbs->audio_get_format = _audio_get_format;
    cbs->video_report_size = _video_report_size;
    cbs->mirror_video_running = _mirror_video_running;
    cbs->display_pin = _display_pin;
    cbs->video_set_codec = _video_set_codec;
    cbs->report_client_request = _report_client_request;
    cbs->on_video_play = _video_play;
    cbs->on_video_scrub = _video_scrub;
    cbs->on_video_rate = _video_rate;
    cbs->on_video_stop = _video_stop;
    cbs->on_video_acquire_playback_info = _video_acquire_playback_info;
    cbs->on_video_playlist_remove = _video_playlist_remove;
}
