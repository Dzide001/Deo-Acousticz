# Fast checks and full-size coverage validation

The phase-trace class contained three full-size coverage acceptance tests. In the
retained baseline they consumed about 135 of the class's 141 seconds; ordinary
phase/reference/history checks were a small fraction of the runtime. This batch
changes **test scheduling**, not engine calculations or accuracy budgets.

The original test bodies and every assertion remain byte-for-byte identical after
removing the new category annotations/import. `results/baseline.json` pins that
normalized source hash, baseline commit, previous case timings and source XML hash.

## Tiers

Run from `android/`:

```sh
# Default: all shared checks and routine Android checks.
./gradlew :shared-core:jvmTest :app:testDebugUnitTest

# The three original full-size coverage/save-reopen cases.
./gradlew :app:testDebugUnitTest -PacousticValidationTier=large

# Both Android tiers together, for a complete single-run checkpoint.
./gradlew :shared-core:jvmTest :app:testDebugUnitTest -PacousticValidationTier=full
```

`fast` is the Android default. `large` includes only methods annotated with
`LargeSceneValidation`; `full` applies no category exclusion. Unknown tier values
fail configuration. Tier selection is an explicit Gradle test input, so switching
tiers cannot reuse another tier's successful task result. Existing benchmark
frequency/export environment settings remain test inputs too. Shared-core test
selection is unchanged.

The large tier contains:

- `wholeMeasuredArraySceneCompletesAndReopensWithIdenticalCoverage`
- `largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly`
- `fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics`

It retains the original 32/64-emitter scenes, high-frequency/nonuniform directivity,
all receiver comparisons, save/reopen, cancellation, 100% valid audience area,
area-weighted statistics and the pinned 1 kHz diffuse mean. No receiver count,
sampling, convergence/work budget or tolerance is relaxed. The smaller measured
32-emitter scattering/polarity regression remains in the fast tier.

CI runs both tiers on every normal Kotlin test job. It uploads fast/shared XML and
reports before executing the large tier, then uploads large-tier XML separately.
Both tiers must pass before the job succeeds. The local default no longer executes
large acceptance cases: use `full` to reproduce historical complete-suite commands
on current source. Old checkpoint logs/commands remain historical evidence.

## Evidence and limits

`results/fast.json` and `results/large.json` retain all executed case IDs and timings;
their union must have the original 449 Android cases with no overlap. The two phase
XML files must partition all 31 baseline phase cases exactly. `results/verification.json`
records those checks, shared test totals, Android assembly and configuration checks.
Gradle logs retain the commands' outcomes.

Run `python3 validation/test-tiers/verify.py` to audit the retained partition, hashes
and unchanged original test bodies. It pins this batch's historical 449-case
snapshot; future intentional test changes require refreshing that evidence.

Timings are single local observations, include different selected workloads, and
do not imply a speedup in the acoustic engine, OnePlus calculations or CI's total
acceptance work. Full-venue diffuse/high-frequency device qualification remains
open. Production/shared code, project formats and Android behavior are unchanged.

The implementation uses standard [JUnit 4 categories](https://junit.org/junit4/javadoc/4.13/org/junit/experimental/categories/Category.html)
and [Gradle JUnit category selection](https://docs.gradle.org/current/userguide/java_testing.html#test_grouping).
