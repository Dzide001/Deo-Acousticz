#!/usr/bin/env python3
"""Verify the six retained runs and summarize their observed spread."""
import hashlib
import json
from pathlib import Path
import re
import statistics
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'results'
observations = json.loads((OUT / 'observations.json').read_text())
assert [r['variant'] for r in observations] == ['before', 'after', 'after', 'before', 'before', 'after']
experiment = json.loads((ROOT.parent / 'diffuse-cache-profile/results/experiment.json').read_text())
for record in observations:
    folder = OUT / f'{record["run"]:02d}-{record["variant"]}'
    assert record == json.loads((folder / 'observation.json').read_text())
    expected = experiment['beforeSourceSha256' if record['variant'] == 'before' else 'afterSourceSha256']
    assert record['sourceSha256'] == expected and record['profilingEnabled'] is False
    fixture = folder / 'fixture/full-venue-diffuse.json'
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == record['fixtureSha256']
    log = (folder / 'build.log').read_text()
    assert 'BUILD SUCCESSFUL' in log and '[jfr,startup]' not in log
    suite = ET.parse(folder / 'scene.xml').getroot()
    assert suite.get('tests') == '1'
    assert all(int(suite.get(key, 0)) == 0 for key in ('failures', 'errors', 'skipped'))
    output = suite.findtext('system-out')
    assert '64 emitters, 1000 Hz, 333 receivers' in output
    assert float(re.search(r'333 receivers: ([\d.]+) ms', output).group(1)) == record['firstCalculationMs']
    assert float(re.search(r'area mean: ([\d.]+) dB', output).group(1)) == record['meanDb'] == 56.71420139630636
    assert float(suite.get('time')) == record['acceptanceSeconds']

def spread(values):
    return dict(minimum=min(values), median=statistics.median(values), maximum=max(values), count=len(values))

summary = dict(scope='Unprofiled fresh JVM workers; shared host, no isolation/thermal guarantee',
    order=[r['variant'] for r in observations], variants={},
    accuracy='All six original all-receiver/statistics, save/reopen and role-contribution checks passed',
    confidence='Exploratory three observations per variant; host contention changed during the series. No causal speedup claim.')
for variant in ('before', 'after'):
    rows = [r for r in observations if r['variant'] == variant]
    summary['variants'][variant] = dict(
        firstCalculationSeconds=spread([r['firstCalculationMs'] / 1000 for r in rows]),
        completeAcceptanceSeconds=spread([r['acceptanceSeconds'] for r in rows]))
summary['observedMedianFirstCalculationReductionPercent'] = 100 * (1 -
    summary['variants']['after']['firstCalculationSeconds']['median'] /
    summary['variants']['before']['firstCalculationSeconds']['median'])
(OUT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary['variants'], indent=2))
