#!/usr/bin/env python3
"""Load only the pinned synthetic fixture after checking a fresh private backup."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import time

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--backup',type=Path,required=True)
parser.add_argument('--apk',type=Path,required=True)
args=parser.parse_args()
HERE=Path(__file__).resolve().parent
OUT=HERE/'results/oneplus';OUT.mkdir(exist_ok=True)
ROOT=HERE.parent.parent
ADB=['/Users/dzide/Library/Android/sdk/platform-tools/adb','-s','50eb54c9']
PKG='com.droidacoustic.pro'
digest=lambda b:hashlib.sha256(b).hexdigest()
def adb(*parts,data=None,timeout=45):
    return subprocess.run(ADB+list(parts),input=data,capture_output=True,check=True,timeout=timeout).stdout
def manifest(data):
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        return {m.name:digest(t.extractfile(m).read()) for m in t if m.isfile()}
original=json.loads((args.backup/'original-manifest.json').read_text())
assert manifest((args.backup/'original-data.tar').read_bytes())==original
assert (args.backup/'original.apk').stat().st_size>1_000_000
assert adb('shell','getprop','ro.product.model').decode().strip()=='OPD2415'
adb('shell','am','force-stop',PKG)
assert manifest(adb('exec-out','run-as',PKG,'tar','-cf','-','files','shared_prefs'))==original, 'Refresh backup: device data changed'
scene=(HERE/'results/01-before/fixture.json').read_text()
record=json.dumps(dict(format=1,sha256=digest(scene.encode()),scene=scene),separators=(',',':')).encode()
buffer=io.BytesIO()
with tarfile.open(fileobj=buffer,mode='w') as t:
    entry=tarfile.TarInfo('files/projects/recovery.current');entry.mode=0o600;entry.size=len(record)
    t.addfile(entry,io.BytesIO(record))
adb('exec-in','run-as',PKG,'tar','-xf','-',data=buffer.getvalue())
(OUT/'install.log').write_bytes(adb('install','-r',str(args.apk.resolve()),timeout=120))
package=adb('shell','dumpsys','package',PKG).decode()
flags=[line.strip() for line in package.splitlines() if 'flags=[' in line]
assert flags and not any('DEBUGGABLE' in line for line in flags)
(OUT/'package-flags.txt').write_text('\n'.join(flags)+'\n')
(OUT/'fixture.json').write_text(scene)
(OUT/'start.json').write_text(json.dumps(dict(startUnix=time.time(),startMonotonic=time.monotonic(),
    fixtureSha256=digest(scene.encode()),apkSha256=digest(args.apk.read_bytes()),apkBytes=args.apk.stat().st_size,
    build='release, R8 optimized, non-debuggable, test-key signature',device='OPD2415',serial='50eb54c9'),indent=2)+'\n')
(OUT/'launch.log').write_bytes(adb('shell','am','start','-W','-n',PKG+'/.MainActivity'))
print('Pinned fixture launched on OnePlus; original backup verified before replacement',flush=True)
