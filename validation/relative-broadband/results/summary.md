# Relative weighted broadband results — revision 1

Reviewed 2026-11-18. **74 JUnit tests pass** (36 shared-core, 38 Android), zero failures/skips,
plus **5 Python CSV packaging tests**. Independent broadband, relative weighted-total and existing
numerical reference checks reproduce. Android debug assembly and iOS simulator shared-test
compilation pass. No device installation, on-device UI acceptance or numerical execution on iOS
was performed.

| Check | Result |
|---|---|
| Six A/C/Z reference ratios, each at 0/+12 dB gain | 12 captures passed; maximum residual 5.27251131643e-10 dB, bound 1e-7 dB |
| Reference receiver normalizes to zero and common gain cancels | Passed |
| Ratio of weighted sums, not averaged band dB ratios | Passed with an explicit unequal-band-loss case |
| Missing numerator/reference band | Unavailable; no partial aggregate or substituted band |
| Zero/near-null/below-floor weighted denominator | Unavailable |
| Weighting, method, units and schema mismatch | Rejected or NOT_COMPARABLE |
| Raw numerator/reference band powers and weighted reference status | Retained |
| Android Undo/Redo and disk save/fresh reopen | Original version-4 package retained |
| Queued capture after weighting change | Rejected; prior snapshot preserved |
| Existing weighting, relative, absolute and persistence checks | Passed |

`normalized-snapshots.json` retains the twelve complete synthetic packages and per-case residuals,
including weighted-total reference levels, validity flags and raw band powers at both receivers.
`reference.json` pins independent weighted-total source references and scripts with hashes. Refreshed
numerical results record shared Kotlin source-tree hash `eb0265299eb19471089c2bab0ad6c9722c1c10adbf572f031e372b38830727e4`.
These are numerical checks under the declared finite-band/per-band-drive model. The synthetic
examples do not qualify manufacturer prediction accuracy or controlled-measurement validation.
