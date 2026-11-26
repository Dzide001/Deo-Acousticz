# Current-scene engine snapshots — revision 1

Android Settings → **Imported comparisons → Capture current scene at reference samples** freezes
an engine snapshot at the imported reference's sample IDs, world coordinates and frequency labels.
The reference's levels and conditions do not configure the engine. The shared `PredictionRequest`
retargets its frozen sources and scene inputs to those receivers/bands and uses the same production
calculation path. The result is stored in the engine slot and can be copied as comparison JSON.

Source identities/calibration, generic status, directivity mode, source/array geometry, electronic
steering, gain/delay/polarity/EQ/filters/taper, source summation, actual frequency segments, atmosphere,
boundaries/materials and global ground curve/phase come from the current scene. Speaker assets are
hashed only for comparison capture, with the existing asset cache; ordinary listener/coverage jobs
do not acquire extra hashing work. Boundary facets/materials receive a conservative sorted hash;
it is not a proof of geometrical/acoustical equivalence across different tessellations.

## Protocol and qualification

The captured package uses the existing comparison-import schema. Its engine conditions contain
canonical JSON descriptions derived from the immutable request, rather than the reference's prose.
This normally produces `NOT_COMPARABLE` until the independent reference protocol has been reviewed
and explicitly mapped to the same conventions. The app never silently rewrites the reference to
force a pass. Copy the engine package to inspect actual source positions, processing and frequency
edges; independently establish that the original external setup used those settings before
annotating its protocol. Keep its original export/payload and provenance intact.

Known reference drives are scaled by actual flat gain: `10^(gain/10)` for watts or `10^(gain/20)`
for RMS volts. EQ, filters and taper are recorded separately. A single shared electrical unit/value
is reported only when every source has that exact effective reference; unknown or heterogeneous
references remain `UNKNOWN` and cannot qualify an absolute comparison. There is no impedance-based
watts/volts conversion. Calibration remains declared, not independently certified.

Generic responses (including assets explicitly labelled generic estimates) mark the snapshot
synthetic. Lost measured assets produce unavailable samples, not a generic substitute. Global
external-validation status stays pending regardless of a conditional residual pass.

## Numerical scope

- Capture currently reports **absolute unweighted band mean-square levels**, with the current band
  width and exact shared segment/data-band definitions retained. Band labels are nominal engine
  centres, not an assertion of exact-tone processing. Spectrum-total mode must be changed to band
  mode first. Reference samples must use integer labels from 25–20,000 Hz.
- Reference weighting, level metric, bandwidth or relative-response conventions are never copied
  onto the calculation. A relative reference therefore needs a separately supported normalization
  workflow; [explicit relative normalization](../relative-response/README.md) now supplies a versioned
  reference receiver and safety checks, without inventing a denominator.
- Levels come from `10 log10(totalPower)`, not the display's 0 dB floor. Missing, non-finite or
  nonpositive power is explicitly unavailable (`null`), preventing a false finite level at a null.
- At most 512 samples and 64 frequency labels are captured per operation. Frequency groups avoid
  recalculating unrelated receiver/frequency combinations. Cancellation/failure publishes no partial
  dataset. The existing boundary/diffuse work and accuracy limits remain in force.
- Receiver inputs are requested Double metres; production geometry uses Float coordinates.
  Conversion error must be at most `1e-5 m` per component or capture fails. The package records
  this numerical policy; it is not a claim of real-world positional or interference accuracy.

## Immutable state and recovery

Requests freeze source response grids, DSP, geometry and atmosphere before dispatch. A capture
sequence/revision guard rejects late results after scene edits, new captures, reference replacement
or import clearing. Cancellation saves work but publication also checks identity/revision. Previously
saved engine snapshots stay frozen after subsequent scene changes; a new capture is explicit.

Applying a snapshot creates one scene Undo checkpoint. Scene schema 26 already stores comparison
workspaces, so no additional save-schema change is needed. Original engine JSON, scene revision,
conditions/payload/document hashes and raw samples survive Undo/Redo and disk save/fresh reopen.
Restore recomputes the comparison rather than trusting a saved pass flag. Android supplies the picker,
Main/worker orchestration and UI; conditions, calculations, encoding and matching remain shared.

## Reproduce retained numerical checks

`reference.py` uses the retained independent Python point-source/air equation implementation from
`validation/numerical`, without importing Kotlin or engine results. It generates exact one-third
band-cell reference levels for a synthetic 100 dB omni source at `(0,1,0)`, receivers at `(10,1,0)`
and `(20,1,0)` labelled 1/2 kHz, and a second capture with +6 dB gain. Tolerance is `1e-5 dB` for
this numerical check, not physical prediction accuracy. Script/dependency hashes are retained.

```sh
python3 validation/engine-capture/reference.py --check
cd android
./gradlew :shared-core:jvmTest --tests '*EngineSceneSnapshotTest' --tests '*EngineSceneCapturePackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*EngineComparisonCaptureTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh raw capture packages and residuals go to
`android/shared-core/build/reports/engine-capture/results.json`; reviewed results remain under
`results/`. Synthetic controls cannot qualify external validation. CI checks independent reference
reproducibility, runs the test suites and uploads the capture report. Actual manufacturer exports
and controlled measurements under reviewed matching protocols are still required for V2 acceptance.
