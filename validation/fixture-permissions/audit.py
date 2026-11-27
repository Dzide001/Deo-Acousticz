#!/usr/bin/env python3
"""Audit current public files/index, NOT existing Git history or forks."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--index',action='store_true');a=p.parse_args()
meta=json.loads((Path(__file__).parent/'external-sources.json').read_text())
def hashes(value):
    if isinstance(value,dict):return set().union(*(hashes(v) for v in value.values()))
    if isinstance(value,list):return set().union(*(hashes(v) for v in value))
    return {value} if isinstance(value,str) and len(value)==64 and all(c in '0123456789abcdef' for c in value) else set()
known=hashes(meta)
paths=subprocess.check_output(['git','ls-files','-z']+([] if a.index else ['--cached','--others','--exclude-standard']),cwd=ROOT).decode().split('\0')
checked=0
for name in set(paths)-{''}:
    file=ROOT/name
    if not a.index and not file.is_file():continue
    assert file.suffix.upper() not in ('.CF1','.CF2'),f'Binary speaker fixture must not be tracked: {name}'
    assert file.name!='external-validation.json',f'Private external input marker in public files: {name}'
    data=subprocess.check_output(['git','show',':'+name],cwd=ROOT) if a.index else file.read_bytes()
    assert hashlib.sha256(data).hexdigest() not in known,f'Known kit sample copied into public files: {name}'
    checked+=1
for pkg in ('cf1-reference','clf-handedness','clf-magnitude'):
    path=ROOT/'validation'/pkg
    read=lambda name: subprocess.check_output(['git','show',':validation/'+pkg+'/'+name],cwd=ROOT) if a.index else (path/name).read_bytes()
    manifest=json.loads(read('provenance.json'));reference=json.loads(read('results/reference.json'))
    assert manifest['sourceKind']=='PROJECT_AUTHORED_SYNTHETIC'
    assert reference['scope'].startswith('PROJECT_AUTHORED_SYNTHETIC_')
print(f'{checked} public files audited: no tracked CLF binaries or known kit sample copies; synthetic scope explicit. History/forks NOT audited.')
