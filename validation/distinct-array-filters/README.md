# Independent array DSP validation — 2026-11-26

This extends the shared-eight-filter fixture with **four different chains**, each
containing eight HPF/LPF/EQ sections, across 64 emitters and 333 receivers. Arrays
have different gains and complex responses; one uses 96 kHz and another
alternates 48/96 kHz sections. Geometry, source data, work/convergence limits and
1 kHz third-octave diffuse integration are unchanged. This is a synthetic numerical
check, not a manufacturer comparison or a high-frequency diffuse qualification.

The opt-in `DistinctFilterBenchmarkTest` captures listener/source levels, every
receiver level/power, valid area and exact project export/reimport. Normal test
tiers exclude it. New shared tests exercise equal-chain sharing, distinct complex
responses, source reordering, filter edits and independent Nyquist missing data.

One fresh-worker baseline/candidate pair exchanges only the captured pre/post
sampling and biquad code from `filter-transfer-reuse`. All other code is held
constant. The baseline retains scalar sampling-filter evaluation and repeated
biquad construction; this is not an independent reference for the entire acoustic
engine. The first observation pins the new fixture/reference, and the optimized
run must match it at 0.001 dB level, 1e-5 dB mean and
max(1e-10, reference power * 1e-9). No JFR or diagnostic clocks are enabled.
A single pair is an accuracy check with timing observations, not a repeatability
or device speedup claim. Host load and source hashes are retained.

From the repository root with JDK 21:

```sh
python3 validation/distinct-array-filters/run.py
python3 validation/distinct-array-filters/verify.py
```

The runner refuses existing trial folders and restores both optimized files in
`finally`. Do not edit/build concurrently. After an abrupt process kill, check
candidate hashes before building. Raw fixture, observations, test XML and logs
are retained under `results/`.

The production engine is unchanged. Native tests reuse the exact optimized,
non-debuggable release APK already verified in `filter-transfer-reuse`, with a
fresh original-project/APK backup. Save/reopen checks compare native compact
inputs, each independent DSP chain and hashed assets with the fixture. Restore
checks require the original APK and all original file bytes, no temporary assets,
and the original project reopening unchanged. Private backups and screenshots
remain outside Git. Native timings use UI polling and can have different origins
for launch versus an explicit Manual Calculate after reopen.

The first exploratory fixture also offset corners and Q values. Its scalar
coverage completed in 1563.799 s; the candidate run was stopped and its logs
retained under `results/exploratory-offset-corners/`. It has **no candidate accuracy
or performance acceptance**. A second trial was stopped early after code review
confirmed Q also changes integration knots; its logs are retained separately.
The final full-venue isolation fixture holds Q and corners fixed. Small shared
sampling tests additionally cover distinct Q/corners with scalar equality.
The knot-building expression in `DirectField` includes each filter frequency plus
multiples of frequency/Q; extra distinct knots are an evidenced mechanism, but
no completed profile establishes the complete cause of the severe slowdown.

Validation passed **661 shared + 446 routine Android** tests and iOS shared-test
compilation. The three unchanged large cases retain preceding-batch evidence;
production code and the previously built optimized APK are unchanged. The audit
pins the reused APK digest and all four relevant production-core source hashes.

## Retained bounded-fixture results

One scalar/candidate pair: coverage **73.2565 / 55.5684 s**, listener
**8.7295 / 8.5954 s**. Maximum numerical residuals are zero, mean
**55.1767400999864 dB**, valid area 100%. This is not a repeated speed rating.
OnePlus initial launch and fresh save/reopen completed within **149.0 / 149.9 s**,
both **55.2 dB** (second timing starts at Manual Calculate). All four saved DSP
chains, scene inputs and hashed assets match the fixture. Original APK and nine
original files were restored byte for byte, temporary asset removed, and the
original project reopened unchanged. Device timings are polling bounds, not
matched native before/after measurements.

Next profile integration slices and bounded cache behavior in the exploratory
corner/Q-offset case before attempting another long native workload. The passing
bounded fixture does not qualify that scene.
