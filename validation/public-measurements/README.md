# Public calibrated PA measurement comparisons — revision 1

This package runs the production shared-core capture path against real **JBL Professional PRX635**
measurements from Princeton University's 3D3A Laboratory. It is an angular holdout experiment from
one measurement session, not a matched MAPP/ArrayCalc/Soundvision export or independent-session
field validation. Its measured reference levels are not engine-generated synthetic values.

## Sources and calibration

- [Database, public archive and CC BY 4.0 declaration](https://3d3a.princeton.edu/loudspeaker-directivity-ongoing-experimental-survey)
- [PRX635 measurement/setup notes](https://3d3a.princeton.edu/jbl-professional-prx635)
- [3D3A data-format/calibration manual](https://3d3a.princeton.edu/sites/g/files/toruqf931/files/documents/3D3A_Directivity_Database_0.pdf)

The speaker-specific notes give 31 March 2015, a nominal 1.7 m orbit, tweeter alignment and a
rotation axis through the cabinet centre. The manual describes a B&K 4189-A-021 microphone and
B&K 4231 calibration: −11 dBFS corresponds to 94 dB SPL; FFT levels convert with +105 dB.
That is published calibration provenance (`DECLARED`), not an independently checked certificate.
Actual electrical drive, original system preset and atmosphere are undocumented. This package
therefore compares **relative on-axis-normalized band magnitudes only**. It does not infer watts,
volts, impedance, absolute SPL, maximum output or a named manufacturer preset.

The unchanged horizontal MAT file is retained in a small ZIP under `source/`, with [attribution](source/NOTICE.md).
`protocol.json` pins both its original SHA-256 and the complete upstream download's SHA-256.
The whole upstream archive is not required for routine reproduction. No measurement assets or
Python dependencies are added to the APK.

## Disjoint training and comparison

`prepare.py` reads the original IRs with SciPy, uses the documented onset at sample 4096 and a
0.5 ms rise / 3 ms flat / 0.5 ms fall Tukey window, then computes squared FFT magnitudes. It
integrates piecewise-constant FFT-bin power cells clipped to the exact analysis edges, divided by
band width. Band centres are 500, 1000, 2000, 4000, 8000 and 16000 Hz, at the engine's float32
one-third-octave width. The published 1/24-octave smoothed plot images are not digitized or treated
as an independent numerical reference. Calibration cancels in the per-band on-axis ratio; calibrated
on-axis values are nevertheless retained for inspection.

Two source models use every **20°** or **10°** measured horizontal direction. The twelve held-out
angles are −55°, −45°, …, −5°, +5°, …, +55°, each at six frequencies: **72 samples per model**.
No held-out angular response enters either training asset. The measured on-axis row is the explicit
normalization reference; it is not a fitted offset. The two experiments reuse the same 72 held-out
measurements, so 144 comparisons are not 144 independent measurements.

Published positive angles point to speaker-left when viewed from behind. With the engine facing
+X and +Z to speaker-right, receivers use `(r cos(angle), 0, −r sin(angle))`. This mapping and all
frequency edges are retained. Source assets deliberately have only elevation zero; no vertical
pattern is inferred. Their on-axis sensitivity is a computational 0 dB normalization with unknown
electrical reference, **not a physical 0 dB SPL calibration or a general-use speaker library model**.
The benchmark never captures or qualifies absolute SPL with these assets.

The independent Python reference also computes dB-linear interpolation from the training angles.
The JVM benchmark decodes the portable source asset, freezes it with `FrozenResponse.measured`,
and runs `PredictionRequest.captureComparison` at the held-out receivers. The capture includes
normal spreading and air calculations; equal-radius on-axis normalization cancels their common
factors. It must reproduce the independent interpolation within **0.0001 dB**, independently of
whether the physical accuracy screen passes.

## Protocol review and evidence limits

Raw measurement envelopes use honest textual descriptions and initially return `NOT_COMPARABLE`
against the engine's structured conditions. `reviewed-protocols.json` is a **pinned explicit review**
of six field mappings. It is not regenerated from current engine output during tests. Each field
records original value, reviewed representation, rationale and evidence URL. Source identity,
zero additional DSP, coordinate transform, frequency cells, equal-radius cancellation of unknown
atmosphere, and direct-only propagation were reviewed against the source documentation and replay.
Unknown original preset/atmosphere remain disclosed; they have not become measured known values.

Shared `ComparisonProtocolReview` requires the exact reviewed original-document hash and old field
values. It preserves the complete original envelope and records every annotation. It cannot change
payload/levels, evidence class, calibration, drive, units, normalization or broadband recipe. Stale,
duplicate, unsupported, undocumented and repeated reviews fail; calibration checks still apply.
Annotations are declarations, not authenticated approvals. This batch supplies prepared annotations
through the shared API; it does not add an Android protocol-editing UI.

The provisional engineering screen, chosen in `protocol.json` **before processing the measurements**,
is maximum absolute residual ≤1.0 dB and RMS ≤0.5 dB across the complete declared sample set.
These are explicit project acceptance targets, not asserted industry-standard tolerances or
measurement uncertainty. Neither errors nor acceptance thresholds are fitted after the result.

| Training angular step | Held-out samples | RMS residual | Maximum absolute residual | Accuracy screen |
|---|---:|---:|---:|---|
| 20° | 72 | 0.615063 dB | 2.615989 dB | FAILED |
| 10° | 72 | 0.500084 dB | 2.655764 dB | FAILED |

The finer grid improves overall RMS, but does **not** reduce the largest error. The shared engine
reproduces independent interpolation within 0.000001 dB; this is numerical agreement with the
chosen model, not physical accuracy. `FAILED` comparisons are retained as failures. In report
metrics, `qualified=true` means the datasets are comparable, **not that the accuracy screen passed**.
The authoritative comparison status and the separate RMS acceptance flag express the failures.

The chamber and short time window limit low-frequency validity; the source paper gives a chamber
quarter-wavelength limit around 425 Hz. There is no extrapolation below 500 Hz. One unit/session,
one horizontal orbit and one radius cannot establish independent-session reproducibility, full 3D
coverage, distance extrapolation, array phase/coherence, reflections, scattering or venue accuracy.

## Reproduce and inspect in Android

Use a validation-only environment (no application runtime dependencies):

```sh
python3 -m venv /tmp/acoustic-measurement-tools
/tmp/acoustic-measurement-tools/bin/python -m pip install -r validation/public-measurements/requirements.txt
/tmp/acoustic-measurement-tools/bin/python validation/public-measurements/prepare.py --check
cd android
./gradlew :shared-core:jvmTest --tests '*PublicMeasurementComparisonTest' --tests '*ComparisonProtocolReviewTest' :app:testDebugUnitTest --tests '*PublicMeasurementRecoveryTest'
```

The preparation check permits 1e-10 absolute numeric variation across FFT/BLAS platforms while
requiring exact metadata hashes. Regenerate `fixtures.json` only after reviewing processing changes;
re-review hashes and protocol annotations explicitly. No test silently refreshes reviewed results.
Fresh JVM output: `android/shared-core/build/reports/public-measurements/results.json`.
Retained evidence: [results/reports.json](results/reports.json) and [results/checks.json](results/checks.json).

Android Settings → Imported comparisons can load either `imports/prx635-10deg-reference.json`
and its matching `-engine.json`, or the 20° pair. The existing residual plots and JSON report export
show the real measured failures. Those files contain frozen snapshots, not a new current scene;
importing the pair does not build a PRX635 venue design. The focused final run passed **59 automated tests** (36 shared, 23 Android), Android debug assembly
and iOS simulator shared-test compilation. This test pass verifies reproducibility and correctly retained
failed accuracy screens; it is not a measurement-accuracy pass. Android tests cover import, report identity,
review lineage, Undo/Redo, disk save and fresh reopen. On-device visual/export acceptance remains
separate from these automated checks.

## Next validation gates

1. Add spatial-support warnings and decide how sparse data should constrain claims. Obtain denser
   independent angular data before testing finer source grids against these fixed targets. Using the
   existing 5° held-out rows as new training data would invalidate this experiment; any cross-validation
   alternative needs an explicitly separate split/protocol, not a silent fixture refresh.
2. Obtain a separate-session or independent-laboratory measurement of the same documented model,
   preset, geometry and drive, with sufficient full-sphere data and uncertainty information.
3. Obtain permitted manufacturer-software exports with fully recorded source/DSP/atmosphere and
   receiver protocols. Keep physical and data-consistency validation distinct.
4. Validate multiple radii, vertical angles, arrays and venue propagation separately. V2 remains open.
