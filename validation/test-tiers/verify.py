#!/usr/bin/env python3
"""Audit retained tier evidence and unchanged phase-test bodies. No Gradle dependency."""
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent
out=ROOT/'results'
baseline=json.loads((out/'baseline.json').read_text())
fast=json.loads((out/'fast.json').read_text())
large=json.loads((out/'large.json').read_text())
a={r['id'] for r in fast['cases']};b={r['id'] for r in large['cases']}
assert not a&b and len(a|b)==449
assert not any(r['failed'] or r['skipped'] for r in fast['cases']+large['cases'])
phase={name.split('PhaseTraceIntegrationTest.',1)[1] for name in a|b if 'PhaseTraceIntegrationTest.' in name}
assert phase=={r['name'] for r in baseline['cases']} and len(phase)==31
source=ROOT.parent.parent/'android/app/src/test/kotlin/com/droidacoustic/pro/scene/PhaseTraceIntegrationTest.kt'
normalized=source.read_text().replace('import org.junit.experimental.categories.Category\n','').replace('    @Category(LargeSceneValidation::class)\n','')
assert hashlib.sha256(normalized.encode()).hexdigest()==baseline['originalSourceSha256']
verification=json.loads((out/'verification.json').read_text())
for name,digest in verification['evidenceSha256'].items():
    assert hashlib.sha256((out/name).read_bytes()).hexdigest()==digest,name
print('446 fast + 3 large cases cover 449; all 31 original phase cases and test bodies preserved')
