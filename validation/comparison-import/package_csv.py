#!/usr/bin/env python3
"""Wrap a UTF-8 export unchanged with explicit comparison protocol metadata. No unit guessing."""
import argparse
import hashlib
import json
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('csv',type=Path)
parser.add_argument('protocol',type=Path,help='JSON metadata envelope without payload')
parser.add_argument('output',type=Path)
args=parser.parse_args()
assert args.csv.stat().st_size<=8*1024*1024, 'CSV too large'
raw=args.csv.read_bytes(); payload=raw.decode('utf-8')
assert len(payload)<=2*1024*1024, 'CSV exceeds shared payload text limit'
root=json.loads(args.protocol.read_text(encoding='utf-8'))
assert root['schema']=='droidacoustic.comparison-import' and root['version'] in (1,2,3,4,5)
assert root['format']=='CSV'
root['source']['fileName']=args.csv.name
root['payload']=payload
root['sourceSha256']=hashlib.sha256(raw).hexdigest()
text=json.dumps(root,ensure_ascii=False,indent=2,allow_nan=False)+'\n'
assert len(text)<=4*1024*1024, 'Envelope exceeds shared document text limit'
args.output.write_text(text,encoding='utf-8')
print('Packaged original CSV with its hash. Import in the app to validate units, conditions and samples.')
