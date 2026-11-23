#!/usr/bin/env bash
set -euo pipefail
BASE=85a63d3aa61c3411d5663bb1e4ca679310949aca
mkdir -p validation-evidence
chmod +x gradlew
for variant in position ota; do
  git apply --check ".validation/hecate/payload/$variant.patch"
  git apply ".validation/hecate/payload/$variant.patch"
  cp -R .validation/hecate/overlay/app/src/debug app/src/
  mkdir -p app/src/androidTest/java/com/example/displayconnect
  if [ "$variant" = position ]; then TEST=PositionValidationTest; else TEST=OtaValidationTest; fi
  cp ".validation/hecate/overlay/app/src/androidTest/java/com/example/displayconnect/$TEST.kt" app/src/androidTest/java/com/example/displayconnect/
  adb shell wm size 720x1280
  adb shell wm density 320
  set +e
  ./gradlew --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.displayconnect.$TEST" > "validation-evidence/$variant-gradle.txt" 2>&1
  result=$?
  set -e
  mkdir -p "validation-evidence/$variant"
  adb pull /sdcard/Android/data/com.example.displayconnect/files/ "validation-evidence/$variant/screenshots" || true
  if [ -d app/build/outputs/androidTest-results/connected ]; then cp -R app/build/outputs/androidTest-results/connected "validation-evidence/$variant/results"; fi
  # Fail closed before switching variants. No rerun is authorized.
  test "$result" = 0
  if [ "$variant" = ota ]; then
    adb shell am force-stop com.example.displayconnect
    adb shell dumpsys activity services com.example.displayconnect > validation-evidence/ota-after-force-stop.txt
    adb shell dumpsys power | grep 'DisplayConnect::Ota' > validation-evidence/ota-lock-after-force-stop.txt || true
    test ! -s validation-evidence/ota-lock-after-force-stop.txt
  fi
  git apply --reverse ".validation/hecate/payload/$variant.patch"
  rm -f "app/src/androidTest/java/com/example/displayconnect/$TEST.kt" app/src/debug/java/com/example/displayconnect/ValidationActivity.kt app/src/debug/AndroidManifest.xml
  adb uninstall com.example.displayconnect
  adb uninstall com.example.displayconnect.test || true
  # No APK, Gradle cache, location history, private traces or signing files retained.
  test "$(du -sb validation-evidence | cut -f1)" -le 10485760
done
