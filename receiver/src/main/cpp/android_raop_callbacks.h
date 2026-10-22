#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include <pthread.h>
#include "raop.h"
#include "audio_engine.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Forwards the few RAOP events this receiver needs to Kotlin (NativeCallbacks).
 * Metadata, cover art, DACP and PIN are answered natively with a no-op.
 *
 * HLS video (YouTube and other apps that hand over a stream URL) is forwarded only after
 * android_callbacks_enable_hls; until then the HLS callbacks behave exactly as in 0.1.4
 * (no-op, empty playback info). The four Kotlin methods for it are looked up optionally:
 * if R8 removed them, mirroring still starts and HLS simply stays off.
 */
typedef struct {
    JavaVM *jvm;
    jobject callback_obj;
    raop_t *raop;
    jmethodID on_video_data;
    jmethodID on_audio_format;
    jmethodID on_volume;
    jmethodID on_client_volume;
    jmethodID on_audio_teardown;
    jmethodID on_conn_init;
    jmethodID on_conn_destroy;
    jmethodID on_mirror_running;
    jmethodID on_client_request;
    AudioEngine *audio_engine;

    /* HLS video: optional methods, 1 only if all four were found */
    jmethodID on_video_play;
    jmethodID on_video_scrub;
    jmethodID on_video_rate;
    jmethodID on_video_stop;
    int hls_methods_ok;
    int hls_enabled;

    /* playback snapshot, pushed by Kotlin, read by the httpd thread on /playback-info.
       duration -1 is the "video finished" sentinel. */
    int sync_inited;
    pthread_mutex_t playback_lock;
    pthread_cond_t play_ready_cond;
    double playback_position;
    double playback_duration;
    float playback_rate;
    int playback_ready;
    int play_ready;
} android_callback_ctx_t;

/* returns 0 on success, -1 if a Kotlin method is missing (R8 rules, renamed method) */
int android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);

/* returns 0 if HLS callbacks now reach Kotlin, -1 if the Kotlin methods are missing */
int android_callbacks_enable_hls(android_callback_ctx_t *ctx);

/* Kotlin player state; duration -1 marks the end of the video. While the end marker is
   set, ordinary updates are ignored until the next /play or connection. */
void android_callbacks_update_playback_info(android_callback_ctx_t *ctx, double position,
                                            double duration, float rate, int ready);

#ifdef __cplusplus
}
#endif

#endif
