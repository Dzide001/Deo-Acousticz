# Engine snapshot capture results — revision 1

Reviewed 2026-11-18. **81 JUnit tests passed**: 56 shared-core and 25 Android, zero
failures/skips. The independent capture-reference check and existing 18 numerical fixtures pass.
Android debug assembly and iOS simulator shared-test compilation pass. No device installation,
on-device UI acceptance or numerical execution on iOS was performed.

| Check | Result |
|---|---|
| Two synthetic point-source levels against independent Python references | Passed |
| +6 dB gain capture and effective electrical drive | Passed |
| Maximum numerical level residual | 5.17303533343e-10 dB (limit 1e-5 dB) |
| Raw below-zero levels and unavailable measured data | Passed; no display-floor substitution |
| Reference levels ignored by engine calculation | Passed |
| Geometry/bandwidth/atmosphere/ground/directivity/generic identity | Passed |
| Frozen request stability after scene changes | Passed |
| Late/superseded/cleared/queued captures | Rejected as required |
| Capture Undo/Redo and disk save/fresh reopen | Passed with original engine document retained |
| Existing prediction, import, recovery and stale-result regressions | Passed |

`captured-snapshots.json` retains full engine packages and raw per-sample residuals for both gain
settings, reference hash and synthetic numerical-only scope. `reference.json` retains independent
reference levels and equation/script hashes. The refreshed numerical package report records shared
Kotlin source-tree hash `ca9f8b2b05c787fa587b345772aefc80ab87e864ce5ec04b577a05a62b047747`. Both synthetic captures remain explicitly
unqualified for external validation. Actual manufacturer exports and controlled measurements under
reviewed matching protocols are still pending.
