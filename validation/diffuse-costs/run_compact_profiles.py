#!/usr/bin/env python3
"""Matched diagnostic profiles with the final single-copy timer helper.
Only the pinned solver file is temporarily replaced; do not build/edit concurrently.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
OUT=HERE/'results'
source=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/CoherentDiffuse.kt'
candidate=source.read_bytes()
assert candidate==(OUT/'candidate-source.kt.txt').read_bytes()
for variant in ('before','after'):
    original=OUT/variant
    if original.exists():original.rename(OUT/('initial-'+variant))
baseline=(OUT/'initial-before/profiled-source.kt.txt').read_text().replace(
    '.also {diagnostics?.surfaceSamples=diagnostics?.surfaceSamples?.plus(it.size) ?: 0L}',
    '.also {samples->diagnostics?.let {it.surfaceSamples+=samples.size}}').encode()
try:
    for variant,data in (('before',baseline),('after',candidate)):
        folder=OUT/variant;folder.mkdir(exist_ok=False)
        source.write_bytes(data);(folder/'source.kt.txt').write_bytes(data)
        env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(folder))
        command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true',
            '--tests','*DiffuseCostProfileTest','-Pkotlin.compiler.execution.strategy=in-process','--offline']
        print(variant+': final timer helper, JFR disabled',flush=True)
        with (folder/'build.log').open('w') as log:
            subprocess.run(command,cwd=ROOT/'android',env=env,check=True,stdout=log,stderr=subprocess.STDOUT)
        assert '[jfr,startup]' not in (folder/'build.log').read_text()
        shutil.copyfile(ROOT/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.DiffuseCostProfileTest.xml',folder/'profile-test.xml')
        (folder/'run.json').write_text(json.dumps(dict(command=command,jfrEnabled=False,
            sourceSha256=hashlib.sha256(data).hexdigest(),timerHelperSha256=hashlib.sha256(
                (ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/DiffuseDiagnostics.kt').read_bytes()).hexdigest()),indent=2)+'\n')
        print((folder/'costs.json').read_text(),flush=True)
finally:
    source.write_bytes(candidate)
    print('Candidate source restored',flush=True)
