# Bounded filter-knot / cache-cost profile — 2026-11-26

This profiles the next gate from `distinct-array-filters`: the original
corner/Q-offset 64-emitter scene whose scalar coverage took 1563.799 s. It keeps
the full geometry, sources, filtering, atmosphere, material/scattering model,
convergence tests and all work/cache limits. Only receiver selection is bounded:
indices **0, 166 and 332** from the original 333-receiver coverage mesh.
The simpler distinct-gain/sample-rate fixture is the control. These are different
inputs, so their timing difference is **not** an optimization speedup.

`FilterKnotProfileTest` runs real immutable shared-core requests, checks all six
levels/powers against the retained full-scene scalar references, and records opt-in
costs. New shared diagnostics count nonempty diffuse integrations, total base
intervals, refinement passes, total/max quadrature tones and peak spectrum-cache
values/entries/accounted key bytes. They do not include geometry-cache payload,
registry/object overhead, total application memory, or direct-path refinements.
Clock/counter work remains disabled for ordinary requests. No integration,
cache-admission, geometry or persistence behavior has changed in this batch.

| Three-receiver aggregate | Common corners/Q | Offset corners/Q |
| --- | ---: | ---: |
| Base intervals | 9 | 39 |
| Refinement passes | 9 | 9 |
| Total / max tones | 1008 / 192 | 2184 / 416 |
| Peak cached values | 2,867,088 | 4,194,296 |
| Cache misses | 102,396 | 292,204 |
| Miss tone evaluations | 2,867,088 | 8,972,248 |
| Geometry evaluations | 4,751,872 | 13,253,632 |
| Instrumented miss time | 10.542 s | 27.670 s |
| Instrumented request time | 12.081 s | 28.838 s |

Both scenes converge after three passes per receiver. The offset case nearly
exhausts the **4,194,304-double / 32 MiB value budget**, leaving only eight values,
while entry/key budgets remain below their limits. About 96% of its observed
request time is inside misses. Every sampled level and power matches exactly.
This identifies cache-capacity pressure and repeated miss work; it does not prove
the full 333-receiver cost curve, native performance, or one exclusive cause.

The next optimization experiment should address admission/reuse under the fixed
value budget, with bounded geometry traversal as another measured candidate.
Do not increase cache/work limits or drop filter knots/convergence checks to make
the test pass. Compare fresh before/after requests on these receiver indices,
then expand to intermediate receiver counts before another full-venue/tablet run.

Run from the repository root, JDK 21:

```sh
python3 validation/filter-knot-costs/run.py --output /tmp/knot-profile-repeat
python3 validation/filter-knot-costs/verify.py
```

The runner refuses existing output folders and records raw observations, logs and
JUnit XML. The checked-in `results/profile/costs.json` is the initial observation;
`results/inputs.json` pins the earlier fixture/reference files. All six values are
verified independently by the audit. Normal fast/large suites exclude this manual
test. Shared regression coverage checks profiled/unprofiled exact equality and
cache-occupancy bounds with a deliberately tiny cache.

Android build, routine suites and iOS shared-test compilation are recorded with
the results. No tablet install was needed for this profiling-only batch; the
original OnePlus project from the preceding batch was not changed. Native and
full-venue acceptance of the expensive case remain open. Existing saved-project
formats and DSP settings are unchanged.
