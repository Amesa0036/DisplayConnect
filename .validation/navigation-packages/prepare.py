import hashlib,json,pathlib,subprocess,re
ROOT=pathlib.Path(__file__).resolve().parents[2]
CERT='82e815ddea89f1295d50e24410c3caa7f8a46b6e49fa767a5ad7795806551645'
def tree_manifest(root):
 paths=[p for p in root.rglob('*') if p.is_file() and (str(p.relative_to(root)).startswith(('app/src/main/','app/src/test/')) or str(p.relative_to(root)) in ['app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat'] or str(p.relative_to(root)).startswith('gradle/'))]
 return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
def prepare():
 manifest=json.loads((ROOT/'.validation/navigation-packages/source-hashes.json').read_text())
 for kind,sha in [('candidate',subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()),('rescue','77bee6d4c2484289ed4f6873da4d7b4799355a6a')]:
  root=pathlib.Path(kind)
  assert subprocess.check_output(['git','-C',kind,'rev-parse','HEAD'],text=True).strip()==sha
  assert not (root/'app/src/debug').exists(),'Unexpected debug source/overlay'
  assert not (root/'app/src/release').exists(),'Unexpected release source'
  assert tree_manifest(root)==manifest[kind],kind+' source manifest mismatch'
  for p in (root/'app/src/main').rglob('*'):
   if not p.is_file():continue
   assert p.suffix.lower() not in ['.jks','.keystore','.apk','.bin','.p12','.pem','.gpx','.db','.csv'],str(p)
   b=p.read_bytes()
   assert not re.search(rb'BEGIN (?:RSA |EC )?PRIVATE KEY',b),str(p)
  gradle=(root/'app/build.gradle.kts').read_text()
  assert 'applicationId = "com.example.displayconnect"' in gradle
  if kind=='rescue':
   assert 'versionCode = 22' in gradle and 'versionName = "3.19-hecate-position-LOCAL"' in gradle
   (root/'app/build.gradle.kts').write_text(gradle.replace('versionCode = 22','versionCode = 25').replace('3.19-hecate-position-LOCAL','3.19-position-rescue-LOCAL'))
   changed=subprocess.check_output(['git','-C',kind,'diff','--name-only'],text=True).splitlines()
   assert changed==['app/build.gradle.kts'],'Rescue parity changed outside version metadata'
   assert (root/'app/src/main/java/com/example/displayconnect/network/OtaTransferService.kt').exists()
  else:assert 'versionCode = 24' in gradle and 'versionName = "3.20-hecate-nav-LOCAL"' in gradle
 print('Pinned production trees verified. Rescue only version metadata changed. No overlay copied.')
if __name__=='__main__':prepare()
