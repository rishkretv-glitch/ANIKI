#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v gradle >/dev/null 2>&1; then
  echo 'ERROR: Install Gradle 8.13 or build with GitHub Actions .github/workflows/build-debug-apk.yml' >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  echo 'ERROR: Configure ANDROID_HOME or ANDROID_SDK_ROOT (Android SDK API 36).' >&2
  exit 1
fi
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
printf '\nAPK: %s\n' "$PWD/app/build/outputs/apk/debug/app-debug.apk"
