#!/usr/bin/env python3
"""Observe two cold native runs of the already-loaded fixture. No project replacement.

First start.json is created by the documented launch procedure. The second run
reopens the application's own autosaved checkpoint. Failed UI dumps are discarded.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent / 'results/oneplus'
SCREENSHOTS = Path('/tmp/acoustic-diffuse-native-screenshots')
SCREENSHOTS.mkdir(exist_ok=True)
ADB = ['/Users/dzide/Library/Android/sdk/platform-tools/adb', '-s', '50eb54c9']
PKG = 'com.droidacoustic.pro'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--private-data-unavailable', action='store_true',
    help='Non-debuggable release: inspect checkpoints only after restoring the debug APK')
parser.add_argument('--runs',type=int,choices=(1,2),default=2,
    help='Use one run when verifying a final-build reopen of an already autosaved fixture')
args = parser.parse_args()

def adb(*args, timeout=15):
    return subprocess.check_output(ADB + list(args), timeout=timeout, stderr=subprocess.STDOUT)

observations = []
for run in range(1,args.runs+1):
    if run == 1:
        start = json.loads((OUT / 'start.json').read_text())['startMonotonic']
    else:
        adb('shell', 'am', 'force-stop', PKG)
        start = time.monotonic()
        (OUT / 'reopen-launch.log').write_bytes(adb('shell', 'am', 'start', '-W', '-n', PKG + '/.MainActivity', timeout=45))
    last_running = 0.0
    before = adb('shell', 'dumpsys', 'battery')
    (OUT / f'run-{run}-battery-before.txt').write_bytes(before)
    rejected = 0
    while time.monotonic() - start < 900:
        try:
            dump = adb('shell', 'uiautomator', 'dump', '/sdcard/acoustic-bench.xml', timeout=10)
            if b'dumped to:' not in dump:
                raise ValueError('Unsuccessful UI dump')
            xml = adb('exec-out', 'cat', '/sdcard/acoustic-bench.xml', timeout=10)
            nodes = list(ET.fromstring(xml).iter('node'))
            texts = [n.get('text', '') for n in nodes]
            if 'Deo Acousticz' not in texts or '4 SPK' not in texts or 'Band coverage • 1000 Hz' not in texts:
                raise ValueError('Benchmark app is not visible')
            elapsed = time.monotonic() - start
            if any('Calculation failed' in text for text in texts):
                raise RuntimeError('Native calculation failed')
            if any('Calculating…' in text for text in texts):
                last_running = elapsed
            elif '56.7' in texts and 'AREA AVG' in texts:
                (OUT / f'run-{run}-complete.xml').write_bytes(xml)
                (SCREENSHOTS / f'run-{run}-complete.png').write_bytes(adb('exec-out', 'screencap', '-p', timeout=20))
                # Read the native autosave, not the injected portable input.
                record = None
                if not args.private_data_unavailable:
                    record = adb('exec-out', 'run-as', PKG, 'cat', 'files/projects/recovery.current')
                    decoded = json.loads(record)
                    scene = decoded['scene']
                    assert decoded['sha256'] == hashlib.sha256(scene.encode()).hexdigest()
                    opened = json.loads(scene)
                    assert len(opened['speakers']) == 4 and sum(s['arrayElements'] for s in opened['speakers']) == 64
                    assert opened['selectedBandHz'] == 1000 and opened['analysisProfile'] == 'Fast'
                    (OUT / f'run-{run}-saved-checkpoint.json').write_bytes(record)
                (OUT / f'run-{run}-battery-after.txt').write_bytes(adb('shell', 'dumpsys', 'battery'))
                observation = dict(run=run, coldLaunch=True, completionLowerSeconds=last_running,
                    completionUpperSeconds=elapsed, displayedMeanDb=56.7,
                    rejectedSnapshots=rejected,
                    checkpointInspectionDeferred=args.private_data_unavailable,
                    checkpointSha256=hashlib.sha256(record).hexdigest() if record is not None else None)
                observations.append(observation)
                (OUT / 'observations.json').write_text(json.dumps(observations, indent=2) + '\n')
                print(f'Run {run} complete within {elapsed:.1f}s; 56.7 dB', flush=True)
                break
            else:
                (OUT / f'run-{run}-unexpected.xml').write_bytes(xml)
                raise RuntimeError('Native calculation stopped without the expected 56.7 dB result')
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError, ValueError, ET.ParseError) as error:
            rejected += 1
        time.sleep(5)
    else:
        raise TimeoutError('Native completion was not observed within 15 minutes')
print(f'{args.runs} cold native coverage run(s) completed', flush=True)
