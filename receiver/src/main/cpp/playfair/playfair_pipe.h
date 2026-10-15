/*
 * AirRelay: FairPlay key decryption through the separate playfair helper program.
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
 *
 * playfair (GPL-3.0 only) is not linked into libairrelay_native.so. It is built as its own
 * executable, libairrelay_playfair.so in the app's native library directory, and run once
 * per key: 236 bytes on stdin (message3, then the 72-byte encrypted key), 16 bytes on
 * stdout, exit code 0. Nothing else is exchanged.
 */
#ifndef AIRRELAY_PLAYFAIR_PIPE_H
#define AIRRELAY_PLAYFAIR_PIPE_H

#ifdef __cplusplus
extern "C" {
#endif

#define PLAYFAIR_MESSAGE3_LEN 164
#define PLAYFAIR_CIPHER_LEN 72
#define PLAYFAIR_INPUT_LEN (PLAYFAIR_MESSAGE3_LEN + PLAYFAIR_CIPHER_LEN)
#define PLAYFAIR_KEY_LEN 16
#define PLAYFAIR_DEFAULT_TIMEOUT_MS 2000

enum playfair_result {
    PLAYFAIR_OK = 0,
    PLAYFAIR_ERR_NO_HELPER = -1,  /* no helper path set */
    PLAYFAIR_ERR_SPAWN = -2,      /* socketpair or fork failed */
    PLAYFAIR_ERR_EXEC = -3,       /* helper could not be started (exit code 127) */
    PLAYFAIR_ERR_WRITE = -4,      /* input not accepted */
    PLAYFAIR_ERR_TIMEOUT = -5,    /* no answer in time; the helper was killed */
    PLAYFAIR_ERR_CRASHED = -6,    /* ended by a signal */
    PLAYFAIR_ERR_EXIT = -7,       /* non-zero exit code */
    PLAYFAIR_ERR_OUTPUT = -8      /* not exactly 16 bytes */
};

/* Full path of the helper executable; 0 on success, -1 if it is too long. */
int airrelay_playfair_set_helper(const char *path);
/* Upper bound for one call, start to exit; values <= 0 restore the default. */
void airrelay_playfair_set_timeout_ms(int timeout_ms);
/* Runs the helper once. On anything but PLAYFAIR_OK, key_out is zeroed. Thread-safe. */
int airrelay_playfair_decrypt(const unsigned char message3[PLAYFAIR_MESSAGE3_LEN],
                              const unsigned char cipher[PLAYFAIR_CIPHER_LEN],
                              unsigned char key_out[PLAYFAIR_KEY_LEN]);

/* Called by UxPlay's fairplay_playfair.c (patch 0008). Implemented next to the JNI code,
   where it also logs the duration and any error. 0 on success, -1 on failure. */
int airrelay_fairplay_decrypt(const unsigned char message3[PLAYFAIR_MESSAGE3_LEN],
                              const unsigned char cipher[PLAYFAIR_CIPHER_LEN],
                              unsigned char key_out[PLAYFAIR_KEY_LEN]);

#ifdef __cplusplus
}
#endif
#endif
