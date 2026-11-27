#!/usr/bin/env python3
"""Audit matched power vectors and summarize balanced, unprofiled request pairs."""
import json,hashlib,statistics,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
runs=json.loads((OUT/'runs.json').read_text());assert [r['variant'] for r in runs]==['before','after','after','before','profile']
residual=0.;relative=0.
for run in runs:
    folder=OUT/f'{run["run"]:02d}-{run["variant"]}'
    assert json.loads((folder/'run.json').read_text())==run
    source=OUT/('before-DiffuseIncidentCache.kt.txt' if run['variant']=='before' else 'candidate-DiffuseIncidentCache.kt.txt')
    assert hashlib.sha256(source.read_bytes()).hexdigest()==run['sourceSha256']
    assert 'BUILD SUCCESSFUL' in (folder/'build.log').read_text() and '[jfr,startup]' not in (folder/'build.log').read_text()
    suite=ET.parse(folder/'test.xml').getroot()
    assert int(suite.get('tests'))==1 and all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
    rows=json.loads((folder/'costs.json').read_text());assert set(rows)=={'bounded','offset'}
    for label,ref_folder in [('bounded','01-before'),('offset','exploratory-offset-corners/01-before')]:
        row=rows[label];assert row['profilingEnabled']==(run['variant']=='profile') and row['indices']==[0,166,332]
        ref=json.loads((ROOT/'validation/distinct-array-filters/results'/ref_folder/'observation.json').read_text())
        assert len(row['levelsDb'])==len(row['powers'])==3
        for i,index in enumerate(row['indices']):
            d=abs(row['levelsDb'][i]-ref['levelsDb'][index]);assert d<=.001;residual=max(residual,d)
            p=ref['powers'][index];d=abs(row['powers'][i]-p);assert d<=max(1e-10,abs(p)*1e-9)
            relative=max(relative,d/max(abs(p),1e-300))
summary={}
for label in ('bounded','offset'):
    times={v:[r['times'][label] for r in runs[:4] if r['variant']==v] for v in ('before','after')}
    pairs=[100*(1-runs[a-1]['times'][label]/runs[b-1]['times'][label]) for b,a in ((1,2),(4,3))]
    summary[label]=dict(times=times,medians={v:statistics.median(x) for v,x in times.items()},pairedReductionPercent=pairs)
summary.update(maximumLevelDifferenceDb=residual,maximumRelativePowerDifference=relative,
    scope='Two balanced three-receiver JVM pairs; no universal, native or sustained-thermal speed rating')
(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
