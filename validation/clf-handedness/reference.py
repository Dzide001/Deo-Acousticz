#!/usr/bin/env python3
"""Official CLF sample + independently labelled Fig. 1 directions; no engine imports."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def generate():
    provenance = json.loads((ROOT / 'provenance.json').read_text())
    for name, digest in provenance['fixtureSha256'].items():
        assert hashlib.sha256((ROOT / 'fixtures' / name).read_bytes()).hexdigest() == digest
    text = (ROOT / 'fixtures/official-1000.tab').read_text().splitlines()
    start = text.index('<BAND>\t1000') + 1
    arcs = [[float(v) for v in r.split()] for r in text[start:]]
    assert len(arcs) == 72 and all(len(r) == 37 for r in arcs)
    cases = []
    for yaw, tilt in [(0, 0), (90, 0), (-90, 0), (37, 25)]:
        for label, phi in [('left', 90), ('right', 270), ('up', 0), ('down', 180)]:
            for theta in [30, 45, 60, 90]:
                # Fig. 1 gives LEFT at phi=90, RIGHT at phi=270, independently
                # of the app. Construct world rays from these labelled directions.
                t, p, y, a = map(math.radians, (theta, phi, yaw, tilt))
                forward = 10 * math.cos(t)
                up = 10 * math.sin(t) * math.cos(p)
                right = -10 * math.sin(t) * math.sin(p)
                def world(f, u, r):
                    pitched_f = math.cos(a) * f + math.sin(a) * u
                    return [2 + math.cos(y) * pitched_f - math.sin(y) * r,
                            3 - math.sin(a) * f + math.cos(a) * u,
                            -4 + math.sin(y) * pitched_f + math.cos(y) * r]
                value = arcs[phi // 5][theta // 5]
                mirror = arcs[(-phi) % 360 // 5][theta // 5]
                cases.append(dict(label=label, phi=phi, theta=theta, yaw=yaw, tiltDown=tilt,
                                  receiver=world(forward, up, right), onAxis=world(10, 0, 0),
                                  relativeDb=value, mirroredDb=mirror))
    return dict(scope='EXTERNAL_CLF_FORMAT_HANDEDNESS_NOT_PHYSICAL_ACCURACY', toleranceDb=0.001,
                referenceScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                provenanceSha256=hashlib.sha256((ROOT / 'provenance.json').read_bytes()).hexdigest(), cases=cases)


def equivalent(a, b):
    if isinstance(b, float):
        return abs(a-b) < 1e-12
    if isinstance(b, dict):
        return a.keys() == b.keys() and all(equivalent(a[k], v) for k, v in b.items())
    if isinstance(b, list):
        return len(a) == len(b) and all(equivalent(x, y) for x, y in zip(a, b))
    return a == b


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = generate()
    path = ROOT / 'results/reference.json'
    if args.check:
        assert equivalent(json.loads(path.read_text()), result), 'Stale CLF handedness reference'
    else:
        path.write_text(json.dumps(result, indent=2) + '\n')
    print('64 official CLF labelled direction references verified')
