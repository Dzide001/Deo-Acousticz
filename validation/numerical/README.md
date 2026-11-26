# Reproducible numerical validation, revision 1

This package verifies equations and records known model limitations. It does **not** establish
manufacturer equivalence, physical accuracy of a real loudspeaker/array, or room-acoustic validity.
It adds no production solver path or Android-specific calculation.

## Files and provenance

- `fixtures.json`: 18 versioned scenarios, full coordinates, source gain/delay/polarity, rectangular
  frequency intervals, fixed environment and acceptance tolerances. Lengths are metres, delays
  seconds and frequencies Hz. Synthetic omnidirectional sources emit 100 dB at the 1 m reference
  before path air loss. Powers are normalized mean-square pressure `(p / 20 µPa)²`, not watts.
- `reference.py`: independent Python-standard-library implementation; imports no project engine
  code or generated Kotlin values. Implements spherical spreading, signed scalar complex sums,
  the rectangular-band pair integral, ideal images, and direct midpoint quadrature. The fixed
  20°C/50% RH air calculation is a separate encoding of the ISO 9613-1 formula, simplified at the
  reference temperature/pressure. Agreement checks numerical encoding, not experimental air data.
- `results/reference.json`: retained analytical values and raw quadrature values at four resolutions,
  with SHA-256 hashes for both the fixtures and the reference script. The phase convention is
  `exp(+j 2π f t)`; tone cases compare real/imaginary pressure as well as power.
- `results/engine-jvm.json`: reviewed snapshot of actual shared-engine results, fixture/reference
  hashes, shared-core Kotlin source-tree hash, runtime/platform, normalized errors, pass/fail and
  frequency-refinement values. The source hash covers sorted relative paths and bytes of all `.kt`
  files under `android/shared-core/src`, with NUL separators. Fresh
  runs write under `android/shared-core/build/reports/numerical-validation/`, leaving this snapshot
  untouched. JUnit writes the report before asserting numerical success, retaining failed results.
- `results/summary.md`: readable results for this revision.

The evaluator is in shared-core `commonTest` (`NumericalValidationSuite`), using production
`DirectField`, `tonePressure`, finite boundary geometry and floor paths. JVM code only reads inputs,
checks fixture hashes, asserts reference convergence and writes the report. The evaluator compiles
with the iOS simulator test sources; numerical execution in this revision is JVM only.

## Reproduce

From the repository root:

```sh
python3 validation/numerical/reference.py --check
```

From `android/`, using the installed JDK/Gradle dependencies:

```sh
./gradlew :shared-core:jvmTest --tests '*NumericalValidationPackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*PredictionModelCleanupTest' --tests '*PredictionConcurrencyTest' --tests '*SceneJsonRoundTripTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

`--offline` assumes dependencies are already cached. CI checks reference reproducibility before
running the Kotlin suites and uploads fresh engine JSON plus shared/Android JUnit results.

To intentionally revise fixtures or reference equations, run `python3 validation/numerical/reference.py`
without `--check`, inspect the changed equations/inputs/results, run the shared tests, and only then
copy the fresh engine JSON into `results/engine-jvm.json`. Review the summary too. Do not adjust
acceptance limits merely to make a changed engine pass. The reference script never reads the
retained engine result to generate expected values.

## Accuracy and convergence criteria

For non-null power, error is `abs(engine - reference) / scale`, with `scale` the incoherent sum of
individual path powers (at least 1). This avoids dividing by a cancellation null. This is **not**
relative error in the final received power, and near-null differences must not be expressed as a
small dB error. All values must be finite.

- Ordinary cases: normalized power error ≤ `3e-6`, chosen to allow Float geometry/level interfaces
  while catching material summation errors. At a single-source non-null result this corresponds to
  about 0.000013 dB; that is numerical tolerance, not claimed real-world SPL accuracy.
- Exact half-period and polarity nulls: normalized power error ≤ `1e-12`.
- Tone real/imaginary pressure error: each component within `3e-6 * sqrt(scale)`.
- Constant-amplitude band references: midpoint integration at 64/256/1024/4096 samples versus
  the exact integral. Final normalized error ≤ `2e-6` and at least 100× below the first error,
  with a `1e-12` roundoff allowance.
- Frequency-varying wideband reference: continuous air attenuation, complex summation at 65,536
  midpoint samples, checked against 256/1024/4096/16,384 samples with the same final bounds.
  The engine independently subdivides the interval into 1/4/16/64/256 segments. It must meet the
  fixture tolerance at the finest grid and improve error by at least 100× (or reach roundoff).
  Intermediate error need not decrease monotonically. These sample counts qualify only this fixture.

For magnitude-only data the production engine intentionally holds directional response and air
attenuation at each segment centre while integrating delay interference analytically. The ordinary
band references freeze those amplitudes identically. The separate refinement fixture tests the limit
as these frequency cells shrink, against continuous-frequency air loss. A wide cell is not silently
claimed equivalent to a continuously sampled physical response.

## Known limits are separate from accuracy evidence

Four cases are labelled `known-model-limitation`: either side of a finite mirror edge and below/above
a barrier top. They verify the current **clipped image / binary visibility** behavior, not a full-wave
reference. They deliberately retain abrupt changes and zero direct-path power in a geometric shadow.
They do not prescribe these as physically correct or supply unverified “diffraction correction” levels.
No Kirchhoff, BEM/FEM, measured diffraction, impedance-ground or multiple-bounce reference is claimed.

The asymmetric eight-emitter fixture validates propagation from given emitter positions, gains,
delays and polarity. It does not validate near-field cabinet coupling, manufacturer polars, mechanical
splay generation or automatic array optimization. Directivity, measured responses, DSP and diffuse
sampling retain their existing regression suites; they are not externally qualified by this package.
Controlled measurements and matched manufacturer exports are the next separate validation gate.
