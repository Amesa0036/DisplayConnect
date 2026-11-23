import sys,pathlib,re,hashlib,json,xml.etree.ElementTree as ET,zipfile,gzip,shutil
CAP=50*1024*1024
KINDS=('rescue','candidate')
RAW=[f'{k}-{t}.{ext}' for k in KINDS for t,ext in [('signature','txt'),('manifest','xml'),('files','txt'),('dex','txt')]]
FINAL=[f'{k}.apk' for k in KINDS]+[n+'.gz' for n in RAW]+['verification.json','SHA256SUMS']
def inventory(stage):
 return [{'name':p.name,'bytes':p.stat().st_size} for p in sorted(stage.iterdir()) if p.is_file()]
def diagnose(stage,destination):
 d={'schema':1,'final_limit_bytes':CAP,'files':inventory(stage) if stage.exists() else [],'verification_written':(stage/'verification.json').exists(),'output_directory_exists':pathlib.Path('verified-pair').exists()}
 # Only filenames and sizes from the isolated staging folder, never file contents or keys.
 b=json.dumps(d,indent=2).encode();assert len(b)<=65536
 destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(b)
CERT='82e815ddea89f1295d50e24410c3caa7f8a46b6e49fa767a5ad7795806551645'
A='{http://schemas.android.com/apk/res/android}'
def candidate_commit():
 return __import__('subprocess').check_output(['git','-C','candidate','rev-parse','HEAD'],text=True).strip()
def verify(stage):
 report={'certificate_sha256':CERT,'production_only':True,'sources':{'candidate':candidate_commit(),'rescue':'426d7109193a3b3e8b98654d268a9c7903a54323'},'packages':[]}
 for kind,code,name in [('rescue',23,'3.18-position-rescue-LOCAL'),('candidate',22,'3.19-hecate-position-LOCAL')]:
  p=stage/f'{kind}.apk';b=p.read_bytes();assert 0<len(b)<24*1024*1024,'APK size cap'
  sig=(stage/f'{kind}-signature.txt').read_text();hashes=re.findall(r'^.*certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$',sig,re.M)
  assert len(hashes)==1 and hashes[0].lower()==CERT,'APK signer mismatch'
  x=ET.parse(stage/f'{kind}-manifest.xml').getroot()
  assert x.get('package')=='com.example.displayconnect' and x.get(A+'versionCode')==str(code) and x.get(A+'versionName')==name,'APK identity/version mismatch'
  activities=[e.get(A+'name','') for e in x.findall('.//activity')]
  assert any(s.endswith('.MainActivity') for s in activities),'Missing real activity'
  forbidden=['OtaReviewActivity','ValidationActivity','OtaReviewPixelsTest','FirmwareChannelsUiTest']
  dex=(stage/f'{kind}-dex.txt').read_text()
  assert not any(s in dex or any(s in a for a in activities) for s in forbidden),'Synthetic test component in APK'
  services=[e.get(A+'name','') for e in x.findall('.//service')]
  assert any(s.endswith('.OtaTransferService') for s in services)==True,'Service parity mismatch'
  with zipfile.ZipFile(p) as z:
   assert z.testzip() is None
   for n in z.namelist():
    low=n.lower();assert not low.endswith(('.keystore','.jks','.p12','.pem','.gpx','.csv','.db')),n
    raw=z.read(n)
    if low.endswith('.bin'):assert n=='DebugProbesKt.bin' and hashlib.sha256(raw).hexdigest()=='4d13bd92f86a0b1415062b8ed57655c0738ccfbdf0786c1d730d988b27fd4400','Unexpected binary payload'
    assert not re.search(rb'BEGIN (?:RSA |EC )?PRIVATE KEY|alexmesa@gmail\.com|34633338256',raw),'Personal data or key material in APK'
  report['packages'].append({'kind':kind,'application_id':'com.example.displayconnect','version_code':code,'version_name':name,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()})
 report['raw_inventory']=inventory(stage)
 print(json.dumps({'raw_inventory':report['raw_inventory']},sort_keys=True))
 # Verify the original analyzer output first, then losslessly compress it.
 # Never compress APKs again, change the APKs, or weaken the final 50MiB cap.
 for name in RAW:
  src=stage/name
  with src.open('rb') as i,(stage/(name+'.gz')).open('wb') as o:
   with gzip.GzipFile(filename='',mode='wb',fileobj=o,mtime=0) as g:shutil.copyfileobj(i,g)
  with gzip.open(stage/(name+'.gz'),'rb') as i,src.open('rb') as original:
   while True:
    a=i.read(1024*1024);b=original.read(1024*1024)
    assert a==b,'Diagnostic compression mismatch'
    if not a:break
  src.unlink()
 # apksigner may create .idsig files; they are not APKs or required for installation.
 for k in KINDS:
  sidecar=stage/(k+'.apk.idsig')
  if sidecar.exists():sidecar.unlink()
 assert {p.name for p in stage.iterdir()}==set(FINAL)-{'verification.json','SHA256SUMS'},'Unexpected staging file'
 report['compressed_inventory']=inventory(stage)
 (stage/'verification.json').write_text(json.dumps(report,indent=2))
 (stage/'SHA256SUMS').write_text(''.join(f"{p['sha256']}  {p['kind']}.apk\n" for p in report['packages']))
 assert {p.name for p in stage.iterdir()}==set(FINAL),'Final allowlist mismatch'
 total=sum(p.stat().st_size for p in stage.iterdir() if p.is_file())
 print(json.dumps({'final_inventory':inventory(stage),'final_bytes':total,'limit_bytes':CAP},sort_keys=True))
 assert total<CAP,'Artifact pair exceeds50MiB'
 print('Both APKs verified. No emulator/installation/OTA has been performed.')
if __name__=='__main__':
 if sys.argv[1]=='--diagnose':diagnose(pathlib.Path(sys.argv[2]),pathlib.Path(sys.argv[3]))
 else:verify(pathlib.Path(sys.argv[1]))
