# Speaker-data validation results — revision 1

Reviewed 2026-11-17. The official Martin Audio XD archive was downloaded; both XD12 source
files matched the pinned local corpus hashes. Retained references are reconstructed directly
from the publisher's TAB by the independent Python script.

| Check | Result | Maximum error / observation |
|---|---|---:|
| TAB decoded relative response, 36 samples | Passed: data consistency only | 5.34057616e-07 dB |
| CF2 decoded relative response, 36 samples | Passed: data consistency only | 5.34057616e-07 dB |
| Each 27-band row against its corresponding text source | Passed | 3.05175782e-06 dB |
| Difference between the distinct TAB sensitivity and CF2 axial rows | Recorded, not forced to match | 1.1 dB |
| TAB electrical normalization | File-declared 1 W / 1 m | Not independently calibrated |
| Binary electrical normalization | Unknown | Absolute-drive comparison blocked |
| Unknown-drive comparison gate regression | NOT_COMPARABLE | No level residuals computed |
| Manufacturer-software comparison | Pending | No matched export available |
| Controlled measurement | Pending | No matched measurement available |

The unknown-drive gate is a refusal test using the relative sample set with an intentionally
unsupported absolute protocol, not an actual absolute-output measurement/export.

**47 JUnit tests passed: 15 shared-core and 32 Android, zero skipped.** Shared tests include
all 18 existing numerical fixtures, calibration declaration handling, legacy payload/hash stability,
provenance round trips and strict matched-comparison gates. Android tests cover real manufacturer
files, imported calibration, save/reopen, missing/corrupt assets and importer regressions.
Android debug assembly and iOS simulator shared-test compilation passed. No device installation,
physical calibration or numerical execution on iOS was performed.

`engine.json` retains input/reference/decoded-asset hashes, raw directional residuals, scope labels
and both calibration reports. `reference.json` retains sample identities, coordinates, values and
script/source hashes. The numerical package's refreshed engine snapshot records the current shared
Kotlin source-tree hash. See [reproduction instructions](../README.md).
