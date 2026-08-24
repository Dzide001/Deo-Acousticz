#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
APP_ID="com.droidacoustic.pro"
MAIN_ACTIVITY="com.droidacoustic.pro.MainActivity"

ADB_BIN="${ADB_BIN:-adb}"
ADB_ARGS=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB_ARGS=("-s" "$ANDROID_SERIAL")
fi

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools and ensure adb is in PATH."
  exit 1
fi

if ! "$ADB_BIN" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1; then
  echo "No connected Android device found (or unauthorized)."
  echo "Check with: $ADB_BIN devices"
  exit 1
fi

echo "[1/4] Building debug APK..."
cd "$ANDROID_DIR"
./gradlew :app:assembleDebug

echo "[2/4] Installing debug APK..."
./gradlew :app:installDebug

echo "[3/4] Restarting app..."
"$ADB_BIN" "${ADB_ARGS[@]}" shell am force-stop "$APP_ID" >/dev/null || true

echo "[4/4] Launching app..."
"$ADB_BIN" "${ADB_ARGS[@]}" shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null

echo "Done. App launched: $APP_ID/$MAIN_ACTIVITY"
