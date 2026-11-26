# EQ isolation and broadband drive normalization — 2026-11-26

Passed: 615 shared JVM tests, 19 focused Android tests, Android debug build and iOS shared-core
test compilation. [OnePlus acceptance](../device/drive-eq-2026-11-26/README.md) passed. The original
project and imported assets were restored and verified; the tested build remains installed.

## Semantics and migration

Shared DiscreteBandEq applies an offset only at its stored frequency. Missing EQ keys mean 0 dB,
not the nearest populated gain. Both production SourceSnapshot emitters and the remaining Android
source adapter use it. Legacy saved maps retain their entries, gain, delay, polarity and filters;
the erroneous leakage into unset bands is corrected. These offsets remain magnitude-only legacy
data and are not substituted for validated digital filters or their phase.

Shared BroadbandDrive uses the existing BroadbandSourceSpectrum allocation:

| Policy | Relative mean-square drive per octave | Total across eight octaves |
| --- | --- | --- |
| TOTAL_EQUAL_BAND_POWER — default for new scenes | 1/8 | 1 |
| PER_BAND_REFERENCE — legacy compatibility | 1 | 8 |

The reference is relative to each emitting source/model; it is not a universal 1 W claim and does
not normalize total venue power across cabinets. A/C weighting is applied to the resulting band
powers after drive allocation. Band mode is unchanged. Common positive scaling preserves coherent
interference, reflected/diffuse proportions and phase. Missing required bands remain unavailable.
The common map presets are positive at every band; arbitrary custom comparison allocations retain
their existing restrictions on phase capture.

Projects without broadbandDrive always load as PER_BAND_REFERENCE. The selected field is then
saved explicitly. New models and Clear/New scenes use fixed total. Schema 31 prevents older
readers from silently applying historical drive to a fixed-total project. Invalid policies fail before
scene mutation. Undo/Redo and variants use the same scene field. Comparison captures deliberately
retain their separately declared source-spectrum protocol; their controls already identify that
they do not change coverage maps.

UI: the compact map convention label says total drive or per-band drive. Settings → Signal →
Broadband relative drive offers Fixed total and Per-band reference. Existing projects keep their
historical drive until the user selects Fixed total; no unexplained 9 dB migration occurs on reopen.

## Numerical verification

DriveAndEqTest uses production immutable requests to check:

- +6 dB at a stored 1 kHz EQ key and 0 dB change at all other displayed octave centres.
- Multiple positive/negative offsets leave unconfigured frequencies neutral.
- Fixed-total power sums to one, and removes exactly 10 log10(8) = 9.0308998699 dB versus legacy
  for Z, A and C weighting. A one-metre, one-source analytic energy equation including the known
  air model agrees within 0.00002 dB. The independent no-air reference is retained separately;
  it must not be mistaken for the air-attenuated engine result.
- Single-band and explicit legacy behavior stay unchanged.
- Valid measured complex samples and validated low-pass filter phase remain identical under
  drive normalization. Phase samples are provided at the actual test frequencies; unsupported
  phase interpolation is not assumed.
- Required missing broadband responses are not dropped or reweighted.

DriveAndEqRecoveryTest checks new defaults, absent-field legacy migration, invalid-input atomicity,
old sparse EQ projects, file-store save/reopen, both policies, stale-result clearing, Undo/Redo and
Clear/New defaults. Existing comparison capture/recovery and missing-data tests also pass. The
full shared suite provides regression coverage for the remaining acoustic paths. This is numerical
verification, not new manufacturer equivalence, maximum-output or field-accuracy qualification.

Reproduce from android/ with JDK 21:

```sh
./gradlew :shared-core:jvmTest :shared-core:compileTestKotlinIosSimulatorArm64 \
  :app:testDebugUnitTest --tests '*DriveAndEqRecoveryTest' \
  --tests '*CoverageModeControlsTest' --tests '*PredictionValidityRecoveryTest' \
  --tests '*EngineComparisonCaptureTest' :app:assembleDebug \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

normalization_reference.py independently produces ideal no-air Z/A/C totals. results/ retains
those values, JUnit reports, suite counts and build logs. The OnePlus records UI-rounded observations.
