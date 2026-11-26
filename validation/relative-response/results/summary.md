# Relative-response results — revision 1

Reviewed 2026-11-18. **53 JUnit tests passed** (34 shared-core, 19 Android), zero
failures/skips, plus **3 Python packaging tests**. Independent relative/absolute reference scripts
and the previous 18 numerical fixtures pass. Android debug assembly and iOS simulator shared-test
compilation pass. No on-device UI acceptance, physical measurement or numerical iOS execution
was performed.

| Check | Result |
|---|---|
| Four per-frequency point-source ratios against independent Python | Passed, maximum residual 2.58675747489e-10 dB (limit 1e-7 dB) |
| +12 dB common-gain cancellation above the safety floor | Passed |
| Reference receiver normalizes to 0 dB | Passed |
| Equal-range measured off-axis attenuation | −6 dB retained within 1e-7 dB |
| Missing bands / zero or below-floor denominator | Unavailable; no substituted denominator |
| On-axis coordinate / multi-emitter axis ambiguity | Rejected as required |
| Missing, unsupported or mismatched normalization metadata | Rejected or NOT_COMPARABLE |
| Raw denominator levels and validity diagnostics | Retained in snapshots |
| Relative capture Undo/Redo and disk save/fresh reopen | Passed |
| Stale capture / changed scene publication | Rejected as required |
| Existing absolute capture, importer and persistence regressions | Passed |

`normalized-snapshots.json` retains both complete synthetic engine packages, per-frequency raw
reference levels/flags, basis, hashes and raw residuals. `reference.json` retains independent
expected values and script/dependency hashes. Refreshed numerical results record shared Kotlin
source-tree hash `d53229f3127dccdd5d80c01cb89615b289f96c6a2d81cbef6ccf1a5f2c4464f4`. This is numerical verification only; all retained
examples remain synthetic and do not qualify external manufacturer/measurement validation.
