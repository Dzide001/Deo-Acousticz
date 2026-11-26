# Source-spectrum and total-drive results — revision 1

Reviewed 2026-11-18. **71 JUnit tests pass** (32 shared-core, 39 Android), zero failures/skips,
plus **6 Python CSV packaging tests**. Independent allocation/broadband/reference scripts reproduce.
Android debug assembly and iOS simulator shared-test compilation pass. No device installation,
on-device UI acceptance or numerical iOS execution was performed.

| Check | Result |
|---|---|
| 18 equal-band/flat-PSD/custom A/C/Z absolute totals | Maximum residual 1.08902185048e-08 dB; limit 1e-5 dB |
| 18 corresponding weighted-relative ratios | Maximum residual 3.02684100006e-09 dB; limit 1e-7 dB |
| Total mean-square-drive fractions | Sum to one within 1e-12 |
| Flat-PSD allocation follows actual width, not equal octave powers | Passed |
| Equal eight-band total versus legacy per-band drive | −9.03089987 dB as expected |
| Explicit zero excitation / missing active response | Zero band skipped; missing active band unavailable |
| Invalid, incomplete, non-finite and tampered allocations | Rejected |
| Preset/custom configuration and snapshot Undo/Redo/disk reopen | Passed |
| Profile changes while capture is queued | Previous snapshot preserved |
| Ordinary listener prediction and existing weighting/persistence regressions | Passed unchanged |

`allocated-snapshots.json` retains 36 complete synthetic engine packages (18 absolute and 18 relative),
resolved allocations, allocated band powers and raw residuals. `reference.json` retains independent
expected allocations/totals and script/dependency hashes. The refreshed numerical report records
shared Kotlin source-tree hash `93d117e790436c56f69eadac97d79fea410811e4e65406d4c246d36afeb2c979`. These tests verify the declared linear,
finite-band model. They do not establish actual amplifier power/headroom, total power across a
multi-speaker system, manufacturer prediction equivalence or controlled-measurement validation.
