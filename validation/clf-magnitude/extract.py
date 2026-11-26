#!/usr/bin/env python3
"""Verify the retained numeric subsets against an extracted official authoring kit."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--extracted-dir',type=Path,required=True);args=parser.parse_args()
manifest=json.loads((ROOT/'provenance.json').read_text())
for record in manifest['fixtures']:
    source=args.extracted_dir/'app/sample text'/record['file']
    assert hashlib.sha256(source.read_bytes()).hexdigest()==record['sourceSha256']
    lines=source.read_text().splitlines();first=next(i for i,r in enumerate(lines) if r.strip()=='<BAND>\t1000')+1
    rows=[]
    for row in lines[first:]:
        if row.startswith('<'):break
        rows.append(row.strip())
    fixture=ROOT/'fixtures'/record['file']
    assert hashlib.sha256(fixture.read_bytes()).hexdigest()==record['fixtureSha256']
    assert fixture.read_text().splitlines()[7:]==rows
print('Both retained numeric subsets match the hash-pinned official CF1 text samples')
