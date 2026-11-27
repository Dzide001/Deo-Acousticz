# Repeated matched filter-heavy measured-array benchmarks — 2026-11-26

This is a **synthetic measured-response** numerical/performance fixture, not
manufacturer or field validation. It extends the retained 64-emitter, 333-receiver,
1 kHz third-octave diffuse scene with identical active DSP on every emitter:
80 Hz HPF and 8 kHz LPF (Q=1/√2), plus six Q=2 peaking filters at 125/250/500/
1000/2000/4000 Hz with gains +2/−1/+1.5/−3/+2/−2 dB. Sample rate is 48 kHz.
Each physical array receives a separately constructed equal chain, exercising
value-based sharing rather than only object identity.

## Matched protocol

Three pairs run **before/after, after/before, before/after** in six fresh JVM
workers. No JFR or `DiffuseDiagnostics` clocks are enabled. Both variants use the
same current shared API, timer helper, work/cancellation limits and cache budgets.
Only `CoherentDiffuse.kt` is temporarily exchanged: the baseline uses the captured
pre-miss-setup scalar endpoint/eager per-emitter filter implementation; the candidate
uses current miss-only chunk setup, equal-chain sharing and measured endpoint basis.
Thus this isolates the preceding interpolation/setup batch, not all earlier engine
optimizations. `results/variants.json` pins both source hashes and the run order.

The timed sequence is listener (including source levels), then coverage. Scene
import, request construction and export are outside those stopwatches. Each request
has its own incident cache; no listener/coverage cache is shared. The first request
warms JVM/JIT state before coverage identically in both variants. Host load is
recorded, not controlled; no thermal or statistically universal rating is implied.

Every run retains the exact portable fixture SHA-256 and checks 64 emitters,
eight filters per emitter, 333 receivers, 100% valid area and no unavailable level.
All receiver levels/powers, listener and source levels are retained. Before seeing
results, comparison tolerances were fixed to 0.001 dB per level, 1e−5 dB area mean,
and max(1e−10, |reference power| × 1e−9) per receiver power. The first scalar run
provides the equivalence reference; this is not an independent physical accuracy
reference. Existing shared numerical/DSP-phase tests remain the independent gates.

Each JVM trial also checks exact project export/reimport, including the eight DSP
sections and embedded speaker asset. Native testing separately recalculates after
reopening the app's own compact autosave and separately persisted speaker asset.
No spatial/frequency grid, integration convergence or work limit is relaxed.

## Results

| Request | Before min / median / max | After min / median / max |
| --- | --- | --- |
| Coverage | 78.079 / 83.534 / 83.629 s | 71.824 / 74.543 / 79.870 s |
| Listener, including source levels | 9.106 / 9.369 / 9.660 s | 8.247 / 8.947 / 10.831 s |

All three coverage pairs favour the candidate: **8.01%, 10.76%, 4.50%** lower
duration; median paired reduction **8.01%**. The ratio of variant coverage medians
is a different statistic: **10.76% lower**. Listener pairs are mixed, including
a 12.12% slowdown in the last pair, so no consistent listener benefit is claimed.
Ranges overlap and the shared host is not isolated; this supports an observed
benefit for this eight-common-filter fixture, not a general or thermal guarantee.

Maximum differences across all six runs are **zero** for every retained receiver
level/power, listener/source level and area mean. All inputs have the same fixture
hash and the mean is **54.23276879151662 dB**. The candidate source was restored
and its exact hash verified after the experiment.

Candidate OnePlus launch/reopen intervals were **109.7–116.9 s** and
**131.6–138.8 s**, both **54.2 dB**, without failed snapshots or manual Calculate
actions. Native DSP inputs, compact checksum and separately stored speaker asset
passed the audit. Original APK and all nine original files matched the backup
before launch; the original scene reopened unchanged. The normal profile-installer
file changed afterward. There is no native before/after speed comparison here.

The existing **654 shared checks** remained up-to-date because core implementation
was unchanged; **446 routine Android tests** and release assembly passed. The six
new manual benchmark executions and native computational save/reopen are the new
workload checks. The three unchanged standard large-tier cases were not repeated
for this benchmark-only change. Their scheduling/test bodies remain unchanged.

## Reproduction

Use JDK 21 and preserve existing results first. From the repository root:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  python3 validation/filter-heavy-arrays/run.py
python3 validation/filter-heavy-arrays/verify.py
```

Do not build/edit the workspace concurrently: the runner exchanges only its pinned
solver file and restores the candidate in `finally`. It refuses existing run
directories. Abrupt termination must be followed by checking/restoring the pinned
candidate hash before any build. Unique output directories are Gradle task inputs,
forcing fresh worker executions. Raw XML/logs and JVM/host conditions are retained.

`FilterHeavyBenchmarkTest` is a manual tool, excluded from normal fast/large/full
tiers alongside the existing profiler. To run one candidate observation from
`android/`:

```sh
ACOUSTIC_COST_PROFILE_DIR=/tmp/filter-heavy-run ./gradlew :app:testDebugUnitTest \
  -PacousticCostProfile=true --tests '*FilterHeavyBenchmarkTest' \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

## OnePlus scope and recovery

Use OPD2415 serial `50eb54c9`. Back up the current original APK, `files`,
`shared_prefs` and file hashes outside the repository. Build the candidate before
running the variant-exchange benchmark. Start checks that the backup still matches,
then loads exactly `results/01-before/fixture.json`. The expected displayed mean
comes from the scalar reference, not from the candidate's own output.

Use the retained test-signing init script to build a release-mode, R8-optimized,
non-debuggable APK signed with the test key. Two candidate cold process launches
are observed; the second reopens native autosave. Valid fresh UI dumps must show
the four arrays, 1 kHz mode, complete results and reference mean rounded to 0.1 dB.
Failed snapshots are discarded. A Manual-mode Calculate action, if needed, resets
the stopwatch and is explicitly labeled; it is not mixed with launch timings.
UI completion intervals include activity/listener work and command overhead.
System/ART caches and device temperature are not reset between launches.

These native observations test the candidate only; there is **no native before/
after speed claim**. Restore the original APK to audit the native checksum,
unchanged DSP/scene inputs and versioned speaker-asset hash, then restore all
original data bytes and reopen the original scene. Private backups/screenshots
stay outside version control. Production signing, sustained performance, other
venues and full-venue 4/8 kHz diffuse coverage remain open gates.
