#!/usr/bin/env python3
"""Audit retained interpolation/filter setup accuracy and native recovery evidence."""
import hashlib
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
OUT=HERE/'results'
report=json.loads((OUT/'verification.json').read_text())
for name,digest in report['evidenceSha256'].items():
    assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==digest,name
for name,digest in report['sourceSha256'].items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
def passing(path,count):
    suite=ET.parse(path).getroot()
    assert int(suite.get('tests'))==count
    assert all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
    return suite
b=json.loads((OUT/'before/costs.json').read_text());a=json.loads((OUT/'after/costs.json').read_text())
for variant in ('before','after'):
    passing(OUT/variant/'profile-test.xml',1)
    assert 'BUILD SUCCESSFUL' in (OUT/variant/'build.log').read_text()
for channel in ('listener','coverage'):
    for key in ('hitTones','cacheHits','cacheMisses','surfaceSamples','geometryEvaluations','missTones'):
        assert b[channel][key]==a[channel][key],(channel,key)
    for data in (a,b):
        for key in ('samplingSeconds','spectrumSeconds','missSeconds','hitSeconds','totalSeconds'):
            assert math.isfinite(data[channel][key]) and data[channel][key]>=0
assert b['coverage']['meanDb']==a['coverage']['meanDb']==56.71420139630636
assert b['listener']['levelDb']==a['listener']['levelDb']==67.50961
for name,count in (('shared',654),('android-fast',446)):
    suite=json.loads((OUT/(name+'.json')).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
fast={c['id'] for c in json.loads((OUT/'android-fast.json').read_text())['cases']}
large=passing(OUT/'large-scene.xml',3)
ids={c.get('classname')+'.'+c.get('name') for c in large.findall('testcase')}
assert not fast & ids and len(fast|ids)==449
assert '56.71420139630636 dB' in large.findtext('system-out')
assert '333 receivers' in large.findtext('system-out')
assert 'BUILD SUCCESSFUL' in (OUT/'regression-release-build.log').read_text()
assert 'BUILD SUCCESSFUL' in (OUT/'large-build.log').read_text()

native=OUT/'oneplus';start=json.loads((native/'start.json').read_text())
assert start['device']=='OPD2415' and 'non-debuggable' in start['build']
assert 'DEBUGGABLE' not in (native/'package-flags.txt').read_text()
assert hashlib.sha256((native/'fixture.json').read_bytes()).hexdigest()==start['fixtureSha256']
runs=json.loads((native/'observations.json').read_text());assert [r['run'] for r in runs]==[1,2]
for run in runs:
    assert run['coldLaunch'] and run['displayedMeanDb']==56.7
    assert 0<=run['completionLowerSeconds']<run['completionUpperSeconds']<900
    texts=[n.get('text','') for n in ET.parse(native/f'run-{run["run"]}-complete.xml').iter('node')]
    assert all(t in texts for t in ('4 SPK','Band coverage • 1000 Hz','56.7','AREA AVG'))
    assert not any('Calculating…' in t or 'Calculation failed' in t for t in texts)
record=json.loads((native/'native-saved-checkpoint.json').read_text())
assert hashlib.sha256(record['scene'].encode()).hexdigest()==record['sha256']
recovery=json.loads((native/'native-recovery.json').read_text())
assert recovery['checkpointChecksumValid'] and recovery['sceneInputsMatchFixture'] and recovery['savedByNativeStore']
for digest in recovery['assetHashes']:
    assert hashlib.sha256(b'speaker-data-v1\n'+(native/'assets'/f'{digest}.json').read_bytes()).hexdigest()==digest
restored=json.loads((native/'restoration.json').read_text())
assert restored['allOriginalFilesByteIdentical'] and restored['originalApkRestored'] and restored['extraFileCount']==0
assert restored['originalProjectReopened'] and restored['originalSceneUnchangedAfterReopen']
print('Exact counts/levels, 654 shared + 449 Android tests, native save/reopen and restoration verified')
