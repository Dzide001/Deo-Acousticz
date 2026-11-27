#!/usr/bin/env python3
"""Audit retained profile, accuracy and regression evidence without running Gradle."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
results = ROOT / 'results'
verification = json.loads((results / 'verification.json').read_text())
for name, digest in verification['evidenceSha256'].items():
    assert hashlib.sha256((results / name).read_bytes()).hexdigest() == digest, name
for variant in ('before', 'after'):
    suite = ET.parse(results / f'{variant}-scene.xml').getroot()
    assert suite.attrib['tests'] == '1'
    assert all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped'))
    output = suite.findtext('system-out')
    assert '64 emitters, 1000 Hz, 333 receivers' in output
    mean = float(re.search(r'area mean: ([\d.]+) dB', output).group(1))
    assert mean == 56.71420139630636
    profile = json.loads((results / f'{variant}-profile.json').read_text())
    assert profile['executionEvents'] >= profile['projectSamples'] > 0
    assert len(profile['recordingSha256']) == 64
tests = verification['suites']
assert tests['shared']['tests'] == 645
assert tests['androidFast']['tests'] == 446
assert tests['androidOtherLarge']['tests'] == 2
for suite in tests.values():
    assert suite['failures'] == suite['errors'] == suite['skipped'] == 0
for filename, key in (('shared.json', 'shared'), ('android-fast.json', 'androidFast')):
    inventory = json.loads((results / filename).read_text())
    assert inventory['counts'] == tests[key]
    assert len({case['id'] for case in inventory['cases']}) == tests[key]['tests']
fast = {case['id'] for case in json.loads((results / 'android-fast.json').read_text())['cases']}
large = set()
for filename in ('after-scene.xml', 'other-large-scene.xml'):
    suite = ET.parse(results / filename).getroot()
    assert all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped'))
    large.update(case.get('classname') + '.' + case.get('name') for case in suite.findall('testcase'))
assert len(large) == 3 and not fast & large and len(fast | large) == 449
for filename in ('fast-build.log', 'other-large-build.log'):
    assert 'BUILD SUCCESSFUL' in (results / filename).read_text()
assert verification['iosCompilationPassed'] and verification['androidAssemblyPassed']
print('Profile evidence verified; exact scene mean/reopen, 645 shared + 449 Android cases passed')
