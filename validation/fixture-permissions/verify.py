#!/usr/bin/env python3
"""Audit the new current-checkout qualification without rewriting historical receipts."""
import hashlib,json,subprocess,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1];OUT=HERE/'results'
for pkg in ('cf1-reference','clf-handedness','clf-magnitude'):
    subprocess.run([sys.executable,str(ROOT/'validation'/pkg/'reference.py'),'--check'],check=True)
    for name in ('android.json','engine-jvm.json'):
        p=ROOT/'validation'/pkg/'results'/name
        if p.exists():
            report=json.loads(p.read_text());assert report['scope'].startswith('PROJECT_AUTHORED_SYNTHETIC_') and report['allPassed']
for name,count in [('shared',667),('android-full',449)]:
    suite=json.loads((OUT/(name+'.json')).read_text())
    assert suite['counts']==dict(tests=count,failures=0,errors=0,skipped=0)
    assert len({c['id'] for c in suite['cases']})==count
assert 'BUILD SUCCESSFUL' in (OUT/'full-build.log').read_text()
external=json.loads((OUT/'external-summary.json').read_text())
assert external['rawReportsPublished'] is False and len(external['reports'])==5
assert all(r['allPassed'] and r['scope'].startswith('EXTERNAL_CLF_KIT_HASH_VERIFIED_') for r in external['reports'])
receipt=json.loads((OUT/'verification.json').read_text())
for name,digest in receipt['sourceSha256'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
for name,digest in receipt['evidenceSha256'].items():assert hashlib.sha256((OUT/name).read_bytes()).hexdigest()==digest,name
old=json.loads((ROOT/'validation/pressure-packed-cache/results/verification.json').read_text())
for name,digest in old['sourceSha256'].items():
    if '/src/commonMain/' in name:assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,'Performance runtime changed: '+name
print('Current public synthetic scopes, private external summary, 667 + 449 passing cases and unchanged validated performance runtime verified')
