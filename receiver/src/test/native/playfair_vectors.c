/*
 * AirRelay: records input/output pairs of playfair_decrypt().
 * Copyright (C) 2026 Kevin Merz
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Linked against the playfair sources (GPL-3.0) exactly as they were linked into
 * libairrelay_native.so in 0.1.0. Each line: "<236 bytes in, hex> <16 bytes out, hex>",
 * where the input is message3 (164 bytes) followed by the encrypted key (72 bytes): the
 * exact stdin/stdout contract of the separate libairrelay_playfair.so helper.
 * Frozen on 2026-10-15 in receiver/src/test/resources/native/playfair-vectors-0.1.0.txt.
 *
 * The mode byte message3[12] is always 0 to 3, as senders send it. Measured on 2026-10-15:
 * with any other value playfair reads uninitialised memory and out of its tables, and the
 * result changes from build to build and from call to call; with 0 to 3 the result was the
 * same in four builds (-O0, -O2, zero- and pattern-initialised stack) and on repeated calls
 * in one process. Every vector still runs in a fresh child, like the helper.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "playfair.h"

int main(void) {
    uint32_t st = 0x9E3779B9u;
    for (int n = 0; n < 12; n++) {
        unsigned char in[236], out[16];
        for (int i = 0; i < 236; i++) {
            st ^= st << 13; st ^= st >> 17; st ^= st << 5;
            in[i] = (unsigned char)(st >> 24);
        }
        /* message3 header as a sender writes it: "FPLY", version 3, type 1, seq 3, length 0x98 */
        static const unsigned char hdr[12] = {0x46,0x50,0x4c,0x59,0x03,0x01,0x03,0x00,0x00,0x00,0x00,0x98};
        memcpy(in, hdr, 12);
        if (n == 0) memset(in + 12, 0, 224);   /* all-zero body */
        if (n == 1) memset(in + 13, 0xff, 223);
        in[12] = (unsigned char)(n % 4);        /* FairPlay mode */
        fflush(stdout);
        pid_t pid = fork();
        if (pid == 0) {
            memset(out, 0, 16);
            playfair_decrypt(in, in + 164, out);
            for (int i = 0; i < 236; i++) printf("%02x", in[i]);
            printf(" ");
            for (int i = 0; i < 16; i++) printf("%02x", out[i]);
            printf("\n");
            fflush(stdout);
            _exit(0);
        }
        int status = 0;
        waitpid(pid, &status, 0);
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) { fprintf(stderr, "vector %d crashed\n", n); return 1; }
    }
    return 0;
}
