#!/usr/bin/env python3
"""Two balanced unprofiled pairs. Restore exchanged shared files in finally.
Do not build/edit the workspace concurrently. Existing trials are never overwritten.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
spec=json.loads((OUT/'variants.json').read_text())
candidate={name:(ROOT/name).read_bytes() for name in spec['files']}
baseline={name:(OUT/('before-'+Path(name).name+'.txt')).read_bytes() for name in spec['files']}
digest=lambda b:hashlib.sha256(b).hexdigest()
for name in candidate:
    assert digest(candidate[name])==spec['files'][name]['candidateSha256']
    assert digest(baseline[name])==spec['files'][name]['beforeSha256']
assert not os.environ.get('JAVA_TOOL_OPTIONS') and not os.environ.get('JDK_JAVA_OPTIONS')
expected=None
reference=None
runs=[]
try:
    for number,variant in enumerate(spec['order'],1):
        folder=OUT/f'{number:02d}-{variant}';folder.mkdir(exist_ok=False)
        for name,data in (baseline if variant=='before' else candidate).items():(ROOT/name).write_bytes(data)
        env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(folder))
        command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true','--tests','*DistinctFilterBenchmarkTest',
            '-Pkotlin.compiler.execution.strategy=in-process','--offline']
        print(f'{number}/2 {variant}: fresh JVM, clocks/JFR disabled',flush=True)
        load=os.getloadavg()
        with (folder/'build.log').open('w') as log:
            subprocess.run(command,cwd=ROOT/'android',env=env,check=True,stdout=log,stderr=subprocess.STDOUT)
        assert '[jfr,startup]' not in (folder/'build.log').read_text()
        xml=ROOT/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.DistinctFilterBenchmarkTest.xml'
        shutil.copyfile(xml,folder/'test.xml');suite=ET.parse(xml).getroot()
        assert int(suite.get('tests'))==1 and all(int(suite.get(k,0))==0 for k in ('errors','failures','skipped'))
        result=json.loads((folder/'observation.json').read_text())
        assert result['emitters']==64 and result['filtersPerEmitter']==8 and result['receiverCount']==333
        assert result['validAreaPercent']==100 and result['profilingEnabled'] is False
        fixture=json.loads((folder/'fixture.json').read_text())
        chains=[d['filters'] for d in fixture['dsp']]
        assert len(chains)==4 and len({json.dumps(c,sort_keys=True) for c in chains})==4
        assert all(len(c['filters'])==8 for c in chains)
        assert {f['sampleRateHz'] for c in chains for f in c['filters']}=={48000,96000}
        if reference is None:
            reference=result
            expected=dict(meanDb=result['meanDb'],displayedMeanDb=round(result['meanDb'],1),
                fixtureSha256=digest((folder/'fixture.json').read_bytes()))
            (OUT/'expectation.json').write_text(json.dumps(expected,indent=2)+'\n')
            shutil.copy2(folder/'fixture.json',OUT/'pinned-fixture.json')
        assert digest((folder/'fixture.json').read_bytes())==expected['fixtureSha256']
        assert abs(result['meanDb']-expected['meanDb'])<=1e-5
        assert abs(result['listenerDb']-reference['listenerDb'])<=.001
        for key in ('sourceLevelsDb','levelsDb'):
            assert len(result[key])==len(reference[key])
            assert all(abs(a-b)<=.001 for a,b in zip(result[key],reference[key]))
        assert len(result['powers'])==333
        assert all(abs(a-b)<=max(1e-10,abs(a)*1e-9) for a,b in zip(result['powers'],reference['powers']))
        row=dict(run=number,variant=variant,listenerSeconds=result['listenerSeconds'],coverageSeconds=result['coverageSeconds'],
            meanDb=result['meanDb'],loadAverage=load,command=command,sourceSha256={n:digest((ROOT/n).read_bytes()) for n in spec['files']})
        (folder/'run.json').write_text(json.dumps(row,indent=2)+'\n');runs.append(row)
        (OUT/'runs.json').write_text(json.dumps(runs,indent=2)+'\n')
        print(f'  listener {row["listenerSeconds"]:.3f}s; coverage {row["coverageSeconds"]:.3f}s; {row["meanDb"]:.9f}dB',flush=True)
finally:
    for name,data in candidate.items():(ROOT/name).write_bytes(data)
    print('Candidate shared files restored',flush=True)
