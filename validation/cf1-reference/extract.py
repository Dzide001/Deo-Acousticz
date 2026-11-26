#!/usr/bin/env python3
"""Audit retained numeric TAB subsets / binary fixtures against the original official kit."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--extracted-dir',type=Path,required=True);args=parser.parse_args()
manifest=json.loads((ROOT/'provenance.json').read_text())
for pair in manifest['pairs']:
    source=args.extracted_dir/'app/sample text'/pair['text']
    binary=args.extracted_dir/'app/sample distribution'/pair['binary']
    assert hashlib.sha256(source.read_bytes()).hexdigest()==pair['sourceTextSha256']
    assert hashlib.sha256(binary.read_bytes()).hexdigest()==pair['binarySha256']
    assert binary.read_bytes()==(ROOT/'fixtures'/pair['binary']).read_bytes()
    lines=source.read_text().splitlines();first=next(i for i,r in enumerate(lines) if r.startswith('<BAND>'))
    rows=[]
    for row in lines[first:]:
        if row.startswith('<') and not row.startswith('<BAND>'):break
        rows.append(row.strip())
    fixture=(ROOT/'fixtures'/pair['text']).read_text().splitlines()
    assert fixture[6:]==rows
    assert next(r.strip() for r in lines if r.startswith('<SENSITIVITY>'))==fixture[2]
powered=args.extracted_dir/'app/sample distribution/Powered.CF1'
assert hashlib.sha256(powered.read_bytes()).hexdigest()==manifest['poweredSha256']
assert powered.read_bytes()==(ROOT/'fixtures/Powered.CF1').read_bytes()
print('Retained CF1 numerical rows and binary fixtures match the hash-pinned official kit')
