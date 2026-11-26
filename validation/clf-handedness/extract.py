#!/usr/bin/env python3
"""Reproduce retained numeric fixtures from a hash-matched, extracted official kit."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--extracted-dir', type=Path, required=True, help='Directory containing app/')
args = parser.parse_args()
provenance = json.loads((ROOT / 'provenance.json').read_text())
for name, digest in provenance['sourceSha256'].items():
    assert hashlib.sha256((args.extracted_dir / name).read_bytes()).hexdigest() == digest, name
assert hashlib.sha256((args.extracted_dir / 'app/CLF_specs.pdf').read_bytes()).hexdigest() == provenance['specificationSha256']
lines = (args.extracted_dir / 'app/sample text/clf2_passive_point.tab').read_text().splitlines()
first = next(i for i, row in enumerate(lines) if row.strip() == '<BAND>\t1000') + 1
rows = [row.strip() for row in lines[first:first+72]]
text = '\n'.join(['<CLF2>', '<MODELNAME>\tOfficial CLF format example - 1000 Hz subset',
    '<MANUFACTURER>\tCLF authoring example (not a measured product)', '<SENSITIVITY>\t93.7',
    '<BALLOON-SYMMETRY>\t<none>', '<BALLOON-ARC-ORDER>\t<default>',
    '<BALLOON-REF>\t<relative>\t<SIGN>\t<actual>', '<BAND>\t1000'] + rows) + '\n'
fixtures = {'official-1000.tab': text.encode(),
            'Passive2.CF2': (args.extracted_dir / 'app/sample distribution/Passive2.CF2').read_bytes()}
for name, data in fixtures.items():
    assert hashlib.sha256(data).hexdigest() == provenance['fixtureSha256'][name]
    assert (ROOT / 'fixtures' / name).read_bytes() == data, f'Retained fixture mismatch: {name}'
print('Retained fixtures exactly reproduce the hash-matched official source subset / CF2')
