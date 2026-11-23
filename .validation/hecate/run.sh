#!/usr/bin/env bash
set -euo pipefail
BASE=85a63d3aa61c3411d5663bb1e4ca679310949aca
mkdir -p validation-evidence
# AGP normally uninstalls both APKs after connected tests, deleting external files.
# Keep them only until evidence and post-test checks are complete, then remove.
cleanup_package() {
  local package=$1 installed
  installed=$(adb shell pm list packages --user 0 "$package")
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Fxq "package:$package"; then
    adb uninstall "$package"
  fi
  # A failed uninstall is never silently treated as success.
  installed=$(adb shell pm list packages --user 0 "$package")
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Fxq "package:$package"; then
    echo "Package still installed after cleanup: $package" >&2
    return 1
  fi
}
chmod +x gradlew
for variant in position ota; do
  echo "variant=$variant phase=begin" | tee -a validation-evidence/phases.txt
  git apply --check ".validation/hecate/payload/$variant.patch"
  git apply ".validation/hecate/payload/$variant.patch"
  cp -R .validation/hecate/overlay/app/src/debug app/src/
  mkdir -p app/src/androidTest/java/com/example/displayconnect
  if [ "$variant" = position ]; then TEST=PositionValidationTest; else TEST=OtaValidationTest; fi
  cp ".validation/hecate/overlay/app/src/androidTest/java/com/example/displayconnect/$TEST.kt" app/src/androidTest/java/com/example/displayconnect/
  adb shell wm size 720x1280
  adb shell wm density 320
  set +e
  ./gradlew --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.displayconnect.$TEST" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true > "validation-evidence/$variant-gradle.txt" 2>&1
  result=$?
  set -e
  mkdir -p "validation-evidence/$variant"
  if [ -d app/build/outputs/androidTest-results/connected ]; then cp -R app/build/outputs/androidTest-results/connected "validation-evidence/$variant/results"; fi
  adb shell pm path com.example.displayconnect > "validation-evidence/$variant/package-after-tests.txt"
  adb pull /sdcard/Android/data/com.example.displayconnect/files/ "validation-evidence/$variant/screenshots" > "validation-evidence/$variant/pull.txt" 2>&1
  # Evidence is mandatory: never call a run complete if captures disappeared.
  if [ "$variant" = position ]; then
    CAPTURES='position-confirm.png position-valid-synthetic.png position-stale.png position-rejected.png'
  else
    CAPTURES='ota-notification.png'
  fi
  for capture in $CAPTURES; do
    test "$(find "validation-evidence/$variant/screenshots" -type f -name "$capture" -size +0c | wc -l)" = 1
  done
  echo "variant=$variant phase=evidence-copied" | tee -a validation-evidence/phases.txt
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
  cleanup_package com.example.displayconnect
  cleanup_package com.example.displayconnect.test
  echo "variant=$variant phase=cleaned" | tee -a validation-evidence/phases.txt
  # No APK, Gradle cache, location history, private traces or signing files retained.
  test "$(du -sb validation-evidence | cut -f1)" -le 10485760
done
