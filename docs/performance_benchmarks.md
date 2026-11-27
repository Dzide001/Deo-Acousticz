# Measured-array performance and accuracy fixtures

These are reproducible **synthetic measured-data** checks, not comparisons against a
manufacturer's proprietary measurements or an industry application's output.

## Test execution tiers — 2026-11-26

Routine Android checks now default to `-PacousticValidationTier=fast`. The three
full-size coverage/save-reopen cases use the `large` tier; `full` runs both tiers
in one invocation. Their geometry, emitter counts, frequencies, all-receiver checks,
convergence/work budgets and expected statistics are unchanged. CI runs both tiers
and retains their XML separately. This improves routine test feedback, not engine
or OnePlus calculation speed. See [retained tier validation](../validation/test-tiers/README.md).

```sh
./gradlew :shared-core:jvmTest :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest -PacousticValidationTier=large
./gradlew :app:testDebugUnitTest -PacousticValidationTier=full
```

## Full-venue diffuse coverage

### Pressure-triggered lossless diffuse cache — 2026-11-26

Implemented exact XOR-byte packing only when a new spectrum would exceed the
existing **32 MiB payload budget**. Until then, cached rows retain their original
DoubleArray representation and allocation-free hit path. One cancellable sweep
packs compressible retained rows; later admissions pack with raw fallback. No
eviction or persistent queue. Raw entry accounting stays **192 bytes**; packed
wrappers add **32 bytes**, within the unchanged **64 MiB / 262,144-entry** limits.
Logical cached values are separate from encoded payload bytes. Cancellation is
polled per at most 32 conversion rows with zero extra numerical-work charges.
No filter knots, quadrature, arithmetic order, tolerances or project formats change.

Two balanced unprofiled three-receiver pairs improved offset-scene requests
**7.89% / 8.94%**, median **30.9552 → 28.3500 s**, with zero level/power differences
against the original full-scene scalar reference. Common-scene results were mixed
(**−2.43% / +0.72%**); no common-scene gain is claimed. The profile confirms **zero
packed entries** for that scene. Under offset pressure the cache holds **4,727,056
values** in **33,554,414 bytes**, below the 33,554,432-byte limit, but still rejects
109,207 puts. This is a bounded improvement, not a fix for the full-venue slowdown.
[Raw controls, final candidate and rejected prototypes](../validation/pressure-packed-cache/README.md)
are retained. Expanded twelve-receiver-per-scene checks matched all 24 references;
instrumented times were **57.020 s offset / 15.339 s common**. Common raw storage
still fits without packing. Validation passed **667 shared + 449 Android** tests,
including large save/reopen cases, Android debug/release builds and shared iOS-core/
test compilation. No twelve-receiver baseline or timing-speedup claim is made.

Two earlier prototypes were not adopted unchanged: eager packing completed native
common-corner calculations at 55.2 dB within **173.0 / 174.0 s** (unpaired polling
bounds), while the next version still wrapped raw rows and slowed both common-scene
pairs. The final version avoids those raw wrappers. Final optimized, non-debuggable
OnePlus runs completed within **147.8 / 152.4 s**, both **55.2 dB**, including native
save/reopen of the common-corner fixture and exact compact DSP/input/asset recovery.
These polling bounds do not establish a paired native speedup or qualify the full
offset venue. After recovering USB debugging, the original APK and all nine latest
project files were restored byte for byte before reopening. The reopened scene
string is unchanged; only Android's profile marker and a verified rotation of the
restored current checkpoint to recovery.previous changed afterward. Latest X/pan/
array-aim changes were preserved in a fresh backup; the earlier backup remains intact.
The full offset-venue/native performance gate remains open.

Next compare bounded geometry traversal at the existing 65,536 source/surface-entry
limit to reduce repeated miss geometry, preserving each tone's surface addition
order. Expand to 33 receivers before another full offset venue/tablet attempt.
Do not increase memory/work limits or loosen convergence to qualify the mode.

### Bounded filter-knot / cache-cost profile — 2026-11-26

Profiled three spaced receivers (0, 166, 332) from each unchanged 333-receiver
scene. All six levels and powers match the retained full-scene scalar references
exactly. Added opt-in shared counters for integration intervals/passes/tones and
peak spectrum-cache value/entry/key accounting; a tiny-cache regression confirms
exact profiled/unprofiled results and bounds. No numerical integration, cache
admission, persistence or UI behavior changed. Validation passed **662 shared +
446 routine Android** tests, Android debug assembly and iOS shared-test compilation.
The manual profile stays outside normal tiers. No tablet/project changes were needed.

The common-corner control used **3 intervals per receiver**, **192 max tones**,
**2,867,088 cached values** and **12.081 s** instrumented request time. The offset
case used **13 intervals**, **416 max tones**, **4,194,296 cached values** and
**28.838 s**; **27.670 s (~96%)** was spent on cache misses. The value budget is
4,194,304 doubles (32 MiB); entry/key limits were not reached. Both cases required
three passes per receiver. This supports capacity-driven repeated work, but does
not establish a complete 333-receiver curve or native speed. Times compare
different inputs under instrumentation, not before/after optimization.
[Raw observations, pinned inputs and audit scripts](../validation/filter-knot-costs/README.md)
are retained. The earlier full-scene scalar 26-minute observation remains valid;
the expensive candidate/full-venue/tablet case is still unqualified.

Next run a **fixed-budget cache-admission/reuse experiment**, measuring evicted or
rejected spectra and refinement-level reuse. Bounded geometry traversal is another
candidate because overflow also repeats geometry evaluation. Preserve all knots,
positive quadrature, missing-data behavior, cancellation/work limits and convergence
tolerances. Validate exact receiver references and repeat unprofiled bounded pairs,
then expand receiver counts before another full-venue/native run. Do not increase
cache limits or claim this profiling-only batch fixed the slowdown.

### Independent array DSP validation — 2026-11-26

Added a manually selected four-chain fixture: 64 emitters, 333 receivers, eight
sections per emitter, distinct gains and 48/96 kHz (including mixed-rate) sections.
The full-venue isolation fixture keeps corners/Q common. Small shared regressions
also cover different corners/Q, source reordering, edits and per-source Nyquist
missing responses. Prediction/geometry/serialization production code is unchanged;
normal Android tiers exclude the manual fixture. Validation passed **661 shared +
446 routine Android** tests and iOS shared-test compilation; three unchanged large
cases retain their preceding batch evidence and were not rerun.

A single unprofiled fresh-worker pair completed **73.2565 → 55.5684 s** coverage
and **8.7295 → 8.5954 s** listener. All receiver/source/listener levels and powers
match the pre-sampling-optimization baseline exactly; valid area is 100% and mean
**55.1767400999864 dB**. This is an accuracy check plus individual timing observations,
not repeated or native speed qualification. OnePlus initial launch and save/reopen
completed within **149.0 / 149.9 s**, both **55.2 dB**; the second timing starts at
Manual Calculate. Native compact inputs, all four chains and hashed assets match.
The original APK and all nine original files were restored byte for byte; the
original project reopened unchanged and the temporary asset was removed.
[Scripts, raw results and incomplete exploratory trials](../validation/distinct-array-filters/README.md)
are retained separately.

**New performance gate:** an exploratory fixture that also varied corners/Q took
**1563.8 s (26 minutes)** for scalar baseline coverage. Its candidate trial was
stopped; it has no candidate accuracy/speed or native acceptance. A second Q-varying
trial was stopped early after code inspection. `DirectField` seeds integration with
filter frequency ± multiples of frequency/Q, so distinct settings add knots; this
is a plausible contributor, not a completed causal profile. Do not generalize the
bounded fixture's pass to arbitrary independently filtered arrays.

Next: profile integration slice counts, cache payload/hit/miss behavior and
receiver costs on distinct-corner/Q scenes, starting with bounded receiver subsets.
Retain full-scene accuracy references, work limits and convergence tolerances;
qualify the costly case before expanding performance claims or running long native
trials. Paired native baselines, interactive diffuse latency and full-venue 4/8 kHz
remain open. Preserve the STI branch unmerged.

### Filter-heavy sampling and transfer evaluation — 2026-11-26

Shared immutable biquads now derive coefficients once; chain evaluation reuses
frequency trigonometry across consecutive equal sample rates. Diffuse adaptive
sampling lazily shares equal-chain response rows for at most 32 frequencies,
with scalar fallback above that limit. Missing responses remain missing; empty
chains remain identity. The existing bounded miss chunks use the same helper.
No persistent cache, geometry, convergence tolerance or project format changes.

Two fresh-worker before/after pairs on the pinned 64-emitter/eight-filter fixture
improved coverage **72.3097 → 52.8448 s** (medians), with paired reductions
**26.70% / 27.14%**. Listener medians were **8.4462 → 7.9415 s**. Every retained
receiver/source/listener level and receiver power matches the prior scalar reference
exactly. This is a shared-host observation for one fixture, not a native speed claim.
[Source captures, raw observations and audit scripts](../validation/filter-transfer-reuse/README.md)
are retained. **659 shared + 449 Android** checks pass, including project reopen,
three large-scene checks and new DSP/mixed-rate/missing/fallback regressions;
iOS-core compilation and optimized Android release assembly also pass.

**OnePlus checks complete:** the non-debuggable optimized release completed at
**54.2 dB** on initial launch and after native save/reopen. Completion intervals
were **87.7–94.9 s** from initial launch and **93.5–116.2 s** from the explicit
Manual Calculate action after reopen (9.9 s after launch). These UI polling
bounds include listener work and are not paired native performance measurements.
Native saved DSP, scene inputs and hashed assets match the pinned fixture.
After the earlier ADB outage, the guarded retry verified the exact staged scene
before installation. The original APK and all nine original files were restored
byte for byte, the extra benchmark asset removed, and the original project
reopened unchanged. Distinct per-array chains and paired native performance remain
future gates; interactive/full-venue 4/8 kHz diffuse qualification is still open.

### Repeated matched eight-filter arrays — 2026-11-26

Six fresh JVM workers ran three alternating before/after pairs on the same
64-emitter, 333-receiver, 1 kHz scene with eight active shared HPF/LPF/EQ sections
per emitter. Only the pre/post miss-setup loop differs; all other shared code,
inputs, budgets and numerical criteria are held constant. JFR/diagnostic clocks
are disabled. Raw receiver/source levels and powers match exactly in every run.

| Request | Before min / median / max | After min / median / max |
| --- | --- | --- |
| Coverage | 78.079 / 83.534 / 83.629 s | 71.824 / 74.543 / 79.870 s |
| Listener | 9.106 / 9.369 / 9.660 s | 8.247 / 8.947 / 10.831 s |

Coverage pair reductions were 8.01%, 10.76%, 4.50% (median 8.01%); the ratio of
variant medians is 10.76% lower. Listener results are mixed. These three pairs on
a shared host support a narrow observed coverage benefit, not a general rating.
All six means were 54.23276879151662 dB with identical portable input hashes.

Candidate-only OnePlus intervals were 109.7–116.9 / 131.6–138.8 s, both 54.2 dB.
Native eight-section DSP/scene/asset recovery passed. Original APK and all nine
files matched before launch, and the original scene reopened unchanged. Existing
654 shared checks were up-to-date; 446 routine Android checks and release assembly
passed. See [protocol, raw data, residuals and recovery evidence](../validation/filter-heavy-arrays/README.md).

### Miss interpolation and filter setup — 2026-11-26

Miss-only chunks now share equal nonempty DSP chains, skip identity response tables
and prepare frequency fractions once. Measured Cartesian basis preparation reads
each angular endpoint once, preserving exact scalar values, missing data and custom
fallback. Scratch remains bounded to 32 tones; persistent cache, integration and
work/cancellation limits are unchanged.

Matched instrumented observations: listener **6.451 → 6.840 s**; coverage
**42.388 → 45.628 s**. No computer speedup is demonstrated. Miss time also changes
attribution because lazy setup is charged to the first miss. All samples, hits,
misses, geometry evaluations and levels match exactly. Unprofiled first diffuse
map: **43.112 s**, complete three-case class **115.027 s**; individual observations
do not establish a general latency change.

OnePlus observed completion intervals were **109.7–116.8 s** at initial launch and
**81.2–88.4 s** on the accepted cold-reopen retry, both **56.7 dB**. System/ART caches
and thermal conditions were not reset. The initial reopen observer stopped at a
transient Manual “Recalculation required” state; no completion timing is claimed
for that attempt. The retry ran automatically. These variable timings do not prove
a stable speedup over the prior roughly 123 s observations. All **654 shared tests,
449 normal Android cases**, iOS compilation and release assembly passed. See
[retained source, raw results and native recovery](../validation/diffuse-miss-setup/README.md).

### Listener/coverage cost isolation and transfer optimization — 2026-11-26

Opt-in common-core diagnostics separate listener/source-level work, adaptive
surface sampling, cache misses and receiver transfer. A manual production-request
profiler is excluded from routine test tiers. Matched profiles with the final
single-copy timer helper (JFR disabled) observed:

| Diagnostic stage | Before | After |
| --- | ---: | ---: |
| Listener, including source levels | 8.703 s | 6.632 s |
| Coverage total | 49.576 s | 44.955 s |
| Coverage cache misses | 11.173 s | 8.513 s |
| Coverage cache-hit transfer | 20.557 s | 19.057 s |

These are instrumented times, not unprofiled benchmarks. Counts and levels match
exactly: 2,121,434 surface samples, 103,810 misses, 21,110,530 hits, 591,094,840
cached tone transfers and 56.71420139630636 dB mean. Listener level is unchanged
at 67.50961 dB. Retain all source captures and
[raw profiles, regressions and native recovery evidence](../validation/diffuse-costs/README.md).

Cached transfer now charges every original work unit once per at-most-32-tone
chunk, polling cancellation before chunk writes. Scalar arithmetic/order and
convergence/cache/work limits are preserved. Empty filter chains bypass identity
complex multiply on misses. The first timer helper duplicated the large kernel;
the final helper avoids that JVM regression. Initial JFR captures and the
interrupted initial large run are kept separately from final acceptance.

Final unprofiled acceptance observed **47.975 s** for the first diffuse map and
**117.886 s** for the complete three-test class, with all receiver/reopen and
statistics assertions passing. This single computer observation does not establish
a JVM speedup over the preceding 44.6 s median. All 648 shared tests and 449 normal
Android cases, shared iOS compilation and release assembly passed.

Optimized, non-debuggable test-signed APK observations on OnePlus OPD2415 were
**123.6 / 125.7 s**, both **56.7 dB**. After rebuilding with the compact helper,
a separate cold reopen on the final APK completed within **123.5 s**, again
**56.7 dB**. The preceding fixture observed **159.3 / 159.9 s**. This is about
22% lower observed completion latency for this scene; it is not a sustained
thermal rating, exact CPU stopwatch or qualification for other scenes.
Native saved inputs/checksum and separately stored speaker asset passed the audit.
Original APK and all nine files matched before launch; the original scene reopened
unchanged. Normal post-launch profile-installer updates are recorded separately.
Full-venue 4/8 kHz diffuse coverage remains unqualified, and this mode remains too
slow for interactive design.

### Repeated unprofiled observations — 2026-11-26

Three fresh JVM workers per variant ran the unchanged 64-emitter, 333-receiver,
1 kHz acceptance case in paired order: before/after, after/before, before/after.
JFR was disabled. All six identical exported fixtures retained the exact
56.71420139630636 dB mean and passed every receiver/statistics/reopen assertion.

| Solver | First calculation minimum / median / maximum | Complete acceptance median |
| --- | --- | ---: |
| Before | 50.724 / 51.341 / 156.118 s | 102.430 s |
| After | 44.404 / 44.595 / 45.025 s | 89.618 s |

The observed median improvement is about 13.1%. Other host Java work was active
early and load changed; the slow first baseline is retained. These small,
non-isolated samples are exploratory and do not establish a causal speedup.
See [commands, raw XML, source hashes and complete results](../validation/diffuse-repeat/README.md).

The candidate release-mode APK (R8 optimized, non-debuggable, test-key signed,
9,843,440 bytes) completed two OnePlus OPD2415 cold launches within **159.3 s** and
**159.9 s**, displaying **56.7 dB** both times. Last-running/first-complete bounds
were **152.0–159.3 s** and **152.5–159.9 s**. The second launch reopened the native
autosaved scene. These activity-launch/UI observations include listener work and
are separate from the JVM coverage-only stopwatch. They are close to the earlier
roughly 162 s optimized-build observations and do not demonstrate a tablet speedup.
A live snapshot reported **247,951 KiB PSS / 393,508 KiB RSS / 187 KiB swap PSS**;
it is not a peak measurement. No prediction/crash error was logged during the check.

The ordinary debug attempt was interrupted while still calculating at roughly
eleven minutes; a resource-shrunk debuggable attempt was also abandoned after the
build warned that code optimization was disabled. Neither is a completed timing
or a calculation-failure result. Keep release-mode and debug observations separate.
Native checkpoint/input/asset integrity passed a separate short autosave audit
using the shared format's version-prefixed asset digest. Original APK and all nine
files matched the backup before launch, and the original scene reopened unchanged;
normal recovery rotation afterward is recorded in the retained restoration report.

### JVM cache-transfer profile — 2026-11-26

The unchanged 64-emitter, 333-receiver, 1 kHz full-venue acceptance fixture was
profiled with JDK Flight Recorder. Its cache-hit transfer loop repeatedly traversed
boxed output indices. The shared core now prepares at most 32 primitive indices
per chunk, retaining the exact arithmetic/order and every work check. Interleaved
indices, spectral intervals, chunk tails and cache parity have dedicated coverage.

The first coverage calculation was **56.694 s before / 59.192 s after**; the complete
save/reopen/role-toggle test was **114.863 s / 107.273 s**. Both retained exactly
**56.71420139630636 dB**, with all receiver and statistics assertions passing.
These single profiled JVM observations are mixed and **do not establish an overall
speedup**. No OnePlus result is implied; 4/8 kHz full diffuse coverage remains
unqualified. See [retained profiles and reproduction](../validation/diffuse-cache-profile/README.md).

### Shared-core phase batching and refinement allocation reduction — 2026-11-17

The exact-tone illumination kernel now prepares correlation groups once per surface/chunk and
uses checked eight-lane phase recurrence within at most 32 tones. It re-anchors each chunk and
falls back to direct trigonometry for irregular or insufficiently accurate frequency spacing.
Frequencies, quadrature weights, material/filter responses and convergence tests are unchanged.
A complete regular chunk reduces phase trigonometric pairs from 32 to nine per source; this
operation count is not an end-to-end speedup claim. Independent scalar, near-null, mixed-group,
large-delay and cache-saturation tests pass.

The incident cache now charges shared interned visibility/loss arrays once through its bounded
registry instead of repeatedly as full copies. Power payload remains capped at 32 MiB; the key
accounting budget remains 64 MiB with conservative 192-byte entry charges plus unshared packed
arrays. The entry cap is 262,144. Actual heap/map/registry and rendering overhead is additional.
Adaptive refinement no longer constructs temporary boxed level/edge lists for every source and
band; its seven probe values and acceptance comparisons are identical.

Phase batching plus cache accounting alone observed 229.7 s on the OnePlus, essentially unchanged
from 229.8 s. Removing the refinement allocations then completed the same 64-emitter, 333-receiver,
1 kHz venue within **162.4 s**, displaying **56.7 dB** (about 29% below the earlier observation).
The computer first-calculation observation was 74.80 s with mean **56.7142013963 dB**, and exact
save/reopen statistics and all receiver levels passed. Timings are individual observations, with
native completion bounded by roughly five-second UI polling; they are not thermal/peak guarantees.
A live memory snapshot during the final run reported 248,058 KiB PSS / 384,876 KiB RSS / 191 KiB
swap PSS; it is not a peak measurement. Cold reopen completed within **162.0 s**, again displaying **56.7 dB**. The final 4/8 kHz OnePlus scenes reported calculation failure within **96.3 s / 65.1 s**, with no
partial area average. These are safe failure observations, not complete coverage timings. The
saved final scene retained its four arrays and 8 kHz selection. The debug build and all nine
original saved files were restored, checked byte-for-byte with no extra assets, and the original
project reopened. Full-venue high-frequency coverage remains unqualified.

Final computer full-venue checks still stop safely: **4 kHz exceeds the per-receiver work budget**,
and **8 kHz exceeds 262,144 coherent surface samples**. No budget, accuracy or convergence limit
was relaxed. Dense 4/8 kHz acoustic references pass, but that does not qualify complete high-frequency
venue coverage. Next work must address coupled spatial/frequency integration, rather than infer
qualification from a faster exact-tone kernel.


Post angular-bound allocation patch (2026-11-17): the same 1 kHz full-venue save/reopen test
passed, retaining a 56.7142013963 dB mean; its first computer calculation took 97.46 s. The
OPD2415 optimized build completed within 229.8 s and displayed 56.7 dB. The earlier 234.4 s
observation is close enough that these individual UI-poll timings do not demonstrate a meaningful
speedup. High-frequency whole-venue qualification and interactive latency remain open.


`PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics`
uses four 16-element arrays at 6 m height, 0.2 m element spacing, a 60 × 40 m venue and 333 receivers.
The irregular complex directivity fixture includes 875/1000/1125 Hz knots, and computes the 1 kHz
third octave. A 2 × 2 m stage at (-10, 0), 0.5 m high, has absorption 0.2 and scattering 1.0.
All reflected energy is diffuse; disabling its acoustic role must measurably reduce coverage.
The complete project embeds measured responses and materials, and requires exact save/reopen,
matching coverage/statistics and 100% valid audience area. The expected mean is 56.7142013963 dB.

Shared-core changes reuse incident power independently of receiver propagation. Before the phase/refinement batch, the cache stored
up to 32 MiB of spectral doubles, with separately bounded packed keys (64 MiB), 131,072 spectral
entries, 32,768 profile entries (up to 1 MiB of floats) and 65,536 angular-bound entries.
Object/map overhead is additional; context, frequency and canonical-vector registries are also
bounded. There is no eviction churn or inter-request cache. Overflow recomputes exact values.
Receiver-dependent distance/air loss and outgoing weights are never reused as incident power.
Canonical physical-edge subdivision changes split order, not spatial acceptance criteria.

Development-machine runs observed 95.7 and 109.4 seconds for the first complete calculation;
the full save/reopen check also passed. These timings include the full prediction adapter and
statistics and are not isolated kernel benchmarks.

On the connected OPD2415 tablet, the optimized release build completed within **234.4 s**, with
**56.7 dB** displayed. This is a roughly five-second UI-polling completion upper bound, including
startup, rather than a kernel timer. A live memory snapshot reported 169,574 KiB total PSS,
249,268 KiB total RSS and 46,300 KiB swap PSS; this is not a peak-memory measurement.
Cold reopen completed within **245.1 s**, again displaying **56.7 dB** with the four arrays,
1 kHz third octave and scattering material retained. The validated debug build was restored;
all nine original saved files matched the fresh backup byte-for-byte, with no extra test assets.
This workload remains too slow for
interactive adjustments; cross-receiver reuse is a correctness-preserving foundation, not a claim
of general interactive full-venue performance.

Run the fixture with:

```sh
ACOUSTIC_BENCHMARK_EXPORT_DIR=/tmp/diffuse-venue-benchmarks ./gradlew :app:testDebugUnitTest -PacousticValidationTier=large --tests '*PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics' --offline
```

Full-venue 4/8 kHz diffuse coverage remains a performance gate. An exploratory 4 kHz, 32-emitter
case reached 72,520 surface samples at its first receiver and exhausted the work budget before
the final reuse changes. The budget was not raised and accuracy tolerances were not relaxed.
The unchanged 20-million-operation limit now applies separately to geometry preparation and each
receiver's entire spectrum; cancellation still checks work throughout, and no partial result is
published. Large receiver counts can therefore take substantial total time even within that limit.

## Large-venue direct-field coverage

`PhaseTraceIntegrationTest.largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly`
uses the production Android request adapter and the shared prediction engine:

- 60 × 40 m venue; Fast analysis spacing (3 m), 333 coverage receivers.
- Two or four arrays of 16 elements, 0.2 m spacing, 6 m centre height; arrays separated laterally.
- Coherent third-octave coverage at 4 and 8 kHz for 32 emitters, plus 8 kHz for 64 emitters.
- Complex Cartesian data at 3500/4000/4500/7000/8000/9000 Hz; interpolation limited to 1000 Hz gaps.
- Irregular 13 × 8 angular grid, a narrow feature around 12° azimuth, elevation-dependent magnitude,
  direction-dependent phase and frequency-dependent real/imaginary components.
- No global ground reflection or acoustic stage boundary in this whole-venue fixture.

The test records calculation time without asserting a machine-dependent deadline. It requires finite
coverage at every receiver, coverage statistics, exact embedded-project JSON after save/reopen and
matching coverage levels and area-weighted statistics after reopening, with 100% valid coverage area. Set `ACOUSTIC_BENCHMARK_EXPORT_DIR` to export
self-contained test projects for device testing; leave it unset for ordinary CI runs.

From `android`, with the project JDK configured:

```sh
ACOUSTIC_BENCHMARK_EXPORT_DIR=/tmp/large-venue-benchmarks ./gradlew :app:testDebugUnitTest -PacousticValidationTier=large --tests '*PhaseTraceIntegrationTest.largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly' --offline
./gradlew :shared-core:jvmTest --tests '*LargeVenueMeasuredArrayTest*' --offline
```

If tests are already up to date and exports are needed, rerun the selected test task with
`--rerun-tasks`. Always back up the device's project and assets before loading a benchmark;
restore and compare the saved files after testing. Optimized and debug build timings are separate
measurements. Device UI polling gives completion upper bounds, not exact kernel timings.

## Independent acoustic references

`LargeVenueMeasuredArrayTest` exercises the shared core directly:

- Four 16-element measured arrays, 4/8 kHz, receivers at 15, 40 and 75 m with on/off-axis positions.
  An independent complex-pressure sum is integrated at 8192 frequency midpoints. A 4096-point
  reference must converge within 2e-5 relative; production results must agree within 5e-5 relative.
  Missing frequency coverage remains unavailable; serialization preserves complex responses.
- At 8 kHz, 32 emitters illuminate a fully scattering 4 × 4 m face from 29 m away. The array aims
  the narrow directivity feature at the face. An independent 240 × 240 surface integral is checked
  against 120 × 120 sampling (0.3% convergence) and the adaptive result (0.5% tolerance).
  This includes incidence flux, atmospheric loss, coherent illumination and Lambertian propagation.
- The scattering fixture exceeds the 65,536-entry geometry cache: repeated work must reuse stored
  entries while recomputing overflow entries, and cached/uncached power must agree within 1e-11.

The scattering reference is a single receiver and exact frequency. It does **not** qualify the cost
of third-octave scattering at every receiver in a large venue. That workload, additional boundary
counts, 16 kHz, finer audience meshes and sustained tablet thermal behavior remain separate gates.

## Observations — 2026-11-14

Tablet: OPD2415, Android 16, optimized Android build. Computer results below were recorded during
the full validation build and are not isolated microbenchmarks. Each table entry is one observation;
there is no claim of a statistically stable speedup or a sustained thermal rating.

| Emitters | Third-octave band | Computer coverage calculation | Tablet completion upper bound | Tablet area mean |
| --- | --- | --- | --- | --- |
| 32 | 4 kHz | 2.91 s | 7.8 s | 49.1 dB |
| 32 | 8 kHz | 6.71 s | 12.7 s | 52.0 dB |
| 64 | 8 kHz | 24.36 s | 74.5 s | 55.9 dB |

Tablet completion is observed through fresh UI snapshots approximately every five seconds;
unavailable UI dumps are discarded. The application also calculates its listener result, so these
numbers should not be treated as the same timing boundary as the computer's coverage-only test.
The 32-emitter 8 kHz project reopened at 52.0 dB within 12.7 s. The 64-emitter observation identifies
a remaining interactive-performance limit, rather than qualifying all large measured arrays.

The 8 kHz scattering reference produced 4779.256319697408 normalized mean-square pressure units;
the adaptive result was 4779.232190164533 (about 0.000505% difference). Cached geometry evaluations
were 316,512 cold and 250,976 on repeat: exactly 65,536 entries were reused, with overflow recomputed.

A targeted repeat also checked full valid area and identical area-weighted statistics after reopen.
Computer area means were 49.05937877, 52.03731159 and 55.87139387 dB respectively, matching the
corresponding tablet displays after rounding. Its calculation times were 2.66, 6.04 and 23.65 s;
these repeat observations do not replace the full-build measurements in the table.

The 64-emitter project reopened on the tablet within 69.2 s, retaining 55.9 dB. Both native 8 kHz
save/reopen checks passed. After testing, the debug build was restored and all nine original project
files matched the pre-test backup byte-for-byte; the temporary benchmark asset was removed.

## Summation optimization — 2026-11-14

The direct/specular band sum now uses the same bounded-error integration kernel as incident
scattering, with the material phase derivative included in each arrival's effective delay.
For at least 24 active arrivals per correlated group and no more than one cycle across a slice,
eight positive Gauss samples replace pairwise cross terms. Wider spans retain the analytic sinc
formula. The independent dense-frequency test now covers 64 emitters, and randomized analytic
checks separately cover material phase slopes, group isolation, cancellation and fallback.

After this change, the same full-build computer observations were 1.64 s / 2.86 s / 6.47 s for
32 emitters at 4 kHz / 32 at 8 kHz / 64 at 8 kHz. Area means were unchanged at the printed precision.
The full validation suite passed 922 tests, plus both Android builds and shared iOS compilation.

The initial optimized tablet observation was 20.1 s at 55.9 dB, with the notification panel covering
the app during the initial polls. It is retained as a completion upper bound, not a clean benchmark.

After reconnection, the unobstructed cold reopen completed within **18.4 s** at **55.9 dB**.
The same optimized-build fixture previously completed within 74.5 s and 69.2 s. All are fresh-UI
completion upper bounds, sampled about every five seconds; they include listener work and do not
establish sustained thermal performance or performance for other scenes. The saved 64-emitter
project retained its result after reopening. Native acceptance is complete for this fixture.

The validated debug build and original project were restored afterward. All nine saved files
matched `/tmp/summation-original.tar` byte-for-byte, and the temporary benchmark asset was removed.
