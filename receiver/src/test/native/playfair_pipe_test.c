/*
 * AirRelay: host test driver for the playfair process call (src/main/cpp/playfair/playfair_pipe.c).
 * Copyright (C) 2026 Kevin Merz
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Driven by NativeHostTest. Modes:
 *   vectors <helper> <file>          one call per line of the frozen vector file
 *   call <helper|-> <timeout_ms> <mode>  one call with a fixed input ("-": no helper set)
 * Every mode ends with "children_left <n>": helpers that were not reaped.
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>

#include "playfair_pipe.h"

static long long now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static void hexout(const unsigned char *b, int n) { for (int i = 0; i < n; i++) printf("%02x", b[i]); }

static int unhex(const char *h, unsigned char *b, int n) {
    for (int i = 0; i < n; i++) { unsigned v; if (sscanf(h + 2 * i, "%2x", &v) != 1) return -1; b[i] = (unsigned char)v; }
    return 0;
}

static void children_left(void) {
    int left = 0;
    while (waitpid(-1, NULL, WNOHANG) > 0) left++;
    printf("children_left %d\n", left);
}

int main(int argc, char **argv) {
    if (argc >= 4 && strcmp(argv[1], "vectors") == 0) {
        airrelay_playfair_set_helper(argv[2]);
        FILE *f = fopen(argv[3], "r");
        if (!f) { perror(argv[3]); return 2; }
        char line[1024];
        int n = 0;
        while (fgets(line, sizeof line, f)) {
            unsigned char in[PLAYFAIR_INPUT_LEN], want[PLAYFAIR_KEY_LEN], key[PLAYFAIR_KEY_LEN];
            if (strlen(line) < 2 * PLAYFAIR_INPUT_LEN + 1 + 2 * PLAYFAIR_KEY_LEN) continue;
            if (unhex(line, in, PLAYFAIR_INPUT_LEN) || unhex(line + 2 * PLAYFAIR_INPUT_LEN + 1, want, PLAYFAIR_KEY_LEN)) return 2;
            long long t0 = now_us();
            int r = airrelay_playfair_decrypt(in, in + PLAYFAIR_MESSAGE3_LEN, key);
            long long dt = now_us() - t0;
            printf("vector %d result %d match %d us %lld\n", ++n, r, memcmp(key, want, 16) == 0, dt);
        }
        fclose(f);
        children_left();
        return 0;
    }
    if (argc >= 5 && strcmp(argv[1], "call") == 0) {
        if (strcmp(argv[2], "-") != 0) airrelay_playfair_set_helper(argv[2]);
        airrelay_playfair_set_timeout_ms(atoi(argv[3]));
        unsigned char in[PLAYFAIR_INPUT_LEN], key[PLAYFAIR_KEY_LEN];
        memset(in, 0x5a, sizeof in);
        memset(key, 0xee, sizeof key);
        in[12] = (unsigned char)atoi(argv[4]);
        long long t0 = now_us();
        int r = airrelay_playfair_decrypt(in, in + PLAYFAIR_MESSAGE3_LEN, key);
        long long dt = now_us() - t0;
        printf("result %d\nkey ", r); hexout(key, 16); printf("\nms %lld\n", dt / 1000);
        children_left();
        return 0;
    }
    fprintf(stderr, "usage: playfair_pipe_test vectors <helper> <file> | call <helper|-> <timeout_ms> <mode>\n");
    return 2;
}
