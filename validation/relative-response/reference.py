#!/usr/bin/env python3
"""Independent per-frequency pressure-power ratio for the retained synthetic scene."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
ROOT=Path(__file__).resolve().parent
DEPENDENCY=ROOT.parent/'numerical/reference.py'
spec=importlib.util.spec_from_file_location('reference_equations',DEPENDENCY)
air=importlib.util.module_from_spec(spec);spec.loader.exec_module(air)
rows=[]
for hz in [1000,2000]:
    centre=hz*(2**(1/6)+2**(-1/6))/2
    _,alpha=air.air(centre)
    for distance in [10,20]:
        rows.append(dict(id=f'{hz}:{distance}',frequencyHz=hz,xM=distance,yM=1,zM=0,
                         relativeDb=-20*math.log10(distance/10)-alpha*(distance-10)))
result=dict(scope='SYNTHETIC_RELATIVE_RESPONSE_NUMERICAL_CHECK_ONLY',toleranceDb=1e-7,
            normalization=dict(method='per-frequency-level-difference',xM=10,yM=1,zM=0,minimumReferenceLevelDb=-120),
            referenceScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            equationScriptSha256=hashlib.sha256(DEPENDENCY.read_bytes()).hexdigest(),samples=rows)
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
path=ROOT/'results/reference.json'
if args.check:
    old=json.loads(path.read_text());old_rows=old.pop('samples');new_rows=result.pop('samples')
    assert old==result and len(old_rows)==len(new_rows)
    for a,b in zip(old_rows,new_rows):
        assert {k:v for k,v in a.items() if k!='relativeDb'}=={k:v for k,v in b.items() if k!='relativeDb'}
        assert abs(a['relativeDb']-b['relativeDb'])<1e-10
else:path.write_text(json.dumps(result,indent=2)+'\n')
print('4 independent synthetic relative-response samples verified')
