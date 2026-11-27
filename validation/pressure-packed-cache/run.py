#!/usr/bin/env python3
"""Two bounded unprofiled pairs plus one candidate cost profile. Never overwrite trials."""
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
SOURCE=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/DiffuseIncidentCache.kt'
before=(OUT/'before-DiffuseIncidentCache.kt.txt').read_bytes();candidate=SOURCE.read_bytes()
records=[]
if any((OUT/f'{n:02d}-{label}').exists() for n,label in enumerate(('before','after','after','before','profile'),1)):
    raise SystemExit('Retained trials exist; use a separate package/output rather than overwriting evidence')
(OUT/'candidate-DiffuseIncidentCache.kt.txt').write_bytes(candidate)
try:
    for number,label in enumerate(('before','after','after','before','profile'),1):
        folder=OUT/f'{number:02d}-{label}';folder.mkdir(exist_ok=False)
        SOURCE.write_bytes(before if label=='before' else candidate)
        env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(folder),ACOUSTIC_CACHE_PROFILE=str(label=='profile').lower())
        assert not env.get('JAVA_TOOL_OPTIONS') and not env.get('JDK_JAVA_OPTIONS')
        command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true','--tests','*PackedCacheBenchmarkTest',
            '-Pkotlin.compiler.execution.strategy=in-process','--offline']
        print(f'{number}/5 {label}',flush=True)
        with (folder/'build.log').open('w') as log:
            subprocess.run(command,cwd=ROOT/'android',env=env,check=True,stdout=log,stderr=subprocess.STDOUT)
        xml=ROOT/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.PackedCacheBenchmarkTest.xml'
        shutil.copy2(xml,folder/'test.xml');suite=ET.parse(xml).getroot()
        assert int(suite.get('tests'))==1 and all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped'))
        rows=json.loads((folder/'costs.json').read_text())
        assert all(r['profilingEnabled']==(label=='profile') for r in rows.values())
        row=dict(run=number,variant=label,sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
            loadAverage=os.getloadavg(),command=command,times={k:r['totalSeconds'] for k,r in rows.items()})
        (folder/'run.json').write_text(json.dumps(row,indent=2)+'\n');records.append(row)
        (OUT/'runs.json').write_text(json.dumps(records,indent=2)+'\n')
        print(row['times'],flush=True)
finally:
    SOURCE.write_bytes(candidate)
    print('Candidate cache source restored',flush=True)
