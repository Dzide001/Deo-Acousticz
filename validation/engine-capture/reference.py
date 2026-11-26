#!/usr/bin/env python3
"""Independent point-source reference for the synthetic scene-capture fixture."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parent
DEPENDENCY=ROOT.parent/'numerical/reference.py'
spec=importlib.util.spec_from_file_location('independent_acoustic_reference',DEPENDENCY)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
rows=[]
for hz,distance in [(1000,10),(2000,20)]:
    # Nominal one-third-octave cells at these exact octave-related centres.
    f1=hz/2**(1/6); f2=hz*2**(1/6)
    centre=(f1+f2)/2
    _,alpha=module.air(centre)
    level=100-20*math.log10(distance)-alpha*distance
    rows.append(dict(id=f'r{len(rows)+1}',hz=hz,x=distance,y=1,z=0,levelDb=level,levelDbWithSixDbGain=level+6))
result=dict(scope='SYNTHETIC_SCENE_CAPTURE_NUMERICAL_CHECK_ONLY',
            referenceScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            independentEquationScriptSha256=hashlib.sha256(DEPENDENCY.read_bytes()).hexdigest(),
            toleranceDb=.00001,samples=rows)
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
path=ROOT/'results/reference.json'
if args.check:
    old=json.loads(path.read_text())
    assert old['scope']==result['scope'] and old['referenceScriptSha256']==result['referenceScriptSha256']
    assert old['independentEquationScriptSha256']==result['independentEquationScriptSha256']
    assert old['toleranceDb']==result['toleranceDb'] and len(old['samples'])==len(rows)
    for a,b in zip(old['samples'],rows):
        for key,value in b.items():
            assert abs(a[key]-value)<1e-10 if isinstance(value,float) else a[key]==value
else:
    path.write_text(json.dumps(result,indent=2)+'\n')
print('2 synthetic scene-capture point-source references verified')
