#!/usr/bin/env python3
"""Independent scalar octave-band power summation, same declared finite-band convention."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
ROOT=Path(__file__).resolve().parent
DEP=ROOT.parent/'numerical/reference.py'
spec=importlib.util.spec_from_file_location('independent_equations',DEP)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
bands=[63,125,250,500,1000,2000,4000,8000]
weights={'Z':[0]*8,'A':[-26.2,-16.1,-8.6,-3.2,0,1.2,1,-1.1],'C':[-.8,-.2,0,0,0,-.2,-.8,-3]}
rows=[]
for distance in [10,20]:
    powers=[]
    for index in [4,7,10,13,16,19,22,25]:
        pieces=[]
        for i in [index-1,index,index+1]:
            centre=1000*2**((i-16)/3)
            lo,hi=centre/2**(1/6),centre*2**(1/6)
            _,alpha=module.air((lo+hi)/2)
            power=10**((100-20*math.log10(distance)-alpha*distance)/10)
            pieces.append((hi-lo,power))
        powers.append(sum(w*p for w,p in pieces)/sum(w for w,p in pieces))
    for name,coefficients in weights.items():
        value=10*math.log10(sum(p*10**(w/10) for p,w in zip(powers,coefficients)))
        rows.append(dict(id=f'{name}:{distance}',weighting=name,distanceM=distance,levelDb=value,bandPowers=dict(zip(map(str,bands),powers))))
result=dict(scope='SYNTHETIC_FINITE_OCTAVE_BROADBAND_NUMERICAL_CHECK_ONLY',toleranceDb=1e-5,
            scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),equationSha256=hashlib.sha256(DEP.read_bytes()).hexdigest(),cases=rows)
parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
path=ROOT/'results/reference.json'
if args.check:
    old=json.loads(path.read_text());a=old.pop('cases');b=result.pop('cases');assert old==result and len(a)==len(b)
    for x,y in zip(a,b):
        assert x['id']==y['id'] and abs(x['levelDb']-y['levelDb'])<1e-10
        for f,p in y['bandPowers'].items():assert abs(x['bandPowers'][f]-p)<=1e-12*p
else:path.write_text(json.dumps(result,indent=2)+'\n')
print('6 independent A/C/Z octave-sum reference cases verified')
