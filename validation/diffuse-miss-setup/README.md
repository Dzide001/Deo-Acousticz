# Diffuse miss interpolation and filter setup — 2026-11-26

Use the same pinned synthetic measured-array fixture as
[the preceding cost-isolation batch](../diffuse-costs/README.md): 64 emitters,
333 coverage receivers, 1 kHz third-octave integration and one scattering boundary.
This is numerical/performance evidence, not manufacturer or field qualification.

## Shared-core changes

`DiffuseChunk` prepares at most 32 frequencies and their Cartesian interpolation
fractions once, on the first cache miss. Cache-only chunks never prepare filter
responses. Empty filter chains have no response rows; equal immutable nonempty
chains share one evaluated row. An unavailable response inside a nonempty row
remains missing data, rather than becoming identity. Distinct sample rates,
filter kinds, Q and gains retain distinct keys. Scratch stays O(32 × emitters);
there is no extra persistent cache or capacity increase.

`ComplexSpeakerResponse.linearBandBasis` reads each declared measured angular
endpoint once and fills the existing low/delta scratch. Interior endpoint lookups
previously repeated those same measured grids. Exact measured frequencies keep
their direct values; unsupported gaps, ranges crossing knots, missing angular
responses and disabled measured directivity remain unavailable. The default
`Radiator.complexBandBasis` preserves custom-response endpoint behavior. No new
frequency/angular interpolation or phase information is invented.

These changes preserve quadrature, surface sampling, arithmetic order, cache/work
limits and cancellation behavior. Tests compare the optimized basis bit-for-bit
with independent scalar endpoint lookups at asymmetric angles and both measured
and interior boundaries, and cover gaps, custom responses, identity/shared filter
rows, missing DSP responses and scratch bounds. Existing diffuse tests cover
nonidentity filter phase/gain, interference, conservation and cache overflow.

## Reproduction and evidence

From `android/`, JDK 21, distinct output directories per variant:

```sh
ACOUSTIC_COST_PROFILE_DIR=/tmp/diffuse-miss-cost-run ./gradlew :app:testDebugUnitTest \
  -PacousticCostProfile=true --tests '*DiffuseCostProfileTest' \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

The manual profiler uses production listener/source-level and coverage requests;
normal fast/large/full acceptance excludes it. These are instrumented observations
with JFR disabled, not unprofiled or statistically stable benchmarks. The final
single-copy timer helper from the preceding batch is unchanged. `before` retains
the pre-change source snapshots and profile; `after` retains the candidate profile.
Source/evidence hashes and raw XML/logs make the exact scope reviewable.

Filter preparation is now attributed to the first miss, whereas the old profiler
included eager setup outside its miss timer. Compare total request/spectrum time
and the full breakdown; miss time alone does not isolate interpolation speed.

| Instrumented observation | Before | After |
| --- | ---: | ---: |
| Listener request | 6.451 s | 6.840 s |
| Coverage request | 42.388 s | 45.628 s |
| Coverage miss region | 8.323 s | 9.768 s |
| Coverage spectrum region | 32.191 s | 35.375 s |

These single computer observations do **not** show a speedup. Counts and levels
match exactly: 2,121,434 coverage surface samples, 103,810 misses, 21,110,530 hits,
4,568,640 geometry evaluations and 56.71420139630636 dB mean. Listener level remains
67.50961 dB. Keep the structural allocation/redundant-read reduction distinct from
any latency claim. A filter-heavy controlled benchmark remains a separate gate.

Final unprofiled large-scene acceptance passed all three cases: first diffuse
calculation **43.112 s**, complete class **115.027 s**, exact mean and all-receiver
reopen/statistics assertions intact. All **654 shared tests and 449 Android cases**,
shared iOS compilation and optimized release assembly passed.

Run ordinary accuracy/recovery gates separately:

```sh
./gradlew :shared-core:jvmTest :app:testDebugUnitTest --offline
./gradlew :app:testDebugUnitTest -PacousticValidationTier=large --offline
```

## OnePlus recovery

The retained start/observer/restore/signing helpers reuse the preceding batch's
workflow. Back up the current installed APK, `files`, `shared_prefs` and all file
hashes outside the repository. Start refuses to replace data that changed after
the backup. Install a test-key-signed, R8-optimized, non-debuggable release APK.
Observe two cold launches; the second reopens the native autosaved checkpoint.
Fresh UI evidence must show the four arrays, 1 kHz mode, complete results and
56.7 dB mean. Failed snapshots are discarded. Completion times are observed
running/complete intervals including activity/listener work, not exact CPU times.

Restore the original APK to inspect the native compact checkpoint and versioned
speaker-asset digest (`SHA256("speaker-data-v1\n" + payload)`), then restore all
original files and compare them before launch. Confirm the original scene reopens
unchanged. Normal post-launch recovery/profile-installer updates are reported
separately. Private backups and screenshots remain outside version control.

Production signing, sustained thermal/peak memory behavior, other venues and
full-venue 4/8 kHz diffuse coverage remain separate gates.

Accepted native intervals were **109.7–116.8 s** for the first launch and
**81.2–88.4 s** for the cold-reopen retry, both **56.7 dB**. The preceding batch's
final reopen was observed within 123.5 s. These variable observations do not
establish a stable speedup. System/ART caches and temperature were not reset.
One initial reopen probe saw a transient Manual-mode “Recalculation required”
state and the old observer stopped early; it is retained without a completion
timing. The observer now allows restore startup to settle, and can explicitly
calculate in Manual mode with a separately recorded action timing origin. The
accepted retry calculated automatically; no manual action was needed.
Native checkpoint/input/asset integrity passed. Original APK and all nine files
matched the backup before launch; the original scene reopened unchanged. Only the
normal profile-installer file changed afterward, as recorded in the restoration report.
