#!/usr/bin/env bash
# Reset every attached phone to a clean state: remove all federated-learning data,
# calibration records and equipment (bench/test captures must never become training data),
# reinstall the current debug APK and relaunch the app.
#
# Usage: tools/reset-phones.sh [--keep-equipment] [serial-or-ip:port ...]
# With no device arguments every device listed by `adb devices` is reset.
set -u
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# adb.exe needs Windows-style paths; Git Bash gives POSIX ones.
ROOT="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
ADB="${ADB:-$ROOT/../tools/android-sdk/platform-tools/adb.exe}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.jugaad.agent.debug"
KEEP_EQUIPMENT=0
DEVICES=()
for a in "$@"; do
  case "$a" in
    --keep-equipment) KEEP_EQUIPMENT=1 ;;
    *) DEVICES+=("$a") ;;
  esac
done
if [ ${#DEVICES[@]} -eq 0 ]; then
  mapfile -t DEVICES < <("$ADB" devices | tail -n +2 | awk '$2=="device"{print $1}')
fi
[ -f "$APK" ] || { echo "APK not found: $APK (run ./gradlew :app:assembleDebug)"; exit 1; }
for d in "${DEVICES[@]}"; do
  echo "=== $d ==="
  "$ADB" -s "$d" shell am force-stop "$PKG"
  # One quoted string so the device shell sees the whole run-as command intact.
  if [ $KEEP_EQUIPMENT -eq 1 ]; then
    "$ADB" -s "$d" shell "run-as $PKG rm -rf files/fl && run-as $PKG sh -c 'rm -f files/assets/*/calibration.json'"
  else
    "$ADB" -s "$d" shell "run-as $PKG rm -rf files/fl files/assets files/config"
  fi
  "$ADB" -s "$d" shell "run-as $PKG ls files"
  # Drop any live WiFi Direct group left by a previous session (persistent groups stay remembered).
  # Over wireless adb the link dies the moment WiFi goes down, so the cycle must run detached
  # on the phone and adb has to reconnect afterwards.
  "$ADB" -s "$d" shell "nohup sh -c 'sleep 1; svc wifi disable; sleep 4; svc wifi enable' >/dev/null 2>&1 &"
  case "$d" in
    *:*)
      sleep 12
      for i in 1 2 3 4 5 6 7 8 9 10; do
        "$ADB" connect "$d" 2>&1 | grep -q "connected to" && break
        sleep 5
      done
      "$ADB" -s "$d" wait-for-device
      ;;
    *) sleep 8 ;;
  esac
  "$ADB" -s "$d" install -r -g "$APK" | tail -1
  "$ADB" -s "$d" shell am start -W -n "$PKG/com.jugaad.agent.MainActivity" | grep -E "^Status"
  sleep 6
  "$ADB" -s "$d" logcat -d -s JUGAAD:* | grep -E "heads loaded|FATAL" | tail -2
done
