# Rejected window-traversal experiment

Do not ship this candidate. The 33-receiver offset pair regressed from 126.128 to
143.785 seconds (14.0%) despite exact results and successful disk recovery. The
common pair was 1.9% slower. Reduced geometry work did not offset changed
incident-cache admission/reuse. Production retains the original traversal order.
Historical sources, all eight trials and original experiment notes follow.
Current runtime inputs intentionally differ from this rejected experiment. Trials
ran under the original bounded package directory and were moved intact here after
rejection; paths in historical logs reflect that original location. Its verifier
checks retained artifacts, not current production source.

# Bounded diffuse geometry traversal — 2026-11-27

The old receiver-local cache retained the first 65,536 source/surface entries.
Overflow geometry could be rebuilt at every response interval/frequency chunk.
The candidate visits contiguous surface windows, each within that same entry
budget, across all intervals/chunks of one data band before advancing. Each tone
still adds surfaces in their original order. A single-chunk call retains the old
persistent first-entry cache behavior. Oversized cells retain uncached scratch.

No geometry formulas, quadrature, filter knots, frequency chunks (maximum 32),
source grouping, propagation order, convergence tolerances or work/memory limits
are enlarged or approximated. There is no source × surface × frequency tensor.
Window planning is cancellation-aware. Geometry remains receiver-local and in
shared-core; Android project formats and request capture are unchanged.

## Evidence and scope

`results/inputs.json` pins constant common production sources, Android adapter,
benchmark driver/build inputs, and the two retained synthetic scenes/reference
vectors. Separate baseline/candidate solver captures pin the sole runtime variant.
Fixtures use the same measured-array regression responses and independent filter
settings as the preceding cache work. This is numerical/performance regression
validation, not manufacturer equivalence or real-world calibration.

The first four runs are unprofiled fresh-worker pairs in balanced order:
before/after, after/before, three spaced receivers per scene. Runs five and six
profile the same three receivers separately; their clocks are not speed evidence.
Runs seven and eight compare 33 spaced receivers per scene. The candidate expanded
run additionally saves the complete scene/assets to disk, imports into a fresh
model, recalculates all 33 selected receivers and requires bit-identical levels
and powers. Reopen time is outside the request timing. Every run also compares
selected results to the retained full 333-receiver scalar reference at the existing
0.001 dB / 1e-9 relative-power tolerances. Matched variants must agree exactly.

`results/summary.json` contains paired timings, residuals and geometry-work counts.
Logs, JUnit reports, raw vectors, profiling flags, source hashes and load averages
are retained. A single 33-receiver timing pair is exploratory, not repeated native
or full-venue qualification. No tablet installation is part of this batch.

## Reproduce

Use JDK 21 and existing Gradle dependencies, from the repository root:

```sh
python3 validation/bounded-diffuse-geometry/run.py --stage bounded
python3 validation/bounded-diffuse-geometry/run.py --stage expanded
python3 validation/bounded-diffuse-geometry/verify.py
```

The runner refuses existing trial directories. Use a separate checkout/output for
new trials. It temporarily swaps only `CoherentDiffuse.kt` and restores the
candidate in `finally`. Do not edit/build concurrently with the runner. After an
abrupt interruption, compare current source to the retained candidate before
building. Routine CI excludes this manual timing driver; common numerical tests
and existing full-size acceptance tests remain active.

Focused common tests verify contiguous entry limits, exact ordering, zero/oversized
fallback, invalid budgets, exact tone powers versus uncached/full-cache controls,
and reduced geometry evaluations. Existing diffuse suites cover convergence,
interference, energy partition/conservation, missing data and double-counting.
