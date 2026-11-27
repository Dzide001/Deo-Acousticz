#!/usr/bin/env python3
"""Audit bounded production-request costs and exact retained receiver references."""
import hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
for f,digest in json.loads((OUT/'inputs.json').read_text()).items():
    assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest
rows=json.loads((OUT/'profile/costs.json').read_text())
assert set(rows)=={'bounded','offset'}
max_level=0.;max_power=0.
for label,folder in [('bounded','01-before'),('offset','exploratory-offset-corners/01-before')]:
    row=rows[label]
    reference=json.loads((ROOT/'validation/distinct-array-filters/results'/folder/'observation.json').read_text())
    assert row['indices']==[0,166,332] and row['integrationCalls']==3
    assert row['refinementPasses']==9
    assert row['quadratureTones']>=row['maximumTones']>0
    assert 0<row['cacheValuesPeak']<=4194304 and 0<row['cacheEntriesPeak']<=262144
    assert 0<row['cacheKeyBytesPeak']<=64*1024*1024
    for slot,index in enumerate(row['indices']):
        delta=abs(row['levelsDb'][slot]-reference['levelsDb'][index]);assert delta<=.001
        max_level=max(max_level,delta)
        power=reference['powers'][index];delta=abs(row['powers'][slot]-power)
        assert delta<=max(1e-10,abs(power)*1e-9)
        max_power=max(max_power,delta/max(abs(power),1e-300))
assert 'BUILD SUCCESSFUL' in (OUT/'profile-build.log').read_text()
suite=ET.parse(OUT/'profile-test.xml').getroot()
assert int(suite.get('tests'))==1 and all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
for name,count in [('shared',662),('android-fast',446)]:
    suite=json.loads((OUT/(name+'.json')).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
    assert not any('ProfileTest' in c['id'] or 'BenchmarkTest' in c['id'] for c in suite['cases'])
assert 'BUILD SUCCESSFUL' in (OUT/'regression-build.log').read_text()
v=json.loads((OUT/'verification.json').read_text())
for f,digest in v['sourceSha256'].items():assert hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest,f
for f,digest in v['evidenceSha256'].items():assert hashlib.sha256((OUT/f).read_bytes()).hexdigest()==digest,f
summary=dict(receiversPerScene=3,maximumLevelDifferenceDb=max_level,maximumRelativePowerDifference=max_power,
    intervalRatio=rows['offset']['baseIntervals']/rows['bounded']['baseIntervals'],
    offsetValueBudgetFraction=rows['offset']['cacheValuesPeak']/4194304,
    boundedValueBudgetFraction=rows['bounded']['cacheValuesPeak']/4194304,
    offsetMissTimeFraction=rows['offset']['missSeconds']/rows['offset']['totalSeconds'],
    scope='Opt-in instrumented costs for different inputs; no performance speedup claim, full-scene or native qualification')
(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
