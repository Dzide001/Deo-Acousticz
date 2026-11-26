#!/usr/bin/env python3
"""Independent cardinal samples from the official CF1 examples. No engine imports."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def generate():
    manifest = json.loads((ROOT / 'provenance.json').read_text())
    cases = []
    for source in manifest['fixtures']:
        p = ROOT / 'fixtures' / source['file']
        assert hashlib.sha256(p.read_bytes()).hexdigest() == source['fixtureSha256']
        rows = p.read_text().splitlines()[7:]
        arcs = [[float(v) for v in row.split()] for row in rows]
        # Literal row identities from section 12.2 / footnotes 1 and 3:
        # horizontal default: 0..180; full reversed: 90..0.
        indices = [0, 9, 18, 9] if source['symmetry'] == 'horizontal' else [9, 0, 9, 0]
        for (az, el, label), index in zip([(0, 60, 'up'), (-60, 0, 'left'), (0, -60, 'down'), (60, 0, 'right')], indices):
            cases.append(dict(file=source['file'], azimuth=az, elevation=el, label=label,
                              relativeDb=-arcs[index][6]))  # SIGN reversed: positive loss -> negative dB.
    return dict(scope='OFFICIAL_CF1_TAB_RELATIVE_MAGNITUDE_NOT_BINARY_OR_PHYSICAL_CALIBRATION',toleranceDb=0.001,
                scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                provenanceSha256=hashlib.sha256((ROOT/'provenance.json').read_bytes()).hexdigest(),cases=cases)


if __name__ == '__main__':
    parser = argparse.ArgumentParser();parser.add_argument('--check', action='store_true');args=parser.parse_args()
    result = generate();p=ROOT/'results/reference.json'
    if args.check: assert json.loads(p.read_text()) == result, 'Stale CLF magnitude reference'
    else: p.write_text(json.dumps(result, indent=2)+'\n')
    print('8 official CF1 TAB cardinal magnitude references verified')
