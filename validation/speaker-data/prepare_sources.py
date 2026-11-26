#!/usr/bin/env python3
"""Extract only the two pinned reference files from a locally downloaded official archive."""
import argparse
import hashlib
import io
from pathlib import Path
import zipfile
from reference import HASHES

parser=argparse.ArgumentParser()
parser.add_argument('archive',type=Path)
parser.add_argument('destination',type=Path)
args=parser.parse_args()
with zipfile.ZipFile(args.archive) as outer:
    payload=outer.read('GLL-XD/XDCLFDATA.zip')
with zipfile.ZipFile(io.BytesIO(payload)) as inner:
    files={name:inner.read('XDCLFDATA/XD12CLFDATA/CLF2-Data/'+name) for name in HASHES}
for name,raw in files.items():
    assert hashlib.sha256(raw).hexdigest()==HASHES[name], 'Unexpected publisher revision: '+name
args.destination.mkdir(parents=True,exist_ok=True)
for name,raw in files.items():
    (args.destination/name).write_bytes(raw)
print('Extracted and verified two matched manufacturer files')
