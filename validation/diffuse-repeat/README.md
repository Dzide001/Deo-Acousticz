# Repeated unprofiled full-venue validation — 2026-11-26

Compare the pinned pre-change shared solver with its bounded primitive-index
cache-transfer change. The fixture remains 64 measured emitters, 333 receivers,
1 kHz third-octave coverage and one diffuse scattering boundary. No acoustic
equations, sampling, convergence/work budgets, project format or UI changed.

## Computer protocol

Six fresh JVM test workers use the order **before, after, after, before, before,
after** (three pairs). JFR is disabled. Each unchanged acceptance test calculates
the full map, saves/reopens the portable project, compares every receiver and
area-weighted statistic, then verifies that removing the scattering boundary
changes the prediction. The expected mean remains **56.71420139630636 dB**.

`results/observations.json` retains every observation, including the unusually
slow first baseline; `summary.json` gives medians and ranges. No observation is
silently removed. The host was not isolated: an early process inspection showed
an unrelated Java process using about 390% CPU, while a later sanitized snapshot
showed about 98% aggregate Java CPU. Conditions changed during the series. Treat
the observed difference as exploratory, not a causal or statistically established
speedup, nor an interactive-latency qualification.

| Variant | First calculation: min / median / max | Complete test: median |
| --- | --- | ---: |
| Before | 50.724 / 51.341 / 156.118 s | 102.430 s |
| After | 44.404 / 44.595 / 45.025 s | 89.618 s |

The observed median first-calculation reduction is about **13.1%**. The large
first-baseline outlier remains in the table and retained evidence. The two later
baselines are 51.341 and 50.724 seconds; they also exceed the candidate range, but
three observations on a changing shared host do not isolate causation. All six
exported fixtures have the same SHA-256.

The same source hashes and baseline commit are pinned in
[`experiment.json`](../diffuse-cache-profile/results/experiment.json).
Each run retains its source hash, exact command, raw test XML/build log and
exported fixture. The unique export directory is a Gradle task input, requiring a
fresh test invocation; each run also verifies that its fixture was generated.

To repeat, use JDK 21 and an idle machine. Preserve this evidence first: the runner
refuses to overwrite run directories. Run from the repository root:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  python3 validation/diffuse-repeat/run.py --run
python3 validation/diffuse-repeat/summarize.py
```

**Do not build or edit the workspace concurrently.** The runner checks the pinned
source hashes, temporarily exchanges only `CoherentDiffuse.kt`, and restores the
candidate in `finally`. An interrupted/killed process must be checked before any
build; compare its hash with the pinned candidate. Profiling flags and frequency
overrides must be absent. Test workers have independent JVM/JIT state; this is not
an isolated steady-state microbenchmark.

## OnePlus protocol and scope

Use only OPD2415 serial `50eb54c9`. Back up `files`, `shared_prefs` and the installed
APK outside the repository. Record every file hash. Inject the exact portable
fixture into a checksum-protected `recovery.current` record while the debug APK
still permits `run-as`. Install the candidate **release-mode, non-debuggable APK
signed with the test key**; retain its SHA-256. Launch through the normal activity.
Calculations still run in the platform-independent
shared core; no benchmark-only solver or reduced receiver set is used.

Build from `android/` using the retained signing-only init script:

```sh
./gradlew :app:assembleRelease -I ../validation/diffuse-repeat/test-signing.init.gradle \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

This does not change normal build configuration or production signing. Package
flags must exclude `DEBUGGABLE`; retain the successful release/R8 build log. A
non-debuggable build cannot use `run-as`, so inspect its checkpoint/assets after
reinstalling the original debug APK, before restoring original data.

An initial ordinary debug run was interrupted while still calculating after
roughly eleven minutes; it is **not a completed prediction or failure timing**.
A subsequent debuggable/resource-shrunk build was also abandoned: its build warning
explicitly disables code optimization. Both incomplete observations remain separate
from accepted release-mode results. Neither is used in the performance comparison.

`observe_oneplus.py --private-data-unavailable` observes two cold process launches.
The second opens the app's
own autosaved checkpoint, including separately persisted speaker data. Fresh UI
dumps must identify the app, four arrays, the 1 kHz mode and 56.7 dB area mean;
failed/stale/unavailable dumps are discarded. Retain the last observed running
time and first observed complete time as an interval, not an exact calculation
duration. The timing starts before activity launch and includes native UI/listener
work. This differs from the JVM coverage-only stopwatch. Polling is about five
seconds plus command overhead. Battery/thermal/memory observations are snapshots,
not peak or sustained-performance guarantees. Consecutive cold process launches
do not reset device temperature or system caches.

Accepted candidate observations were **152.0–159.3 s** and **152.5–159.9 s**, with
**56.7 dB** displayed both times and no rejected snapshots. The APK was 9,843,440
bytes; its digest and non-debuggable package flags are retained. These timings
are close to the historical roughly 162 s native result and do not demonstrate
a tablet speedup. The live memory snapshot was 247,951 KiB PSS / 393,508 KiB RSS /
187 KiB swap PSS. Battery temperature was about 33.3 °C after both runs, with USB
power connected. Neither snapshot establishes peak memory or a thermal rating.

Native evidence is limited to the rounded displayed mean, successful complete
status and checkpoint/asset recovery; every-receiver numerical parity is tested
by the JVM fixture. These candidate-only native observations do not establish a
device speedup over the previous solver. They do not qualify production signing, arbitrary
venues or full-venue 4/8 kHz diffuse coverage. Those gates remain open.

After both runs, reinstall the backed-up APK, restore original data, remove only
benchmark-created files, compare all original file bytes and reopen the original
project. Keep private backups outside version control. Retain only the synthetic
fixture and a restoration summary in this package.
`restore_oneplus.py --backup /path/to/private/backup` performs that inspection and
restoration, including the installed APK hash and complete original file manifest.

The final native persistence audit used the shared format's exact speaker hash:
`SHA256("speaker-data-v1\n" + payload)`. After correcting the audit script's initial
plain-hash assumption, a separate six-second release autosave capture verified the
compact checkpoint, all prediction inputs and the separately persisted speaker
asset. It is not a third coverage timing. The two full release runs were not
repeated. `native-recovery.json` and the retained synthetic checkpoint/asset record
that audit. `restoration.json` confirms the original APK and all nine files were
byte-identical before launch, with no extra benchmark files. The original scene
reopened unchanged; normal autosave subsequently refreshed recovery files, so the
report distinguishes the pre-launch byte check from post-launch scene equality.
