#!/bin/sh
# Builds the native host tests (macOS or Linux, cc + OpenSSL 3) into $1. Run by the Gradle
# task buildHostNativeTests before the unit tests; NativeHostTest runs the results.
#   crypto_vectors       crypto rewrite (src/main/cpp/crypto) behind the vector dump
#   libairrelay_playfair the playfair helper program, host build of the same sources
#   playfair_pipe_test   the process call (src/main/cpp/playfair/playfair_pipe.c)
# OpenSSL: AIRRELAY_HOST_OPENSSL=<prefix>, else Homebrew's openssl@3, else pkg-config.
set -eu
OUT=$1
HERE=$(cd "$(dirname "$0")" && pwd)
CPP=$HERE/../../main/cpp
PF=$CPP/third_party/UxPlay/lib/playfair
OSSL=${AIRRELAY_HOST_OPENSSL:-}
if [ -z "$OSSL" ]; then
  for p in /opt/homebrew/opt/openssl@3 /usr/local/opt/openssl@3; do [ -d "$p" ] && OSSL=$p && break; done
fi
if [ -n "$OSSL" ]; then
  OSSL_CFLAGS="-I$OSSL/include"; OSSL_LIBS="-L$OSSL/lib -lcrypto"
else
  OSSL_CFLAGS=$(pkg-config --cflags libcrypto); OSSL_LIBS=$(pkg-config --libs libcrypto)
fi
[ -f "$PF/playfair.c" ] || { echo "playfair sources missing: git submodule update --init" >&2; exit 1; }
mkdir -p "$OUT"
STRICT="-std=c17 -Wall -Wextra -Werror"
# shellcheck disable=SC2086
cc $STRICT -D_DEFAULT_SOURCE -D_DARWIN_C_SOURCE -I"$CPP/crypto" $OSSL_CFLAGS "$HERE/crypto_vectors.c" "$CPP/crypto/airrelay_crypto.c" $OSSL_LIBS -o "$OUT/crypto_vectors"
cc $STRICT -D_DEFAULT_SOURCE -I"$PF" -c "$CPP/playfair/playfair_helper.c" -o "$OUT/playfair_helper.o"
for f in hand_garble modified_md5 omg_hax playfair sap_hash; do cc -w -c "$PF/$f.c" -o "$OUT/$f.o"; done
cc "$OUT/playfair_helper.o" "$OUT/hand_garble.o" "$OUT/modified_md5.o" "$OUT/omg_hax.o" "$OUT/playfair.o" "$OUT/sap_hash.o" -lm -o "$OUT/libairrelay_playfair"
cc $STRICT -D_DEFAULT_SOURCE -D_DARWIN_C_SOURCE -I"$CPP/playfair" "$HERE/playfair_pipe_test.c" "$CPP/playfair/playfair_pipe.c" -lpthread -o "$OUT/playfair_pipe_test"
echo "host native tests built in $OUT"
