# Weighted broadband capture results — revision 1

Reviewed 2026-11-18. **74 JUnit tests passed** (37 shared-core, 37 Android), zero
failures/skips, plus **4 Python packaging tests**. Independent broadband, relative, absolute and
existing numerical references reproduce. Android debug assembly and iOS simulator shared-test
compilation pass. No device installation, on-device UI acceptance or numerical iOS execution was
performed.

| Check | Result |
|---|---|
| Six independent finite-octave A/C/Z sums at 10/20 m | Passed; maximum residual 7.65723484619e-09 dB, limit 1e-5 dB |
| Aggregate reconstructed from raw band powers and weights | Passed |
| +6 dB gain and negative dB results | Passed, without display-floor substitution |
| Missing required high-band response | Aggregate unavailable, not a partial sum |
| Different A/C coefficients or range/metric definitions | Rejected or NOT_COMPARABLE |
| Wrong units, aggregate marker, schema and overlapping-width definitions | Rejected |
| Capture uses scene weighting rather than imported weighting | Passed |
| Per-band diagnostics and original documents through Undo/Redo/save/reopen | Passed |
| Queued capture cancelled by weighting change | Previous snapshot preserved |
| Existing Android weighting, relative/absolute and persistence regressions | Passed |

`weighted-snapshots.json` retains all six complete synthetic engine packages, raw octave-band
powers, actual band cells/coefficients, reference hash and raw aggregate residuals. `reference.json`
retains independent per-band powers, weighted results and script/dependency hashes. Refreshed
numerical results record shared Kotlin source-tree hash `43a8dea40c5b51901cba348bde2bc12f734f97ea1d9b4796deb66104cf822b2f`.
The acceptance limit is numerical only. These are finite-band, per-band-drive sums, not a certified
full-range sound-level-meter reading; no new manufacturer or controlled-measurement validation is
claimed.
