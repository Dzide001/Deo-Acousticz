#!/usr/bin/env python3
"""Prepare explicitly acquired CLF samples OUTSIDE the repository, with pinned source hashes.
Does not download or execute the authoring kit and does not grant redistribution rights.
"""
import argparse,hashlib,json,shutil,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
META=json.loads((Path(__file__).parent/'external-sources.json').read_text())
def digest(data):return hashlib.sha256(data).hexdigest()
def read(base,name,expected):
    data=(base/name).read_bytes();assert digest(data)==expected,f'External source hash mismatch: {name}'
    return data

def balloon(text,band=None):
    lines=text.decode().splitlines()
    first=next(i for i,r in enumerate(lines) if r.strip()==f'<BAND>\t{band}') if band is not None else next(i for i,r in enumerate(lines) if r.startswith('<BAND>'))
    out=[]
    for line in lines[first:]:
        line=line.strip()
        if line.startswith('<') and not line.startswith('<BAND>'):break
        if band is not None and line.startswith('<BAND>') and out:break
        if line:out.append(line)
    return out

def header(version,sensitivity,symmetry,order,actual=False):
    return [f'<CLF{version}>','<MODELNAME>\tPrivate external source subset',sensitivity,
        f'<BALLOON-SYMMETRY>\t<{symmetry}>',f'<BALLOON-ARC-ORDER>\t<{order}>',
        '<BALLOON-REF>\t<relative>\t<SIGN>\t'+('<actual>' if actual else '<reversed>')]
def write_lines(path,lines):path.write_text('\n'.join(lines)+'\n');return digest(path.read_bytes())
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--extracted-dir',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    out=a.output.resolve();assert not out.is_relative_to(ROOT),'External inputs must remain outside the repository'
    out.mkdir(parents=True,exist_ok=False)
    base=a.extracted_dir.resolve();common=dict(sourceKind='EXTERNAL_CLF_KIT_HASH_VERIFIED',scope='External fictitious CLF format examples; no electrical/manufacturer calibration or redistribution grant')
    for pkg in META:
        path=out/'validation'/pkg;(path/'fixtures').mkdir(parents=True);(path/'results').mkdir()
        shutil.copy2(ROOT/'validation'/pkg/'reference.py',path/'reference.py')
    path=out/'validation/cf1-reference';pairs=[]
    for pair in META['cf1-reference']['pairs']:
        source=read(base,'app/sample text/'+pair['text'],pair['sourceTextSha256'])
        binary=read(base,'app/sample distribution/'+pair['binary'],pair['binarySha256'])
        sensitivity=next(r.strip() for r in source.decode().splitlines() if r.startswith('<SENSITIVITY>'))
        sha=write_lines(path/'fixtures'/pair['text'],header(1,sensitivity,'horizontal','default')+balloon(source))
        (path/'fixtures'/pair['binary']).write_bytes(binary)
        pairs.append(dict(pair,fixtureTextSha256=sha))
    data=read(base,'app/sample distribution/Powered.CF1',META['cf1-reference']['poweredSha256']);(path/'fixtures/Powered.CF1').write_bytes(data)
    manifest=dict(common,pairs=pairs,poweredFile='Powered.CF1',poweredSha256=digest(data))
    (path/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
    path=out/'validation/clf-handedness';meta=META['clf-handedness']
    text=read(base,'app/sample text/clf2_passive_point.tab',meta['sourceSha256']['app/sample text/clf2_passive_point.tab'])
    binary=read(base,'app/sample distribution/Passive2.CF2',meta['sourceSha256']['app/sample distribution/Passive2.CF2'])
    sha=write_lines(path/'fixtures/private-1000.tab',header(2,'<SENSITIVITY>\t100','none','default',True)+balloon(text,1000))
    (path/'fixtures/Passive2.CF2').write_bytes(binary)
    manifest=dict(common,textFile='private-1000.tab',binaryFile='Passive2.CF2',fixtureSha256={'private-1000.tab':sha,'Passive2.CF2':digest(binary)},sourceSha256=meta['sourceSha256'],labels=meta['labels'])
    (path/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
    path=out/'validation/clf-magnitude';records=[]
    for record in META['clf-magnitude']['fixtures']:
        data=read(base,'app/sample text/'+record['file'],record['sourceSha256'])
        sha=write_lines(path/'fixtures'/record['file'],header(1,'<SENSITIVITY>\t100',record['symmetry'],record['order'])+balloon(data,1000))
        records.append(dict(record,fixtureSha256=sha))
    (path/'provenance.json').write_text(json.dumps(dict(common,fixtures=records),indent=2)+'\n')
    for pkg in META:subprocess.run([sys.executable,str(out/'validation'/pkg/'reference.py')],check=True)
    (out/'external-validation.json').write_text(json.dumps(dict(sourceKind=common['sourceKind'],redistributionPermission='NOT_ESTABLISHED; LOCAL INPUT ONLY',kitSha256=META['cf1-reference']['kitSha256']),indent=2)+'\n')
    print('Private hash-verified external inputs prepared; nothing copied into the repository')
