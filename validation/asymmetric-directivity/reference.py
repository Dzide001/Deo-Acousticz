#!/usr/bin/env python3
"""Independent native-frame directivity oracle. Standard library only; no engine imports."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# Deliberately unsorted signed azimuths exercise normalization and seam lookup.
AZ = [90, 0, -90, 45, -180, -45]
EL = [-45, 0, 45]
LOSS = {0: 0, 45: -18, 90: -28, 180: -40, 270: -9, 315: -3}
VERTICAL = [-5, 0, -12]
BANDS = {500: 87, 1000: 93, 4000: 101}


def attenuation(az, el):
    a = az % 360
    keys = sorted(LOSS) + [360]
    lo, hi = next((x, y) for x, y in zip(keys, keys[1:]) if x <= a <= y)
    horizontal = LOSS[lo] + (LOSS[hi % 360] - LOSS[lo]) * (a - lo) / (hi - lo)
    return horizontal + (-5 * (-el / 45) if el < 0 else -12 * el / 45)


def world(yaw, tilt, az, el):
    # Independent active matrices: R_y(-yaw) R_z(-tilt), in X-forward/Y-up/Z-right.
    y, t, a, e = map(math.radians, (yaw, tilt, az, el))
    x, up, right = 10 * math.cos(e) * math.cos(a), 10 * math.sin(e), 10 * math.cos(e) * math.sin(a)
    pitched_x = math.cos(t) * x + math.sin(t) * up
    pitched_y = -math.sin(t) * x + math.cos(t) * up
    return [2 + math.cos(y) * pitched_x - math.sin(y) * right,
            3 + pitched_y, -4 + math.sin(y) * pitched_x + math.cos(y) * right]


def outputs():
    speaker = dict(speakerId='synthetic-asymmetric', model='SYNTHETIC ONLY: left loud/right quiet',
                   sensitivityByBand={str(k): v for k, v in BANDS.items()}, patterns=[
                       dict(frequency=f, azimuths=AZ, elevations=EL,
                            spl=[[LOSS[a % 360] + v for v in VERTICAL] for a in AZ]) for f in BANDS])
    directions = [(0, 0), (-45, 0), (45, 0), (-22.5, 0), (22.5, 0),
                  (-90, 0), (90, 0), (-179, 0), (179, 0), (-22.5, 22.5), (22.5, -22.5)]
    cases = []
    for yaw, tilt in [(0, 0), (90, 0), (-90, 0), (179, 25), (-181, 25), (37, -35)]:
        for f in BANDS:
            for az, el in directions:
                cases.append(dict(yaw=yaw, tiltDown=tilt, frequency=f, azimuth=az, elevation=el,
                                  receiver=world(yaw, tilt, az, el), onAxis=world(yaw, tilt, 0, 0),
                                  relativeDb=attenuation(az, el), mirroredDb=attenuation(-az, el)))
    return {'speaker.json': speaker, 'results/reference.json': dict(
        scope='SYNTHETIC_NATIVE_DECODED_SPEAKER_ORIENTATION_ONLY', toleranceDb=0.002,
        scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), cases=cases)}


def equivalent(actual, expected):
    # Permit platform libm rounding only, far below the engine tolerance.
    if isinstance(expected, float):
        return isinstance(actual, (int, float)) and abs(actual - expected) <= 1e-12
    if isinstance(expected, dict):
        return isinstance(actual, dict) and actual.keys() == expected.keys() and all(
            equivalent(actual[k], v) for k, v in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(
            equivalent(a, b) for a, b in zip(actual, expected))
    return actual == expected


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for name, value in outputs().items():
        path = ROOT / name
        if args.check:
            assert equivalent(json.loads(path.read_text()), value), f'Stale reference: {name}'
        else:
            path.write_text(json.dumps(value, indent=2) + '\n')
    print('198 asymmetric orientation references verified (including mirrored negative controls)')
