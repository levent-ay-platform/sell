#!/usr/bin/env bash
set -euo pipefail
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
mkdir -p screenshots
adb shell wm size 786x1746
adb shell wm density 320
sleep 4
adb shell am force-stop tn.malak.app || true
focus=''
for attempt in 1 2 3; do
  adb shell input keyevent KEYCODE_BACK || true
  adb shell am start -W -n tn.malak.app/.MainActivity || true
  sleep 5
  focus="$(adb shell dumpsys window | grep -m1 'mCurrentFocus=' || true)"
  echo "FOCUS_CHECK_ATTEMPT_${attempt}: ${focus}"
  case "$focus" in
    *tn.malak.app*) break ;;
  esac
done
case "$focus" in
  *tn.malak.app*) : ;;
  *)
    echo 'MALAK_SCREEN_NOT_VISIBLE: cannot accept screenshot proof'
    adb logcat -d -t 220 | tail -80 || true
    exit 1
    ;;
esac
adb exec-out screencap -p > screenshots/locked-home-393dp.png
adb shell input swipe 250 650 410 575 400
sleep 2
focus="$(adb shell dumpsys window | grep -m1 'mCurrentFocus=' || true)"
case "$focus" in
  *tn.malak.app*) : ;;
  *) echo "MALAK_SCREEN_LOST_FOCUS_AFTER_SWIPE: $focus"; exit 1 ;;
esac
adb exec-out screencap -p > screenshots/immersive-map-393dp.png
test "$(wc -c < screenshots/locked-home-393dp.png)" -gt 100000
test "$(wc -c < screenshots/immersive-map-393dp.png)" -gt 100000
echo CAPTURE_ANDROID_UI_OK
