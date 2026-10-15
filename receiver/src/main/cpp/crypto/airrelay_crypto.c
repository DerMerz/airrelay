/*
 * AirRelay: cryptographic helpers used by the AirPlay protocol code (UxPlay lib/).
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
 * Origin: written from scratch on 2026-10-15 to replace UxPlay's lib/crypto.c (GPL-3.0
 * only, by other authors), without reading that file. Sources: the function names in its
 * header, how the LGPL callers in lib/ use them, the OpenSSL 3 manual pages, and black-box
 * measurements of the previous binary (tools/crypto-vectors/). The device key derivation
 * below was measured by recording the OpenSSL calls the previous binary made.
 */
#include "crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>

/* ------------------------------------------------------------------ AES (CTR, CBC) */

enum aes_mode { MODE_CTR, MODE_CBC };

struct aes_ctx_s {
    EVP_CIPHER_CTX *evp;
    enum aes_mode mode;
    int encrypt;                    /* 1 encrypt, 0 decrypt (CBC only; CTR is symmetric) */
    uint8_t key[AES_128_BLOCK_SIZE];
    uint8_t iv[AES_128_BLOCK_SIZE];
    unsigned int block_used;        /* CTR: bytes of the current keystream block consumed */
};

static int aes_start(aes_ctx_t *ctx) {
    const EVP_CIPHER *cipher = ctx->mode == MODE_CTR ? EVP_aes_128_ctr() : EVP_aes_128_cbc();
    if (EVP_CIPHER_CTX_reset(ctx->evp) != 1) return 0;
    if (EVP_CipherInit_ex(ctx->evp, cipher, NULL, ctx->key, ctx->iv, ctx->encrypt) != 1) return 0;
    if (ctx->mode == MODE_CBC) EVP_CIPHER_CTX_set_padding(ctx->evp, 0);
    ctx->block_used = 0;
    return 1;
}

static aes_ctx_t *aes_new(enum aes_mode mode, const uint8_t *key, const uint8_t *iv, int encrypt) {
    aes_ctx_t *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->evp = EVP_CIPHER_CTX_new();
    ctx->mode = mode;
    ctx->encrypt = encrypt;
    memcpy(ctx->key, key, AES_128_BLOCK_SIZE);
    memcpy(ctx->iv, iv, AES_128_BLOCK_SIZE);
    if (!ctx->evp || !aes_start(ctx)) {
        EVP_CIPHER_CTX_free(ctx->evp);
        OPENSSL_cleanse(ctx, sizeof(*ctx));
        free(ctx);
        return NULL;
    }
    return ctx;
}

static void aes_free(aes_ctx_t *ctx) {
    if (!ctx) return;
    EVP_CIPHER_CTX_free(ctx->evp);
    OPENSSL_cleanse(ctx, sizeof(*ctx));
    free(ctx);
}

/* in and out may be the same buffer (EVP allows exact overlap) */
static void aes_run(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    if (!ctx || len <= 0) return;
    int outl = 0;
    EVP_CipherUpdate(ctx->evp, out, &outl, in, len);
}

aes_ctx_t *aes_ctr_init(const uint8_t *key, const uint8_t *iv) {
    return aes_new(MODE_CTR, key, iv, 1);
}

void aes_ctr_reset(aes_ctx_t *ctx) {
    if (ctx) aes_start(ctx);
}

void aes_ctr_encrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    if (!ctx || len <= 0) return;
    aes_run(ctx, in, out, len);
    ctx->block_used = (ctx->block_used + (unsigned int)len) % AES_128_BLOCK_SIZE;
}

void aes_ctr_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    aes_ctr_encrypt(ctx, in, out, len);
}

void aes_ctr_start_fresh_block(aes_ctx_t *ctx) {
    if (!ctx || ctx->block_used == 0) return;
    uint8_t scratch[AES_128_BLOCK_SIZE] = {0};
    int rest = (int)(AES_128_BLOCK_SIZE - ctx->block_used);
    aes_run(ctx, scratch, scratch, rest);
    OPENSSL_cleanse(scratch, sizeof(scratch));
    ctx->block_used = 0;
}

void aes_ctr_destroy(aes_ctx_t *ctx) {
    aes_free(ctx);
}

aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t direction) {
    return aes_new(MODE_CBC, key, iv, direction == AES_ENCRYPT ? 1 : 0);
}

void aes_cbc_reset(aes_ctx_t *ctx) {
    if (ctx) aes_start(ctx);
}

/* Whole blocks only. A trailing partial block is left untouched in out and does not carry
   into the next call (the previous implementation ended the process in that case). */
static void aes_cbc_run(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    int whole = len - len % AES_128_BLOCK_SIZE;
    aes_run(ctx, in, out, whole);
}

void aes_cbc_encrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    aes_cbc_run(ctx, in, out, len);
}

void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len) {
    aes_cbc_run(ctx, in, out, len);
}

void aes_cbc_destroy(aes_ctx_t *ctx) {
    aes_free(ctx);
}

/* ------------------------------------------------------------------ X25519 */

struct x25519_key_s {
    EVP_PKEY *pkey;
};

static x25519_key_t *x25519_wrap(EVP_PKEY *pkey) {
    if (!pkey) return NULL;
    x25519_key_t *k = calloc(1, sizeof(*k));
    if (!k) { EVP_PKEY_free(pkey); return NULL; }
    k->pkey = pkey;
    return k;
}

x25519_key_t *x25519_key_generate(void) {
    EVP_PKEY *pkey = NULL;
    EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_X25519, NULL);
    if (pctx && EVP_PKEY_keygen_init(pctx) == 1) EVP_PKEY_keygen(pctx, &pkey);
    EVP_PKEY_CTX_free(pctx);
    return x25519_wrap(pkey);
}

x25519_key_t *x25519_key_from_raw(const unsigned char data[X25519_KEY_SIZE]) {
    return x25519_wrap(EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, NULL, data, X25519_KEY_SIZE));
}

void x25519_key_get_raw(unsigned char data[X25519_KEY_SIZE], const x25519_key_t *key) {
    size_t len = X25519_KEY_SIZE;
    if (!key || EVP_PKEY_get_raw_public_key(key->pkey, data, &len) != 1 || len != X25519_KEY_SIZE) {
        memset(data, 0, X25519_KEY_SIZE);
    }
}

void x25519_key_destroy(x25519_key_t *key) {
    if (!key) return;
    EVP_PKEY_free(key->pkey);
    free(key);
}

void x25519_derive_secret(unsigned char secret[X25519_KEY_SIZE], const x25519_key_t *ours, const x25519_key_t *theirs) {
    int ok = 0;
    if (ours && theirs) {
        EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new(ours->pkey, NULL);
        size_t len = X25519_KEY_SIZE;
        ok = pctx && EVP_PKEY_derive_init(pctx) == 1 && EVP_PKEY_derive_set_peer(pctx, theirs->pkey) == 1
             && EVP_PKEY_derive(pctx, secret, &len) == 1 && len == X25519_KEY_SIZE;
        EVP_PKEY_CTX_free(pctx);
    }
    if (!ok && RAND_bytes(secret, X25519_KEY_SIZE) != 1) {
        /* no randomness either: an all-0xff secret still fails the peer's signature check */
        memset(secret, 0xff, X25519_KEY_SIZE);
    }
}

/* ------------------------------------------------------------------ misc */

int get_random_bytes(unsigned char *buf, int num) {
    if (!buf || num < 0) return 0;
    return RAND_bytes(buf, num) == 1 ? 1 : 0;
}

void pk_to_base64(const unsigned char *pk, int pk_len, char *pk_base64, int len) {
    if (!pk_base64 || len <= 0) return;
    pk_base64[0] = '\0';
    if (!pk || pk_len <= 0) return;
    size_t need = 4 * (((size_t)pk_len + 2) / 3) + 1;
    unsigned char *tmp = malloc(need);
    if (!tmp) return;
    int n = EVP_EncodeBlock(tmp, pk, pk_len);
    if (n < 0) n = 0;
    if (n > len - 1) n = len - 1;
    memcpy(pk_base64, tmp, (size_t)n);
    pk_base64[n] = '\0';
    free(tmp);
}

/* ------------------------------------------------------------------ AES-128-GCM */

static int gcm_run(int encrypt, const unsigned char *in, int len, unsigned char *out,
                   unsigned char *key, unsigned char *iv, unsigned char *tag) {
    if (len < 0 || !key || !iv || !tag) return -1;
    EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
    int outl = 0, finl = 0, ok = c != NULL;
    ok = ok && EVP_CipherInit_ex(c, EVP_aes_128_gcm(), NULL, NULL, NULL, encrypt) == 1;
    ok = ok && EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, AES_128_BLOCK_SIZE, NULL) == 1;
    ok = ok && EVP_CipherInit_ex(c, NULL, NULL, key, iv, encrypt) == 1;
    ok = ok && (len == 0 || EVP_CipherUpdate(c, out, &outl, in, len) == 1);
    if (ok && !encrypt) ok = EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, tag) == 1;
    ok = ok && EVP_CipherFinal_ex(c, out + outl, &finl) == 1;
    if (ok && encrypt) ok = EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, tag) == 1;
    EVP_CIPHER_CTX_free(c);
    return ok ? outl + finl : -1;
}

int gcm_encrypt(const unsigned char *plaintext, int plaintext_len, unsigned char *ciphertext,
                unsigned char *key, unsigned char *iv, unsigned char *tag) {
    return gcm_run(1, plaintext, plaintext_len, ciphertext, key, iv, tag);
}

int gcm_decrypt(unsigned char *ciphertext, int ciphertext_len, unsigned char *plaintext,
                unsigned char *key, unsigned char *iv, unsigned char *tag) {
    return gcm_run(0, ciphertext, ciphertext_len, plaintext, key, iv, tag);
}

/* ------------------------------------------------------------------ Ed25519 */

/* The public key comes first: UxPlay's pairing_session_make_nonce() hashes the first 32
   bytes of this struct, so they must exist and should not change between runs. */
struct ed25519_key_s {
    unsigned char pub[ED25519_KEY_SIZE];
    EVP_PKEY *pkey;
};

/* Fixed by the receivers already in use: senders have seen the public key derived with it. */
static const char DEVICE_KEY_PREFIX[] = "UxPlay-Persistent-Not-Secure-Public-Key";

static ed25519_key_t *ed25519_wrap(EVP_PKEY *pkey) {
    if (!pkey) return NULL;
    ed25519_key_t *k = calloc(1, sizeof(*k));
    size_t len = ED25519_KEY_SIZE;
    if (!k || EVP_PKEY_get_raw_public_key(pkey, k->pub, &len) != 1 || len != ED25519_KEY_SIZE) {
        free(k);
        EVP_PKEY_free(pkey);
        return NULL;
    }
    k->pkey = pkey;
    return k;
}

static EVP_PKEY *ed25519_derived(const char *device_id) {
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int dlen = 0;
    EVP_MD_CTX *m = EVP_MD_CTX_new();
    int ok = m && EVP_DigestInit_ex(m, EVP_sha512(), NULL) == 1
             && EVP_DigestUpdate(m, DEVICE_KEY_PREFIX, strlen(DEVICE_KEY_PREFIX)) == 1
             && EVP_DigestUpdate(m, device_id, strlen(device_id)) == 1
             && EVP_DigestFinal_ex(m, digest, &dlen) == 1 && dlen >= ED25519_KEY_SIZE;
    EVP_MD_CTX_free(m);
    EVP_PKEY *pkey = ok ? EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, digest, ED25519_KEY_SIZE) : NULL;
    OPENSSL_cleanse(digest, sizeof(digest));
    return pkey;
}

static EVP_PKEY *ed25519_random(void) {
    EVP_PKEY *pkey = NULL;
    EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, NULL);
    if (pctx && EVP_PKEY_keygen_init(pctx) == 1) EVP_PKEY_keygen(pctx, &pkey);
    EVP_PKEY_CTX_free(pctx);
    return pkey;
}

static EVP_PKEY *ed25519_from_keyfile(const char *keyfile, int *result) {
    FILE *f = fopen(keyfile, "r");
    if (f) {
        EVP_PKEY *pkey = PEM_read_PrivateKey(f, NULL, NULL, NULL);
        fclose(f);
        if (pkey && EVP_PKEY_get_base_id(pkey) == EVP_PKEY_ED25519) return pkey;
        EVP_PKEY_free(pkey);
        /* unreadable: set it aside instead of overwriting it */
        size_t n = strlen(keyfile) + 5;
        char *aside = malloc(n);
        if (aside) {
            snprintf(aside, n, "%s.bad", keyfile);
            rename(keyfile, aside);
            free(aside);
        }
    }
    EVP_PKEY *pkey = ed25519_random();
    if (!pkey) return NULL;
    f = fopen(keyfile, "w");
    if (f) {
        if (PEM_write_PrivateKey(f, pkey, NULL, NULL, 0, NULL, NULL) == 1) *result = 1;
        fclose(f);
    }
    return pkey;
}

ed25519_key_t *ed25519_key_generate(const char *device_id, const char *keyfile, int *result) {
    int dummy = 0;
    if (!result) result = &dummy;
    *result = 0;
    if (keyfile && keyfile[0]) return ed25519_wrap(ed25519_from_keyfile(keyfile, result));
    return ed25519_wrap(ed25519_derived(device_id ? device_id : ""));
}

ed25519_key_t *ed25519_key_from_raw(const unsigned char data[ED25519_KEY_SIZE]) {
    return ed25519_wrap(EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, NULL, data, ED25519_KEY_SIZE));
}

void ed25519_key_get_raw(unsigned char data[ED25519_KEY_SIZE], const ed25519_key_t *key) {
    if (key) memcpy(data, key->pub, ED25519_KEY_SIZE);
    else memset(data, 0, ED25519_KEY_SIZE);
}

ed25519_key_t *ed25519_key_copy(const ed25519_key_t *key) {
    if (!key || EVP_PKEY_up_ref(key->pkey) != 1) return NULL;
    ed25519_key_t *k = calloc(1, sizeof(*k));
    if (!k) { EVP_PKEY_free(key->pkey); return NULL; }
    memcpy(k->pub, key->pub, ED25519_KEY_SIZE);
    k->pkey = key->pkey;
    return k;
}

void ed25519_key_destroy(ed25519_key_t *key) {
    if (!key) return;
    EVP_PKEY_free(key->pkey);
    free(key);
}

void ed25519_sign(unsigned char *signature, size_t signature_len,
                  const unsigned char *data, size_t data_len,
                  const ed25519_key_t *key) {
    size_t len = signature_len;
    EVP_MD_CTX *m = EVP_MD_CTX_new();
    int ok = m && key && EVP_DigestSignInit(m, NULL, NULL, NULL, key->pkey) == 1
             && EVP_DigestSign(m, signature, &len, data, data_len) == 1;
    EVP_MD_CTX_free(m);
    if (!ok) memset(signature, 0, signature_len);
}

int ed25519_verify(const unsigned char *signature, size_t signature_len,
                   const unsigned char *data, size_t data_len,
                   const ed25519_key_t *key) {
    EVP_MD_CTX *m = EVP_MD_CTX_new();
    int ok = m && key && EVP_DigestVerifyInit(m, NULL, NULL, NULL, key->pkey) == 1
             && EVP_DigestVerify(m, signature, signature_len, data, data_len) == 1;
    EVP_MD_CTX_free(m);
    return ok ? 1 : 0;
}

/* ------------------------------------------------------------------ SHA-512, MD5 */

struct digest_ctx {
    EVP_MD_CTX *evp;
    const EVP_MD *md;
};
struct sha_ctx_s { struct digest_ctx d; };
struct md5_ctx_s { struct digest_ctx d; };

static int digest_open(struct digest_ctx *d, const EVP_MD *md) {
    d->md = md;
    d->evp = EVP_MD_CTX_new();
    return d->evp && EVP_DigestInit_ex(d->evp, md, NULL) == 1;
}

static void digest_update(struct digest_ctx *d, const uint8_t *in, int len) {
    if (len > 0) EVP_DigestUpdate(d->evp, in, (size_t)len);
}

static void digest_final(struct digest_ctx *d, uint8_t *out, unsigned int *len) {
    unsigned int n = 0;
    EVP_DigestFinal_ex(d->evp, out, &n);
    if (len) *len = n;
}

static void digest_reset(struct digest_ctx *d) {
    EVP_DigestInit_ex(d->evp, d->md, NULL);
}

sha_ctx_t *sha_init(void) {
    sha_ctx_t *c = calloc(1, sizeof(*c));
    if (c && !digest_open(&c->d, EVP_sha512())) { EVP_MD_CTX_free(c->d.evp); free(c); c = NULL; }
    return c;
}
void sha_update(sha_ctx_t *ctx, const uint8_t *in, int len) { if (ctx) digest_update(&ctx->d, in, len); }
void sha_final(sha_ctx_t *ctx, uint8_t *out, unsigned int *len) { if (ctx) digest_final(&ctx->d, out, len); }
void sha_reset(sha_ctx_t *ctx) { if (ctx) digest_reset(&ctx->d); }
void sha_destroy(sha_ctx_t *ctx) { if (ctx) { EVP_MD_CTX_free(ctx->d.evp); free(ctx); } }

md5_ctx_t *md5_init(void) {
    md5_ctx_t *c = calloc(1, sizeof(*c));
    if (c && !digest_open(&c->d, EVP_md5())) { EVP_MD_CTX_free(c->d.evp); free(c); c = NULL; }
    return c;
}
void md5_update(md5_ctx_t *ctx, const uint8_t *in, int len) { if (ctx) digest_update(&ctx->d, in, len); }
void md5_final(md5_ctx_t *ctx, uint8_t *out, unsigned int *len) { if (ctx) digest_final(&ctx->d, out, len); }
void md5_reset(md5_ctx_t *ctx) { if (ctx) digest_reset(&ctx->d); }
void md5_destroy(md5_ctx_t *ctx) { if (ctx) { EVP_MD_CTX_free(ctx->d.evp); free(ctx); } }

char *get_md5(char *string) {
    static const char digits[] = "0123456789abcdef";
    unsigned char md[16];
    unsigned int n = 0;
    if (!string || EVP_Digest(string, strlen(string), md, &n, EVP_md5(), NULL) != 1 || n != 16) return NULL;
    char *hex = malloc(33);
    if (!hex) return NULL;
    for (int i = 0; i < 16; i++) {
        hex[2 * i] = digits[md[i] >> 4];
        hex[2 * i + 1] = digits[md[i] & 0x0f];
    }
    hex[32] = '\0';
    return hex;
}
