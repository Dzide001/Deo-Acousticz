# Full-venue diffuse JVM profile — 2026-11-26

Profile the production shared solver through the unchanged Android scene acceptance
fixture: 64 measured emitters, 333 receivers and 1 kHz third-octave diffuse coverage.
The fixture checks every receiver and area-weighted statistics after fresh JSON
reopen, as well as the scattering boundary's contribution. This is synthetic
measured-response data, not manufacturer or field validation.

| JFR-enabled observation | Before | After |
| --- | ---: | ---: |
| First coverage calculation | 56.694 s | 59.192 s |
| Complete acceptance test | 114.863 s | 107.273 s |
| Area mean | 56.71420139630636 dB | 56.71420139630636 dB |

**End-to-end speedup is unproven.** These are single local JVM observations with
profiling enabled. The first calculation became slower while the complete test
became faster. They do not qualify OnePlus performance, interactive latency or
full-venue 4/8 kHz diffuse coverage. No spatial/frequency sampling, convergence,
work/cancellation limits, cache budgets or tolerances changed.

The baseline profile identified repeated traversal of boxed job indices in the
cache-hit transfer loop. Replace it with one primitive index array of at most 32
entries per frequency chunk. Preserve output ordering, arithmetic and every work
check. The follow-up profile contains fewer sampled stacks in that loop; sample
counts are not exact CPU times and JIT/inlining can change line attribution.
The regression exercises interleaved output indices, multiple spectral intervals,
32-tone chunks plus tails, two receivers, and cached/uncached agreement.

## Reproduction

Use JDK 21 and run from `android/`, selecting a fresh recording filename for each
variant. `acousticProfileRecording` is optional; ordinary tests do not enable JFR.
Gradle may consider an identical invocation up to date; use a new recording path
or `--rerun-tasks` when deliberately repeating an observation.

```sh
./gradlew :app:testDebugUnitTest -PacousticValidationTier=large \
  -PacousticProfileRecording=/tmp/acoustic-diffuse-run.jfr \
  --tests '*PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics' \
  -Pkotlin.compiler.execution.strategy=in-process --offline

jfr print --json --events jdk.ExecutionSample,jdk.NativeMethodSample \
  --stack-depth 64 /tmp/acoustic-diffuse-run.jfr > /tmp/acoustic-diffuse-run.json
```

From the repository root:

```sh
python3 validation/diffuse-cache-profile/summarize.py \
  /tmp/acoustic-diffuse-run.json /tmp/acoustic-diffuse-summary.json \
  --recording /tmp/acoustic-diffuse-run.jfr
python3 validation/diffuse-cache-profile/verify.py
```

`results/experiment.json` pins the baseline commit and both solver source hashes;
`candidate.patch` retains the exact change. To reproduce before, use that baseline
solver in a separate checkout with the same optional profiling/test-tier tooling.
Keep JDK, fixture, machine and profiling configuration matched. Raw JFR/event JSON
remain temporary because they can include JVM environment details. Retained stack
summaries pin the recording SHA-256, identify sampled methods and exclude those
payloads. XML and build logs retain actual results. `verification.json` records
the final suite inventories and hashes, including the other large-scene checks.
All **645 shared tests and 449 Android cases** passed across the retained runs;
Android assembly and shared iOS compilation passed too.

Next performance gate: repeated unprofiled matched observations, then OnePlus
acceptance. Coupled spatial/frequency integration remains a separate investigation
for the still-unqualified high-frequency diffuse workload.
