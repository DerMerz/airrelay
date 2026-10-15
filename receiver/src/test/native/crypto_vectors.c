/*
 * AirRelay: black-box vector dump for the crypto.h interface.
 * Copyright (C) 2026 Kevin Merz
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE and
 * LICENSE-EXCEPTION.md in the repository root.
 *
 * Calls only the functions declared in crypto.h with fixed inputs and prints every result
 * as one line "name value". Linked once against the crypto implementation that shipped in
 * 0.1.0 (measured on 2026-10-15 with tools/measure_0.1.0_native.sh, frozen in
 * receiver/src/test/resources/native/crypto-vectors-0.1.0.txt) and once against the rewrite
 * (NativeHostTest): the outputs must be identical except for the differences listed and
 * explained in NativeHostTest. Cases that might abort the process run in a child; their
 * line then says "crashed".
 *
 * Usage: crypto_vectors <scratch keyfile path> <rfc8032-test1.pem>
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "crypto.h"

static void hex(const char *name, const unsigned char *b, size_t n) {
    printf("%s ", name);
    for (size_t i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

static uint32_t rng_state = 0x2545F491u;
static void fill(unsigned char *b, size_t n, uint32_t seed) {
    rng_state = seed ? seed : 1u;
    for (size_t i = 0; i < n; i++) {
        rng_state ^= rng_state << 13; rng_state ^= rng_state >> 17; rng_state ^= rng_state << 5;
        b[i] = (unsigned char)(rng_state >> 24);
    }
}

/* Runs fn in a child and copies its stdout line(s) through; prints "<name> crashed" if the
   child dies or exits non-zero, so an abort in one case does not hide the others. */
static void isolated(const char *name, void (*fn)(void)) {
    fflush(stdout);
    pid_t pid = fork();
    if (pid == 0) { fn(); fflush(stdout); _exit(0); }
    int status = 0;
    waitpid(pid, &status, 0);
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) printf("%s crashed\n", name);
}

static const uint8_t KEY[16] = {0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f};
static const uint8_t IV[16]  = {0xf0,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,0xf9,0xfa,0xfb,0xfc,0xfd,0xfe,0xff};

static void sha_cases(void) {
    unsigned char out[64]; unsigned int len = 0;
    sha_ctx_t *c = sha_init();
    sha_final(c, out, &len); hex("sha512.empty", out, 64); printf("sha512.len %u\n", len);
    sha_reset(c);
    sha_update(c, (const uint8_t *)"abc", 3); sha_final(c, out, NULL); hex("sha512.abc", out, 64);
    unsigned char big[1000]; fill(big, sizeof big, 7);
    sha_reset(c);
    sha_update(c, big, 1); sha_update(c, big + 1, 127); sha_update(c, big + 128, 0); sha_update(c, big + 128, 872);
    sha_final(c, out, NULL); hex("sha512.chunks1000", out, 64);
    sha_destroy(c);

    md5_ctx_t *m = md5_init();
    md5_final(m, out, &len); hex("md5.empty", out, 16); printf("md5.len %u\n", len);
    md5_reset(m);
    md5_update(m, big, 500); md5_update(m, big + 500, 500); md5_final(m, out, NULL); hex("md5.chunks1000", out, 16);
    md5_destroy(m);
    char *s = get_md5((char *)"abc"); printf("get_md5.abc %s\n", s); free(s);
    s = get_md5((char *)""); printf("get_md5.empty %s\n", s); free(s);
    s = get_md5((char *)"AirPlay:raop:3939"); printf("get_md5.digest %s\n", s); free(s);
}

static void ctr_cases(void) {
    unsigned char in[128], out[128];
    fill(in, sizeof in, 11);
    char name[64];
    /* A: consecutive calls of odd lengths share one keystream */
    aes_ctx_t *c = aes_ctr_init(KEY, IV);
    const int lens[] = {1, 15, 16, 17, 31, 5, 43};
    int off = 0;
    for (size_t i = 0; i < sizeof lens / sizeof lens[0]; i++) {
        aes_ctr_encrypt(c, in + off, out, lens[i]);
        snprintf(name, sizeof name, "ctr.seq.%zu.len%d", i, lens[i]); hex(name, out, lens[i]);
        off += lens[i];
    }
    /* B: reset rewinds to the initial counter */
    aes_ctr_reset(c);
    aes_ctr_encrypt(c, in, out, 20); hex("ctr.after_reset.20", out, 20);
    /* C: start_fresh_block mid-block and on a block boundary, on a new context (the way
       mirror_buffer.c uses it: init once, never reset) */
    aes_ctr_destroy(c);
    c = aes_ctr_init(KEY, IV);
    aes_ctr_encrypt(c, in, out, 5); hex("ctr.fresh.a5", out, 5);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 5); hex("ctr.fresh.b5", out, 5);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 16); hex("ctr.fresh.c16", out, 16);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 3); hex("ctr.fresh.d3", out, 3);
    aes_ctr_start_fresh_block(c);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 16); hex("ctr.fresh.e16_twice", out, 16);
    /* C2: start_fresh_block right after a reset. Known, intended difference: the 0.1.0
       binary kept counting the consumed bytes across aes_ctr_reset and skipped 7 bytes here;
       the rewrite skips nothing because the keystream starts on a block boundary. No caller
       in UxPlay resets a counter-mode context (GuardsExistTest checks this). */
    aes_ctr_reset(c);
    aes_ctr_encrypt(c, in, out, 9);
    aes_ctr_reset(c);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 16); hex("ctr.fresh_after_reset.16", out, 16);
    /* D: decrypt continues the same keystream as encrypt; in place */
    aes_ctr_reset(c);
    aes_ctr_decrypt(c, in, out, 7); hex("ctr.dec.7", out, 7);
    memcpy(out, in, 40);
    aes_ctr_decrypt(c, out, out, 40); hex("ctr.dec.inplace40", out, 40);
    aes_ctr_encrypt(c, in, out, 0); aes_ctr_encrypt(c, in, out, 9); hex("ctr.after_zero.9", out, 9);
    aes_ctr_destroy(c);
    /* E: fresh context, fresh block first */
    c = aes_ctr_init(KEY, IV);
    aes_ctr_start_fresh_block(c);
    aes_ctr_encrypt(c, in, out, 16); hex("ctr.fresh_first.16", out, 16);
    aes_ctr_destroy(c);
    /* F: counter carry across the low 64 bits of the IV */
    uint8_t ivmax[16]; memset(ivmax, 0xff, 16);
    c = aes_ctr_init(KEY, ivmax);
    aes_ctr_encrypt(c, in, out, 48); hex("ctr.carry.48", out, 48);
    aes_ctr_destroy(c);
}

static void cbc_cases(void) {
    unsigned char in[128], out[128];
    fill(in, sizeof in, 13);
    aes_ctx_t *e = aes_cbc_init(KEY, IV, AES_ENCRYPT);
    aes_cbc_encrypt(e, in, out, 32); hex("cbc.enc.32", out, 32);
    aes_cbc_encrypt(e, in + 32, out, 16); hex("cbc.enc.next16", out, 16);
    aes_cbc_reset(e);
    aes_cbc_encrypt(e, in, out, 16); hex("cbc.enc.after_reset16", out, 16);
    aes_cbc_destroy(e);
    aes_ctx_t *d = aes_cbc_init(KEY, IV, AES_DECRYPT);
    aes_cbc_decrypt(d, in, out, 48); hex("cbc.dec.48", out, 48);
    aes_cbc_decrypt(d, in + 48, out, 16); hex("cbc.dec.next16", out, 16);
    aes_cbc_reset(d);
    aes_cbc_decrypt(d, in, out, 16); hex("cbc.dec.after_reset16", out, 16);
    aes_cbc_reset(d);
    memset(out, 0xaa, sizeof out);
    aes_cbc_decrypt(d, in, out, 0); hex("cbc.dec.zero_len_untouched", out, 16);
    memcpy(out, in, 32);
    aes_cbc_decrypt(d, out, out, 32); hex("cbc.dec.inplace32", out, 32);
    aes_cbc_destroy(d);
}

/* a length that is not a multiple of the block size: what is written, and does the rest
   leak into the next call? (callers always pass whole blocks; recorded for completeness) */
static void cbc_partial(void) {
    unsigned char in[64], out[64];
    fill(in, sizeof in, 13);
    aes_ctx_t *d = aes_cbc_init(KEY, IV, AES_DECRYPT);
    memset(out, 0xaa, sizeof out);
    aes_cbc_decrypt(d, in, out, 20); hex("cbc.partial.dec20", out, 32);
    aes_cbc_reset(d);
    memset(out, 0xaa, sizeof out);
    aes_cbc_decrypt(d, in, out, 16); hex("cbc.partial.after_reset16", out, 16);
    aes_cbc_destroy(d);
}

static void gcm_cases(void) {
    unsigned char key[16], iv[16], pt[32], ct[48], tag[16], back[48];
    fill(key, 16, 17); fill(iv, 16, 19); fill(pt, 32, 23);
    memset(ct, 0xaa, sizeof ct);
    int n = gcm_encrypt(pt, 32, ct, key, iv, tag);
    printf("gcm.enc.ret %d\n", n); hex("gcm.enc.ct", ct, 48); hex("gcm.enc.tag", tag, 16);
    memset(back, 0xaa, sizeof back);
    n = gcm_decrypt(ct, 32, back, key, iv, tag);
    printf("gcm.dec.ret %d\n", n); hex("gcm.dec.pt", back, 48);
    iv[15]++;
    n = gcm_encrypt(pt, 32, ct, key, iv, tag);
    printf("gcm.enc_iv15.ret %d\n", n); hex("gcm.enc_iv15.ct", ct, 32); hex("gcm.enc_iv15.tag", tag, 16);
    n = gcm_encrypt(pt, 5, ct, key, iv, tag);
    printf("gcm.enc5.ret %d\n", n); hex("gcm.enc5.ct", ct, 5); hex("gcm.enc5.tag", tag, 16);
}

static void gcm_bad_tag(void) {
    unsigned char key[16], iv[16], pt[32], ct[32], tag[16], back[48];
    fill(key, 16, 17); fill(iv, 16, 19); fill(pt, 32, 23);
    gcm_encrypt(pt, 32, ct, key, iv, tag);
    tag[0] ^= 1;
    memset(back, 0xaa, sizeof back);
    int n = gcm_decrypt(ct, 32, back, key, iv, tag);
    printf("gcm.badtag.ret %d\n", n);
    printf("gcm.badtag.ok %d\n", n <= 0);
}

static void gcm_bad_ct(void) {
    unsigned char key[16], iv[16], pt[32], ct[32], tag[16], back[48];
    fill(key, 16, 17); fill(iv, 16, 19); fill(pt, 32, 23);
    gcm_encrypt(pt, 32, ct, key, iv, tag);
    ct[31] ^= 0x80;
    int n = gcm_decrypt(ct, 32, back, key, iv, tag);
    printf("gcm.badct.ret %d\n", n);
}

static void ed_cases(void) {
    /* the last three are the device ids of receiver/contract/compat-contract.txt: their keys
       are the AirPlay identity (TXT record "pk") that senders have already seen */
    const char *ids[] = {"00:00:00:00:00:00", "12:34:56:78:9A:BC", "A4:5E:60:E1:02:3F", "x", "",
                         "62:40:D4:9A:D3:FA", "8E:61:8F:51:AD:E7", "0A:53:50:49:45:47"};
    char name[64];
    unsigned char pk[32], pk2[32], msg[64], sig[64];
    fill(msg, 64, 29);
    for (size_t i = 0; i < sizeof ids / sizeof ids[0]; i++) {
        int r = -7;
        ed25519_key_t *k = ed25519_key_generate(ids[i], "", &r);
        ed25519_key_get_raw(pk, k);
        snprintf(name, sizeof name, "ed.derived.%zu.pk", i); hex(name, pk, 32);
        snprintf(name, sizeof name, "ed.derived.%zu.result", i); printf("%s %d\n", name, r);
        ed25519_sign(sig, 64, msg, 64, k);
        snprintf(name, sizeof name, "ed.derived.%zu.sig", i); hex(name, sig, 64);
        ed25519_key_t *k2 = ed25519_key_generate(ids[i], "", &r);
        ed25519_key_get_raw(pk2, k2);
        snprintf(name, sizeof name, "ed.derived.%zu.stable", i); printf("%s %d\n", name, memcmp(pk, pk2, 32) == 0);
        ed25519_key_t *cp = ed25519_key_copy(k);
        ed25519_key_get_raw(pk2, cp);
        snprintf(name, sizeof name, "ed.derived.%zu.copy_same", i); printf("%s %d\n", name, memcmp(pk, pk2, 32) == 0);
        ed25519_key_destroy(k);
        ed25519_sign(sig, 64, msg, 64, cp); /* copy still usable after the original is gone */
        ed25519_key_t *pub = ed25519_key_from_raw(pk);
        snprintf(name, sizeof name, "ed.derived.%zu.verify_ok", i); printf("%s %d\n", name, ed25519_verify(sig, 64, msg, 64, pub));
        sig[10] ^= 4;
        snprintf(name, sizeof name, "ed.derived.%zu.verify_badsig", i); printf("%s %d\n", name, ed25519_verify(sig, 64, msg, 64, pub));
        sig[10] ^= 4; msg[0] ^= 1;
        snprintf(name, sizeof name, "ed.derived.%zu.verify_badmsg", i); printf("%s %d\n", name, ed25519_verify(sig, 64, msg, 64, pub));
        msg[0] ^= 1;
        ed25519_key_get_raw(pk2, pub);
        snprintf(name, sizeof name, "ed.derived.%zu.from_raw_roundtrip", i); printf("%s %d\n", name, memcmp(pk, pk2, 32) == 0);
        ed25519_key_destroy(cp);
        ed25519_key_destroy(pub);
        ed25519_key_destroy(k2);
    }
    /* RFC 8032 section 7.1 TEST 1: verify the published signature with the published key */
    static const unsigned char rfc_pk[32] = {0xd7,0x5a,0x98,0x01,0x82,0xb1,0x0a,0xb7,0xd5,0x4b,0xfe,0xd3,0xc9,0x64,0x07,0x3a,0x0e,0xe1,0x72,0xf3,0xda,0xa6,0x23,0x25,0xaf,0x02,0x1a,0x68,0xf7,0x07,0x51,0x1a};
    static const unsigned char rfc_sig[64] = {0xe5,0x56,0x43,0x00,0xc3,0x60,0xac,0x72,0x90,0x86,0xe2,0xcc,0x80,0x6e,0x82,0x8a,0x84,0x87,0x7f,0x1e,0xb8,0xe5,0xd9,0x74,0xd8,0x73,0xe0,0x65,0x22,0x49,0x01,0x55,0x5f,0xb8,0x82,0x15,0x90,0xa3,0x3b,0xac,0xc6,0x1e,0x39,0x70,0x1c,0xf9,0xb4,0x6b,0xd2,0x5b,0xf5,0xf0,0x59,0x5b,0xbe,0x24,0x65,0x51,0x41,0x43,0x8e,0x7a,0x10,0x0b};
    ed25519_key_t *pub = ed25519_key_from_raw(rfc_pk);
    printf("ed.rfc8032_1.verify %d\n", ed25519_verify(rfc_sig, 64, (const unsigned char *)"", 0, pub));
    ed25519_key_destroy(pub);
}

static const char *g_keyfile;
static void ed_keyfile(void) {
    unsigned char pk[32], pk2[32];
    int r = -7, r2 = -7;
    unlink(g_keyfile);
    ed25519_key_t *k = ed25519_key_generate("12:34:56:78:9A:BC", g_keyfile, &r);
    ed25519_key_get_raw(pk, k);
    ed25519_key_t *k2 = ed25519_key_generate("12:34:56:78:9A:BC", g_keyfile, &r2);
    ed25519_key_get_raw(pk2, k2);
    printf("ed.keyfile.first_result %d\n", r);
    printf("ed.keyfile.second_result %d\n", r2);
    printf("ed.keyfile.reloaded_same %d\n", memcmp(pk, pk2, 32) == 0);
    FILE *f = fopen(g_keyfile, "r");
    char line[128] = "missing";
    if (f) { if (!fgets(line, sizeof line, f)) strcpy(line, "empty\n"); fclose(f); }
    line[strcspn(line, "\n")] = 0;
    printf("ed.keyfile.first_line %s\n", line);
    ed25519_key_destroy(k); ed25519_key_destroy(k2);
}

static void x_cases(void) {
    unsigned char a_pub[32], b_pub[32], s1[32], s2[32];
    x25519_key_t *a = x25519_key_generate(), *b = x25519_key_generate();
    x25519_key_get_raw(a_pub, a); x25519_key_get_raw(b_pub, b);
    x25519_key_t *a_p = x25519_key_from_raw(a_pub), *b_p = x25519_key_from_raw(b_pub);
    x25519_derive_secret(s1, a, b_p); x25519_derive_secret(s2, b, a_p);
    printf("x.agree %d\n", memcmp(s1, s2, 32) == 0);
    unsigned char zero[32] = {0};
    printf("x.secret_nonzero %d\n", memcmp(s1, zero, 32) != 0);
    unsigned char raw[32];
    x25519_key_get_raw(raw, b_p);
    printf("x.from_raw_roundtrip %d\n", memcmp(raw, b_pub, 32) == 0);
    x25519_key_t *c = x25519_key_generate();
    x25519_key_get_raw(raw, c);
    printf("x.generate_differs %d\n", memcmp(raw, a_pub, 32) != 0);
    /* RFC 7748 section 6.1: derive with our random key against Bob's public key twice */
    static const unsigned char bob_pub[32] = {0xde,0x9e,0xdb,0x7d,0x7b,0x7d,0xc1,0xb4,0xd3,0x5b,0x61,0xc2,0xec,0xe4,0x35,0x37,0x3f,0x83,0x43,0xc8,0x5b,0x78,0x67,0x4d,0xad,0xfc,0x7e,0x14,0x6f,0x88,0x2b,0x4f};
    x25519_key_t *bob = x25519_key_from_raw(bob_pub);
    x25519_derive_secret(s1, a, bob); x25519_derive_secret(s2, a, bob);
    printf("x.derive_repeatable %d\n", memcmp(s1, s2, 32) == 0);
    x25519_key_destroy(a); x25519_key_destroy(b); x25519_key_destroy(a_p); x25519_key_destroy(b_p);
    x25519_key_destroy(c); x25519_key_destroy(bob);
}

/* a sender that offers the all-zero (low-order) public key */
static void x_zero_point(void) {
    unsigned char zero[32] = {0}, s1[32], s2[32], sentinel[32];
    memset(sentinel, 0xaa, 32);
    memcpy(s1, sentinel, 32);
    memcpy(s2, sentinel, 32);
    x25519_key_t *a = x25519_key_generate();
    x25519_key_t *z = x25519_key_from_raw(zero);
    x25519_derive_secret(s1, a, z);
    x25519_derive_secret(s2, a, z);
    printf("x.zero_point.written %d\n", memcmp(s1, sentinel, 32) != 0);
    printf("x.zero_point.not_zero %d\n", memcmp(s1, zero, 32) != 0);
    printf("x.zero_point.unpredictable %d\n", memcmp(s1, s2, 32) != 0);
}

/* RFC 8032 section 7.1 TEST 1 through the keyfile path: the published private key as PKCS#8
   PEM must give the published public key and signature of the empty message */
static const char *g_rfc_pem;
static void ed_rfc_keyfile(void) {
    int r = -7;
    unsigned char pk[32], sig[64];
    ed25519_key_t *k = ed25519_key_generate("ignored", g_rfc_pem, &r);
    ed25519_key_get_raw(pk, k);
    hex("ed.rfc8032_1.pk", pk, 32);
    printf("ed.rfc8032_1.result %d\n", r);
    ed25519_sign(sig, 64, (const unsigned char *)"", 0, k);
    hex("ed.rfc8032_1.sig", sig, 64);
    ed25519_key_destroy(k);
}

static void misc_cases(void) {
    unsigned char b[64];
    memset(b, 0, sizeof b);
    printf("random.ret16 %d\n", get_random_bytes(b, 16));
    unsigned char zero[16] = {0};
    printf("random.nonzero %d\n", memcmp(b, zero, 16) != 0);
    unsigned char pk[32];
    fill(pk, 32, 31);
    char out[64];
    memset(out, '#', sizeof out); out[63] = 0;
    pk_to_base64(pk, 32, out, 45); printf("b64.32 [%s]\n", out);
    memset(out, '#', sizeof out); out[63] = 0;
    pk_to_base64(pk, 1, out, 45); printf("b64.1 [%s]\n", out);
    memset(out, '#', sizeof out); out[63] = 0;
    pk_to_base64(pk, 2, out, 45); printf("b64.2 [%s]\n", out);
    memset(out, '#', sizeof out); out[63] = 0;
    pk_to_base64(pk, 3, out, 45); printf("b64.3 [%s]\n", out);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: crypto_vectors <scratch keyfile> <rfc8032-test1.pem>\n"); return 2; }
    g_keyfile = argv[1];
    g_rfc_pem = argv[2];
    sha_cases();
    ctr_cases();
    cbc_cases();
    isolated("cbc.partial", cbc_partial);
    gcm_cases();
    isolated("gcm.badtag", gcm_bad_tag);
    isolated("gcm.badct", gcm_bad_ct);
    ed_cases();
    isolated("ed.keyfile", ed_keyfile);
    isolated("ed.rfc8032_1", ed_rfc_keyfile);
    x_cases();
    isolated("x.zero_point", x_zero_point);
    misc_cases();
    return 0;
}
