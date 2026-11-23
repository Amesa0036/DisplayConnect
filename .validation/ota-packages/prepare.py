import hashlib,json,pathlib,subprocess,re
ROOT=pathlib.Path(__file__).resolve().parents[2]
CERT='82e815ddea89f1295d50e24410c3caa7f8a46b6e49fa767a5ad7795806551645'
def tree_manifest(root):
 paths=[p for p in root.rglob('*') if p.is_file() and (str(p.relative_to(root)).startswith('app/src/main/') or str(p.relative_to(root)) in ['app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat'] or str(p.relative_to(root)).startswith('gradle/'))]
 return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
def prepare():
 manifest=json.loads((ROOT/'.validation/ota-packages/source-hashes.json').read_text())
 for kind,sha in [('candidate','426d7109193a3b3e8b98654d268a9c7903a54323'),('rescue','85a63d3aa61c3411d5663bb1e4ca679310949aca')]:
  root=pathlib.Path(kind)
  assert subprocess.check_output(['git','-C',kind,'rev-parse','HEAD'],text=True).strip()==sha
  assert not (root/'app/src/debug').exists(),'Unexpected debug source/overlay'
  assert not (root/'app/src/release').exists(),'Unexpected release source'
  assert tree_manifest(root)==manifest[kind],kind+' source manifest mismatch'
  for p in (root/'app/src/main').rglob('*'):
   if not p.is_file():continue
   assert p.suffix.lower() not in ['.jks','.keystore','.apk','.bin','.p12','.pem','.gpx','.db','.csv'],str(p)
   b=p.read_bytes()
   assert not re.search(rb'BEGIN (?:RSA |EC )?PRIVATE KEY|alexmesa@gmail\.com|34633338256',b),str(p)
  gradle=(root/'app/build.gradle.kts').read_text()
  assert 'applicationId = "com.example.displayconnect"' in gradle
  if kind=='rescue':
   assert 'versionCode = 19' in gradle and 'versionName = "3.17-hecate-preamble-LOCAL"' in gradle
   (root/'app/build.gradle.kts').write_text(gradle.replace('versionCode = 19','versionCode = 21').replace('3.17-hecate-preamble-LOCAL','3.17-ota-rescue-LOCAL'))
   changed=subprocess.check_output(['git','-C',kind,'diff','--name-only'],text=True).splitlines()
   assert changed==['app/build.gradle.kts'],'Rescue parity changed outside version metadata'
   assert not (root/'app/src/main/java/com/example/displayconnect/network/OtaTransferService.kt').exists()
  else:assert 'versionCode = 20' in gradle and 'versionName = "3.18-hecate-ota-LOCAL"' in gradle
 print('Pinned production trees verified. Rescue only version metadata changed. No overlay copied.')
if __name__=='__main__':prepare()
