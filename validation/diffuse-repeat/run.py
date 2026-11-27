#!/usr/bin/env python3
"""Six fresh-worker, unprofiled matched scene runs; restores solver in finally.

Do not edit/build the workspace concurrently. Requires an explicit --run flag.
Only the pinned solver file is temporarily exchanged; results are kept per run.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', action='store_true', required=True)
args = parser.parse_args()
experiment = json.loads((HERE.parent / 'diffuse-cache-profile/results/experiment.json').read_text())
source = ROOT / experiment['source']
candidate = source.read_bytes()
baseline = subprocess.check_output(['git', 'show', experiment['baselineCommit'] + ':' + experiment['source']], cwd=ROOT)
digest = lambda data: hashlib.sha256(data).hexdigest()
assert digest(candidate) == experiment['afterSourceSha256'], 'Unexpected candidate solver'
assert digest(baseline) == experiment['beforeSourceSha256'], 'Unexpected baseline solver'
out = HERE / 'results'
out.mkdir(exist_ok=True)
env = dict(os.environ)
assert not env.get('JAVA_TOOL_OPTIONS') and not env.get('JDK_JAVA_OPTIONS'), 'Remove extra JVM options'
assert not env.get('ACOUSTIC_DIFFUSE_BENCH_HZ'), 'Use the pinned 1 kHz fixture'
records = []
try:
    # Paired order reverses the middle pair to reduce simple ordering bias.
    for index, variant in enumerate(('before', 'after', 'after', 'before', 'before', 'after'), 1):
        folder = out / f'{index:02d}-{variant}'
        folder.mkdir(exist_ok=False)
        source.write_bytes(baseline if variant == 'before' else candidate)
        env['ACOUSTIC_BENCHMARK_EXPORT_DIR'] = str(folder / 'fixture')
        command = ['./gradlew', ':app:testDebugUnitTest', '-PacousticValidationTier=large',
            '--tests', '*PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics',
            '-Pkotlin.compiler.execution.strategy=in-process', '--offline']
        print(f'{index}/6 {variant}: fresh test worker; JFR disabled', flush=True)
        with (folder / 'build.log').open('w') as log:
            subprocess.run(command, cwd=ROOT / 'android', env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        log = (folder / 'build.log').read_text()
        assert 'BUILD SUCCESSFUL' in log and '[jfr,startup]' not in log
        xml = ROOT / 'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.PhaseTraceIntegrationTest.xml'
        (folder / 'scene.xml').write_bytes(xml.read_bytes())
        suite = ET.parse(xml).getroot()
        assert suite.get('tests') == '1'
        assert all(int(suite.get(key, 0)) == 0 for key in ('errors', 'failures', 'skipped'))
        output = suite.findtext('system-out')
        assert '64 emitters, 1000 Hz, 333 receivers' in output
        ms = float(re.search(r'333 receivers: ([\d.]+) ms', output).group(1))
        mean = float(re.search(r'area mean: ([\d.]+) dB', output).group(1))
        assert mean == 56.71420139630636
        fixture = folder / 'fixture/full-venue-diffuse.json'
        assert fixture.is_file()
        record = dict(run=index, variant=variant, firstCalculationMs=ms,
            acceptanceSeconds=float(suite.get('time')), meanDb=mean,
            sourceSha256=digest(source.read_bytes()), fixtureSha256=digest(fixture.read_bytes()),
            command=command, profilingEnabled=False)
        (folder / 'observation.json').write_text(json.dumps(record, indent=2) + '\n')
        records.append(record)
        (out / 'observations.json').write_text(json.dumps(records, indent=2) + '\n')
        print(f'  {ms / 1000:.3f} s first calculation; {suite.get("time")} s complete; {mean} dB', flush=True)
finally:
    source.write_bytes(candidate)
    print('Candidate solver restored', flush=True)
