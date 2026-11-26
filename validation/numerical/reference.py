#!/usr/bin/env python3
"""Independent scalar reference; Python standard library only, no engine imports.

Point-source p/p_ref = 10^(L/20) / r * 10^(-alpha*r/20).
Phase convention: exp(+j*2*pi*f*(r/c+delay)); polarity multiplies by -1.
Band reference: exact pairwise rectangular-band integral. Independent midpoint
quadrature verifies that integral using direct complex sums, not the sinc formula.
Air: ISO 9613-1 equation implementation; this is equation verification, not
independent experimental validation of atmospheric attenuation.
"""
import argparse
import cmath
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def air(hz):
    # Fixed fixture environment: 20 C, 50% RH, 101.325 kPa.
    c = 331.3 * math.sqrt(1 + 20 / 273.15)
    h = 50 * 10 ** (-6.8346 * (273.16 / 293.15) ** 1.261 + 4.6151)
    oxygen = 24 + 40400 * h * (0.02 + h) / (0.391 + h)
    nitrogen = 9 + 280 * h
    alpha = 8.686 * hz**2 * (1.84e-11 +
        0.01275 * math.exp(-2239.1 / 293.15) / (oxygen + hz**2 / oxygen) +
        0.1068 * math.exp(-3352 / 293.15) / (nitrogen + hz**2 / nitrogen))
    return c, alpha


def paths(case, hz=None):
    c, alpha = air(case['hz'] if hz is None else hz)
    arrivals = []
    receiver = case['receiver']
    for source in case['sources']:
        position = source['position']
        coefficient = -1 if source.get('inverted', False) else 1
        gain = 10 ** ((100 + source.get('gainDb', 0)) / 20) * coefficient
        def add(point, reflection=1):
            distance = math.dist(position, point)
            assert distance >= 0.1  # No near-distance clamp exercised here.
            arrivals.append((gain / distance * 10 ** (-alpha * distance / 20) * reflection,
                             distance / c + source.get('delayS', 0)))
        wall = case.get('wall')
        blocked = False
        if wall and position[0] * receiver[0] < 0:
            t = -position[0] / (receiver[0] - position[0])
            y = position[1] + t * (receiver[1] - position[1])
            z = position[2] + t * (receiver[2] - position[2])
            blocked = 0 < y < wall['height'] and abs(z) < wall['halfWidth']
        if not blocked:
            add(receiver)
        if 'groundAbsorption' in case:
            add([receiver[0], -receiver[1], receiver[2]], math.sqrt(1 - case['groundAbsorption']))
        if wall and wall['role'] == 'RIGID_REFLECTOR' and position[0] * receiver[0] > 0:
            t = position[0] / (position[0] + receiver[0])
            y = position[1] + t * (receiver[1] - position[1])
            z = position[2] + t * (receiver[2] - position[2])
            if 0 < y < wall['height'] and abs(z) < wall['halfWidth']:
                add([-receiver[0], receiver[1], receiver[2]])
    return arrivals


def analytic(case, arrivals):
    if case['kind'] == 'tone':
        p = sum(a * cmath.exp(2j * math.pi * case['hz'] * t) for a, t in arrivals)
        return abs(p)**2
    f1, f2 = case['band']
    total = sum(a*a for a, _ in arrivals)
    for i, (a, t) in enumerate(arrivals):
        for b, u in arrivals[:i]:
            q = math.pi * (f2-f1) * (t-u)
            sinc = math.sin(q)/q if q else 1
            total += 2*a*b*math.cos(math.pi*(f1+f2)*(t-u))*sinc
    return max(0, total)


def midpoint(case, arrivals, n):
    f1, f2 = case['band']
    return math.fsum(abs(sum(a*cmath.exp(2j*math.pi*(f1+(i+0.5)*(f2-f1)/n)*t)
                             for a, t in arrivals))**2 for i in range(n))/n


def generate():
    fixture_bytes = (ROOT/'fixtures.json').read_bytes()
    fixtures = json.loads(fixture_bytes)
    assert fixtures['schema'] == 1
    assert fixtures['environment'] == dict(temperatureC=20, humidityPct=50, pressureKPa=101.325)
    assert len({c['id'] for c in fixtures['cases']}) == len(fixtures['cases'])
    rows = []
    for case in fixtures['cases']:
        arrivals = paths(case)
        expected = analytic(case, arrivals)
        refinement = case['kind'] == 'band-refinement'
        def varying(n):
            f1, f2 = case['band']
            return math.fsum(abs(sum(a*cmath.exp(2j*math.pi*f*t) for a,t in paths(case,f)))**2
                             for f in (f1+(i+0.5)*(f2-f1)/n for i in range(n)))/n
        if refinement:
            expected = varying(65536)
        # Null-safe scale: incoherent sum of path powers, not near-zero result.
        scale = max(1.0, math.fsum(a*a for a, _ in arrivals))
        row = {'id': case['id'], 'classification': case['classification'],
               'power': expected, 'scale': scale, 'normalizedTolerance': case['normalizedTolerance']}
        if case['kind'] == 'tone':
            pressure = sum(a * cmath.exp(2j*math.pi*case['hz']*t) for a,t in arrivals)
            row['complexPressure'] = [pressure.real, pressure.imag]
        if refinement:
            row['convergence'] = [{'samples': n, 'power': varying(n)} for n in [256,1024,4096,16384]]
            errors = [abs(q['power']-expected)/scale for q in row['convergence']]
            assert errors[-1] < 2e-6 and errors[-1] <= max(errors[0]/100,1e-12), errors
        if case['kind'] == 'band' and not case.get('wall'):
            row['convergence'] = [{'samples': n, 'power': midpoint(case, arrivals, n)}
                                  for n in [64, 256, 1024, 4096]]
            errors = [abs(q['power']-expected)/scale for q in row['convergence']]
            assert errors[-1] <= 2e-6, (case['id'], errors)
            # Permit roundoff once already converged; no assertion of universal monotonicity.
            assert errors[-1] <= max(errors[0]/100, 1e-12), (case['id'], errors)
        rows.append(row)
    return {'schema': 1, 'referenceScriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'fixtureSha256': hashlib.sha256(fixture_bytes).hexdigest(), 'cases': rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='Verify retained reference results without replacing them')
    args = parser.parse_args()
    result = generate()
    destination = ROOT/'results/reference.json'
    if args.check:
        retained = json.loads(destination.read_text())
        assert retained['referenceScriptSha256'] == result['referenceScriptSha256'], 'Reference script changed: regenerate and review results'
        assert retained['fixtureSha256'] == result['fixtureSha256'], 'Fixtures changed: regenerate and review references'
        assert len(retained['cases']) == len(result['cases'])
        for old, new in zip(retained['cases'], result['cases']):
            assert old['id'] == new['id'] and old['classification'] == new['classification']
            assert old['normalizedTolerance'] == new['normalizedTolerance']
            assert len(old.get('complexPressure',[])) == len(new.get('complexPressure',[]))
            for a,b in zip(old.get('complexPressure',[]), new.get('complexPressure',[])):
                assert abs(a-b) <= 1e-12 * math.sqrt(new['scale'])
            for key in ('power', 'scale'):
                assert abs(old[key]-new[key]) <= 1e-12 * new['scale'], (new['id'], key)
            assert len(old.get('convergence', [])) == len(new.get('convergence', []))
            for a, b in zip(old.get('convergence', []), new.get('convergence', [])):
                assert a['samples'] == b['samples']
                assert abs(a['power']-b['power']) <= 1e-12 * new['scale']
    else:
        destination.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
    print(f"{len(result['cases'])} reference cases and quadrature convergence checks passed")


if __name__ == '__main__':
    main()
