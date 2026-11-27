#!/usr/bin/env python3
"""Project-authored analytic CLF regression fixtures; never manufacturer/conformance data."""
import argparse,hashlib,json,math,struct
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OCT=[32,63,125,250,500,1000,2000,4000,8000,16000]
THIRD=[25,32,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000]
def digest(b):return hashlib.sha256(b).hexdigest()
def loss(theta,phi,asymmetric=False):
    t,p=math.radians(theta),math.radians(phi)
    return 15*(1-math.cos(t))+.75*math.sin(t)*math.cos(p)+(2*math.sin(t)*math.sin(p) if asymmetric else 0)
def rows(phis,step,asymmetric=False,positive=False):
    return ['\t'.join(f'{(1 if positive else -1)*loss(theta,phi,asymmetric):.6f}' for theta in range(0,181,step)) for phi in phis]
def tab(version,bands,levels,sym='horizontal',order='default',asymmetric=False):
    header=[f'<CLF{version}>','<MODELNAME>\tProject-authored synthetic regression',
        '<SENSITIVITY>\t'+'\t'.join(map(str,levels)),f'<BALLOON-SYMMETRY>\t<{sym}>',
        f'<BALLOON-ARC-ORDER>\t<{order}>','<BALLOON-REF>\t<relative>\t<SIGN>\t'+('<actual>' if version==2 else '<reversed>')]
    phis=range(0,360,5) if sym=='none' else (range(90,-1,-10) if sym=='full' else range(0,181,10))
    out=header[:]
    for hz in bands:out += [f'<BAND>\t{hz}']+rows(phis,5 if version==2 else 10,asymmetric,version==1)
    return ('\n'.join(out)+'\n').encode()
def binary(version,bands,levels):
    cf2=version==2;arcs,samples,step,offset=(72,37,5,0x3798) if cf2 else (36,19,10,0x23a8)
    table=THIRD if cf2 else OCT;lo,hi=table.index(bands[0]),table.index(bands[-1]);count=30 if cf2 else len(bands)
    data=bytearray(offset+count*arcs*samples*4)
    struct.pack_into('<I',data,0,0xABD41 if cf2 else 0xABD40)
    for at,text in [(0x14,'synthetic'),(0x138,'Project-authored synthetic'),(0x238,'Project validation'),(0x338,'Not measured; not external conformance')]:
        data[at:at+len(text)]=text.encode()
    struct.pack_into('<II',data,0x1210,lo,hi)
    for hz,level in zip(bands,levels):struct.pack_into('<f',data,(0x1000 if cf2 else 0x1240)+table.index(hz)*4,level)
    for i,hz in enumerate(bands):
        slot=table.index(hz) if cf2 else i
        for arc in range(arcs):
            for sample in range(samples):
                struct.pack_into('<f',data,offset+(slot*arcs*samples+arc*samples+sample)*4,-loss(sample*step,arc*step,cf2))
    return bytes(data)
def fixtures():
    output={};manifests={}
    common=dict(sourceKind='PROJECT_AUTHORED_SYNTHETIC',scope='Synthetic layout/coordinate regression; NOT independently sourced CLF conformance or calibration',generatorSha256=digest(Path(__file__).read_bytes()))
    pairs=[]
    for label,bands,levels in [('active',OCT[2:8],[91.25+.5*i for i in range(6)]),('passive',OCT[2:9],[88+.75*i for i in range(7)])]:
        text=f'synthetic_cf1_{label}.tab';bin=f'Synthetic{label.title()}.CF1'
        t,b=tab(1,bands,levels),binary(1,bands,levels)
        output[f'cf1-reference/fixtures/{text}']=t;output[f'cf1-reference/fixtures/{bin}']=b
        pairs.append(dict(text=text,binary=bin,fixtureTextSha256=digest(t),binarySha256=digest(b)))
    powered=binary(1,OCT[2:8],[0]*6);output['cf1-reference/fixtures/SyntheticPowered.CF1']=powered
    manifests['cf1-reference']=dict(common,pairs=pairs,poweredFile='SyntheticPowered.CF1',poweredSha256=digest(powered))
    t=tab(2,[1000],[93.25],sym='none',asymmetric=True);b=binary(2,[1000,1250],[93.25,93.25])
    output['clf-handedness/fixtures/synthetic-1000.tab']=t;output['clf-handedness/fixtures/SyntheticAsymmetric.CF2']=b
    manifests['clf-handedness']=dict(common,textFile='synthetic-1000.tab',binaryFile='SyntheticAsymmetric.CF2',fixtureSha256={'synthetic-1000.tab':digest(t),'SyntheticAsymmetric.CF2':digest(b)},labels=dict(up=0,left=90,down=180,right=270))
    mag=[]
    for label,sym,order in [('active','horizontal','default'),('powered','full','reversed')]:
        name=f'synthetic_cf1_{label}.tab';t=tab(1,[1000],[100],sym=sym,order=order)
        output[f'clf-magnitude/fixtures/{name}']=t;mag.append(dict(file=name,fixtureSha256=digest(t),symmetry=sym,order=order))
    manifests['clf-magnitude']=dict(common,fixtures=mag)
    for pkg,manifest in manifests.items():output[f'{pkg}/provenance.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    return output
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--write',action='store_true');p.add_argument('--prepare',action='store_true');a=p.parse_args()
    for name,data in fixtures().items():
        path=ROOT/'validation'/name
        is_binary=path.suffix.upper() in ('.CF1','.CF2')
        if a.write or a.prepare and is_binary:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        else:assert path.read_bytes()==data,f'Stale authored fixture: {path}'
    print('Project-authored synthetic fixtures verified; external conformance NOT claimed')
