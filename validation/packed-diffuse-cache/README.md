# Eager-packing prototype — 2026-11-26

**Superseded by `../pressure-packed-cache/`: these observations describe the
eager-packing prototype, not the final cache policy.**

Following `filter-knot-costs`, retain the 32 MiB spectrum payload budget, 64 MiB
accounted key budget, entry limit, request lifetime, contexts/loss keys and no-eviction
policy. Cached powers may now use an exact XOR-byte representation: the first
raw double bits plus adjacent XORs, with two four-bit byte-width headers per byte.
Every decoded value has its original raw bits. Incompressible or tiny rows retain
copied doubles. The row limit remains 32; there is no persistent compressed format.

Charge encoded headers/data against the same byte budget. Increase the conservative
key/entry charge from 192 to 224 bytes for the representation wrapper, rather than
ignoring its memory cost. Object/registry overhead is additional, as before. Logical
cached-value count can exceed the old double-equivalent limit; payload-byte metrics
are now the relevant capacity measure. Decode scratch is at most one 32-tone row.
All quadrature nodes, weights, ordering, filter knots, positive integration,
convergence tolerances, missing-data behavior, cancellation and work limits stay
unchanged. No source-response approximation or new cross-request cache is added.

Shared tests cover all row sizes, arbitrary raw bit patterns, signed zero and extreme
values, smooth/constant packing, incompressible fallback, buffer ownership, tiny
byte/key/entry limits, rejected/missing data, independent receivers/losses and
conservation/interference regressions.

## Protocol

Run two unprofiled fresh-worker pairs **before/after, after/before**, followed by
one candidate instrumented cost observation, on the same three spaced receivers
in each retained scene. Only `DiffuseIncidentCache.kt` is exchanged. Current APIs,
codec availability, diagnostics and all other physics are held constant. Every
level and power is checked against the established 333-receiver scalar reference.
The baseline does not use the codec or the new byte metrics. Timed runs contain no
JFR/diagnostic clocks. Host load is recorded, not controlled.

From repository root with JDK 21:

```sh
python3 validation/packed-diffuse-cache/run.py
python3 validation/packed-diffuse-cache/summarize.py
```

Existing trial folders are refused. Do not build/edit concurrently. The runner
restores the candidate source in `finally`; after an abrupt kill, verify its hash
before building. Raw source captures, vectors, logs, JUnit XML and request timings
are retained. The manual benchmark is excluded from normal tiers.

## Results

The two offset-scene pairs improved **9.80% / 9.85%**: medians **31.9073 →
28.7719 s**. The common-corner pairs were mixed (**+6.00% / −0.26%**), so no
consistent gain is claimed there. All retained levels and powers match exactly.
These are three-receiver request timings, not full-venue or device speed ratings.

The instrumented candidate retained **4,727,056 logical values** using
**33,554,414 payload bytes**, under the unchanged 33,554,432-byte limit.
It retained 157,232 packed entries, with 35,219,968 accounted key bytes, and still
rejected 109,207 puts. This reduces pressure without eliminating cache overflow.
The new diagnostics distinguish logical values from actual encoded payload bytes.

A separate unprofiled candidate check expanded to **12 receivers per scene**:
all 24 levels/powers match the original full-scene reference. Times were
**60.057 s offset / 19.875 s common corners**. There is no matched twelve-receiver
baseline and no extrapolation to all 333 receivers.

Validation passed **666 shared + 449 normal Android** tests, including the large
save/reopen cases; Android debug/release and iOS-core/test compilation passed.
The original test-key signature is applied to the non-debuggable R8 release APK
for reversible native functional checking. This is not production signing.

The prototype OnePlus functional save/reopen was checked with the **common-corner**
four-chain fixture (55.1767400999864 dB reference, 55.2 dB displayed), not the
unqualified expensive offset scene. Both observations matched 55.2 dB within **173.0 / 174.0 s**. These are
unpaired polling bounds, not a causal or thermal speed comparison. The first
original-APK restore attempt encountered an offline transport before installation;
the original nine-file/APK backup remained safe; original restoration was subsequently verified after debugging recovered.
A pressure-only refinement is being tested to avoid decoding scenes whose raw
spectra fit the budget.
The full offset venue, native offset performance, sustained thermals and
interactive latency remain open gates. Next measure bounded geometry traversal
and 33-receiver costs before attempting another long full-scene/device run.
