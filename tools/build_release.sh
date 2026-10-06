#!/bin/bash
# Store build: signed with the upload key, checked before it is handed out.
#
#   tools/build_release.sh
#
# Reads the keystore password from the macOS keychain (service airrelay-upload-keystore,
# account kevin). Fails if the upload key is missing instead of falling back to the debug
# key, if the APK is signed with anything else, if a test file for the App Tester ended up
# inside, or if Amazon's public key (AppstoreAuthenticationKey.pem) is missing.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

# SHA-256 of the upload certificate (keytool -list -v, apksigner --print-certs)
UPLOAD_CERT_SHA256="9b305bbdfdaba06171cdfb7c8082ff14cfac50a9598f42bd4ea621dec7e449c8"

# Amazon's public key: dropped into ../store/amazon/ from the developer console, taken over
# into the app's assets here (it is public, it belongs into the repository)
PEM_SRC="${AIRRELAY_PEM_SRC:-../store/amazon/AppstoreAuthenticationKey.pem}"
PEM_DST=receiver/src/main/assets/AppstoreAuthenticationKey.pem
if [ -f "$PEM_SRC" ] && ! cmp -s "$PEM_SRC" "$PEM_DST"; then
    mkdir -p "$(dirname "$PEM_DST")"
    cp "$PEM_SRC" "$PEM_DST"
    echo "taken over: $PEM_SRC -> $PEM_DST (commit it)"
fi
if [ ! -f "$PEM_DST" ] && [ "${AIRRELAY_ALLOW_MISSING_PEM:-0}" != 1 ]; then
    echo "FAIL: $PEM_DST is missing. Put the key from the Amazon console into $PEM_SRC." >&2
    echo "      (Test build without it: AIRRELAY_ALLOW_MISSING_PEM=1 $0)" >&2
    exit 1
fi

AIRRELAY_UPLOAD_KEYSTORE_PASSWORD="$(security find-generic-password -s airrelay-upload-keystore -a kevin -w)"
export AIRRELAY_UPLOAD_KEYSTORE_PASSWORD
export AIRRELAY_REQUIRE_UPLOAD_KEY=1

./gradlew :receiver:assembleRelease --console=plain

APK=receiver/build/outputs/apk/release/receiver-release.apk
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"

certs="$("$APKSIGNER" verify --print-certs "$APK")"
if ! grep -q "certificate SHA-256 digest: $UPLOAD_CERT_SHA256" <<<"$certs"; then
    echo "FAIL: $APK is not signed with the upload key" >&2
    echo "$certs" >&2
    exit 1
fi
if unzip -l "$APK" | grep -qi "sdktester"; then
    echo "FAIL: an App Tester file is inside the release APK" >&2
    exit 1
fi
if ! unzip -l "$APK" | grep -q "assets/AppstoreAuthenticationKey.pem"; then
    if [ "${AIRRELAY_ALLOW_MISSING_PEM:-0}" = 1 ]; then
        echo "WARNING: no AppstoreAuthenticationKey.pem in the APK. Test build only, not for the store." >&2
    else
        echo "FAIL: AppstoreAuthenticationKey.pem is not inside $APK" >&2
        exit 1
    fi
fi

ls -l "$APK"
echo "OK: signed with the upload key ($UPLOAD_CERT_SHA256)"
./gradlew --stop >/dev/null
