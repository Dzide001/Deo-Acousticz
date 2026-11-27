#!/usr/bin/env python3
"""Audit the two raw observations and summarize matched medians and residuals."""
import hashlib
import json
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
OUT=HERE/'results'
spec=json.loads((OUT/'variants.json').read_text())
runs=json.loads((OUT/'runs.json').read_text())
assert [r['variant'] for r in runs]==spec['order'] and len(runs)==2
reference=json.loads((OUT/'01-before/observation.json').read_text());fixture_hash=None;max_db=0.0;max_relative=0.0;max_mean=0.0
for row in runs:
    folder=OUT/f'{row["run"]:02d}-{row["variant"]}'
    assert json.loads((folder/'run.json').read_text())==row
    assert row['sourceSha256']=={n:v['beforeSha256' if row['variant']=='before' else 'candidateSha256'] for n,v in spec['files'].items()}
    assert 'BUILD SUCCESSFUL' in (folder/'build.log').read_text() and '[jfr,startup]' not in (folder/'build.log').read_text()
    suite=ET.parse(folder/'test.xml').getroot();assert int(suite.get('tests'))==1
    assert all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
    obs=json.loads((folder/'observation.json').read_text())
    scene=json.loads((folder/'fixture.json').read_text())
    assert obs['profilingEnabled'] is False and obs['emitters']==64 and obs['receiverCount']==333
    assert obs['filtersPerEmitter']==8 and obs['validAreaPercent']==100
    assert len(scene['speakers'])==4 and sum(s['arrayElements'] for s in scene['speakers'])==64
    assert len(scene['dsp'])==4 and all(len(d['filters']['filters'])==8 for d in scene['dsp'])
    chains=[d['filters'] for d in scene['dsp']]
    assert len({json.dumps(chain,sort_keys=True) for chain in chains})==4
    assert {f['sampleRateHz'] for chain in chains for f in chain['filters']}=={48000,96000}
    current_hash=hashlib.sha256((folder/'fixture.json').read_bytes()).hexdigest()
    assert row['coverageSeconds']==obs['coverageSeconds'] and row['listenerSeconds']==obs['listenerSeconds']
    assert len(obs['levelsDb'])==len(obs['powers'])==333 and len(obs['sourceLevelsDb'])==4
    if fixture_hash is None:fixture_hash=current_hash
    assert fixture_hash==current_hash
    max_mean=max(max_mean,abs(reference['meanDb']-obs['meanDb']))
    differences=[abs(reference['listenerDb']-obs['listenerDb'])]
    for key in ('levelsDb','sourceLevelsDb'):differences += [abs(a-b) for a,b in zip(reference[key],obs[key])]
    max_db=max(max_db,max(differences))
    for a,b in zip(reference['powers'],obs['powers']):
        assert abs(a-b)<=max(1e-10,abs(a)*1e-9)
        max_relative=max(max_relative,abs(a-b)/max(abs(a),1e-300))
assert max_db<=.001 and max_mean<=1e-5
def spread(values):return dict(minimum=min(values),median=statistics.median(values),maximum=max(values),count=len(values))
variants={v:{k:spread([r[k] for r in runs if r['variant']==v]) for k in ('listenerSeconds','coverageSeconds')} for v in ('before','after')}
pairs=[]
for ids in ((1,2),):
    b,a=(runs[i-1] for i in ids)
    pairs.append(dict(beforeRun=ids[0],afterRun=ids[1],coverageReductionPercent=100*(1-a['coverageSeconds']/b['coverageSeconds']),
        listenerReductionPercent=100*(1-a['listenerSeconds']/b['listenerSeconds'])))
summary=dict(variants=variants,pairs=pairs,medianPairedCoverageReductionPercent=statistics.median(p['coverageReductionPercent'] for p in pairs),
    coverageMedianReductionPercent=100*(1-variants['after']['coverageSeconds']['median']/variants['before']['coverageSeconds']['median']),
    maximumLevelDifferenceDb=max_db,maximumMeanDifferenceDb=max_mean,maximumRelativePowerDifference=max_relative,
    fixtureSha256=fixture_hash,meanDb=reference['meanDb'],scope='One validation pair on a shared host; not a repeated or native performance qualification')
(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
