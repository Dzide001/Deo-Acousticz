#!/usr/bin/env python3
"""Audit fixed-budget cache results, reference accuracy and original-device recovery."""
import hashlib,json,subprocess,sys,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
subprocess.run([sys.executable,str(HERE/'summarize.py')],check=True,stdout=subprocess.DEVNULL)
v=json.loads((OUT/'verification.json').read_text())
for name,digest in v['sourceSha256'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
for name,digest in v['evidenceSha256'].items():assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==digest,name
for name,digest in json.loads((OUT/'inputs.json').read_text()).items():
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
for name,count in [('shared',666),('android-full',449)]:
    suite=json.loads((OUT/(name+'.json')).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
    assert not any('BenchmarkTest' in c['id'] or 'ProfileTest' in c['id'] or 'ExpansionTest' in c['id'] for c in suite['cases'])
for log in ('expansion-build.log','android-full-build.log'):
    assert 'BUILD SUCCESSFUL' in (OUT/log).read_text()
profile=json.loads((OUT/'05-profile/costs.json').read_text())
for row in profile.values():
    assert 0<row['cachePayloadBytesPeak']<=32*1024*1024
    assert 0<row['cacheKeyBytesPeak']<=64*1024*1024 and row['cacheEntriesPeak']<=262144
    assert row['cachePackedEntriesPeak']>0 and row['refinementPasses']==9
assert profile['offset']['cacheValuesPeak']>4194304 and profile['offset']['cacheRejectedPuts']>0
rows=json.loads((OUT/'expansion/costs.json').read_text());assert set(rows)=={'bounded','offset'}
for label,folder in [('bounded','01-before'),('offset','exploratory-offset-corners/01-before')]:
    row=rows[label];indices=[i*332//11 for i in range(12)]
    assert not row['profilingEnabled'] and row['indices']==indices
    ref=json.loads((ROOT/'validation/distinct-array-filters/results'/folder/'observation.json').read_text())
    assert len(row['powers'])==len(row['levelsDb'])==12
    for i,index in enumerate(indices):
        assert abs(row['levelsDb'][i]-ref['levelsDb'][index])<=.001
        p=ref['powers'][index];assert abs(row['powers'][i]-p)<=max(1e-10,abs(p)*1e-9)
suite=ET.parse(OUT/'expansion/test.xml').getroot()
assert int(suite.get('tests'))==1 and all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
expected=json.loads((OUT/'expectation.json').read_text());native=OUT/'oneplus'
start=json.loads((native/'start.json').read_text());assert start['device']=='OPD2415'
assert start['fixtureSha256']==expected['fixtureSha256']==hashlib.sha256((native/'fixture.json').read_bytes()).hexdigest()
assert 'DEBUGGABLE' not in (native/'package-flags.txt').read_text()
artifact=json.loads((OUT/'apk.json').read_text());assert artifact['sha256']==start['apkSha256']
assert artifact['bytes']==start['apkBytes']
observations=json.loads((native/'observations.json').read_text());assert [r['run'] for r in observations]==[1,2]
for run in observations:
    assert run['displayedMeanDb']==expected['displayedMeanDb'] and run['coldLaunch']
    assert 0<=run['completionLowerSeconds']<run['completionUpperSeconds']<900
    texts=[n.get('text','') for n in ET.parse(native/f'run-{run["run"]}-complete.xml').iter('node')]
    assert all(t in texts for t in ('4 SPK','Band coverage • 1000 Hz','AREA AVG',f'{expected["displayedMeanDb"]:.1f}'))
    assert not any('Calculation failed' in t or 'Calculating…' in t for t in texts)
record=json.loads((native/'native-saved-checkpoint.json').read_text())
assert hashlib.sha256(record['scene'].encode()).hexdigest()==record['sha256']
scene=json.loads(record['scene']);fixture=json.loads((native/'fixture.json').read_text())
assert scene['dsp']==fixture['dsp'] and len({json.dumps(d['filters'],sort_keys=True) for d in scene['dsp']})==4
recovery=json.loads((native/'native-recovery.json').read_text())
assert all(recovery[k] for k in ('checkpointChecksumValid','sceneInputsMatchFixture','savedByNativeStore'))
for digest in recovery['assetHashes']:
    assert hashlib.sha256(b'speaker-data-v1\n'+(native/'assets'/f'{digest}.json').read_bytes()).hexdigest()==digest
restore=json.loads((native/'restoration.json').read_text())
assert all(restore[k] for k in ('allOriginalFilesByteIdentical','originalApkRestored','originalProjectReopened','originalSceneUnchangedAfterReopen'))
assert restore['extraFileCount']==0
print('Exact bounded/expanded references, fixed payload budget, 666 shared + 449 Android checks, native reopen and restoration verified')
