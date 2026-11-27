#!/usr/bin/env python3
"""Audit retained shared diagnostics, normal regressions and native recovery."""
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
OUT=HERE/'results'
verification=json.loads((OUT/'verification.json').read_text())
for name,digest in verification['evidenceSha256'].items():
    assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==digest,name
for name,digest in verification['sourceSha256'].items():
    assert hashlib.sha256((HERE.parent.parent/name).read_bytes()).hexdigest()==digest,name

def passing(path,count):
    suite=ET.parse(path).getroot()
    assert int(suite.get('tests'))==count
    assert all(int(suite.get(key,0))==0 for key in ('failures','errors','skipped'))
    return suite

before=json.loads((OUT/'before/costs.json').read_text())
after=json.loads((OUT/'after/costs.json').read_text())
for variant in ('before','after'):
    passing(OUT/variant/'profile-test.xml',1)
    profile=json.loads((OUT/('initial-'+variant)/'jfr-summary.json').read_text())
    assert profile['executionEvents']>=profile['projectSamples']>0
    assert len(profile['recordingSha256'])==64
for channel in ('listener','coverage'):
    for key in ('hitTones','cacheHits','cacheMisses','surfaceSamples','geometryEvaluations','missTones'):
        assert before[channel][key]==after[channel][key],(channel,key)
    for variant in (before,after):
        for key in ('samplingSeconds','spectrumSeconds','missSeconds','hitSeconds','totalSeconds'):
            assert math.isfinite(variant[channel][key]) and variant[channel][key]>=0
assert before['coverage']['meanDb']==after['coverage']['meanDb']==56.71420139630636
assert before['listener']['levelDb']==after['listener']['levelDb']==67.50961

for filename,count in (('shared.json',648),('android-fast.json',446)):
    suite=json.loads((OUT/filename).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
fast={c['id'] for c in json.loads((OUT/'android-fast.json').read_text())['cases']}
assert not any('DiffuseCostProfileTest' in c for c in fast)
large=passing(OUT/'large-scene.xml',3)
large_ids={c.get('classname')+'.'+c.get('name') for c in large.findall('testcase')}
assert not fast & large_ids and len(fast | large_ids)==449
output=large.findtext('system-out')
assert '64 emitters, 1000 Hz, 333 receivers' in output
assert float(re.search(r'diffuse 1000 Hz area mean: ([\d.]+)',output).group(1))==56.71420139630636
for filename in ('regression-release-build.log','large-build.log'):
    assert 'BUILD SUCCESSFUL' in (OUT/filename).read_text()

native=OUT/'oneplus'
start=json.loads((native/'start.json').read_text())
assert start['fixtureSha256']==hashlib.sha256((native/'fixture.json').read_bytes()).hexdigest()
assert 'non-debuggable' in start['build'] and start['device']=='OPD2415'
assert 'DEBUGGABLE' not in (native/'package-flags.txt').read_text()
for folder,count in ((OUT/'oneplus-initial',2),(native,1)):
    runs=json.loads((folder/'observations.json').read_text())
    assert [r['run'] for r in runs]==list(range(1,count+1))
    for run in runs:
        assert run['coldLaunch'] and run['displayedMeanDb']==56.7
        assert 0<=run['completionLowerSeconds']<run['completionUpperSeconds']<900
        texts=[n.get('text','') for n in ET.parse(folder/f'run-{run["run"]}-complete.xml').iter('node')]
        assert all(t in texts for t in ('4 SPK','Band coverage • 1000 Hz','56.7','AREA AVG'))
        assert not any('Calculating…' in t or 'Calculation failed' in t for t in texts)
equivalence=json.loads((OUT/'android-runtime-equivalence.json').read_text())
assert equivalence['finalApkSha256']==start['apkSha256']
record=json.loads((native/'native-saved-checkpoint.json').read_text())
assert hashlib.sha256(record['scene'].encode()).hexdigest()==record['sha256']
audit=json.loads((native/'native-recovery.json').read_text())
assert audit['checkpointChecksumValid'] and audit['sceneInputsMatchFixture'] and audit['savedByNativeStore']
for digest in audit['assetHashes']:
    assert hashlib.sha256(b'speaker-data-v1\n'+(native/'assets'/f'{digest}.json').read_bytes()).hexdigest()==digest
restored=json.loads((native/'restoration.json').read_text())
assert restored['allOriginalFilesByteIdentical'] and restored['originalApkRestored'] and restored['extraFileCount']==0
assert restored['originalProjectReopened'] and restored['originalSceneUnchangedAfterReopen']
print('Matched cost counts/accuracy, 648 shared + 449 Android cases, three native launches and restoration verified')
