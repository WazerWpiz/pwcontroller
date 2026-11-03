#!/usr/bin/env bash
# Build helper for hosts where the Android SDK lives in /opt/android-sdk and the
# Google aapt2 binary is x86_64 while the host is ARM64 (aapt2 is executed through
# qemu-user-static, see the wrapper /opt/x86tools/aapt2).
#
#   tools/build.sh [assembleDebug|assembleRelease|lintDebug|clean]
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")/.."

if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

# aapt2 runs under qemu on ARM64 hosts
if [ -x /opt/x86tools/aapt2 ] && ! grep -q 'aapt2FromMavenOverride' gradle.properties; then
    printf '\nandroid.aapt2FromMavenOverride=/opt/x86tools/aapt2\n' >> gradle.properties
fi

./gradlew "${1:-assembleDebug}"
