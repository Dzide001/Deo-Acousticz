# Angular sampling support and sparse-data warnings — revision 1

Shared `PolarGrid.angularSupport` describes the actual stored azimuth/elevation sampling and can
inspect a speaker-local direction with `at(azimuthDeg, elevationDeg)`. It is diagnostic metadata;
it does not change numerical interpolation, source asset identity or the original accuracy screen.

Android's existing speaker-data warning panel now displays the shared warnings. Project reports
include the per-band metadata in `speakerDataValidation[].angularSupport`. Magnitude and complex
response grids are assessed separately; a complete magnitude balloon cannot hide sparse complex
data. Warnings combine matching issues across bands with bounded frequency labels. Metadata are
computed lazily per immutable grid, outside the prediction hot path.

## Interpretation

- Stored knots and poles identify sampled coordinates, not confirmed measurement or accuracy.
  [Explicit origin declarations](../angular-domains/README.md) now distinguish acquisition/derivation. Pole
  longitude is immaterial. Other directions inside the elevation range are interpolated.
- A single measured elevation supports only that plane. Outside the stored elevation range, the
  engine already returns unavailable; this behavior remains unchanged.
- A single azimuth column is repeated by the existing interpolation at other longitudes. The new
  warnings and `SINGLE_AZIMUTH_EXTRAPOLATION` status identify those unmeasured directions away
  from poles. This batch does not silently turn existing axisymmetry assumptions into missing data.
- Axis gaps exceeding **5°** trigger a sampling advisory and `SPARSE_INTERPOLATION` for a query
  crossing such a cell. This is a conservative project advisory, not an industry accuracy standard,
  an error bound or a guarantee for finer grids. Narrow lobes/nulls can exist between samples.
- Cyclic azimuth wrapping, negative angles, 0°/360° duplicate endpoints and local nonuniform
  spacing follow the existing grid normalization. Local brackets describe the queried cell, not
  merely the largest gap anywhere in the balloon.
- An azimuth gap greater than `max(10°, 3 × lower-median cyclic gap)` is flagged as a
  **possible missing sector**. This is a disclosed heuristic, not an inferred coverage declaration.
  Existing interpolation across that gap remains numerically available. Uniformly coarse full-circle
  grids instead receive sparse-data warnings. No unsupported extrapolation is called a measured knot.
- Invalid grids/queries have explicit statuses. No angular support warning creates missing bands,
  fabricates a response, certifies provenance or overwrites the comparison verdict.

Angular-support report schema 2 now also includes explicit origin declarations and counts; the
retained revision-1 snapshots below document the earlier sampling-only run.
Reports identify axis spacing, counts, elevation limits and issue codes at every stored frequency.
The analysis concerns stored samples, not whether the source's publication measured, mirrored,
upsampled or synthesized them; that requires separate provenance review. Android currently shows
asset-level warnings, not a per-receiver support overlay. The shared per-direction API is exercised
against real receiver directions in the retained benchmark.

## Evidence and reproduction

The [public calibrated PRX635 package](../public-measurements/README.md) is reused without altering
training/holdout splits, source normalization, reference levels or thresholds. Both 10° and 20°
models flag all 72 held-out directions as sparse interpolation; elevation 1° is correctly outside
the horizontal-only data. Their original measured accuracy screens remain **FAILED**:

| Grid | RMS error | Maximum error |
|---|---:|---:|
| 20° | 0.615063 dB | 2.615989 dB |
| 10° | 0.500084 dB | 2.655764 dB |

The new checks report these limitations; they do not repair interpolation error. Production replay
is rerun separately against the original independent reference, and numerical regression/convergence
cases remain unchanged.

From `android/`:

```sh
./gradlew :shared-core:jvmTest --tests '*AngularSupportTest' --tests '*AngularSupportPackageTest' --tests '*PublicMeasurementComparisonTest' :app:testDebugUnitTest --tests '*AngularSupportRecoveryTest' --offline
```

Fresh diagnostics go to `android/shared-core/build/reports/angular-support/results.json`.
Reviewed snapshots and execution details are retained in [results/](results/). Tests cover cyclic
seams, missing-sector heuristics, sparse/nonuniform grids, singleton axes, poles, malformed data,
separate complex/magnitude support, stable serialization, real measured holdouts and Android disk
save/reopen with identical warnings, report metadata, decoded-asset hash and predicted level.
The focused final run passed **64 tests** (40 shared, 24 Android), Android debug assembly and iOS
simulator shared-test compilation. Execution details are in [results/checks.json](results/checks.json).
The complete rerun public-measurement report is byte-for-byte identical to its retained baseline.
No project migration is needed: diagnostics are recomputed from existing persisted speaker assets.
On-device visual acceptance remains separate from automated checks.

Next: explicit measured/symmetry/interpolated angular-domain declarations, followed by receiver-level
support overlays and a separately reviewed policy for refusing unsupported azimuth extrapolation.
Acquire denser independent data before evaluating finer grids against these fixed held-out angles;
do not reuse validation rows as new training samples.
