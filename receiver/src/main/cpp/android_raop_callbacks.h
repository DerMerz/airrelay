#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include "raop.h"
#include "audio_engine.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Forwards the few RAOP events this receiver needs to Kotlin (NativeCallbacks).
 * Everything else (metadata, cover art, DACP, HLS video, PIN) is answered natively
 * with a no-op, because the receiver only mirrors the screen and plays its sound.
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
} android_callback_ctx_t;

/* returns 0 on success, -1 if a Kotlin method is missing (R8 rules, renamed method) */
int android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);

#ifdef __cplusplus
}
#endif

#endif
