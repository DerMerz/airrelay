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
 * Written from scratch for AirRelay as a drop-in for the function names the UxPlay sources
 * call. It replaces UxPlay's lib/crypto.c and lib/crypto.h, which are not compiled. The
 * behaviour was specified from the callers (LGPL), the OpenSSL 3 manual pages, and
 * measurements of the previous binary (tools/crypto-vectors/), not from its source.
 */
#ifndef AIRRELAY_CRYPTO_H
#define AIRRELAY_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AES_128_BLOCK_SIZE 16
#define X25519_KEY_SIZE 32
#define ED25519_KEY_SIZE 32

typedef enum aes_direction_e { AES_DECRYPT, AES_ENCRYPT } aes_direction_t;

/* ---- AES-128 in counter mode: one continuous keystream across calls ---- */
typedef struct aes_ctx_s aes_ctx_t;
aes_ctx_t *aes_ctr_init(const uint8_t *key, const uint8_t *iv);
/* back to the initial counter */
void aes_ctr_reset(aes_ctx_t *ctx);
void aes_ctr_encrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len);
void aes_ctr_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len);
/* drops what is left of a partly used keystream block; no effect on a block boundary */
void aes_ctr_start_fresh_block(aes_ctx_t *ctx);
void aes_ctr_destroy(aes_ctx_t *ctx);

/* ---- AES-128 in CBC mode without padding; whole blocks only, chaining across calls ---- */
aes_ctx_t *aes_cbc_init(const uint8_t *key, const uint8_t *iv, aes_direction_t direction);
void aes_cbc_reset(aes_ctx_t *ctx);
void aes_cbc_encrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len);
void aes_cbc_decrypt(aes_ctx_t *ctx, const uint8_t *in, uint8_t *out, int len);
void aes_cbc_destroy(aes_ctx_t *ctx);

/* ---- X25519 (RFC 7748) ---- */
typedef struct x25519_key_s x25519_key_t;
x25519_key_t *x25519_key_generate(void);
/* a peer's public key */
x25519_key_t *x25519_key_from_raw(const unsigned char data[X25519_KEY_SIZE]);
/* the public key */
void x25519_key_get_raw(unsigned char data[X25519_KEY_SIZE], const x25519_key_t *key);
void x25519_key_destroy(x25519_key_t *key);
/* If the peer key is unusable (a low-order point), the secret is filled with random bytes:
   the handshake then fails instead of agreeing on a known value. */
void x25519_derive_secret(unsigned char secret[X25519_KEY_SIZE], const x25519_key_t *ours, const x25519_key_t *theirs);

/* 1 on success, 0 on failure */
int get_random_bytes(unsigned char *buf, int num);
/* standard base64 with padding, NUL-terminated, at most len bytes including the NUL */
void pk_to_base64(const unsigned char *pk, int pk_len, char *pk_base64, int len);

/* ---- AES-128-GCM with a 16-byte IV, 16-byte tag, no additional data ---- */
/* returns the number of bytes written, or -1 */
int gcm_encrypt(const unsigned char *plaintext, int plaintext_len, unsigned char *ciphertext,
                unsigned char *key, unsigned char *iv, unsigned char *tag);
/* returns the number of bytes written, or -1 if the tag does not match */
int gcm_decrypt(unsigned char *ciphertext, int ciphertext_len, unsigned char *plaintext,
                unsigned char *key, unsigned char *iv, unsigned char *tag);

/* ---- Ed25519 (RFC 8032) ---- */
typedef struct ed25519_key_s ed25519_key_t;
/* keyfile empty (or NULL): the key is derived from device_id and nothing is stored, so the
   same device always announces the same public key. keyfile set: a PEM (PKCS#8) private key
   is read from it, or created and written; *result is 1 if a new file was written, else 0. */
ed25519_key_t *ed25519_key_generate(const char *device_id, const char *keyfile, int *result);
/* a peer's public key */
ed25519_key_t *ed25519_key_from_raw(const unsigned char data[ED25519_KEY_SIZE]);
/* the public key */
void ed25519_key_get_raw(unsigned char data[ED25519_KEY_SIZE], const ed25519_key_t *key);
/* a second handle on the same key; both may be destroyed */
ed25519_key_t *ed25519_key_copy(const ed25519_key_t *key);
void ed25519_key_destroy(ed25519_key_t *key);
void ed25519_sign(unsigned char *signature, size_t signature_len,
                  const unsigned char *data, size_t data_len,
                  const ed25519_key_t *key);
/* 1 if the signature is valid, else 0 */
int ed25519_verify(const unsigned char *signature, size_t signature_len,
                   const unsigned char *data, size_t data_len,
                   const ed25519_key_t *key);

/* ---- SHA-512 and MD5, incremental; final may be followed by reset ---- */
typedef struct sha_ctx_s sha_ctx_t;
sha_ctx_t *sha_init(void);
void sha_update(sha_ctx_t *ctx, const uint8_t *in, int len);
/* writes 64 bytes; len (may be NULL) receives the length */
void sha_final(sha_ctx_t *ctx, uint8_t *out, unsigned int *len);
void sha_reset(sha_ctx_t *ctx);
void sha_destroy(sha_ctx_t *ctx);

typedef struct md5_ctx_s md5_ctx_t;
md5_ctx_t *md5_init(void);
void md5_update(md5_ctx_t *ctx, const uint8_t *in, int len);
/* writes 16 bytes; len (may be NULL) receives the length */
void md5_final(md5_ctx_t *ctx, uint8_t *out, unsigned int *len);
void md5_reset(md5_ctx_t *ctx);
void md5_destroy(md5_ctx_t *ctx);
/* MD5 of a C string as 32 lowercase hex digits; the caller frees it */
char *get_md5(char *string);

#ifdef __cplusplus
}
#endif
#endif
