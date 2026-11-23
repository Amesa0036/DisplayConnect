#!/usr/bin/env bash
set -euo pipefail
umask 077
KEYDIR="$RUNNER_TEMP/navigation-package-signing"
mkdir -p "$KEYDIR"
cleanup() {
  status=$?
  trap - EXIT
  # Metadata only; no keystore, secrets or APK contents in failure diagnostics.
  if ! python3 control/.validation/navigation-packages/verify.py --diagnose "$KEYDIR/staging" diagnostics/package-inventory.json; then
    echo 'Could not preserve package inventory' >&2
    test "$status" -ne 0 || status=1
  fi
  rm -rf "$KEYDIR"
  exit "$status"
}
trap cleanup EXIT
KEY="$KEYDIR/debug.keystore"
test -n "${DEBUG_KEYSTORE_BASE64:-}"
printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode > "$KEY"
unset DEBUG_KEYSTORE_BASE64
keytool -exportcert -keystore "$KEY" -storepass android -alias androiddebugkey -file "$KEYDIR/cert.der"
test "$(sha256sum "$KEYDIR/cert.der" | cut -d ' ' -f1)" = 82e815ddea89f1295d50e24410c3caa7f8a46b6e49fa767a5ad7795806551645
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
APKSIGNER=$(find "$SDK/build-tools" -type f -name apksigner | sort -V | tail -n1)
test -n "$APKSIGNER"
APKTOOL="$SDK/cmdline-tools/latest/bin/apkanalyzer"
test -x "$APKTOOL"
mkdir -p "$KEYDIR/staging"
for kind in rescue candidate; do
  INPUT="$kind/app/build/outputs/apk/debug/app-debug.apk"
  test -s "$INPUT"
  OUT="$KEYDIR/staging/$kind.apk"
  "$APKSIGNER" sign --ks "$KEY" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$OUT" "$INPUT"
  "$APKSIGNER" verify --verbose --print-certs "$OUT" > "$KEYDIR/staging/$kind-signature.txt"
  "$APKTOOL" manifest print "$OUT" > "$KEYDIR/staging/$kind-manifest.xml"
  "$APKTOOL" files list "$OUT" > "$KEYDIR/staging/$kind-files.txt"
  "$APKTOOL" dex packages --defined-only "$OUT" > "$KEYDIR/staging/$kind-dex.txt"
done
python3 control/.validation/navigation-packages/verify.py "$KEYDIR/staging"
# Publish neither binary unless the whole pair passes verification.
test ! -e verified-pair
mkdir verified-pair
for kind in rescue candidate; do
  cp "$KEYDIR/staging/$kind.apk" verified-pair/
  for name in signature.txt manifest.xml files.txt dex.txt; do
    cp "$KEYDIR/staging/$kind-$name.gz" verified-pair/
  done
done
cp "$KEYDIR/staging/verification.json" "$KEYDIR/staging/SHA256SUMS" verified-pair/
