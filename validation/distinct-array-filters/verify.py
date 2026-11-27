#!/usr/bin/env python3
"""Audit matched observations, native filtered recovery and the restored candidate."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jvm-only',action='store_true',help='Audit computer evidence without accepting native checks')
args=parser.parse_args()
HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
OUT=HERE/'results'
subprocess.run([sys.executable,str(HERE/'summarize.py')],check=True,stdout=subprocess.DEVNULL)
verification=json.loads((OUT/'verification.json').read_text())
for name,digest in verification['evidenceSha256'].items():
    assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==digest,name
for name,digest in verification['sourceSha256'].items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
variants=json.loads((OUT/'variants.json').read_text())
source=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/CoherentDiffuse.kt'
for name,record in variants['files'].items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==record['candidateSha256']
for name,count in (('shared',661),('android-fast',446)):
    suite=json.loads((OUT/(name+'.json')).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
    assert not any('BenchmarkTest' in c['id'] for c in suite['cases'])
assert 'BUILD SUCCESSFUL' in (OUT/'regression-build.log').read_text()
expected=json.loads((OUT/'expectation.json').read_text())
summary=json.loads((OUT/'summary.json').read_text())
assert expected['meanDb']==summary['meanDb'] and expected['fixtureSha256']==summary['fixtureSha256']
assert 'BUILD SUCCESSFUL' in (OUT/'regression-build.log').read_text()
if args.jvm_only:
    print('Two matched runs, numerical references, 661 shared and 446 fast Android cases verified; native acceptance pending')
    sys.exit(0)
native=OUT/'oneplus';start=json.loads((native/'start.json').read_text())
prior=json.loads((ROOT/'validation/filter-transfer-reuse/results/verification.json').read_text())
for name,digest in prior['sourceSha256'].items():
    assert verification['sourceSha256'][name]==digest, 'Reused APK core changed: '+name
reuse=json.loads((OUT/'apk-reuse.json').read_text())
prior_start=json.loads((ROOT/'validation/filter-transfer-reuse/results/oneplus/start.json').read_text())
assert reuse['productionChanges'] is False
assert start['apkSha256']==reuse['apkSha256']==prior_start['apkSha256']
assert start['apkBytes']==reuse['apkBytes']==prior_start['apkBytes']

assert start['fixtureSha256']==expected['fixtureSha256']
assert hashlib.sha256((native/'fixture.json').read_bytes()).hexdigest()==start['fixtureSha256']
assert start['device']=='OPD2415' and 'DEBUGGABLE' not in (native/'package-flags.txt').read_text()
runs=json.loads((native/'observations.json').read_text());assert [r['run'] for r in runs]==[1,2]
for run in runs:
    assert run['coldLaunch'] and run['displayedMeanDb']==expected['displayedMeanDb']
    assert 0<=run['completionLowerSeconds']<run['completionUpperSeconds']<900
    texts=[n.get('text','') for n in ET.parse(native/f'run-{run["run"]}-complete.xml').iter('node')]
    assert all(t in texts for t in ('4 SPK','Band coverage • 1000 Hz','AREA AVG',f'{expected["displayedMeanDb"]:.1f}'))
    assert not any('Calculating…' in t or 'Calculation failed' in t for t in texts)
record=json.loads((native/'native-saved-checkpoint.json').read_text())
assert hashlib.sha256(record['scene'].encode()).hexdigest()==record['sha256']
scene=json.loads(record['scene']);fixture=json.loads((native/'fixture.json').read_text())
assert scene['dsp']==fixture['dsp']
assert len(scene['dsp'])==4 and all(len(d['filters']['filters'])==8 for d in scene['dsp'])
assert len({json.dumps(d['filters'],sort_keys=True) for d in scene['dsp']})==4
audit=json.loads((native/'native-recovery.json').read_text())
assert audit['checkpointChecksumValid'] and audit['sceneInputsMatchFixture'] and audit['savedByNativeStore']
for digest in audit['assetHashes']:
    assert hashlib.sha256(b'speaker-data-v1\n'+(native/'assets'/f'{digest}.json').read_bytes()).hexdigest()==digest
restore=json.loads((native/'restoration.json').read_text())
assert restore['allOriginalFilesByteIdentical'] and restore['originalApkRestored'] and restore['extraFileCount']==0
assert restore['originalProjectReopened'] and restore['originalSceneUnchangedAfterReopen']
print('Two matched filtered runs, unchanged numerical results, native DSP/asset recovery and original restoration verified')
