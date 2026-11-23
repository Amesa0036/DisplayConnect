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
check_ota_lock_released() {
  python3 - "$1" <<'PY_LOCK'
import re

def ota_lock_active(dump):
    lines=dump.splitlines()
    starts=[i for i,line in enumerate(lines) if re.fullmatch(r'Wake Locks: size=\d+',line)]
    ends=[i for i,line in enumerate(lines) if re.fullmatch(r'Suspend Blockers: size=\d+',line)]
    if len(starts)!=1 or len(ends)!=1 or starts[0]>=ends[0]:
        raise ValueError('Missing/ambiguous current wake-lock section')
    start,end=starts[0],ends[0]
    rows=[line for line in lines[start+1:end] if line.strip()]
    if len(rows)!=int(lines[start].split('=')[1]):
        raise ValueError('Wake-lock count does not match rows')
    tags=[]
    for row in rows:
        match=re.fullmatch(r"  [A-Z_]+ +'(.*)'(?: .*)?",row)
        if not match:raise ValueError('Unknown current wake-lock row')
        tags.append(match[1])
    return 'DisplayConnect::Ota' in tags

import sys
try:
    active=ota_lock_active(open(sys.argv[1]).read())
    print('ota_lock_active='+str(active))
    sys.exit(1 if active else 0)
except (ValueError,OSError) as error:
    print('Cannot verify current wake locks: '+str(error),file=sys.stderr)
    sys.exit(2)
PY_LOCK
}
# Preserve diagnostics and clean the emulator even when assertions/captures fail.
finish() {
  local status=$?
  trap - EXIT
  set +e
  mkdir -p validation-evidence/final
  adb logcat -d -t 600 > validation-evidence/final/logcat.txt 2>&1
  adb shell dumpsys activity services com.example.displayconnect > validation-evidence/final/services-before-stop.txt 2>&1
  adb shell am force-stop com.example.displayconnect || status=1
  adb shell dumpsys activity services com.example.displayconnect > validation-evidence/final/services-after-stop.txt 2>&1
  adb shell dumpsys power > validation-evidence/final/power-after-stop.txt 2>&1
  if grep -q "ServiceRecord.*com.example.displayconnect.*OtaTransferService" validation-evidence/final/services-after-stop.txt; then status=1; fi
  check_ota_lock_released validation-evidence/final/power-after-stop.txt > validation-evidence/final/lock-check.txt 2>&1 || status=1
  cleanup_package com.example.displayconnect || status=1
  cleanup_package com.example.displayconnect.test || status=1
  echo "exit=$status" > validation-evidence/final/exit.txt
  exit "$status"
}
trap finish EXIT
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
  if [ "$variant" = ota ]; then
    TEST="$TEST#syntheticTaskSurvivesScreenOffAndActivityRecreation"
  fi
  set +e
  ./gradlew --no-daemon :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.displayconnect.$TEST" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true > "validation-evidence/$variant-gradle.txt" 2>&1
  result=$?
  set -e
  if [ "$variant" = ota ] && [ "$result" = 0 ]; then
    # Permission revocation can kill the app. Do it only between instrumentations.
    adb shell pm revoke com.example.displayconnect android.permission.POST_NOTIFICATIONS
    adb shell dumpsys package com.example.displayconnect > validation-evidence/ota-permission-before-blocked.txt
    set +e
    adb shell am instrument -w -r -e class 'com.example.displayconnect.OtaValidationTest#blockedNotificationAndSyntheticFailureReleaseOwnership' com.example.displayconnect.test/androidx.test.runner.AndroidJUnitRunner > validation-evidence/ota-blocked-instrumentation.txt 2>&1
    result=$?
    set -e
    # am instrument may exit zero even when a test fails. Require the test runner verdict.
    if ! grep -Eq '^OK \(1 test\)' validation-evidence/ota-blocked-instrumentation.txt; then result=1; fi
    if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' validation-evidence/ota-blocked-instrumentation.txt; then result=1; fi
    adb shell pm grant com.example.displayconnect android.permission.POST_NOTIFICATIONS
  fi
  mkdir -p "validation-evidence/$variant"
  if [ -d app/build/outputs/androidTest-results/connected ]; then cp -R app/build/outputs/androidTest-results/connected "validation-evidence/$variant/results"; fi
  adb shell pm path com.example.displayconnect > "validation-evidence/$variant/package-after-tests.txt"
  adb logcat -d -t 600 > "validation-evidence/$variant/logcat.txt" 2>&1
  adb shell dumpsys power > "validation-evidence/$variant/power-after-tests.txt" 2>&1
  adb pull /sdcard/Android/data/com.example.displayconnect/files/ "validation-evidence/$variant/screenshots" > "validation-evidence/$variant/pull.txt" 2>&1
  # Evidence is mandatory: never call a run complete if captures disappeared.
  if [ "$variant" = position ]; then
    CAPTURES='position-confirm.png position-valid-root.png position-valid-synthetic.png position-stale.png position-rejected.png'
  else
    CAPTURES='ota-notification.png ota-after-test.png ota-diagnostics.txt ota-blocked-complete.png'
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
    adb shell dumpsys power > validation-evidence/ota-power-after-force-stop.txt
    check_ota_lock_released validation-evidence/ota-power-after-force-stop.txt > validation-evidence/ota-lock-after-force-stop.txt 2>&1
  fi
  git apply --reverse ".validation/hecate/payload/$variant.patch"
  rm -f "app/src/androidTest/java/com/example/displayconnect/${TEST%%#*}.kt" app/src/debug/java/com/example/displayconnect/ValidationActivity.kt app/src/debug/AndroidManifest.xml
  cleanup_package com.example.displayconnect
  cleanup_package com.example.displayconnect.test
  echo "variant=$variant phase=cleaned" | tee -a validation-evidence/phases.txt
  # No APK, Gradle cache, location history, private traces or signing files retained.
  test "$(du -sb validation-evidence | cut -f1)" -le 10485760
done
