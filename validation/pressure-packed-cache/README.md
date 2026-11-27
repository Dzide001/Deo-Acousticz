# Pressure-triggered lossless diffuse cache — 2026-11-26

This refines the eagerly packed prototype in `../packed-diffuse-cache/`.
Keep rows as copied doubles, with the original allocation-free hit path, until a
new row would exceed the **32 MiB payload budget**. Then perform one cancellable
sweep that losslessly packs compressible retained rows; subsequent admissions use
packing with raw fallback. No eviction, persistent queue or larger budget is added.
Raw rows keep their original DoubleArray representation and 192-byte entry charge;
packed wrappers add 32 bytes. Accounting remains under the original 64 MiB and
262,144-entry limits. Logical values and physical encoded bytes are reported
separately. Peak payload accounting includes the raw-cache high-water mark.

The codec preserves every raw double bit using adjacent XORs and packed byte-width
nibbles, with a raw fallback when no size reduction is possible. This is transient
request-local storage, not a project format or acoustic approximation. No geometry,
filter knots, quadrature nodes/weights/order, tolerances or source/path-loss keys
change. Cache conversion polls cancellation once per at most 32 rows, through
`BoundaryWork.check(0)`, without changing numerical-work charges.

The raw control capture has only its `put` signature adapted to accept the unused
cancellation callback; its original admission/evaluation behavior is unchanged.
`CachedPowers`, current diagnostics and all other source files stay constant.
Two unprofiled fresh-worker pairs run **before/after, after/before**, then one
candidate profile; each checks the same three spaced receiver indices in both
retained scenes against the full 333-receiver scalar reference. Normal test tiers
exclude manual benchmarks. Input hashes, source captures, raw vectors, JUnit XML,
logs, clocks/profiling flags and load observations are retained.

```sh
# Repository root, JDK 21. Refuses existing trial folders.
python3 validation/pressure-packed-cache/run.py
python3 validation/pressure-packed-cache/summarize.py
```

Do not build/edit concurrently with the source-swapping runner. Candidate source
is restored in `finally`; after an abrupt kill, verify hashes before building.

Shared tests cover raw-bit round trips, signed zero/extremes, incompressible
fallback, ownership, byte/key/entry bounds, more logical values within the same
byte cap, raw hits before pressure, cancellation during conversion, and diffuse
accuracy/interference/conservation and save/reopen regressions.

The eagerly packed prototype matched numerical references but its common-corner
OnePlus observations were 173.0 / 174.0 s. Those are unpaired UI polling bounds,
not proof of a causal regression; they motivated avoiding unnecessary decode work.
A second pressure-only prototype still wrapped raw rows and slowed both common-scene
pairs; its trials are retained under `results/wrapper-prototype/`. The final policy
removes raw wrappers entirely. The first original-APK restoration failed while USB was offline. The original
backup remained safe. Debugging was recovered, the earlier original was restored,
and subsequent X/pan/array-aim changes were preserved in a fresh private backup.
Final common-corner native save/reopen and original restoration are now complete. Full offset-venue/native
performance remains unqualified until expanded, matched receiver checks justify
another long full-scene attempt.

## Final three-receiver results

Offset medians **30.9552 → 28.3500 s**, paired improvements **7.89% / 8.94%**.
Common-scene pairs were mixed (**−2.43% / +0.72%**); no consistent gain is claimed.
Maximum level and relative-power residuals are zero. The common scene retains
**0 packed entries** and the original 22,936,704-byte raw payload. The offset
scene stores **4,727,056 logical values in 33,554,414 bytes** but still rejects
109,207 puts. No whole-venue or device speed claim follows.

All 24 twelve-receiver reference values match. Instrumented expanded times:
**57.020 s offset / 15.339 s common**; no matched expanded timing claim is made.
The common twelve-receiver cache also retains zero packed rows. Validation passed
**667 shared + 449 Android** tests, Android debug/release builds and shared iOS-core/
test compilation. The two independent numerical/convergence and spectrum reference
scripts also pass (18 cases each).

Final R8-optimized, non-debuggable OnePlus observations completed within **147.8 /
152.4 s**, both **55.2 dB**, including native save/reopen of the **common-corner**
four-chain scene. Compact inputs, DSP and content-addressed assets match exactly.
These are polling bounds, not matched native before/after speed measurements.
The expensive offset venue remains unqualified on the device.

The original APK and all nine latest original files were restored byte for byte
before reopening. The latest scene string is unchanged after reopen. Android
updated `files/profileInstalled`, and the app rotated the restored current
checkpoint to `recovery.previous`; the latter is verified byte-identical to the
backup's current checkpoint. No unrelated changes are accepted. The latest
speaker edits and the earlier original backup are both preserved privately.
See `results/oneplus/restoration.json` and `results/native-status.json`.

`python3 validation/pressure-packed-cache/verify.py` audits all evidence, including
native recovery and restoration. `--jvm-only` deliberately excludes device acceptance.
