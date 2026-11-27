#!/usr/bin/env python3
"""Run one bounded instrumented comparison in a new output folder, JDK 21."""
import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parent.parent.parent
assert not os.environ.get('JAVA_TOOL_OPTIONS') and not os.environ.get('JDK_JAVA_OPTIONS')
command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true','--tests','*FilterKnotProfileTest',
    '-Pkotlin.compiler.execution.strategy=in-process','--offline']
with (out/'build.log').open('w') as log:
    subprocess.run(command,cwd=root/'android',env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(out)),
        stdout=log,stderr=subprocess.STDOUT,check=True)
shutil.copy2(root/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.FilterKnotProfileTest.xml',out/'test.xml')
source=root/'android/app/src/test/kotlin/com/droidacoustic/pro/scene/FilterKnotProfileTest.kt'
(out/'run.json').write_text(json.dumps(dict(command=command,sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
    profilingEnabled=True,receiversPerScene=3),indent=2)+'\n')
print((out/'costs.json').read_text())
