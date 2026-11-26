import subprocess,sys,pathlib,json,tarfile,hashlib,time,re
ADB='/Users/dzide/Library/Android/sdk/platform-tools/adb'
BASE=[ADB,'-s','50eb54c9'];PKG='com.droidacoustic.pro'
PRIVATE=pathlib.Path('/Volumes/Deo X9/Android Acoustic Pro/device-backups/data-validity-2026-11-26')
OUT=pathlib.Path('/Volumes/Deo X9/Android Acoustic Pro/droidacoustic/validation/device/data-validity-2026-11-26')
APK='/Volumes/Deo X9/Android Acoustic Pro/droidacoustic/android/app/build/outputs/apk/debug/app-debug.apk'
def adb(*a):return subprocess.check_output(BASE+list(a),timeout=90)
def stop():adb('shell','am','force-stop',PKG)
def start():adb('shell','am','start','-n',PKG+'/.MainActivity');time.sleep(3)
def archive(path):
 with path.open('wb') as f:subprocess.run(BASE+['exec-out','run-as',PKG,'tar','-cf','-','files','shared_prefs','databases'],stdout=f,check=True,timeout=60)
def contents(path):
 with tarfile.open(path) as t:return {m.name:t.extractfile(m).read() for m in t if m.isfile()}
def scene():return json.loads(json.loads(adb('exec-out','run-as',PKG,'cat','files/projects/recovery.current'))['scene'])
def guard():assert scene()['speakers'][0]['label']=='VALIDITY TEST','Refusing to modify a non-test project'
def ui():
 adb('shell','uiautomator','dump','/sdcard/coverage-controls-ui.xml')
 b=adb('exec-out','cat','/sdcard/coverage-controls-ui.xml');return E.fromstring(b)
import xml.etree.ElementTree as E
def texts(root):return [(n.get('text') or n.get('content-desc'),n.get('bounds')) for n in root.iter('node') if n.get('text') or n.get('content-desc')]
def tap(label):
 root=ui();n=next(n for n in root.iter('node') if (n.get('text') or n.get('content-desc'))==label)
 b=list(map(int,re.findall(r'\d+',n.get('bounds'))));adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(1)
def shot(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
mode=sys.argv[1]
if mode=='backup':
 PRIVATE.mkdir(parents=True,exist_ok=True);p=PRIVATE/'original-app-data.tar';assert not p.exists(),'Never overwrite original backup'
 stop();archive(p);files=contents(p);assert 'files/projects/recovery.current' in files
 (PRIVATE/'original-sha256.json').write_text(json.dumps({k:hashlib.sha256(v).hexdigest() for k,v in files.items()},indent=2));print('Backed up',len(files),'files',hashlib.sha256(p.read_bytes()).hexdigest())
elif mode=='ui':print('\n'.join(str(x) for x in texts(ui())))
elif mode=='tap':guard();tap(sys.argv[2])
elif mode=='shot':guard();shot(sys.argv[2])
elif mode=='scene':guard();s=scene();print({k:s.get(k) for k in ['signalType','selectedBandHz','signalAutoCalculate','splScaleMode']})
elif mode=='restart':guard();stop();start()
elif mode=='restore':
 stop();original=contents(PRIVATE/'original-app-data.tar');archive(PRIVATE/'after-test.tar');current=contents(PRIVATE/'after-test.tar')
 # Only remove new files after retaining them privately; original files are overwritten from backup.
 for extra in sorted(current.keys()-original.keys()):
  assert extra.startswith(('files/projects/','shared_prefs/','databases/')) and '..' not in extra
  adb('shell','run-as',PKG,'rm',extra)
 adb('push',str(PRIVATE/'original-app-data.tar'),'/data/local/tmp/coverage-controls-original.tar')
 adb('shell','run-as',PKG,'tar','-xf','/data/local/tmp/coverage-controls-original.tar')
 archive(PRIVATE/'restored-before-launch.tar');assert contents(PRIVATE/'restored-before-launch.tar')==original
 (OUT/'restoration.json').write_text(json.dumps({'verifiedOriginalFiles':len(original),'extraFiles':0,'byteIdenticalBeforeLaunch':True},indent=2));start()
 archive(PRIVATE/'restored-after-launch.tar');restored=contents(PRIVATE/'restored-after-launch.tar')
 a=json.loads(json.loads(original['files/projects/recovery.current'])['scene']);b=json.loads(json.loads(restored['files/projects/recovery.current'])['scene'])
 changes=[k for k in a if k!='version' and a[k]!=b.get(k)];assert not changes,changes
 assert all(restored[k]==v for k,v in original.items() if k.startswith('files/projects/assets/'))
 r=json.loads((OUT/'restoration.json').read_text());r['reopenedOriginalSceneAndAssets']='verified';(OUT/'restoration.json').write_text(json.dumps(r,indent=2));print(r)

elif mode=='install':
 assert (PRIVATE/'original-app-data.tar').exists();print(adb('install','-r',APK).decode());stop()
elif mode=='acceptance':
 events=[]
 def record(check,**values):
  events.append({'check':check,**values});(OUT/'events.json').write_text(json.dumps(events,indent=2));print(events[-1],flush=True)
 def ns(r):return [t for t,b in texts(r)]
 def calculate(expected):
  for _ in range(20):
   r=ui();ts=ns(r)
   action=next((t for t in ['Recalculate','Calculate','Retry calculation'] if t in ts),None)
   if action:tap(action);break
  deadline=time.monotonic()+50
  while time.monotonic()<deadline:
   r=ui()
   if any(t.startswith('Results '+expected) for t in ns(r)):return r
  raise AssertionError(ns(r)[:45])
 def load(case):
  stop();adb('push',str(OUT/(case+'.current')),'/data/local/tmp/validity.current')
  adb('shell','run-as',PKG,'cp','/data/local/tmp/validity.current','files/projects/recovery.current');start();guard()
 for case in ['available','partial','unavailable']:
  load(case);r=calculate(case)
  assert not any(t=='Results up to date' for t in ns(r))
  if case!='available':assert any('missing' in t and 'S0' in t for t in ns(r))
  shot(case);record(case+' warning',status=next(t for t in ns(r) if t.startswith('Results ')))
  time.sleep(2);stop();start();r=calculate(case);shot(case+'-reopened');record(case+' survives save/reopen')
 # Missing broadband data can be resolved by choosing its supported narrow band.
 tap('Band');r=ui();assert not any(t.startswith('Results unavailable') for t in ns(r))
 r=calculate('available');record('Warning clears after a supported band is calculated');shot('resolved-band')
 # Remove only a test asset after preserving it within the test project directory.
 load('available');calculate('available');time.sleep(2);s=scene();asset=s['speakerAssetManifest']['point_small']['sha256']
 key='files/projects/assets/'+asset+'.json';assert key not in contents(PRIVATE/'original-app-data.tar')
 stop();adb('shell','run-as',PKG,'mv',key,'files/projects/missing-test-asset.json');start();r=calculate('unavailable')
 shot('missing-asset');record('Missing asset stays unavailable after reopen',status=next(t for t in ns(r) if t.startswith('Results unavailable')))
