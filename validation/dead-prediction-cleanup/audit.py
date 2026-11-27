#!/usr/bin/env python3
"""Prevent duplicate Android prediction paths from silently returning."""
import re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
vm=(ROOT/'android/app/src/main/kotlin/com/droidacoustic/pro/scene/SceneViewModel.kt').read_text()
retired=['elementSplDb','lineArraySplDb','arrayPressure','directContribution','directSplDb','directPathSplDb',
    'coherentSumDb','referenceLevelDb','perElementClfActive','clfDirectivityAttenuationDb',
    'horizontalDirectivityAttenuationDb','verticalAimAttenuationDb','combineAcrossBands','refSplDb',
    'atmosphericLossDb','intelligibilityScore','applyOptimizerParams']
assert not re.search(r'\bfun\s+(?:'+'|'.join(retired)+r')\b',vm)
assert not re.search(r'\bCoherentContribution\b|\boptimizerJob\b',vm)
assert 'fun runOptimization() = Unit' in vm
assert 'private fun predictionSources(' in vm and 'AlignmentSource(s.id,predictionSources(s,dsp,air)' in vm
assert 'internal fun capturePredictionRequest(' in vm and 'SourceSnapshot(' in vm
assert not (ROOT/'android/app/src/main/kotlin/com/droidacoustic/pro/optimizer/ArrayCalcOptimizer.kt').exists()
for name in ('DspChainTest.kt','LineArrayDirectivityTest.kt','MeasuredDirectivityTest.kt'):
    test=(ROOT/'android/app/src/test/kotlin/com/droidacoustic/pro/scene'/name).read_text()
    assert not re.search(r'\.(?:'+'|'.join(retired)+r')\s*\(',test)
subprocess.run([sys.executable,str(ROOT/'validation/retired-room-model/audit.py')],check=True)
print('Retired Android predictors absent; shared request path, live alignment and disabled optimizer contract retained')
