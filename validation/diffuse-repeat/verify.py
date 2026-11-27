#!/usr/bin/env python3
"""Audit retained repeated-run, native recovery and restoration evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
OUT = HERE / 'results'
subprocess.run([sys.executable, str(HERE / 'summarize.py')], check=True, stdout=subprocess.DEVNULL)
verification = json.loads((OUT / 'verification.json').read_text())
for name, expected in verification['evidenceSha256'].items():
    assert hashlib.sha256((OUT / name).read_bytes()).hexdigest() == expected, name
observations = json.loads((OUT / 'observations.json').read_text())
assert len({r['fixtureSha256'] for r in observations}) == 1
native = OUT / 'oneplus'
start = json.loads((native / 'start.json').read_text())
assert start['fixtureSha256'] == observations[0]['fixtureSha256']
assert start['device'] == 'OPD2415' and 'non-debuggable' in start['build']
assert 'DEBUGGABLE' not in (native / 'package-flags.txt').read_text()
assert 'BUILD SUCCESSFUL' in (native / 'release-build.log').read_text()
assert ':app:minifyReleaseWithR8' in (native / 'release-build.log').read_text()
runs = json.loads((native / 'observations.json').read_text())
assert len(runs) == 2 and [r['run'] for r in runs] == [1, 2]
for run in runs:
    assert run['coldLaunch'] and run['displayedMeanDb'] == 56.7
    assert 0 <= run['completionLowerSeconds'] < run['completionUpperSeconds'] < 900
    texts = [n.get('text', '') for n in ET.parse(native / f'run-{run["run"]}-complete.xml').iter('node')]
    assert all(text in texts for text in ('Deo Acousticz', '4 SPK', 'Band coverage • 1000 Hz', '56.7', 'AREA AVG'))
    assert not any('Calculating…' in t or 'Calculation failed' in t for t in texts)
record = json.loads((native / 'native-saved-checkpoint.json').read_text())
assert hashlib.sha256(record['scene'].encode()).hexdigest() == record['sha256']
recovery = json.loads((native / 'native-recovery.json').read_text())
assert recovery['checkpointChecksumValid'] and recovery['sceneInputsMatchFixture'] and recovery['savedByNativeStore']
for key in recovery['assetHashes']:
    assert hashlib.sha256(b'speaker-data-v1\n' + (native / 'assets' / (key + '.json')).read_bytes()).hexdigest() == key
restored = json.loads((native / 'restoration.json').read_text())
assert restored['originalFileCount'] == 9
assert restored['allOriginalFilesByteIdentical'] and restored['originalApkRestored'] and restored['extraFileCount'] == 0
assert verification['originalProjectReopened'] and verification['candidateSourceRestored']
print('Six unprofiled scene runs, two native cold launches, asset recovery and original restoration verified')
