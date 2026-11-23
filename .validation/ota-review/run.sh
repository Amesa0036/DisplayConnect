#!/usr/bin/env bash
set -euo pipefail
mkdir -p review-evidence
finish() {
  result=$?
  trap - EXIT
  set +e
  adb shell am force-stop com.example.displayconnect
  adb pull /sdcard/Android/data/com.example.displayconnect/files/ review-evidence/screenshots
  cp -R app/build/outputs/androidTest-results review-evidence/ 2>/dev/null
  printf '%s\n' "$result" > review-evidence/exit.txt
  adb uninstall com.example.displayconnect
  adb uninstall com.example.displayconnect.test
  size=$(du -sb review-evidence | cut -f1)
  if [ "$size" -gt 10485760 ]; then rm -rf review-evidence; mkdir review-evidence; printf '%s\n' 'Evidence exceeded10MiB; validation failed, no large upload.' > review-evidence/error.txt; result=1; fi
  exit "$result"
}
trap finish EXIT
cp -R .validation/ota-review/overlay/app/src/debug app/src/
mkdir -p app/src/androidTest/java/com/example/displayconnect
cp .validation/ota-review/overlay/app/src/androidTest/java/com/example/displayconnect/OtaReviewPixelsTest.kt app/src/androidTest/java/com/example/displayconnect/
adb shell wm size 720x1280
adb shell wm density 320
chmod +x gradlew
./gradlew --no-daemon --no-configuration-cache :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.displayconnect.OtaReviewPixelsTest > review-evidence/compile-render.txt 2>&1
