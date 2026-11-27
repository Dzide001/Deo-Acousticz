# Production-request test migration — 2026-11-27

Removed duplicate Android prediction helpers from `SceneViewModel`: per-element and
array SPL/pressure, direct paths, coherent/band summation, empirical directivity,
air-loss and metadata adapters. Retired the dormant approximate optimizer solver;
its state types, audience-grid support and disabled public entry point remain.
The live alignment source adapter and immutable request capture match the baseline
byte-for-byte (see `results/production-path-checks.json`). Shared production sources,
project serialization and viewport footprint calculations are unchanged.

## Active checks

| Previous check | Replacement |
| --- | --- |
| Android `elementSplDb` arithmetic | Common `DspPredictionRequestTest`: sensitivity, distance, gain, exact-band EQ, delay and polarity interference |
| Private Android angle/aim helpers | Common `DirectivityPredictionRequestTest`: actual fixed-radius receiver predictions, yaw, tilt and array orientation |
| Android air-loss adapter | Common `AirPropagationRequestTest`: request output versus independent attenuation anchors and distance scaling |
| Setter-only DSP assertions | Android `DspChainTest`: clamping, neighboring/absent EQ, immutable capture, digital filters and disk save/fresh reopen |
| Test-only angle and metadata facades | Actual Android prediction requests or shared measured-radiator metadata contract |
| Empirical intelligibility score | Archived with its 16 tests; unsupported, no replacement metric claimed |

The common request suite adds 29 numerical cases. These are synthetic numerical
regressions, not manufacturer or physical validation. Shared tests compile for the
iOS simulator target; no iPad runtime is claimed. Android debug and release assembly
are build checks, not signed-release or device acceptance. This cleanup does not
change diffuse performance or qualify high-frequency full-venue calculations.

Validation passed **696 shared + 407 Android tests**, with no failures or skips.
The Android count falls because numerical checks moved to common tests and 16 unsupported
score tests were retired. Debug/release assembly and iOS shared-test compilation passed.

## Reproduce

From `android`, with JDK 21 and existing dependencies:

```sh
./gradlew :shared-core:jvmTest :shared-core:compileTestKotlinIosSimulatorArm64 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
  -PacousticValidationTier=full -Pkotlin.compiler.execution.strategy=in-process --offline
```

From the repository root:

```sh
python3 validation/dead-prediction-cleanup/audit.py
python3 validation/fixture-permissions/audit.py
git diff --check
```

`results/verification.json` records actual run totals and source hashes; the full
build log, test inventory and affected JUnit reports are retained alongside it.
The cleanup audit also runs in CI. The solver and unsupported-score archives are
hash-pinned under `validation/retired-room-model`, outside compiled source sets.
The STI branch remains unmerged. No tablet project was modified for this task.
