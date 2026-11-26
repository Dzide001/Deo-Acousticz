import subprocess,sys,pathlib,json,tarfile,hashlib,time,re
ADB='/Users/dzide/Library/Android/sdk/platform-tools/adb'
BASE=[ADB,'-s','50eb54c9'];PKG='com.droidacoustic.pro'
PRIVATE=pathlib.Path('/Volumes/Deo X9/Android Acoustic Pro/device-backups/coverage-controls-2026-11-25')
OUT=pathlib.Path('/Volumes/Deo X9/Android Acoustic Pro/droidacoustic/validation/device/coverage-controls-2026-11-25')
APK='/Volumes/Deo X9/Android Acoustic Pro/droidacoustic/android/app/build/outputs/apk/debug/app-debug.apk'
def adb(*a):return subprocess.check_output(BASE+list(a),timeout=90)
def stop():adb('shell','am','force-stop',PKG)
def start():adb('shell','am','start','-n',PKG+'/.MainActivity');time.sleep(3)
def archive(path):
 with path.open('wb') as f:subprocess.run(BASE+['exec-out','run-as',PKG,'tar','-cf','-','files','shared_prefs','databases'],stdout=f,check=True,timeout=60)
def contents(path):
 with tarfile.open(path) as t:return {m.name:t.extractfile(m).read() for m in t if m.isfile()}
def scene():return json.loads(json.loads(adb('exec-out','run-as',PKG,'cat','files/projects/recovery.current'))['scene'])
def guard():assert scene()['speakers'][0]['label']=='CONTROL TEST','Refusing to modify a non-test project'
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
elif mode=='setup':
 assert (PRIVATE/'original-app-data.tar').exists();print(adb('install','-r',APK).decode());stop()
 adb('push',str(OUT/'recovery.current'),'/data/local/tmp/coverage-controls.current')
 adb('shell','run-as',PKG,'cp','/data/local/tmp/coverage-controls.current','files/projects/recovery.current');start();guard();print('Synthetic scene loaded')
elif mode=='reload':
 stop();adb('push',str(OUT/'recovery.current'),'/data/local/tmp/coverage-controls.current');adb('shell','run-as',PKG,'cp','/data/local/tmp/coverage-controls.current','files/projects/recovery.current');start();guard()
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
 guard();print(adb('install','-r',APK).decode());stop();start();guard()
elif mode=='compact':
 guard();events=[]
 def record(name,**values):
  events.append({'check':name,**values});(OUT/'compact-events.json').write_text(json.dumps(events,indent=2));print(events[-1],flush=True)
 def ns(root):return [x[0] for x in texts(root)]
 def saved(**values):
  for _ in range(25):
   if all(scene().get(k)==v for k,v in values.items()):return
   time.sleep(.3)
  raise AssertionError(values)
 def current():
  deadline=time.monotonic()+50
  while time.monotonic()<deadline:
   r=ui()
   if 'Results up to date' in ns(r):return r
  raise AssertionError('No current result')
 def no_prose(root):
  ts=ns(root)
  for phrase in ['All analysis bands combined','Use Calculate / Recalculate','Updates automatically','Coverage is integrated','Coverage centre frequency']:
   assert not any(phrase in t for t in ts),phrase
 def bounds(root,text):return list(map(int,re.findall(r'\d+',next(b for t,b in texts(root) if t==text))))
 # Normalize mode deterministically; startup recovery gets one calculation before manual-edit checks.
 if scene()['signalAutoCalculate']:tap('Auto')
 tap('Band');tap('Recalculate');current();saved(signalAutoCalculate=False)
 r=ui();no_prose(r);ts=ns(r);assert ts.count('Manual')==1 and 'Auto' not in ts
 a=bounds(r,'Manual');b=bounds(r,'Recalculate');assert a[3]<250 and b[3]<250 and a[2]<b[0] and b[0]-a[2]<160,(a,b)
 record('Single mode button beside Calculate',manualBounds=a,calculateBounds=b)
 # Choose a different band, proving that Manual leaves it stale.
 target='125' if scene()['selectedBandHz']!=125 else '250';tap(target);r=ui()
 assert 'Recalculation required — tap Recalculate' in ns(r);assert '—' in ns(r);time.sleep(2)
 assert 'Recalculation required — tap Recalculate' in ns(ui());shot('compact-manual-stale')
 tap('Recalculate');r=current();assert float(ns(r)[ns(r).index('AREA AVG')+1])>0;shot('compact-band-manual');record('Manual waits for explicit recalculation')
 tap('Manual');saved(signalAutoCalculate=True);r=current();assert ns(r).count('Auto')==1 and 'Manual' not in ns(r) and 'Recalculate' in ns(r)
 record('Same button toggles Auto; Calculate stays available')
 tap('Broadband SPL');r=current();no_prose(r);assert not any(t in ns(r) for t in ['63','125','250','500','1k','2k','4k','8k']);assert float(ns(r)[ns(r).index('AREA AVG')+1])>0
 shot('compact-broadband-auto');record('Broadband hides frequency row and explanations')
 saved(signalType='SPECTRUM',signalAutoCalculate=True);stop();start();r=current();assert ns(r).count('Auto')==1;record('Auto and Broadband survive restart')
 tap('Auto');saved(signalAutoCalculate=False);time.sleep(2);stop();start();r=ui();assert ns(r).count('Manual')==1;no_prose(r)
 saved(signalType='SPECTRUM',signalAutoCalculate=False);shot('compact-manual-reopened');record('Manual choice survives restart without scene edit')
 tap('Band');r=ui();assert all(t in ns(r) for t in ['63','125','250','500','1k','2k','4k','8k']);record('Returning to Band restores frequency buttons')
 tap('Recalculate');current();shot('compact-final');record('Final manual calculation completes')
