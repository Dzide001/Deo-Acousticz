#!/usr/bin/env python3
"""Three fresh-worker paired comparisons; restore only the exchanged solver in finally.
Do not build/edit this workspace concurrently. Existing result folders are never overwritten.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
OUT=HERE/'results'
source=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/CoherentDiffuse.kt'
candidate=source.read_bytes();baseline=(OUT/'baseline-source.kt.txt').read_bytes()
digest=lambda b:hashlib.sha256(b).hexdigest()
variants=json.loads((OUT/'variants.json').read_text())
assert digest(candidate)==variants['candidateSha256'] and digest(baseline)==variants['baselineSha256']
assert not os.environ.get('JAVA_TOOL_OPTIONS') and not os.environ.get('JDK_JAVA_OPTIONS')
rows=[];reference=None;fixture_hash=None
try:
    for number,variant in enumerate(variants['order'],1):
        folder=OUT/f'{number:02d}-{variant}';folder.mkdir(exist_ok=False)
        data=baseline if variant=='before' else candidate
        source.write_bytes(data)
        env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(folder))
        command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true',
            '--tests','*FilterHeavyBenchmarkTest','-Pkotlin.compiler.execution.strategy=in-process','--offline']
        conditions=dict(loadAverage=os.getloadavg(),logicalCpuCount=os.cpu_count(),machine=platform.machine())
        print(f'{number}/6 {variant}: unprofiled fresh JVM worker',flush=True)
        with (folder/'build.log').open('w') as log:
            subprocess.run(command,cwd=ROOT/'android',env=env,check=True,stdout=log,stderr=subprocess.STDOUT)
        text=(folder/'build.log').read_text();assert 'BUILD SUCCESSFUL' in text and '[jfr,startup]' not in text
        xml=ROOT/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.FilterHeavyBenchmarkTest.xml'
        shutil.copyfile(xml,folder/'test.xml')
        suite=ET.parse(xml).getroot();assert int(suite.get('tests'))==1
        assert all(int(suite.get(key,0))==0 for key in ('errors','failures','skipped'))
        observed=json.loads((folder/'observation.json').read_text())
        assert observed['emitters']==64 and observed['filtersPerEmitter']==8 and observed['receiverCount']==333
        assert observed['validAreaPercent']==100 and observed['profilingEnabled'] is False
        current_hash=digest((folder/'fixture.json').read_bytes())
        if reference is None:
            reference=observed;fixture_hash=current_hash
            (OUT/'expectation.json').write_text(json.dumps(dict(meanDb=reference['meanDb'],
                displayedMeanDb=round(reference['meanDb'],1),fixtureSha256=fixture_hash),indent=2)+'\n')
        assert current_hash==fixture_hash,'Prediction inputs changed'
        assert abs(reference['meanDb']-observed['meanDb'])<=1e-5
        assert abs(reference['listenerDb']-observed['listenerDb'])<=.001
        for key in ('levelsDb','sourceLevelsDb'):
            assert len(reference[key])==len(observed[key])
            assert all(abs(a-b)<=.001 for a,b in zip(reference[key],observed[key])),key
        assert all(abs(a-b)<=max(1e-10,abs(a)*1e-9) for a,b in zip(reference['powers'],observed['powers']))
        row=dict(run=number,variant=variant,listenerSeconds=observed['listenerSeconds'],
            coverageSeconds=observed['coverageSeconds'],meanDb=observed['meanDb'],
            sourceSha256=digest(data),fixtureSha256=current_hash,conditions=conditions,command=command)
        (folder/'run.json').write_text(json.dumps(row,indent=2)+'\n');rows.append(row)
        (OUT/'runs.json').write_text(json.dumps(rows,indent=2)+'\n')
        print(f'  listener {row["listenerSeconds"]:.3f}s; coverage {row["coverageSeconds"]:.3f}s; {row["meanDb"]:.9f}dB',flush=True)
finally:
    source.write_bytes(candidate)
    print('Candidate shared solver restored',flush=True)
