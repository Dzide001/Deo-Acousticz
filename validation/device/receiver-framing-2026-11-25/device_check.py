import subprocess,sys,pathlib,xml.etree.ElementTree as E,time
ADB='/Users/dzide/Library/Android/sdk/platform-tools/adb'
BASE=[ADB,'-s','50eb54c9']
def adb(*args):return subprocess.check_output(BASE+list(args),timeout=45)
def ui():
 adb('shell','uiautomator','dump','/sdcard/framing-ui.xml')
 b=adb('exec-out','cat','/sdcard/framing-ui.xml');pathlib.Path('/tmp/framing-ui.xml').write_bytes(b)
 return E.fromstring(b)
mode=sys.argv[1]
if mode in ('acceptance','final-labels'):
 import json
 project=json.loads(json.loads(adb('exec-out','run-as','com.droidacoustic.pro','cat','files/projects/recovery.current'))['scene'])
 receivers=project.get('alignmentReferences',{}).get('receivers',[])
 assert len(project.get('speakers',[]))==1 and [(r['name'],r['x'],r['y'],r['z']) for r in receivers]==[('Balcony',15,8,-4),('Balcony',15,8,-4),('Floor',5,1.2,3)], 'Load the synthetic fixture before acceptance; back up the original project separately.'

if mode=='ui':
 for n in ui().iter('node'):
  t=n.get('text') or n.get('content-desc')
  if t:print(t,n.get('bounds'))
elif mode=='tap':
 adb('shell','input','tap',sys.argv[2],sys.argv[3]);time.sleep(.5)
elif mode=='swipe':adb('shell','input','swipe',*sys.argv[2:])
elif mode=='shot':pathlib.Path(sys.argv[2]).write_bytes(adb('exec-out','screencap','-p'))
elif mode=='scene':
 import json
 r=json.loads(adb('exec-out','run-as','com.droidacoustic.pro','cat','files/projects/recovery.current'))
 s=json.loads(r['scene']);print({k:s.get(k) for k in ['receiverAngularSelection','receiverCameraFocus','receiverAngularOverlayEnabled']})
elif mode=='restart':
 adb('shell','am','force-stop','com.droidacoustic.pro');adb('shell','am','start','-n','com.droidacoustic.pro/.MainActivity');time.sleep(2)
elif mode=='acceptance':
 import re,json
 out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True);events=[]
 def taptext(text):
  root=ui();ns=[n for n in root.iter('node') if (n.get('text') or n.get('content-desc'))==text]
  if not ns:raise RuntimeError('Control absent: '+text)
  v=list(map(int,re.findall(r'\d+',ns[0].get('bounds'))));adb('shell','input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2));time.sleep(1)
  events.append({'action':text})
 def shot(name):
  (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 def scene():return json.loads(json.loads(adb('exec-out','run-as','com.droidacoustic.pro','cat','files/projects/recovery.current'))['scene'])
 for view in ['Plan','Section','3D']:
  taptext(view);taptext('Frame');shot(view.lower())
  adb('shell','input','swipe','900','800','1150','950','500');time.sleep(1);shot(view.lower()+'-drag');taptext('Frame')
 taptext('Details');shot('details');taptext('Wider');taptext('Wider');time.sleep(2)
 before=scene();events.append({'saved':{k:before.get(k) for k in ['receiverAngularSelection','receiverCameraFocus']}})
 adb('shell','am','force-stop','com.droidacoustic.pro');adb('shell','am','start','-n','com.droidacoustic.pro/.MainActivity');time.sleep(3);shot('reopened')
 after=scene();assert before.get('receiverCameraFocus')==after.get('receiverCameraFocus');assert before.get('receiverAngularSelection')==after.get('receiverAngularSelection')
 events.append({'saveReopen':'passed'})
 taptext('Fit to venue');time.sleep(2);assert scene().get('receiverCameraFocus') is None
 events.append({'frameAll':'cleared focus'})
 (out/'events.json').write_text(json.dumps(events,indent=2));print(events)
elif mode=='final-labels':
 import json
 out=pathlib.Path(sys.argv[2])
 adb('shell','am','force-stop','com.droidacoustic.pro');adb('shell','am','start','-n','com.droidacoustic.pro/.MainActivity');time.sleep(3)
 adb('shell','input','tap','2280','344');time.sleep(1)
 # Fit-to-venue cleared focus; expand panel, whose Frame action retains the selected receiver.
 root=ui()
 import re
 def tap(t):
  n=next(n for n in ui().iter('node') if n.get('text')==t);a=list(map(int,re.findall(r'\d+',n.get('bounds'))));adb('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2));time.sleep(1)
 try:tap('Frame')
 except StopIteration:tap('Frame selected')
 time.sleep(2);(out/'final-labels.png').write_bytes(adb('exec-out','screencap','-p'))
 # Coincident centre selection must change ID while keeping the pinned frame target.
 adb('shell','input','tap','1370','1074');time.sleep(2)
 s=json.loads(json.loads(adb('exec-out','run-as','com.droidacoustic.pro','cat','files/projects/recovery.current'))['scene']);print('selected',s.get('receiverAngularSelection'),'focus',s.get('receiverCameraFocus'))
 (out/'final-selection.png').write_bytes(adb('exec-out','screencap','-p'))
