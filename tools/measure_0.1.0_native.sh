#!/bin/sh
# Re-measures the frozen native vectors from the code that shipped in 0.1.0: UxPlay's
# lib/crypto.c and lib/playfair/ from the submodule, compiled for the host (macOS, clang,
# OpenSSL 3 from Homebrew) and called only through their headers. The source of crypto.c
# is compiled, not read: AirRelay's replacement (receiver/src/main/cpp/crypto/) was written
# without it. Output goes to stdout or the given directory; compare with
# receiver/src/test/resources/native/*-0.1.0.txt. Measured on 2026-10-15 with OpenSSL 3.6.3.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$(mktemp -d)}
OSSL=${AIRRELAY_HOST_OPENSSL:-/opt/homebrew/opt/openssl@3}
U=$ROOT/receiver/src/main/cpp/third_party/UxPlay/lib
T=$ROOT/receiver/src/test/native
mkdir -p "$OUT"
cc -w -I"$U" -I"$OSSL/include" "$T/crypto_vectors.c" "$U/crypto.c" "$U/utils.c" \
   -L"$OSSL/lib" -lcrypto -o "$OUT/crypto_vectors_0.1.0"
cc -w -I"$U/playfair" "$T/playfair_vectors.c" "$U/playfair/hand_garble.c" "$U/playfair/modified_md5.c" \
   "$U/playfair/omg_hax.c" "$U/playfair/playfair.c" "$U/playfair/sap_hash.c" -o "$OUT/playfair_vectors_0.1.0"
# RFC 8032 test 1 seed, wrapped into PKCS#8 PEM (same as NativeHostTest.rfc8032Pem)
SEED=$(grep -v '^#' "$ROOT/receiver/src/test/resources/native/rfc8032-test1.seed.hex" | tr -d ' \n')
{ echo "-----BEGIN PRIVATE"" KEY-----"; echo "302e020100300506032b657004220420$SEED" | xxd -r -p | base64; echo "-----END PRIVATE"" KEY-----"; } > "$OUT/rfc.pem"
"$OUT/crypto_vectors_0.1.0" "$OUT/scratch.key" "$OUT/rfc.pem" > "$OUT/crypto-vectors-0.1.0.txt" 2>/dev/null
"$OUT/playfair_vectors_0.1.0" > "$OUT/playfair-vectors-0.1.0.txt"
echo "$OUT"
