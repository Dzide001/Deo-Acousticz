# Explicit relative-response normalization — revision 1

The shared core now supports current-scene capture as a per-frequency level difference:

`relative(f, receiver) = 10 log10(P(f, receiver)) − 10 log10(P(f, referenceReceiver))`

The numerator and denominator use the **same immutable scene, band segments, processing,
summation and propagation model**. Each frequency has its own denominator. This is normalization
of total predicted mean-square pressure, including enabled reflections/scattering. There is no
fitted offset, frequency interpolation, distance correction, phase normalization or replacement of
missing levels. Different receiver distances therefore retain spreading and air-loss differences.

## Choose an explicit basis

Comparison-import **version 2** carries a required `normalization` object for relative levels:

```json
{
  "method": "per-frequency-level-difference",
  "xM": 10,
  "yM": 1,
  "zM": 0,
  "minimumReferenceLevelDb": -120
}
```

Use `conditions.levelConvention`:

- `RELATIVE_TO_REFERENCE`: total-field response relative to that world-space receiver. This works
  for multi-emitter systems; it does not claim the reference is an acoustic axis.
- `RELATIVE_TO_ON_AXIS`: the same operation, but capture additionally requires exactly one physical
  emitter and verifies that the receiver is on its mechanical acoustic axis (within 0.001°) and
  outside the engine's minimum-distance clamp. Arrays must use the explicit reference convention;
  a centroid or steered array axis is not guessed.

Declare `units.level` as `dB_relative`. Frequencies remain Hz, coordinates metres. The supported
normalization fields/method are exact: unknown correction options are rejected. The imported CSV
or JSON levels must already use the declared normalization; import does not silently transform an
absolute external dataset or invent an external on-axis denominator. Keep the original raw export
and normalization method/noise controls as provenance when preparing external relative data.

The Android capture button uses the imported basis but derives all scene inputs independently.
Settings displays the level convention, receiver and safety floor. A coordinate or floor change
alters the canonical comparison conditions and is not silently matched to an older snapshot.
Producer/calibration and scene-protocol gates still apply; normalization does not authenticate data
or remove mismatched source processing, geometry or electrical-reference declarations.

## Missing data and near-null denominators

`minimumReferenceLevelDb` is an explicit predeclared safety threshold in the model's unfloored
reference-level scale, bounded to −300…200 dB. It is not a measured noise floor or a claim of absolute
calibration. Select it before evaluating results. Both powers must be finite and positive, and the
denominator level must meet the threshold. Otherwise all normalized samples at that frequency are
unavailable (`null`). Missing source bands also remain unavailable; no neighbouring denominator is
borrowed. Numerator levels are not clipped to the display floor.

The captured `source.normalizationReferences` records raw denominator dB and `VALID`, `UNAVAILABLE`
or `BELOW_REFERENCE_FLOOR` for each frequency. Thus a cancellation, invalid source band or rejected
near-null denominator is auditable after reopen. Common gain cancels numerically while denominators
remain above the threshold; source balance and DSP differences remain in the scene protocol.
Unknown electrical calibration can still produce relative numerical levels, but the existing strict
protocol matching and measurement-calibration gates are not bypassed.

## Compatibility and persistence

Absolute engine captures continue to use import version 1. Legacy version-1 relative documents
remain readable but cannot qualify workspace comparisons or scene capture without an explicit
basis. Version 2 relative packages retain the canonical normalization with their conditions,
original payload/hash and denominator diagnostics. Contradictory absolute/relative declarations
and mismatched duplicate normalization metadata are rejected before application.

Scene schema **27** preserves this extension through Undo/Redo and disk save/fresh reopen, while
older scenes remain loadable. Older apps reject the newer scene schema rather than silently
ignoring normalization and potentially qualifying an invalid comparison. Existing revision/sequence
checks still reject stale relative captures after scene or imported-protocol changes.

Use `validation/comparison-import/package_csv.py` with a version-2 metadata envelope to package a
UTF-8 relative CSV unchanged. `validation/comparison-import/fixtures/relative-reference.json` is an
explicitly synthetic example, not a manufacturer export or measurement.

## Reproduce accuracy and recovery checks

`reference.py` uses the retained independent Python propagation/air equations to calculate four
synthetic relative levels for 1/2 kHz at 10/20 m, normalized to the 10 m receiver. A second shared-core
capture applies +12 dB common gain. Both snapshots must match references within **1e-7 dB** and
survive workspace serialization. This tolerance verifies numerical implementation, not physical
prediction accuracy. Script/dependency hashes, original snapshots, denominator levels and residuals
are retained under `results/`.

```sh
python3 validation/relative-response/reference.py --check
python3 validation/comparison-import/test_package_csv.py
cd android
./gradlew :shared-core:jvmTest --tests '*RelativeResponseCaptureTest' --tests '*RelativeResponsePackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*EngineComparisonCaptureTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh raw reports go to `android/shared-core/build/reports/relative-response/results.json`. Tests
also cover zero reference response, per-frequency denominators, gain invariance, missing bands,
near-null/below-floor refusal, on-axis/array rejection, unsupported metadata, stale capture and
Android disk save/reopen/Undo/Redo. Synthetic examples remain unqualified for external validation.
Real matched manufacturer exports and controlled measurements are still pending.
