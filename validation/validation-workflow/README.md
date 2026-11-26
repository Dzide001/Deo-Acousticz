# Validation workflow — revision 1

In Android Settings → Imported comparisons, choose **Review protocol and residuals** after
importing a reference and importing/capturing an engine snapshot. **Export report** writes a
standalone UTF-8 JSON report through Android's document picker.

The shared core produces all compatibility diagnostics, residuals, metrics and plot coordinates.
Android only renders them and writes the selected export. A report freezes the workspace at the
export click; subsequent scene/import changes cannot silently replace its inputs.

## Review and interpretation

- Protocol rows review speaker revision/calibration, preset/DSP, geometry/boundaries, frequency
  integration, metric, atmosphere, ground, drive, normalization and broadband weighting/excitation.
  Structured condition strings expose differing JSON leaves. Equivalent JSON with differing string
  encoding is explicitly identified; the existing strict matcher still rejects the encoding mismatch.
- Residuals are **engine minus reference in dB**. Diagnostic pairing requires identical sample IDs,
  frequency and coordinates. Missing, moved, frequency-mismatched, extra and unavailable samples
  have explicit row statuses, without a fabricated zero residual. Reordered rows may be paired for
  inspection while strict comparison remains not comparable.
- RMS, signed bias and maximum absolute error summarize only the paired rows. A report is marked
  `DIAGNOSTIC_ONLY_NOT_QUALIFIED` if protocol, completeness, origin or calibration checks block
  qualification. A low diagnostic error never overrides that status. There is no offset fitting.
- The overview plots residual against sample order. Per-receiver plots use log-frequency positions.
  Broadband aggregates have only the overview, since their frequency marker is not a tone. The shaded
  tolerance band is the reference's declared tolerance, not independently justified field accuracy.
- `PASSED`/`FAILED` apply only to the strict matched sample set and declared evidence scope. Declared
  manufacturer provenance and measurement calibration are not authenticated by this software.

## Report and recovery contract

Schema `droidacoustic.validation-report`, version 1 includes the authoritative summary and hashes,
protocol review, residual rows and metrics, normalized plots, model limitations, and both original
import envelopes as strings. Each envelope retains its raw payload. Review UI abbreviates long
values and paginates; exports retain originals. Leaf differences are capped at 512 with full count
and an explicit truncation flag. Exporting does not change scene data or qualification.

Reports contain no wall-clock timestamp: restoring a saved workspace recomputes exactly the same
report. Existing project storage already persists both original documents; no archive migration is
needed. Review expansion, page and plot selection are temporary UI state. The export file can be
inspected independently; import its two `inputs` documents separately to reconstruct the comparison.
The report itself is not an import envelope.

## Reproduce

From `android/`, run:

```sh
./gradlew :shared-core:jvmTest --tests '*ComparisonValidationTest' --tests '*ValidationWorkflowPackageTest' --tests '*ComparisonImportsTest' :app:testDebugUnitTest --tests '*ComparisonImportIntegrationTest'
```

`ValidationWorkflowPackageTest` reads the versioned synthetic comparison-import fixtures and
produces `android/shared-core/build/reports/validation-workflow/results.json`. The reviewed copy is
[results/reports.json](results/reports.json). One example has residuals +1 and −2 dB under matching
protocol; another changes ground conditions. Both remain unqualified because their inputs are
synthetic. Tests also exercise declared independent evidence solely to test qualification branches;
that is not an actual manufacturer comparison.

The final focused run passed **54 tests** (32 shared, 22 Android), Android debug assembly and
iOS simulator shared-test compilation; details are retained in [results/checks.json](results/checks.json).
Original-document hashes and example residual metrics were also checked independently with Python.
On-device visual/document-picker acceptance was not run in this batch.

Tests cover signed residual/RMS/bias math, log-frequency coordinates, nested/encoding mismatch
review, incomplete/moved/reordered/overflow samples, synthetic gating, deterministic reconstruction,
Undo/Redo, disk save and fresh project reopen. Android debug build and iOS simulator shared-test
compilation check platform compatibility. No new acoustic propagation approximation is introduced.

Real matched manufacturer exports, calibrated measurements and justified acceptance tolerances
remain V2 release gates. This workflow makes that evidence reviewable; it does not supply it.
