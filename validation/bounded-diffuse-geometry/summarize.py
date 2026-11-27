#!/usr/bin/env python3
"""Compare raw request times and exact vectors; diagnostic clocks are separate."""
import json,statistics
from pathlib import Path
OUT=Path(__file__).resolve().parent/'results'
rows={d.name:json.loads((d/'costs.json').read_text()) for d in OUT.iterdir() if d.is_dir() and (d/'costs.json').exists() and (d/'run.json').exists()}
summary={}
for scene in ('bounded','offset'):
    pairs=[]
    for before,after in [('01-before','02-after'),('04-before','03-after'),('07-expanded-before','08-expanded-after')]:
        if before not in rows or after not in rows:continue
        b=rows[before][scene];a=rows[after][scene]
        assert not b['profilingEnabled'] and not a['profilingEnabled'] and b['indices']==a['indices']
        level=max(abs(x-y) for x,y in zip(b['levelsDb'],a['levelsDb']))
        power=max(abs(x-y)/max(abs(x),1e-10) for x,y in zip(b['powers'],a['powers']))
        assert level==0 and power==0,(scene,before,level,power)
        pairs.append(dict(before=before,after=after,receivers=len(b['indices']),beforeSeconds=b['totalSeconds'],afterSeconds=a['totalSeconds'],improvementPercent=100*(b['totalSeconds']-a['totalSeconds'])/b['totalSeconds'],maxLevelDifferenceDb=level,maxRelativePowerDifference=power))
    summary[scene]={'unprofiledPairs':pairs}
    if len(pairs)>=2:
        b=statistics.median(p['beforeSeconds'] for p in pairs[:2]);a=statistics.median(p['afterSeconds'] for p in pairs[:2])
        summary[scene]['threeReceiverMedians']={'beforeSeconds':b,'afterSeconds':a,'improvementPercent':100*(b-a)/b}
    if '05-profile-before' in rows and '06-profile-after' in rows:
        b=rows['05-profile-before'][scene];a=rows['06-profile-after'][scene]
        assert b['profilingEnabled'] and a['profilingEnabled']
        assert b['levelsDb']==a['levelsDb'] and b['powers']==a['powers']
        stable=['geometryEvaluations','cacheHits','cacheMisses','cacheRejectedPuts','hitTones','missTones',
          'cacheEntriesPeak','cacheKeyBytesPeak','cachePayloadBytesPeak','cachePackedEntriesPeak','cacheValuesPeak',
          'surfaceSamples','baseIntervals','refinementPasses','quadratureTones','maximumTones','integrationCalls']
        assert all(b[k]==a[k] for k in stable),(scene,'changed geometry/cache/quadrature work')
        summary[scene]['profileGeometryEvaluations']={'before':b['geometryEvaluations'],'after':a['geometryEvaluations']}
        summary[scene]['profileCacheAndQuadratureWorkUnchanged']=True
    if '08-expanded-after' in rows:summary[scene]['diskSaveReopenMatched']=rows['08-expanded-after'][scene]['diskSaveReopenMatched']
(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
