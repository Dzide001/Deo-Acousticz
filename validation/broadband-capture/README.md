# Weighted broadband engine snapshots — revision 1

Select **Spectrum** and A, C or Z weighting in Android, import a broadband reference package, then
use **Capture current scene at reference samples**. Shared-core capture freezes the actual scene,
bands and weighting; the imported reference supplies only receiver IDs/locations and the requested
metric type. It cannot override the scene's weighting or band range. Both reference and captured
engine weighting are visible in Settings.

## Defined metric

`P_total = sum(P_band * 10^(weightDb_band / 10)); L_total = 10 log10(P_total)`

Each `P_band` is the production octave-band mean-square pressure, integrating the shared third-band
data cells/actual edges. Source interference is calculated within bands as configured; bands add
as independent powers. There is no addition/averaging of dB, second weighting pass, or display-floor
substitution. If any required band is missing/non-finite, the aggregate is unavailable (`null`).
A zero-power band contributes zero; a nonpositive aggregate has no finite dB value.

The Android spectrum currently uses eight nominal octave centres: **63, 125, 250, 500, 1000, 2000,
4000 and 8000 Hz**. Actual low/high edges and data cells are recorded in `frequencyConvention`;
centre labels are not the exact overall integration endpoints. In particular the top octave needs
response data above 8 kHz. Existing A/C/Z octave offsets now live in shared `OctaveWeighting`, so
Android and portable calculations use the same table. These are finite-band engineering offsets,
not a continuous-frequency weighting filter or a calibrated full-range sound-level meter.

The existing spectrum drive convention is explicit: **fixed total drive within each band, then sum
independent band powers**. The separate [explicit source-spectrum workflow](../source-spectrum/README.md) now supports
fixed-total alternatives. Legacy mode does not divide total power by eight or assume a fixed overall
broadband electrical input/pink-noise spectrum. External comparisons must use that same source-drive,
PSD, bandwidth and level metric. A physical full-range meter export with an unspecified excitation
is not automatically equivalent, even if labelled dBA.

## Import contract

Broadband packages use comparison-import **version 3**, `conditions.levelConvention = BROADBAND_SPL`,
`units.frequency = aggregate`, and level units `dB_A`, `dB_C` or `dB_Z`. Sample `frequencyHz` is
explicitly **0 as an aggregate marker**, not a physical 0 Hz stimulus; no arbitrary single-frequency
label is reused. Ordinary band samples still require positive frequency labels. CSV packaging supports
version 3 and preserves the original UTF-8 payload/hash.

A required `broadband` object records:

- `weighting`: A, C or Z, plus the exact per-band `weightingDb` map.
- `bandwidthOct`: 1 and sorted unique `bandCentersHz` (2–8 supported octave centres).
- `summation`: `independent-band-powers`.
- `driveConvention`: `fixed-total-drive-per-band; sum-independent-band-powers`.

See `validation/comparison-import/fixtures/broadband-reference.json` for an **explicitly synthetic**
example. The parser checks the coefficient table against the label and exact band membership.
Overlapping fractional-width bands, duplicate/unsupported centres, contradictory units/metrics,
missing definitions and mixed relative/broadband declarations are rejected. The matching contract
includes the canonical definition and actual scene frequency cells, so different weighting or ranges
cannot silently match. [Relative weighted broadband normalization](../relative-broadband/README.md) now provides a separate
version-4 contract with an explicit weighted-total denominator.

Source/producer and electrical-drive declarations still require review. Missing absolute electrical
reference or unknown measurement calibration still blocks qualification. Synthetic examples remain
unqualified. Neither importing a manufacturer name nor getting a numerical residual pass establishes
manufacturer equivalence or real-world calibration.

## Retained diagnostics and recovery

Captured packages retain **raw unfloored per-band mean-square powers** per receiver in
`bandDiagnostics`, plus the canonical bands, coefficients, scene/DSP/source identities and aggregate
levels. Missing band powers remain null; they are never discarded and cannot be hidden by A-weighting.
Additional diagnostic capture skips unused phase-trace work without changing acoustic summation.

The existing immutable request, cancellation and revision/sequence/reference guards also cover
weighting/spectrum changes. A completed capture creates one Undo checkpoint. Raw documents and
band diagnostics survive Undo/Redo and disk save/fresh reopen. Scene schema **28** and import version
3 stop older apps from ignoring the aggregate semantics; older scenes/band/relative captures remain
readable in the new app. Ordinary production weighting values and prior snapshot calculations are
preserved.

## Reproduce numerical checks

`reference.py` uses independent retained Python propagation/air equations to calculate each octave
from three rectangular third-band cells with bandwidth-weighted power, then applies A/C/Z offsets
and sums power. Six synthetic cases cover 10/20 m and all three weightings. Tolerance is **1e-5 dB**
for this finite-band numerical model, not claimed physical prediction accuracy. Script/dependency
hashes, per-band references, full snapshots and residuals are retained under `results/`.

```sh
python3 validation/broadband-capture/reference.py --check
python3 validation/comparison-import/test_package_csv.py
cd android
./gradlew :shared-core:jvmTest --tests '*BroadbandSnapshotTest' --tests '*BroadbandCapturePackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*EngineComparisonCaptureTest' --tests '*WeightingTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh output is `android/shared-core/build/reports/broadband-capture/results.json`. Tests also cover
power-sum reconstruction from diagnostics, gain changes, levels below 0 dB, missing high-band data,
weighting/metric/range rejection, original weighting regressions, stale weighting edits and disk
save/reopen. CI checks reference reproducibility and uploads fresh reports. No real matched external
broadband export or controlled measurement was supplied; physical validation stays pending.
