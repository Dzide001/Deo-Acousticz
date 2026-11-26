#!/usr/bin/env python3
"""Independent allocation of total mean-square drive to retained unallocated band powers."""
import argparse
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent
DEP=ROOT.parent/'broadband-capture/results/reference.json'
reference=json.loads(DEP.read_text());bands=[63,125,250,500,1000,2000,4000,8000]
weights={'Z':[0]*8,'A':[-26.2,-16.1,-8.6,-3.2,0,1.2,1,-1.1],'C':[-.8,-.2,0,0,0,-.2,-.8,-3]}
# Actual base-two octave widths are proportional to these exact centres.
raws={'TOTAL_EQUAL_BAND_POWER':[1]*8,'TOTAL_FLAT_PSD':[62.5,125,250,500,1000,2000,4000,8000],
      'TOTAL_CUSTOM_BAND_POWER':[8,4,2,1,1,2,4,8]}
cases=[]
for row in reference['cases']:
 for mode,raw in raws.items():
    fractions=[p/sum(raw) for p in raw]
    powers=[row['bandPowers'][str(f)]*p for f,p in zip(bands,fractions)]
    import math
    level=10*math.log10(sum(p*10**(w/10) for p,w in zip(powers,weights[row['weighting']])))
    cases.append(dict(weighting=row['weighting'],distanceM=row['distanceM'],mode=mode,levelDb=level,
                      fractions=dict(zip(map(str,bands),fractions))))
result=dict(scope='SYNTHETIC_TOTAL_DRIVE_ALLOCATION_ONLY',toleranceDb=1e-5,
            scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),unallocatedReferenceSha256=hashlib.sha256(DEP.read_bytes()).hexdigest(),cases=cases)
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();path=ROOT/'results/reference.json'
if args.check:
 old=json.loads(path.read_text());a=old.pop('cases');b=result.pop('cases');assert old==result and len(a)==len(b)
 for x,y in zip(a,b):
  assert (x['mode'],x['weighting'],x['distanceM'])==(y['mode'],y['weighting'],y['distanceM'])
  assert abs(x['levelDb']-y['levelDb'])<1e-10 and x['fractions']==y['fractions']
else:path.write_text(json.dumps(result,indent=2)+'\n')
print('18 independent spectrum / total-drive references verified')
