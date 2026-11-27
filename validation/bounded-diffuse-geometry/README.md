# Bounded diffuse geometry/material optimization — 2026-11-27

Production keeps the original patch/frequency traversal and incident-cache
admission order. On a geometry-cache miss, an angle-independent absorption and
scattering partition is prepared once for the patch/data band instead of repeated
per emitter. Angular absorption or multi-angle scattering retains the exact
per-emitter path, including material selection/tie rules. The prepared value is
one temporary scalar; there is no additional persistent cache or frequency tensor.

The geometry cache remains **65,536 source/surface entries**, frequency scratch
remains **32 tones**, and incident-cache byte/key/entry limits are unchanged.
No geometry formula, material curve, quadrature, filter knot, summation order,
convergence tolerance, work limit or project schema changes. All logic remains
in shared-core; Android changes only add an opt-in benchmark driver and task inputs.

## Evidence

`results/inputs.json` pins constant production sources, Android adapter, test/build
inputs, and two retained synthetic measured-array fixtures/full 333-receiver
scalar references. Separate solver captures pin the sole runtime variant. No
manufacturer or physical calibration claim follows from these regression scenes.

Four unprofiled fresh-worker runs form balanced three-receiver pairs, before/after
and after/before. Two separate profiles compare geometry and cache work, with no
speed claims from their instrumented clocks. Expanded runs compare **33 spaced
receivers per scene** at the existing 0.001 dB / 1e-9 relative-power reference
tolerances. Matched before/after vectors must agree exactly. The expanded candidate
also saves scene/assets to disk, imports into a fresh model and recalculates all
33 receivers per scene, requiring bit-identical powers/levels and identical DSP.
Reopen calculation time is excluded from the reported request time.

Raw vectors, timings, JUnit XML, build logs, source/input hashes, profiling flags
and load averages are retained. Expanded timing is one pair, not repeated native
or full-venue acceptance. No tablet project is changed in this batch.

Balanced three-receiver medians: common **11.072 → 10.637 s (3.93%)**, offset
**28.876 → 27.906 s (3.36%)**. Common paired improvements are **7.43% / 0.25%**;
offset **4.48% / 2.21%**. Separate profiles confirm all geometry/cache/quadrature
work counters and occupancy remain identical. Instrumented clocks are not used
as speed evidence.

All 66 selected receiver values match the retained references; matched variants and
fresh disk-reopened calculations have **zero** level/power differences. The single
33-receiver pair improves common **15.127 → 14.633 s (3.26%)**, offset **125.514 →
118.193 s (5.83%)**. This is a bounded desktop observation; full-venue/native speed
and repeated expanded qualification remain open.

Validation passed **699 shared + 407 Android tests**, debug/release assembly and
iOS shared-test compilation, with no failures or skips. `results/verification.json`,
full logs, inventories and affected JUnit reports retain the local regression checks.

## Rejected experiment

[Window traversal](../windowed-diffuse-geometry/README.md) reduced geometry work,
but changed incident-cache admission/reuse. Its 33-receiver offset pair regressed
126.128 → 143.785 seconds (14.0%); common regressed 1.9%. All vectors and save/reopen
were exact, demonstrating why numerical correctness alone is insufficient for a
performance patch. That runtime change and its window helper are **not shipped**.
Its sources and eight trials remain archived separately.

## Reproduce

From the repository root with JDK 21 and existing dependencies:

```sh
python3 validation/bounded-diffuse-geometry/run.py --stage bounded
python3 validation/bounded-diffuse-geometry/run.py --stage expanded
python3 validation/bounded-diffuse-geometry/verify.py
python3 validation/windowed-diffuse-geometry/verify.py
```

The runner refuses existing trial folders; use a separate checkout/output for new
trials. It temporarily swaps only `CoherentDiffuse.kt`, restoring the candidate in
`finally`. Do not edit/build concurrently. After abrupt interruption, compare
current source to the retained candidate before building. Routine CI excludes the
manual benchmark; numerical diffuse and existing full-size acceptance tests remain
active. Shared tests cover exact coefficients at multiple angles/frequencies,
material ties, angular fallbacks, interference, conservation and double-counting.
