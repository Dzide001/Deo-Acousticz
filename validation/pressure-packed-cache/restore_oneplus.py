#!/usr/bin/env python3
"""Inspect synthetic native autosave, then restore the private pre-test backup.

Requires --backup pointing to this session's tar, manifest and original APK.
Never stores the user's original project contents in the repository.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--backup', type=Path, required=True)
args = parser.parse_args()
OUT = Path(__file__).resolve().parent / 'results/oneplus'
ADB = ['/Users/dzide/Library/Android/sdk/platform-tools/adb', '-s', '50eb54c9']
PKG = 'com.droidacoustic.pro'
digest = lambda data: hashlib.sha256(data).hexdigest()

def adb(*parts, data=None, timeout=45):
    return subprocess.run(ADB + list(parts), input=data, capture_output=True, check=True, timeout=timeout).stdout

def manifest(data):
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        return {member.name: digest(archive.extractfile(member).read()) for member in archive if member.isfile()}

original_data = (args.backup / 'original-data.tar').read_bytes()
original_manifest = json.loads((args.backup / 'original-manifest.json').read_text())
assert manifest(original_data) == original_manifest, 'Backup integrity failure'
assert adb('shell', 'getprop', 'ro.product.model').decode().strip() == 'OPD2415'
adb('shell', 'am', 'force-stop', PKG)
# Non-debuggable release private storage becomes accessible after this replacement.
(OUT / 'restore-install.log').write_bytes(adb('install', '-r', str(args.backup / 'original.apk'), timeout=120))
# Package replacement can relaunch the previous foreground activity.
adb('shell', 'am', 'force-stop', PKG)
try:
    record = adb('exec-out', 'run-as', PKG, 'cat', 'files/projects/recovery.current')
    checkpoint = json.loads(record)
    assert digest(checkpoint['scene'].encode()) == checkpoint['sha256']
    scene = json.loads(checkpoint['scene'])
    assert len(scene['speakers']) == 4 and sum(s['arrayElements'] for s in scene['speakers']) == 64
    assert scene['selectedBandHz'] == 1000 and scene['analysisProfile'] == 'Fast'
    assert scene['signalType'] == 'BAND'
    (OUT / 'native-saved-checkpoint.json').write_bytes(record)
    fixture = json.loads((OUT / 'fixture.json').read_text())
    keys = ('speakers', 'venueGeometry', 'audience', 'audienceAreas', 'temperatureC',
        'humidityPct', 'signalType', 'signalBandwidthOct', 'analysisProfile', 'weighting',
        'signalInterferenceEnabled', 'signalDispersionEnabled', 'broadbandDrive',
        'dsp', 'signalLevelDbu', 'selectedBandHz', 'speakerAssetManifest')
    for key in keys:
        assert scene[key] == fixture[key], 'Native save changed input: ' + key
    assets = OUT / 'assets'
    assets.mkdir(exist_ok=True)
    hashes = set()
    for reference in scene['speakerAssetManifest'].values():
        key = reference['sha256']
        assert len(key) == 64 and all(c in '0123456789abcdef' for c in key)
        payload = adb('exec-out', 'run-as', PKG, 'cat', f'files/projects/assets/{key}.json')
        (assets / (key + '.json')).write_bytes(payload)
        assert digest(b'speaker-data-v1\n' + payload) == key
        hashes.add(key)
    assert hashes
    (OUT / 'native-recovery.json').write_text(json.dumps(dict(checkpointChecksumValid=True,
        speakerCount=4, emitterCount=64, bandHz=1000, assetHashes=sorted(hashes),
        sceneInputsMatchFixture=True,
        savedByNativeStore='clfRegistry' not in scene or not scene['clfRegistry']), indent=2) + '\n')
finally:
    current = adb('exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'files', 'shared_prefs')
    (args.backup / 'last-benchmark-data.tar').write_bytes(current)
    extras = sorted(set(manifest(current)) - set(original_manifest))
    for key in extras:
        path = Path(key)
        assert not path.is_absolute() and '..' not in path.parts and path.parts[0] in ('files', 'shared_prefs')
        adb('shell', 'run-as', PKG, 'rm', '--', key)
    adb('exec-in', 'run-as', PKG, 'tar', '-xf', '-', data=original_data)
    restored = adb('exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'files', 'shared_prefs')
    assert manifest(restored) == original_manifest, 'Restored data does not exactly match backup'
    apk_path = adb('shell', 'pm', 'path', PKG).decode().strip().removeprefix('package:')
    installed_hash = adb('shell', 'sha256sum', apk_path).decode().split()[0]
    assert installed_hash == digest((args.backup / 'original.apk').read_bytes())
    (OUT / 'restoration.json').write_text(json.dumps(dict(originalFileCount=len(original_manifest),
        allOriginalFilesByteIdentical=True, extraFileCount=0, removedBenchmarkFileCount=len(extras),
        originalApkRestored=True, installedApkSha256=installed_hash,
        originalManifestSha256=digest((args.backup / 'original-manifest.json').read_bytes())), indent=2) + '\n')
    (args.backup / 'RESTORED.txt').write_text('Original APK and all original file bytes restored; no extra benchmark files.\n')
    adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity')
# Verify a fresh foreground UI and the original scene after normal app recovery.
time.sleep(3)
for attempt in range(6):
    try:
        adb('shell','uiautomator','dump','/sdcard/acoustic-restore.xml',timeout=15)
        xml=adb('exec-out','cat','/sdcard/acoustic-restore.xml')
        texts=[n.get('text','') for n in ET.fromstring(xml).iter('node')]
        if 'Deo Acousticz' in texts: break
    except (subprocess.SubprocessError,ET.ParseError): pass
    time.sleep(2)
else: raise RuntimeError('Original app foreground could not be verified; files are restored')
after=adb('exec-out','run-as',PKG,'tar','-cf','-','files','shared_prefs')
with tarfile.open(fileobj=io.BytesIO(original_data)) as archive:
    old=json.loads(archive.extractfile('files/projects/recovery.current').read())
with tarfile.open(fileobj=io.BytesIO(after)) as archive:
    new=json.loads(archive.extractfile('files/projects/recovery.current').read())
assert digest(new['scene'].encode())==new['sha256']
assert json.loads(new['scene'])==json.loads(old['scene']), 'Original scene changed after reopen'
after_manifest=manifest(after)
changed=sorted(k for k in set(after_manifest)|set(original_manifest) if after_manifest.get(k)!=original_manifest.get(k))
allowed={'files/profileInstalled'}
if 'files/projects/recovery.previous' in changed:
    assert after_manifest['files/projects/recovery.previous']==original_manifest['files/projects/recovery.current'], 'Unexpected previous checkpoint contents'
    allowed.add('files/projects/recovery.previous')
assert set(changed)<=allowed, 'Unexpected file changes after original reopen'
report=json.loads((OUT/'restoration.json').read_text())
report.update(originalProjectReopened=True,originalSceneUnchangedAfterReopen=True,
    allFilesStillByteIdenticalAfterReopen=not changed,filesUpdatedByNormalReopen=changed,
    restorationByteCheckScope='After restoring backup and before reopening original app')
(OUT/'restoration.json').write_text(json.dumps(report,indent=2)+'\n')
print('Native checkpoint/assets verified; original APK/files restored; original project reopened unchanged')
