#!/usr/bin/env python3
"""Verify historical-source retention and absence of retired room calculators."""
import hashlib
import json
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent.parent
package=Path(__file__).resolve().parent
manifest=json.loads((package/'archive.json').read_text())
for record in manifest['files']:
    path=ROOT/record['archive']
    assert path.name.endswith('.kt.txt')
    assert hashlib.sha256(path.read_bytes()).hexdigest()==record['sha256']
retired=['estimateRoomBounds','buildEarlyReflections','estimateRt60','atmosphericLossDbForReflection','floorBoundaryInterferenceDb','intelligibilityScore']
for folder in ['android/app/src','android/shared-core/src']:
    for path in (ROOT/folder).rglob('*.kt'):
        assert not re.search(r'\b(?:'+'|'.join(retired)+r')\s*\(',path.read_text()),f'Retired calculator returned: {path}'
material=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/scene/SurfaceMaterial.kt'
assert material.exists() and 'SceneViewModel' not in material.read_text()
print('Historical source hashes verified; six retired calculator/helper APIs absent from active Kotlin sources')
