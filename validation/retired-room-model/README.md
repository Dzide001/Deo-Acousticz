# Retired room-model cleanup

The Android bounding-room RT60 and image-source helpers had no production callers.
Their tests still counted as passing validation even though the app intentionally
does not publish those metrics or use that reflection model. This batch removes
those calculators from active code and archives their historical source/tests.
It does not replace them with a newly claimed room model.

## Retired code and preserved history

`legacy/*.kt.txt` preserves the original `Rt60Test`, `EarlyReflectionsTest`,
`SurfaceMaterialTest` and removed ViewModel helper fragments. `archive.json` pins
their SHA-256 hashes. These files are outside Gradle source sets and are historical
evidence only. They are not executed, ignored tests or acoustic accuracy evidence.

Removed APIs: `estimateRoomBounds`, `buildEarlyReflections`, `estimateRt60`,
`atmosphericLossDbForReflection`, `floorBoundaryInterferenceDb`, and the unused
internal `RoomBounds` type. The obsolete floor adapter already delegated to shared
physics; it is removed because its test bypassed the real request path. The other
helpers inferred a rectangular room, higher-order images or empirical losses and
were not the active solver. The signed horizontal-angle helper remains in place.
Existing empty/null room-metric state fields remain for API compatibility and are
checked to stay unavailable. The separate STI branch remains unmerged.

## Active validation

The six catalogue checks now live in shared common tests. `SurfaceMaterial` itself
also moves into the shared core, using the existing common octave-band list. Its
IDs, coefficients, nearest-band/endpoint lookup, scalar clamping and average stay
unchanged, preserving old project behavior. Catalogue coefficients are described
as representative legacy defaults, not verified product measurements.

Two common production-request tests verify material-dependent global-ground energy
against `(1 + sqrt(1-alpha))²` where direct and image paths coincide, and verify that
fully absorbing ground leaves only direct energy. This synthetic geometry isolates
the reflection coefficient from interference and air loss; it is not a room test.

Android `SurfaceMaterialTest` now exercises production requests and persistence:
ground curves, inactive unassigned wall/ceiling/order settings, disk save/fresh
reopen, old scalar absorption, custom curves and dirty-state behavior. It checks
that reports omit unsupported RT60/STI fields. Existing `PredictionModelCleanupTest`,
shared facet/material suites, Block/imported-face integration and reflected-phase
trace regressions cover the real experimental first-order boundary implementation.

Tests for 23 unsupported room/reflection behaviors leave the active test suite;
six catalogue cases move to the common core, the ground check migrates to production
requests, and one inactive-setting isolation check is added. A lower Android test
count is expected and does not mean those old room models became supported.

## Reproduce

```sh
python3 validation/retired-room-model/audit.py
cd android
./gradlew :shared-core:jvmTest
./gradlew :app:testDebugUnitTest --tests '*SurfaceMaterialTest' --tests '*PredictionModelCleanupTest' --tests '*UnifiedBoundaryIntegrationTest'
```

The audit checks archive hashes and rejects reintroduced calls/declarations of the
five retired helper APIs in active Kotlin sources. CI also runs it. Targeted JUnit
XML, the build log and `results/verification.json` retain the exact local checks,
including any broader phase/recovery regressions. No physical-device or iOS runtime
execution is claimed. First-order reflections/scattering remain experimental;
external calibration and manufacturer/measurement acceptance remain open.
