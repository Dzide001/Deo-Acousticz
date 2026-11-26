#!/usr/bin/env python3
"""Reproduce measured band ratios and disjoint angular training/holdout sets; no engine imports."""
import argparse, hashlib, io, json, math, zipfile
from pathlib import Path
import numpy as np
from scipy.io import loadmat

ROOT=Path(__file__).resolve().parent

def digest(data): return hashlib.sha256(data).hexdigest()
def encoded(value): return json.dumps(value,ensure_ascii=False,allow_nan=False,indent=2)+'\n'
def prepare():
    p=json.loads((ROOT/'protocol.json').read_text())
    with zipfile.ZipFile(ROOT/'source/JBLPro_PRX635_H_IR.mat.zip') as z:
        raw=z.read('JBLPro_PRX635_H_IR.mat')
    assert digest(raw)==p['sourceMatSha256'], 'Original measurement changed'
    m=loadmat(io.BytesIO(raw)); fs=float(m['fs_H'].item()); ir=m['IR_H'].astype(np.float64)
    assert fs==p['sampleRateHz'] and ir.shape==(72,16384)
    assert np.isfinite(ir).all() and (np.max(np.abs(ir),axis=1)>0).all(), 'Missing measurement rows'
    # Window is fixed by the documented onset, not re-centred on each holdout peak.
    t=(np.arange(ir.shape[1])-p['irOnsetSample'])/fs
    rise=p['windowFadeInMs']/1000; flat=p['windowFlatMs']/1000; fall=p['windowFadeOutMs']/1000
    window=np.zeros(ir.shape[1]); active=(t>=-rise)&(t<0)
    window[active]=.5-.5*np.cos(np.pi*(t[active]+rise)/rise)
    window[(t>=0)&(t<=flat)]=1
    active=(t>flat)&(t<=flat+fall)
    window[active]=.5+.5*np.cos(np.pi*(t[active]-flat)/fall)
    power=np.abs(np.fft.rfft(ir*window,axis=1))**2
    frequencies=np.fft.rfftfreq(ir.shape[1],1/fs); df=fs/ir.shape[1]
    relative={}; on_axis={}; edges={}
    for hz in p['bandsHz']:
        low=hz*2**(-p['bandwidthOct']/2); high=hz*2**(p['bandwidthOct']/2)
        # Integrate constant-power FFT cells, clipped at the exact analysis edges.
        weights=np.maximum(0,np.minimum(high,frequencies+df/2)-np.maximum(low,frequencies-df/2))
        assert abs(weights.sum()-(high-low))<1e-8
        means=(power@weights)/(high-low)
        assert np.isfinite(means).all() and (means>0).all()
        on_axis[str(hz)]=float(10*np.log10(means[0])+105)
        relative[hz]=10*np.log10(means/means[0]);edges[str(hz)]=[low,high]
    def response(angle,hz):return float(relative[hz][(angle%360)//5])
    cases=[]
    for step in p['trainingStepsDeg']:
        training=list(range(-60,61,step)); held=p['holdoutAnglesDeg']
        assert not set(training)&set(held) and 0 in training
        # Use all measured training directions around the circle, not fabricated rear values.
        angles=list(range(0,360,step))
        assert not {a%360 for a in held}&set(angles)
        asset=dict(speakerId=f'prx635-public-horizontal-{step}deg',manufacturer='JBL',model=p['speaker']+' relative horizontal subset',
            measurementGrid='OCTAVE',referenceCalibration=dict(distanceM=1.0,driveUnit='UNKNOWN',
                provenance='Relative-only computational 0 dB on-axis normalization; not absolute SPL or electrical calibration'),
            sensitivityByBand={str(hz):0.0 for hz in p['bandsHz']},patterns=[dict(frequency=hz,azimuths=[float(a) for a in angles],elevations=[0.0],
                # Engine azimuth +Z is clockwise from above, opposite to published speaker-left angles.
                spl=[[response(-a,hz)] for a in angles]) for hz in p['bandsHz']],
            provenance=dict(origin='USER_SUPPLIED',sourceName='Princeton 3D3A calibrated measurements',sourceUrl=p['sourceUrl'],
                sourceSha256=p['sourceMatSha256'],importer='public-measurements/prepare.py v1',
                operatingPreset='Frozen measured system response; original operating preset undocumented; no additional DSP',
                normalizationNotes='On-axis normalized finite-band powers; training angles only; no extrapolation to other elevations'))
        samples=[]
        predicted=[]
        for angle in held:
            r=p['distanceM'];theta=math.radians(angle)
            for hz in p['bandsHz']:
                actual=response(angle,hz)
                samples.append(dict(id=f'h{angle:+d}:{hz}',frequencyHz=hz,xM=r*math.cos(theta),yM=0.0,zM=-r*math.sin(theta),levelDb=actual))
                # Independent dB-linear angular interpolation predicts the production model at held-out angles.
                lo=math.floor(angle/step)*step;hi=lo+step;f=(angle-lo)/step
                predicted.append(response(lo,hz)*(1-f)+response(hi,hz)*f)
        residual=[a-b['levelDb'] for a,b in zip(predicted,samples)]
        cases.append(dict(id=f'prx635-{step}deg',trainingStepDeg=step,trainingAnglesDeg=angles,
            asset=asset,samples=samples,expectedEngineLevelsDb=predicted,expectedResidualsDb=residual,
            expectedRmsDb=math.sqrt(sum(v*v for v in residual)/len(residual)),expectedMaxDb=max(map(abs,residual))))
    return dict(schema=1,scope='REAL_MEASURED_HORIZONTAL_ANGLE_HOLDOUT_NOT_INDEPENDENT_SESSION',
        sourceMatSha256=p['sourceMatSha256'],preparationScriptSha256=digest(Path(__file__).read_bytes()),
        protocolSha256=digest((ROOT/'protocol.json').read_bytes()),onAxisMeasuredBandLevelsDb=on_axis,bandEdgesHz=edges,cases=cases)

def equivalent(actual,expected,path="root"):
    if isinstance(actual,bool) or isinstance(expected,bool): assert actual is expected,path
    elif isinstance(actual,(int,float)) and isinstance(expected,(int,float)):
        assert math.isclose(actual,expected,rel_tol=0,abs_tol=1e-10),path
    elif isinstance(actual,dict) and isinstance(expected,dict):
        assert actual.keys()==expected.keys(),path
        for key in actual: equivalent(actual[key],expected[key],path+"."+key)
    elif isinstance(actual,list) and isinstance(expected,list):
        assert len(actual)==len(expected),path
        for i,(a,b) in enumerate(zip(actual,expected)): equivalent(a,b,path+f"[{i}]")
    else: assert actual==expected,path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    target=ROOT/'fixtures.json';data=encoded(prepare())
    if args.check:
        equivalent(json.loads(data),json.loads(target.read_text()))
        print('Public calibrated-measurement preparation reproduces within 1e-10 dB/numeric tolerance; metadata hashes exact')
    else: target.write_text(data)
if __name__=='__main__':main()
