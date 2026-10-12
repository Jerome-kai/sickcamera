#!/usr/bin/env bash
# Install the built APK on a running emulator and use it the way a person would.
#
# This exists because "it compiles" and "it opens on a phone" are different
# claims: a green build once produced an APK that would not start. Run by CI on
# an Android 16 emulator before a release is published.
#
#   smoke_test.sh path/to/latent.apk
set -uo pipefail

APK="$1"
PKG=io.github.jeromekai.latent
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${SMOKE_OUT:-smoke}"
mkdir -p "$OUT"

ui() { python3 "$HERE/ui.py" "$@"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
step() { echo; echo "=== $* ==="; }

# Whatever happens, keep the evidence: it is the whole point on a failure.
finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
  adb shell getprop ro.build.version.release > "$OUT/android_version.txt" 2>&1 || true
}
trap finish EXIT

fail() {
  echo "SMOKE TEST FAILED: $*"
  shot failure
  echo "--- our process's crash, from logcat ---"
  adb logcat -d | grep -B1 -A28 "Process: $PKG" | head -90 || true
  echo "--- anything the app logged as an error ---"
  adb logcat -d -s AndroidRuntime:E ActivityManager:E | grep -i "jeromekai" | head -20 || true
  exit 1
}

# Did OUR process crash? Google's system apps on the emulator image log their own
# "FATAL EXCEPTION" lines now and then; only one that names this package counts.
app_crashed() {
  adb logcat -d | grep -A4 "FATAL EXCEPTION" | grep -q "Process: $PKG"
}

# A crash anywhere in the app, whatever screen it was on.
assert_no_crash() {
  if app_crashed; then fail "the app crashed ($1)"; fi
  adb shell pidof "$PKG" > /dev/null || fail "the app is not running ($1)"
}

step "Device"
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi

step "Install (granting the camera and notification permissions)"
adb install -r -g "$APK" || fail "the APK would not install"

step "Launch"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" || fail "could not start MainActivity"
ui wait "Welcome" 60 || fail "the first-run tutorial never appeared"
assert_no_crash "first launch"
shot 01-onboarding

step "Finish the tutorial"
ui tap "Skip" 20 || fail "no Skip button on the tutorial"
ui wait "No API key yet" 60 || ui wait "Shutter" 20 || fail "the camera screen never appeared"
assert_no_crash "camera screen"
shot 02-camera

step "Settings"
ui tap "Settings" 20 || fail "no way to open Settings"
ui wait "API key" 30 || fail "Settings did not open"
assert_no_crash "settings"
shot 03-settings
adb shell input keyevent KEYCODE_BACK
ui wait "Shutter" 20 || fail "could not get back to the camera from Settings"

step "Album"
ui tap "Album" 20 || fail "no way to open the Album"
ui wait "Album Empty" 30 || fail "the Album did not open"
assert_no_crash "album"
shot 04-album
adb shell input keyevent KEYCODE_BACK
ui wait "Shutter" 20 || fail "could not get back to the camera from the Album"

step "A hundred random taps and swipes"
adb shell monkey -p "$PKG" --throttle 120 --pct-syskeys 0 --pct-appswitch 0 -s 7 150 > "$OUT/monkey.txt" 2>&1
assert_no_crash "random input"
shot 05-after-monkey

step "Relaunch after the process is killed"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" || fail "could not restart"
ui wait "Shutter" 40 || fail "the camera screen did not come back on the second launch"
assert_no_crash "second launch"

step "The crash screen itself"
# `am crash` makes the app throw on its main thread, which goes through the same
# uncaught-exception path as a real crash. The report must reach the screen.
adb logcat -c
adb shell am crash "$PKG"
ui wait "stopped unexpectedly" 30 || fail "a crash did not produce the crash screen"
shot 06-crash-screen
ui has "Copy" || fail "the crash screen has no Copy button"

echo
echo "SMOKE TEST PASSED"
