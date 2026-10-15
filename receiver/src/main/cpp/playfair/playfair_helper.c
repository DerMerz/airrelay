/*
 * AirRelay: playfair helper program (libairrelay_playfair.so, an executable).
 * Copyright (C) 2026 Kevin Merz
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * A separate program around playfair (UxPlay lib/playfair, GPL-3.0). It reads exactly
 * 236 bytes from stdin (message3, then the 72-byte encrypted key), writes the 16-byte key
 * to stdout and exits with 0. It does not link anything else of AirRelay and is started
 * anew for every key. Exit codes without output: 2 short input, 3 write failed, 4 invalid
 * FairPlay mode, 5 setup failed.
 *
 * Mode check: playfair takes the mode from message3[12] and is only defined for 0 to 3
 * (measured on 2026-10-15: other values read uninitialised memory and out of its tables,
 * the result changed from build to build). Senders only use 0 to 3.
 */
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include "playfair.h"

int main(void) {
    unsigned char in[236], key[16];
    /* the key goes out on a private copy of stdout; stdout itself points to /dev/null, so
       nothing the library might print can ever mix into the 16 bytes */
    int out = dup(1);
    int devnull = open("/dev/null", O_WRONLY);
    if (out < 0 || devnull < 0 || dup2(devnull, 1) < 0) return 5;
    close(devnull);
    size_t got = 0;
    while (got < sizeof(in)) {
        ssize_t n = read(0, in + got, sizeof(in) - got);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 2;
        got += (size_t)n;
    }
    if (in[12] > 3) return 4;
    playfair_decrypt(in, in + 164, key);
    size_t put = 0;
    while (put < sizeof(key)) {
        ssize_t n = write(out, key + put, sizeof(key) - put);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 3;
        put += (size_t)n;
    }
    return 0;
}
