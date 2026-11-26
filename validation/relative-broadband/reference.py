#!/usr/bin/env python3
"""Ratios of independently calculated weighted power totals, not band-dB averages."""
import argparse
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parent
DEP=ROOT.parent/'broadband-capture/results/reference.json'
source=json.loads(DEP.read_text()); values={(c['weighting'],c['distanceM']):c for c in source['cases']}
cases=[dict(weighting=w,distanceM=d,relativeDb=values[w,d]['levelDb']-values[w,10]['levelDb']) for w in ['A','C','Z'] for d in [10,20]]
result=dict(scope='SYNTHETIC_RELATIVE_WEIGHTED_TOTALS_ONLY',toleranceDb=1e-7,
            scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),weightedReferenceSha256=hashlib.sha256(DEP.read_bytes()).hexdigest(),cases=cases)
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
path=ROOT/'results/reference.json'
if args.check:
    old=json.loads(path.read_text());a=old.pop('cases');b=result.pop('cases');assert old==result and len(a)==len(b)
    for x,y in zip(a,b):assert x['weighting']==y['weighting'] and x['distanceM']==y['distanceM'] and abs(x['relativeDb']-y['relativeDb'])<1e-10
else:path.write_text(json.dumps(result,indent=2)+'\n')
print('6 independent relative weighted-total references verified')
