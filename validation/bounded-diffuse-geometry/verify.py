#!/usr/bin/env python3
"""Verify retained bounded/expanded evidence, fixed inputs and restored candidate."""
import hashlib,json,subprocess,sys,xml.etree.ElementTree as E
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=json.loads((OUT/'inputs.json').read_text())
for name,value in manifest['constant_files'].items():assert sha(ROOT/name)==value,name
assert sha(ROOT/manifest['variant_file'])==sha(OUT/'candidate-CoherentDiffuse.kt.txt'),'Candidate not restored'
for name in ['01-before','02-after','03-after','04-before','05-profile-before','06-profile-after','07-expanded-before','08-expanded-after']:
    folder=OUT/name;r=json.loads((folder/'run.json').read_text())
    source=OUT/('candidate-CoherentDiffuse.kt.txt' if r['variant']=='after' else 'before-CoherentDiffuse.kt.txt')
    assert r['sourceSha256']==sha(source),name
    xml=E.parse(folder/'test.xml').getroot()
    assert int(xml.get('tests'))==1 and all(int(xml.get(k,0))==0 for k in ['failures','errors','skipped']),name
    assert 'BUILD SUCCESSFUL' in (folder/'build.log').read_text(),name
    for scene,row in json.loads((folder/'costs.json').read_text()).items():
        assert len(row['indices'])==r['receiverCount'] and row['profilingEnabled']==r['profilingEnabled']
        assert len(set(row['indices']))==r['receiverCount'] and row['indices'][0]==0 and row['indices'][-1]==332
        if r['reopen']:assert row['diskSaveReopenMatched']
        if r['profilingEnabled']:
            assert row['cachePayloadBytesPeak']<=33_554_432
            assert row['cacheKeyBytesPeak']<=67_108_864
            assert row['cacheEntriesPeak']<=262_144
verification=json.loads((OUT/'verification.json').read_text())
for name,value in verification['source_sha256'].items():assert sha(ROOT/name)==value,name
for module in ('shared','android'):
    counts=verification[module]
    assert all(counts[k]==0 for k in ('failures','errors','skipped'))
    assert len(json.loads((OUT/(module+'-test-inventory.json')).read_text()))==counts['tests']
assert 'BUILD SUCCESSFUL' in (OUT/'full-build.log').read_text()
subprocess.run([sys.executable,str(HERE/'summarize.py')],check=True,stdout=subprocess.DEVNULL)
print('Eight trials verified: fixed inputs, matched exact vectors, 33-receiver disk recovery, candidate restored')
