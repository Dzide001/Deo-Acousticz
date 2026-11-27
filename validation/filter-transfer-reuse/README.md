# Filter-heavy sampling and transfer-response reuse — 2026-11-26

Use the exact eight-filter synthetic measured-array fixture and reference from
[the matched benchmark package](../filter-heavy-arrays/README.md): 64 emitters,
333 receivers, 1 kHz third-octave diffuse coverage, eight common HPF/LPF/EQ sections,
54.23276879151662 dB reference mean. This is numerical/performance verification,
not manufacturer or field acceptance.

## Shared-core change

Immutable `DspFilter` instances derive their six biquad coefficients once, after
the same parameter validation. Coefficients are not serialized or included in
data-class equality/hash/copy identity; copy and reopen reconstruct them from the
original settings. `DspFilterChain.at` preserves section order and Nyquist/missing
behavior, sharing frequency sine/cosine values only across consecutive equal sample
rates. A rate change recomputes them; empty-chain identity behavior is retained.

Adaptive diffuse sampling prepares response rows on its first uncached profile,
for at most 32 segment frequencies. Equal immutable chains share a row. Null rows
mean identity and null elements mean missing responses. More than 32 frequencies
retain scalar evaluation. Angular/source responses, incidence/material effects and
shadows are still evaluated at their original points. Rows are local to the
sampling call and released afterward; no cross-request response cache is added.
The same row helper serves existing 32-tone miss chunks. Persistent cache limits,
geometry, quadrature, convergence, work accounting and cancellation polls remain
unchanged.

Focused regressions cover ordered individual-section equivalence with mixed
sample rates, copied/reopened coefficients and phase, invalid/Nyquist responses,
prepared/scalar sampling equality, missing responses and 33-segment fallback.
Existing independent cutoff/phase, high-Q dense integration, interference,
conservation and bounded-cache references remain active.

## Matched benchmark protocol

Two fresh-worker pairs run **before/after, after/before**, without JFR or diagnostic
timers. Only `CoherentDiffuse.kt` and `DspFilters.kt` are temporarily exchanged;
the current shared row-helper API and previously qualified miss setup stay constant.
Source captures and hashes are retained. All receiver levels/powers, listener/source
levels, input hashes and means are compared with the established scalar fixture
at 0.001 dB per level, 1e−5 dB mean and max(1e−10, reference power × 1e−9).
Host load is recorded, not controlled; no universal/thermal speed rating follows.

The retained runner refuses existing trial folders and restores candidate files
in `finally`. Do not build/edit concurrently. After an abrupt process kill, check
both candidate hashes before any build. From the repository root, JDK 21:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  python3 validation/filter-transfer-reuse/run.py
python3 validation/filter-transfer-reuse/verify.py
```

The unchanged manual `FilterHeavyBenchmarkTest` remains excluded from normal test
tiers. Fixture creation/export and request capture are outside the timed listener
and coverage requests. JVM project reopen checks exact inputs; native testing
separately recalculates from the application's autosaved compact project/assets.

## Native recovery

Start/observe/restore helpers reuse the preceding package's workflow with the same
reference mean, fixture and selected OnePlus OPD2415 (`50eb54c9`). Private backups
include the original installed APK, data and file hashes. Only release-mode,
R8-optimized, non-debuggable, test-key-signed APK observations qualify as native
performance checks. UI polling supplies running/complete intervals, not exact
calculation durations. A Manual Calculate action, if required, has its own timing
origin. Private backups and screenshots stay outside version control.

The first install attempt encountered an ADB host-service/USB offline failure
after staging the synthetic recovery slot. That attempt has no completion timing.
After reconnection the guarded `--resume-staged` retry checked every original file
except the exact pinned recovery slot before installing. Both native runs passed
at 54.2 dB; initial launch completed within 94.9 s and explicit Manual Calculate
after reopen within 116.2 s (polling bounds, not a paired native speed claim).
Native compact saved inputs, eight-filter DSP chains and content-addressed assets
match the fixture. The original APK and all nine original files were restored
byte for byte; the extra benchmark asset was removed and the original project
reopened unchanged. Failed-setup logs remain historical evidence only.

Production signing, other scenes, sustained thermal/peak memory ratings,
interactive diffuse latency and full-venue 4/8 kHz qualification remain open gates.

## Retained results

Coverage medians: **72.3097 → 52.8448 s**, paired reductions **26.70% / 27.14%**.
Listener medians: **8.4462 → 7.9415 s**. Maximum level, mean and relative-power
residuals against the prior scalar reference are all zero. All 659 shared checks,
446 fast Android checks and three large Android checks passed, including desktop
project reopen; iOS-core compilation and optimized release assembly passed.
See `results/summary.json`, inventories, raw XML and build logs.

`python3 validation/filter-transfer-reuse/verify.py` audits the matched computer
observations, source hashes, test inventories, native accuracy/reopen and original
restoration. `--jvm-only` deliberately excludes native acceptance. Raw native
observations, recovery/asset hashes, restoration audit and setup logs are retained
under `results/oneplus/`. Private user backups and screenshots remain outside Git.
