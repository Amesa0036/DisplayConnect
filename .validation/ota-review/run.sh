#!/usr/bin/env bash
set -euo pipefail
mkdir -p review-evidence
cleanup_package() {
  local package=$1 installed
  installed=$(adb shell pm list packages --user 0 "$package") || return 1
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Ev '^(package:[A-Za-z0-9_.]+)?$'; then return 1; fi
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Fxq "package:$package"; then
    adb uninstall "$package" || return 1
  fi
  installed=$(adb shell pm list packages --user 0 "$package") || return 1
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Ev '^(package:[A-Za-z0-9_.]+)?$'; then return 1; fi
  if printf '%s\n' "$installed" | tr -d '\r' | grep -Fxq "package:$package"; then
    echo "Package still installed: $package" >&2; return 1
  fi
}
verify_evidence() {
python3 - <<'PY_CHECK'
import pathlib,struct,zlib,xml.etree.ElementTree as ET
root=pathlib.Path('review-evidence')
expected={f'ota-review-{s}-{p}.png' for s in range(6) for p in ['top','bottom']}
files=list((root/'screenshots').rglob('*.png'))
assert len(files)==12 and {p.name for p in files}==expected,'Expected exactly12 named PNGs'
for p in files:
 b=p.read_bytes();assert b[:8]==b'\x89PNG\r\n\x1a\n',f'Not PNG: {p.name}'
 pos=8;chunks=[];image=bytearray();width=height=None
 while pos<len(b):
  assert pos+12<=len(b),'Truncated PNG'
  n=struct.unpack('>I',b[pos:pos+4])[0];typ=b[pos+4:pos+8];end=pos+12+n
  assert end<=len(b),'Truncated chunk'
  data=b[pos+8:pos+8+n];crc=struct.unpack('>I',b[pos+8+n:end])[0]
  assert zlib.crc32(typ+data)&0xffffffff==crc,'Bad PNG CRC'
  if typ==b'IHDR':
   assert not chunks and n==13,'Invalid IHDR'
   width,height,depth,color,compression,filtering,interlace=struct.unpack('>IIBBBBB',data)
   assert (width,height)==(720,1280) and depth==8 and color in (2,6) and compression==filtering==interlace==0,'Unexpected screenshot format'
  if typ==b'IDAT':image.extend(data)
  chunks.append(typ);pos=end
  if typ==b'IEND':assert n==0 and pos==len(b),'Invalid IEND';break
 assert chunks[0]==b'IHDR' and chunks[-1]==b'IEND' and image,'Incomplete PNG'
 stride=width*(4 if color==6 else 3)+1;limit=height*stride
 decoder=zlib.decompressobj();raw=decoder.decompress(image,limit+1)
 assert len(raw)==limit and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail,'Invalid PNG pixel data'
 assert all(raw[y*stride] in range(5) for y in range(height)),'Unknown PNG filter'
 print(p.name,len(b),'720x1280 CRC/zlib valid')
xmls=list((root/'androidTest-results').rglob('TEST-*.xml'))
assert len(xmls)==1,'Expected one Android result XML'
x=ET.parse(xmls[0]).getroot()
assert x.get('tests')=='1' and all(x.get(k)=='0' for k in ['failures','errors','skipped']),'Android renderer failed'
assert not any(x.findall('.//'+tag) for tag in ['failure','error','skipped']),'Failed or skipped result'
cases=x.findall('.//testcase');assert len(cases)==1 and cases[0].get('name')=='renderActualFirmwareCardInjectedStates' and cases[0].get('classname')=='com.example.displayconnect.OtaReviewPixelsTest','Unexpected renderer test'
print('All12 PNGs structurally valid and expected renderer passed. Visual inspection still required.')
PY_CHECK
}
finish() {
  local status=$?
  trap - EXIT
  set +e
  adb shell am force-stop com.example.displayconnect > review-evidence/force-stop.txt 2>&1 || status=1
  adb pull /sdcard/Android/data/com.example.displayconnect/files/ review-evidence/screenshots > review-evidence/pull.txt 2>&1 || status=1
  cp -R app/build/outputs/androidTest-results review-evidence/ 2> review-evidence/results-copy.txt || status=1
  verify_evidence > review-evidence/png-check.txt 2>&1 || status=1
  cleanup_package com.example.displayconnect > review-evidence/cleanup-app.txt 2>&1 || status=1
  cleanup_package com.example.displayconnect.test > review-evidence/cleanup-test.txt 2>&1 || status=1
  printf '%s\n' "$status" > review-evidence/exit.txt
  local size
  size=$(du -sb review-evidence | cut -f1) || size=invalid
  if ! [[ "$size" =~ ^[0-9]+$ ]] || [ "$size" -gt 10485760 ]; then
    rm -rf review-evidence; mkdir review-evidence
    printf '%s\n' 'Evidence size exceeded10MiB or was unverifiable; validation failed, no large upload.' > review-evidence/error.txt
    printf '1\n' > review-evidence/exit.txt; status=1
  fi
  exit "$status"
}
trap finish EXIT
cp -R .validation/ota-review/overlay/app/src/debug app/src/
mkdir -p app/src/androidTest/java/com/example/displayconnect
cp .validation/ota-review/overlay/app/src/androidTest/java/com/example/displayconnect/OtaReviewPixelsTest.kt app/src/androidTest/java/com/example/displayconnect/
adb shell wm size 720x1280
adb shell wm density 320
chmod +x gradlew
# Same proven retention flag as94. Pull/check happens before explicit verified cleanup.
./gradlew --no-daemon --no-configuration-cache :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.displayconnect.OtaReviewPixelsTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true > review-evidence/compile-render.txt 2>&1
