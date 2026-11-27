# Listener, coverage and diffuse cache costs — 2026-11-26

Profile the production requests for the same pinned synthetic measured-response
fixture as [the repeated benchmarks](../diffuse-repeat/README.md): four 16-emitter
arrays, 333 receivers, 1 kHz third-octave coverage and one scattering boundary.
This is numerical/performance validation, not manufacturer or field acceptance.

## Cost separation

`PredictionRequest.calculateProfiled` accepts an opt-in, worker-local
`DiffuseDiagnostics`. Ordinary `calculate` leaves diagnostics disabled. The
instrumentation and calculation remain in common Kotlin for Android/iPad use.
Sampling time is separate; spectrum time includes hit/miss time and setup/key
lookup overhead. These clocks affect execution: **diagnostic seconds are
not unprofiled benchmarks** and sequential listener/coverage times cannot simply
predict concurrent Android latency.

| Diagnostic stage | Before | After |
| --- | ---: | ---: |
| Listener request, including source levels | 8.703 s | 6.632 s |
| Listener cache-miss work | 7.819 s | 5.891 s |
| Coverage request | 49.576 s | 44.955 s |
| Coverage adaptive sampling | 8.839 s | 8.876 s |
| Coverage cache-miss work | 11.173 s | 8.513 s |
| Coverage cache-hit transfer | 20.557 s | 19.057 s |

Both table runs use the final compact timer helper, without JFR. `before` and
`after` contain that matched pair. Initial JFR captures are retained separately
under `initial-before` / `initial-after`. Their larger timings include an early
inline-helper duplication issue; they identify hot stacks but are not the final
performance comparison. The helper now contains a single numerical block, reducing
the maximum kernel instruction offset from 14,135 to 5,103 bytes. The interrupted
initial large test is excluded; final unprofiled acceptance is run separately.

Listener: 74,816 misses / zero hits / 1,852,592 evaluated tones. Coverage:
103,810 misses / 21,110,530 hits / 2,906,680 miss tones / 591,094,840 hit tones.
The two profiles have **identical counts**, including 2,121,434 coverage surface
samples and 4,568,640 geometry evaluations. The coverage mean remains exactly
56.71420139630636 dB; listener level remains 67.50961 dB. Most incident spectra
already hit the cache, but the volume of receiver transfer and cold listener work
still matters. Sampling/cache capacities and integration fidelity were not reduced.

## Changes and checks

Sampled stacks pointed to per-tone work/cancellation callbacks in cached transfer
and filter arithmetic in misses. `DiffuseTransfer.accumulate` now charges all
original tone units once per at-most-32-tone chunk, before writing any values.
It checks cancellation at that boundary; the maximum interval is 32 cheap scalar
transfers. The per-receiver work limit is unchanged. Arithmetic and output order
are unchanged. Failed work/cancellation checks publish no partial prediction.

Empty filter chains bypass identity complex multiplication on cache misses.
Nonempty/invalid filters retain their evaluation and missing-data behavior.
Focused regressions compare the fast path with explicit unity filtering at
identical frequency nodes, including coherent cancellation. Other tests retain
nonidentity filter phase/gain, interference, conservation, cached/uncached parity,
overflow, interleaved indices, chunk tails and receiver-specific loss checks.
Dedicated tests verify every budget unit and cancellation before chunk output.

The first draft of the unity-filter test compared two different adaptive
frequency grids (filter knots are added even for unity filters). The retained
test instead fixes the exact frequency nodes and keeps the tight parity tolerance.

## Reproduction

Run from `android/` with JDK 21. Use a distinct output/recording path per variant:

```sh
ACOUSTIC_COST_PROFILE_DIR=/tmp/diffuse-cost-run ./gradlew :app:testDebugUnitTest \
  -PacousticCostProfile=true \
  --tests '*DiffuseCostProfileTest' \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

Add `-PacousticProfileRecording=/tmp/diffuse-cost-run.jfr` only when collecting
sampled JVM stacks; the final matched cost table leaves JFR disabled.

The manual profiler is excluded from ordinary fast/large/full acceptance runs;
enabling it and its destination are Gradle task inputs. It imports the retained
fixture and builds the same listener/source-level and coverage requests as the
app. Raw JFR and exported events remain temporary. Retained summaries contain
sampled method stacks and the recording digest, excluding JVM environment data.
Use `diffuse-cache-profile/summarize.py` with the JFR export command documented
there. Counts are samples, not exact CPU time. Source captures map profile lines.
`run_compact_profiles.py` reproduces the paired diagnostic run by temporarily
exchanging only the captured solver variant and restoring the candidate in
`finally`; do not edit/build concurrently. It refuses to overwrite result folders.

Run the ordinary full-size accuracy/reopen gates without diagnostics separately:

```sh
./gradlew :app:testDebugUnitTest -PacousticValidationTier=large --offline
```

All three final large cases passed, including every receiver/statistic after fresh
reopen. The unprofiled first diffuse coverage calculation was **47.975 s**, with
the pinned exact mean; the complete three-test class took **117.886 s**. This
single observation does not establish a JVM speedup over the earlier 44.6 s median.
All **648 shared tests and 449 normal Android cases** passed across the final
fast/large runs; shared iOS compilation and release assembly passed too.

## Native workflow

The retained `test-signing.init.gradle`, observer and restoration helpers reuse
the preceding batch's validated workflow. Back up current OnePlus `files`,
`shared_prefs`, their file hashes and its installed debug APK outside the repo.
`start_oneplus.py --backup /path/to/backup --apk /path/to/candidate.apk` verifies
that the backup still matches before replacing the recovery slot with the pinned
fixture. Use an optimized, non-debuggable, test-key-signed release build:

```sh
./gradlew :app:assembleRelease -I ../validation/diffuse-costs/test-signing.init.gradle \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

`observe_oneplus.py --private-data-unavailable` measures two cold launches; the
second reopens the native autosave. Timings are last-running/first-complete UI
intervals including activity/listener work, not JVM coverage-only durations.
Failed UI dumps are discarded. Original project inputs, compact checkpoint and
separate measured asset are audited by `restore_oneplus.py --backup ...`, which
restores the original APK and all file bytes and reopens the original app.
Speaker assets use `SHA256("speaker-data-v1\n" + payload)`. Private backups and
screenshots stay outside version control. Production signing, thermal endurance,
other venues and full-venue 4/8 kHz diffuse acceptance remain separate gates.

Two initial optimized APK launches completed within **123.6 / 125.7 s**, at
**56.7 dB**. After compacting the timer helper, the rebuilt APK changed executable
and ART profile payloads, so it received a separate final cold reopen check:
**123.5 s**, again **56.7 dB**. All three are recorded as running/complete intervals.
The preceding batch observed **159.3 / 159.9 s** for this fixture. This is about
22% lower observed completion latency, not a sustained or all-scene rating.
The final native compact checkpoint, all prediction inputs and separately stored
speaker asset passed the audit. Original APK and all nine files matched the
backup before launch; the original project reopened with unchanged scene inputs.
Normal recovery/profile-installer updates after reopening are recorded separately.

After collecting results, run `python3 validation/diffuse-costs/verify.py` from
the repository root to audit retained profiles, normal suites, native recovery
and restoration. No Gradle/device access is needed for that audit.
