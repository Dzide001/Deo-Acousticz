# Relative weighted broadband normalization — revision 1

The shared core supports **RELATIVE_BROADBAND_TO_REFERENCE** capture with an explicit world-space
reference receiver. Android uses Spectrum mode and its actual scene weighting; the imported package
specifies the comparison type and reference receiver, not a replacement source spectrum or weighting.

The operation is:

`relative = 10 log10(sum(w_band * P_receiver_band)) − 10 log10(sum(w_band * P_reference_band))`

The same frozen scene, bands, coefficients, drive convention, processing, geometry and propagation
model produce both totals. Weights are power factors `10^(weightDb/10)`. This is **not** an average
of normalized band dB, a sum of band ratios, or a second application of A/C weighting. Common gain
cancels while the denominator remains valid, but actual source/DSP/calibration declarations remain
part of strict protocol matching. No fitted offset, distance correction or guessed array axis is used.

## Import and capture

Use comparison-import **version 4**, with:

- `conditions.levelConvention`: `RELATIVE_BROADBAND_TO_REFERENCE`.
- The explicit canonical `broadband` definition from weighted snapshot version 3: A/C/Z coefficients,
  sorted octave centres, width and power/drive convention.
- A `normalization` object with method `weighted-broadband-level-difference`, reference coordinates
  `xM`, `yM`, `zM`, and `minimumReferenceLevelDb`.
- Units `frequency: aggregate`, `position: m`, and `level: dB_relative_A`, `dB_relative_C` or
  `dB_relative_Z`. Sample frequency 0 remains an **aggregate marker**, not a physical stimulus.

`validation/comparison-import/fixtures/relative-broadband-reference.json` is a synthetic example.
The CSV packager supports version 4 without changing payload bytes. External sample levels must
already use the declared weighted-total normalization; the importer does not transform an absolute
external export or invent its denominator. Preserve original exports and normalization/noise-control
records when preparing real relative data.

In Android Settings → Imported comparisons, import the relative weighted package, select Spectrum
and the intended scene weighting, then capture at its receivers. Reference basis and weighting, plus
the engine snapshot's actual weighting, are visible. A differing scene weighting, frequency range,
receiver, threshold or method cannot silently match. The core does not copy reference protocol
strings to force a match or relax the existing independent-origin/measurement-calibration checks.

## Denominator validity and retained evidence

Both weighted totals must be finite and positive. Every required octave must be available at each
receiver; a missing band cannot be hidden by a small A-weighting coefficient or a partial sum.
A zero-power individual band contributes zero. If the reference weighted total is missing,
nonpositive or below the declared safety threshold, normalized results are unavailable (`null`).

`minimumReferenceLevelDb` is applied **after weighted summation**, in the model's weighted reference
level scale. It is a predeclared numerical safety control, not a calibrated instrument noise floor.
Choose it before inspecting residuals. Numerators retain unfloored values. The existing finite-band
spectrum and per-band-drive conventions remain unchanged; this is not full-range meter normalization.

Each package retains numerator `bandDiagnostics`, `referenceBandDiagnostics`, and the raw weighted
reference level/status in `source.normalizationReferences` (aggregate marker 0). Thus the ratio,
missing bands, reference validity and weighting are inspectable after reopen. Synthetic inputs stay
synthetic; relative normalization does not establish physical accuracy or authenticate calibration.

## Compatibility and recovery

Version 4 requires both definitions and the weighted-total method. Per-frequency normalization,
absolute units, missing definitions and old schema versions are rejected for this metric. Earlier
absolute, per-frequency-relative and absolute-broadband packages remain supported independently.
Scene schema **29** protects the extension from older readers while loading older projects.

Capture uses the existing immutable request and revision/sequence/reference guards. Scene/weighting
or reference changes reject late results. Applying one snapshot creates one Undo checkpoint; original
packages, basis, numerator/reference band powers and validity flags survive Undo/Redo and disk save/
fresh reopen. Saved comparisons are recomputed from their original documents, not a stored pass flag.

## Reproduce numerical verification

The retained independent finite-octave A/C/Z reference totals from `validation/broadband-capture`
are normalized to 10 m by `reference.py`, which never reads Kotlin/engine results. Six reference
ratios cover A/C/Z at 10/20 m; shared tests capture each at 0 and +12 dB gain. Tolerance is **1e-7 dB**
for numerical agreement, not a real-world accuracy claim. Script/reference hashes, raw snapshots,
weights, numerator/denominator powers, levels/status and residuals are retained under `results/`.

```sh
python3 validation/broadband-capture/reference.py --check
python3 validation/relative-broadband/reference.py --check
python3 validation/comparison-import/test_package_csv.py
cd android
./gradlew :shared-core:jvmTest --tests '*RelativeBroadbandTest' --tests '*RelativeBroadbandPackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*EngineComparisonCaptureTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh raw results are `android/shared-core/build/reports/relative-broadband/results.json`. Tests also
cover wrong band-ratio averaging, zero reference response, common-gain invariance, near-null and
missing-band refusal, mismatched weighting/method/units/schema, stale weighting edits and save/reopen.
No new real manufacturer export or controlled measurement was supplied; external validation remains
pending. CI verifies the independent reference chain and uploads the fresh report.
