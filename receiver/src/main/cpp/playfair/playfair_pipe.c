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
 */
#include "playfair_pipe.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifdef MSG_NOSIGNAL
#define SEND_FLAGS MSG_NOSIGNAL
#else
#define SEND_FLAGS 0
#endif

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_helper[1024];
static int g_timeout_ms = PLAYFAIR_DEFAULT_TIMEOUT_MS;

int airrelay_playfair_set_helper(const char *path) {
    if (!path || strlen(path) >= sizeof(g_helper)) return -1;
    pthread_mutex_lock(&g_lock);
    strcpy(g_helper, path);
    pthread_mutex_unlock(&g_lock);
    return 0;
}

void airrelay_playfair_set_timeout_ms(int timeout_ms) {
    pthread_mutex_lock(&g_lock);
    g_timeout_ms = timeout_ms > 0 ? timeout_ms : PLAYFAIR_DEFAULT_TIMEOUT_MS;
    pthread_mutex_unlock(&g_lock);
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int remaining(long long deadline) {
    long long r = deadline - now_ms();
    return r > 0 ? (int)r : 0;
}

/* Kills the helper if it is still there and always reaps it, so no zombie stays behind. */
static void reap(pid_t pid, int *status, int kill_first) {
    if (kill_first) kill(pid, SIGKILL);
    while (waitpid(pid, status, 0) < 0 && errno == EINTR) {}
}

int airrelay_playfair_decrypt(const unsigned char message3[PLAYFAIR_MESSAGE3_LEN],
                              const unsigned char cipher[PLAYFAIR_CIPHER_LEN],
                              unsigned char key_out[PLAYFAIR_KEY_LEN]) {
    char helper[sizeof(g_helper)];
    int timeout_ms;
    pthread_mutex_lock(&g_lock);
    memcpy(helper, g_helper, sizeof(helper));
    timeout_ms = g_timeout_ms;
    pthread_mutex_unlock(&g_lock);
    memset(key_out, 0, PLAYFAIR_KEY_LEN);
    if (!helper[0]) return PLAYFAIR_ERR_NO_HELPER;

    unsigned char input[PLAYFAIR_INPUT_LEN];
    memcpy(input, message3, PLAYFAIR_MESSAGE3_LEN);
    memcpy(input + PLAYFAIR_MESSAGE3_LEN, cipher, PLAYFAIR_CIPHER_LEN);

    /* everything the child needs is prepared before fork: after fork only async-signal-safe
       calls (dup2, close, execve, _exit) are allowed in a multi-threaded process */
    struct rlimit rl;
    int maxfd = 1024;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) maxfd = (int)rl.rlim_cur;
    if (maxfd > 65536) maxfd = 65536;
    char *const argv[] = {helper, NULL};
    char *const envp[] = {NULL};

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return PLAYFAIR_ERR_SPAWN;
    fcntl(sv[0], F_SETFD, FD_CLOEXEC);
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(sv[0], SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
    long long deadline = now_ms() + timeout_ms;
    pid_t pid = fork();
    if (pid < 0) {
        close(sv[0]);
        close(sv[1]);
        return PLAYFAIR_ERR_SPAWN;
    }
    if (pid == 0) {
        if (dup2(sv[1], 0) < 0 || dup2(sv[1], 1) < 0) _exit(127);
        for (int fd = 3; fd < maxfd; fd++) close(fd);
        execve(helper, argv, envp);
        _exit(127);
    }
    close(sv[1]);

    int status = 0;
    size_t sent = 0;
    while (sent < sizeof(input)) {
        ssize_t n = send(sv[0], input + sent, sizeof(input) - sent, SEND_FLAGS);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        sent += (size_t)n;
    }
    if (sent < sizeof(input)) {
        close(sv[0]);
        reap(pid, &status, 1);
        /* a helper that could not even be started also refuses the input */
        if (WIFEXITED(status) && WEXITSTATUS(status) == 127) return PLAYFAIR_ERR_EXEC;
        return PLAYFAIR_ERR_WRITE;
    }
    shutdown(sv[0], SHUT_WR);

    /* read until EOF; one byte more than expected, so a longer answer is noticed */
    unsigned char out[PLAYFAIR_KEY_LEN + 1];
    size_t got = 0;
    int timed_out = 0;
    for (;;) {
        struct pollfd p = {sv[0], POLLIN, 0};
        int left = remaining(deadline);
        if (left == 0) { timed_out = 1; break; }
        int pr = poll(&p, 1, left);
        if (pr < 0 && errno == EINTR) continue;
        if (pr <= 0) { timed_out = pr == 0; break; }
        ssize_t n = recv(sv[0], out + got, sizeof(out) - got, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;            /* EOF or error */
        got += (size_t)n;
        if (got == sizeof(out)) break;
    }
    close(sv[0]);

    /* wait for the exit within the same deadline */
    int exited = 0;
    while (!timed_out) {
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) { exited = 1; break; }
        if (w < 0 && errno != EINTR) break;
        if (remaining(deadline) == 0) { timed_out = 1; break; }
        poll(NULL, 0, 1);
    }
    if (!exited) {
        reap(pid, &status, 1);
        memset(out, 0, sizeof(out));
        if (timed_out) return PLAYFAIR_ERR_TIMEOUT;
        return PLAYFAIR_ERR_CRASHED;
    }
    int result = PLAYFAIR_OK;
    if (WIFSIGNALED(status)) result = PLAYFAIR_ERR_CRASHED;
    else if (WIFEXITED(status) && WEXITSTATUS(status) == 127) result = PLAYFAIR_ERR_EXEC;
    else if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) result = PLAYFAIR_ERR_EXIT;
    else if (got != PLAYFAIR_KEY_LEN) result = PLAYFAIR_ERR_OUTPUT;
    if (result == PLAYFAIR_OK) memcpy(key_out, out, PLAYFAIR_KEY_LEN);
    memset(out, 0, sizeof(out));
    return result;
}
