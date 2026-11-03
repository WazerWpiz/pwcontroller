#!/usr/bin/env bash
# Sign an unsigned release APK with the project's keystore.
#
#   tools/sign-apk.sh <keystore.jks> <storepass> [keyalias] [in.apk] [out.apk]
#
# The keystore stays outside the repository; only the path is passed here.
set -euo pipefail

KEYSTORE="${1:?usage: sign-apk.sh <keystore> <storepass> [keyalias] [in.apk] [out.apk]}"
STOREPASS="${2:?missing store password}"
KEYALIAS="${3:-}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
APKSIGNER="$BUILD_TOOLS/apksigner"

if [ -z "$KEYALIAS" ]; then
    # Take the first alias of the keystore
    KEYALIAS="$(keytool -list -keystore "$KEYSTORE" -storepass "$STOREPASS" 2>/dev/null \
        | awk -F', ' '/Your keystore contains/ {print $1; exit}' \
        | tr -d ' ')"
fi

IN_APK="${4:-app/build/outputs/apk/release/app-release-unsigned.apk}"
OUT_APK="${5:-app/build/outputs/apk/release/app-release.apk}"

if [ ! -f "$IN_APK" ]; then
    echo "APK not found: $IN_APK" >&2
    exit 1
fi

echo "Signing $IN_APK"
echo "  keystore : $KEYSTORE"
echo "  alias    : $KEYALIAS"

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$STOREPASS" \
    --key-pass "pass:$STOREPASS" \
    --ks-key-alias "$KEYALIAS" \
    --out "$OUT_APK" \
    "$IN_APK"

"$APKSIGNER" verify --verbose "$OUT_APK" | head -20
echo "Done: $OUT_APK"
