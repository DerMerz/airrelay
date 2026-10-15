/*
 * AirRelay: Android side of the playfair process call (JNI setter, logging).
 * Copyright (C) 2026 Kevin Merz
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version, with the additional
 * permission in LICENSE-EXCEPTION.md (linking with the Amazon Appstore SDK).
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 */
#include <jni.h>
#include <time.h>
#include <android/log.h>

#include "playfair_pipe.h"

#define TAG "AirRelayNative"

static long long now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

/* Kotlin passes the full path of libairrelay_playfair.so in nativeLibraryDir. */
JNIEXPORT jboolean JNICALL
Java_de_merzkevin_airrelay_bridge_NativeBridge_nativeSetPlayfairHelper(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return JNI_FALSE;
    int r = airrelay_playfair_set_helper(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}

int airrelay_fairplay_decrypt(const unsigned char message3[PLAYFAIR_MESSAGE3_LEN],
                              const unsigned char cipher[PLAYFAIR_CIPHER_LEN],
                              unsigned char key_out[PLAYFAIR_KEY_LEN]) {
    long long t0 = now_us();
    int r = airrelay_playfair_decrypt(message3, cipher, key_out);
    long long ms10 = (now_us() - t0) / 100;
    if (r == PLAYFAIR_OK) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "playfair helper: key in %lld.%lld ms", ms10 / 10, ms10 % 10);
        return 0;
    }
    __android_log_print(ANDROID_LOG_ERROR, TAG, "playfair helper failed: %d after %lld.%lld ms", r, ms10 / 10, ms10 % 10);
    return -1;
}
