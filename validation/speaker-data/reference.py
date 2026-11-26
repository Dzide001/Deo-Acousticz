#!/usr/bin/env python3
"""Independent manufacturer-data reconstruction, NOT a vendor prediction-software export."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parent
TAB='CLF2_XD12.tab'
CF2='Martin Audio-XD12.CF2'
HASHES={TAB:'4aecc3ca0c420b48b7a7b522535fb44097d738394faa9dc4f1568ebd96b31ce4',
        CF2:'a0d26c8fbfb23d944a94a8b51a9dcbd1bcbf240fe49c48eb53976d8f9020f65e'}


def generate(directory):
    for name,expected in HASHES.items():
        assert hashlib.sha256((directory/name).read_bytes()).hexdigest()==expected, 'Unmatched manufacturer asset: '+name
    tags={}; bands={}; current=None
    for line in (directory/TAB).read_text(encoding='latin1').splitlines():
        fields=line.split('\t')
        if line.startswith('<BAND>'):
            current=[]; bands[int(fields[1])]=current
        elif line.startswith('<'):
            tags[fields[0]]=fields[1:]
        elif current is not None and line.strip():
            current.append([float(v) for v in fields if v.strip()])
    assert tags['<BALLOON-ARC-ORDER>']==['<reversed>']
    assert tags['<BALLOON-SYMMETRY>']==['<none>']
    assert len(bands)==27 and all(len(arcs)==72 and all(len(row)==37 for row in arcs) for arcs in bands.values())
    def attenuation(hz,az,el):
        a,e=map(math.radians,(az,el))
        theta=math.degrees(math.acos(max(-1,min(1,math.cos(e)*math.cos(a)))))/5
        phi=(math.degrees(math.atan2(math.cos(e)*math.sin(a),math.sin(e)))%360)/5
        # Reversed arcs: ascending phi i reads file row (-i mod 72).
        t=min(35,int(theta)); p=int(phi)%72; ft=theta-t; fp=phi-int(phi)
        values=bands[hz]
        def along(i):
            row=values[(-i)%72]
            return row[t]*(1-ft)+row[t+1]*ft
        return along(p)*(1-fp)+along((p+1)%72)*fp
    points=[]
    for hz in [125,500,1000,2000,8000,16000]:
        for az,el in [(0,0),(30,0),(-45,0),(0,20),(0,-20),(90,0)]:
            points.append(dict(id=f'{hz}:{az}:{el}',hz=hz,az=az,el=el,relativeDb=attenuation(hz,az,el)))
    frequencies=[25,32,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000]
    sensitivity=dict(zip(bands,map(float,tags['<SENSITIVITY>'])))
    axial=dict(zip(frequencies,map(float,tags['<AXIAL-SPECTRUM>'])))
    return dict(schema=1,scope='MANUFACTURER_DATA_CONSISTENCY_NOT_SOFTWARE_VALIDATION',
        publisher='Martin Audio',model='XD12',sourceUrl='https://martin-audio.com/downloads/ease/GLL-XD.zip',
        sourceSha256=HASHES,referenceScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        relativeToleranceDb=.001,referenceRowToleranceDb=.0001,
        sourceRows=[dict(hz=hz,tabSensitivityDb=sensitivity[hz],cf2AxialDb=axial[hz]) for hz in bands],
        relativeSamples=points,externalSoftwareComparison='PENDING_MATCHED_EXPORT',
        absoluteCalibration='TAB_DECLARED_1W_1M; CF2_NORMALIZATION_UNVERIFIED')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source-dir',type=Path,default=ROOT.parent.parent/'corpus/clf/martin_audio')
    parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=generate(args.source_dir); path=ROOT/'results/reference.json'
    if args.check:
        old=json.loads(path.read_text())
        # Floating trig may differ in the final bit across platforms.
        old_points=old.pop('relativeSamples');new_points=result.pop('relativeSamples')
        assert old==result
        assert len(old_points)==len(new_points)
        for a,b in zip(old_points,new_points):
            assert {k:v for k,v in a.items() if k!='relativeDb'}=={k:v for k,v in b.items() if k!='relativeDb'}
            assert abs(a['relativeDb']-b['relativeDb'])<1e-10
    else:
        path.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print('27 reference rows and 36 directional samples verified; external software comparison pending')


if __name__=='__main__':main()
