# Production summation test migration

This batch replaces legacy Android helper checks with tests at the **immutable
production prediction-request boundary** used by listener and coverage calculations.
It changes validation, not the acoustic solver, and makes no manufacturer accuracy
claim. `results/migration.json` records the baseline commit and removed call sites.

## What changed

The old `SummationTest` accepted any coherent-pair gain between +3 and +6 dB and
described an arbitrary bandwidth-dependent blend. It now requires +6.0206 dB for
coincident coherent sources and +3.0103 dB for separate independent sources across
all supported bands. Cancellation is checked in energy rather than at the display
floor. The quiet-source check requires the analytical pressure gain.

Delay tests integrate squared pressure over the actual fractional-octave band:

`pair/single = 2 + 2 cos(2π fc τ) sinc(π B τ)`

Here `fc` is the arithmetic midpoint of the band edges and `B` their difference.
An electrical half-period delay at nominal 1 kHz leaves finite energy over a band;
it must not be tested as complete cancellation at an exact tone. A wider band fills
the null. Shared common tests cover two bandwidths and three delays with a declared
ratio tolerance of 0.000001, plus coherent/independent limits, source grouping and
array-wide gain/polarity/common-delay invariants.

`LineArraySummationTest` now captures production requests for actual array poses,
DSP, taper, splay and steering. It checks far-field pressure gain, whole-array phase
invariance, within-array coherence when inter-source interference is off, twin-array
cancellation/delay residuals and finite results across bands/sizes/spacings. Receiver
grids are batched into requests. Far-field `20 log10(N)` uses a 0.01 dB tolerance
for this finite-distance low-frequency fixture, not an assertion for every array.
Generic splay/directivity checks remain explicitly generic.

The two Android files now contain 19 production-adapter tests and **zero calls** to
`coherentSumDb`, `lineArraySplDb`, `elementSplDb`, `arrayPressure` or `directContribution`.
They previously made 25 calls to those helpers. Physics invariants also run in four
portable shared common tests. The Android summation test saves/reopens a two-source
design with gain, delay, polarity and both interference settings, requiring identical
production energy after reopen.

## Reproduce and evidence

```sh
cd android
./gradlew :shared-core:jvmTest --tests '*ProductionSummationTest'
./gradlew :app:testDebugUnitTest --tests '*SummationTest'
```

The exact formula and fixture expectations are retained in the shared test source.
`results/shared-tests.xml`, `results/summation-tests.xml` and
`results/array-tests.xml` retain the targeted cases. `results/verification.json` and
`results/validation-build.log` retain the full-suite/build gate. No physical device
or iOS runtime check is claimed; iOS compilation checks shared portability.

The complete Android run exposed eight failures in older coverage-edge fixtures:
they declared one sensitivity value for eight bands. The stricter reference parser
correctly rejected them, but the tests continued against generic fallback data.
All three fixture builders now provide one reference per band, and imports are
asserted before inspecting edges. The affected class and summation classes are
rerun after this fixture-only repair; the unchanged 149-second phase-trace class is
not repeated. `full-android-initial.json` records the complete initial class inventory
and timings, and verification distinguishes that run from the post-repair rerun.

## Remaining legacy work

The subsequent [room-model retirement batch](../retired-room-model/README.md)
archives these historical checks and removes their unused calculators. The notes
below describe the backlog at this summation batch's checkpoint.

Compatibility helpers are retained because other old wrappers still reference them.
Legacy RT60 and room-reflection tests in `Rt60Test`, `EarlyReflectionsTest` and parts
of `SurfaceMaterialTest` still exercise unpublished/disabled helpers. They are not
evidence for the live application's prediction path. Migrate or explicitly archive
those checks before retiring their helpers; do not claim this batch removed all
legacy validation or proved room acoustics. Existing independent numerical packages
remain the broader accuracy/convergence references.
