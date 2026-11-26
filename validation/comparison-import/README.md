# Prediction-export and measurement import — revision 1

The shared core imports comparison envelopes containing an **unchanged UTF-8 CSV table** or a
normalized JSON sample array. Android Settings → **Imported comparisons** selects a reference
package and an engine-snapshot package, displays matching status/residuals, and includes full
comparison results in the project report. Both original documents survive project save/reopen,
Undo/Redo and recovery. Clearing the scene clears the imports; Undo restores them.

These are **imported snapshots**, independently of the current scene. The app does not copy a
reference's conditions onto a fresh scene prediction or silently recompute its engine dataset.
The [current-scene capture workflow](../engine-capture/README.md) now derives engine conditions
independently and stores a new snapshot; it never copies the reference setup. This batch accepts
explicit tabular exports; it does not decode native proprietary MAPP/ArrayCalc/Soundvision project
or binary formats, or claim vendor-specific CSV-version conformance without real sample exports.

## Prepare an import

1. Export a level table as UTF-8 CSV from the prediction or measurement application. Retain the
   original export. No decimal-comma, unit conversion, phase extraction, offset fitting, smoothing
   or frequency interpolation is performed by this importer.
2. Copy `protocol-template.json` and enter the actual source/evidence, conditions, units and column
   mapping. Set `synthetic` explicitly. Select `MANUFACTURER_SOFTWARE_EXPORT`,
   `CONTROLLED_MEASUREMENT`, `MANUFACTURER_DATA` or `ENGINE` as appropriate. Do not label data from
   this engine as a manufacturer reference. For measurements, include instrument, method,
   smoothing and time window in `source`, plus `calibrationStatus` (`UNKNOWN` or `DECLARED`) and
   the calibration reference when declared. Declaration does not authenticate calibration.
3. Package the file without changing its bytes:

   ```sh
   python3 validation/comparison-import/package_csv.py export.csv my-protocol.json comparison.json
   ```

4. Import `comparison.json` into the relevant Android slot. Import a separately generated engine
   snapshot with matching protocol/sample identities into the other slot. Producer/version/origin
   are declarations, not authenticated evidence. A numerical pass only covers the declared samples.

The packager embeds the original CSV and its SHA-256. It validates UTF-8 and size, but the shared
core remains the authority for schema, units, CSV parsing and matching. On import, the core checks
any supplied payload hash and computes payload/document hashes. Source metadata and the complete
raw document remain in the saved workspace. Envelope BOM is ignored by Android; the CSV payload,
including its BOM/CRLF, is retained unchanged. No external URL is fetched by importing a package.

## Envelope versions 1–5

[Weighted broadband imports](../broadband-capture/README.md) use version 3, explicit aggregate
units and a canonical band/weight definition.

[Relative weighted broadband](../relative-broadband/README.md) uses version 4 with an explicit
reference receiver and weighted-total normalization method.

[Explicit total-drive spectra](../source-spectrum/README.md) use version 5 and retain their
source recipe and validated allocation fractions.

The template contains all common required fields. `schema` is `droidacoustic.comparison-import`;
`version` is 1 for absolute/legacy imports, or 2 for [explicit relative normalization](../relative-response/README.md). `source.producer`, `source.version` and `source.fileName` must be nonempty.
`payload` is a string containing the original CSV or JSON sample array. Optional `sourceSha256`
must match the UTF-8 payload. `toleranceDb` is finite and positive and comes from the **reference**
package, not an engine-side fitted threshold.

`conditions` records exact model/data revision, processing preset, source/receiver geometry and
coordinate frame, frequency/band convention, level convention, atmosphere, ground, and electrical
reference unit/value. Use `WATTS`, `VOLTS_RMS` or `UNKNOWN`. Unknown absolute drive blocks matching.
Record actual bandwidth/edges, level metric and source summation settings in the protocol strings;
identical-looking numbers from different conventions must not be declared equivalent. There is
no inference of physical settings from a product name or filename.

`units` must explicitly declare frequency `Hz`, position `m`, and either `dB_SPL` with
`ABSOLUTE_SPL` or `dB_relative` with `RELATIVE_TO_ON_AXIS`. Relative values must already use that
on-axis normalization; generic transfer-function dB is not automatically an SPL or on-axis response.
No feet/kHz/dBu conversion, noise-floor substitution, phase/group-delay or multi-channel transfer
function import is supported in this revision.

### CSV payload

`format` is `CSV`. `delimiter` is explicitly comma, semicolon or tab. `columns` maps canonical
fields to exact original header names:

- Required: `frequencyHz`, `levelDb`.
- Optional `id`; otherwise declare a fixed `receiver.id` and generated sample IDs are
  `receiver-id:frequency-as-Double` (for example `r:1000.0`).
- Either map **all** `xM`, `yM`, `zM` columns or declare one fixed `receiver` with those three
  numerical coordinates. Mixed modes are rejected.

Quoted fields, doubled quotes, embedded newlines, UTF-8 BOM and CRLF are supported. Blank/duplicate
headers, ambiguous/repeated mappings, inconsistent row widths, malformed quoting, duplicate sample
IDs, non-positive frequencies and non-finite numbers are rejected. Unmapped extra columns are
retained in the payload but unused. Decimal notation uses a dot. Comment/preamble lines are not
silently removed. Blank level cells mean **unavailable**, never zero SPL or a guessed noise floor.

### JSON samples

`format` is `JSON_SAMPLES`; `payload` is an array encoded as a string. Each sample has `id`,
`frequencyHz`, `xM`, `yM`, `zM`, `levelDb`. A missing measurement level is explicitly JSON `null`;
other coordinates/frequencies must be finite. Schema/units/conditions apply identically to CSV.

## Matching and persistence

Shared `SpeakerComparisons` requires complete identical protocols and all sample IDs, frequencies
and coordinates in order. Missing/unavailable samples, mismatched conditions, unsupported units,
unknown absolute drive, unknown measurement calibration and synthetic fixtures cannot qualify an
external comparison. No intersection-only matching or best-fit offset is used. A `PASSED` status
means residuals meet the reference tolerance under declared conditions, not global validation of
this app or authentication of the imported source.

A two-slot immutable `ComparisonWorkspace` stores source documents and recomputes results on restore,
rather than trusting a saved pass/fail flag. Inputs are parsed before mutating Android state; invalid
imports/projects leave the previous state intact. Scene schema **26** adds the optional workspace;
older scenes open with empty imports and older app versions reject the newer schema.

Limits: 4 Mi characters per envelope, 2 Mi characters per payload, 10,000 samples, 64 CSV columns,
and 24 Mi characters per saved workspace. Android reads at most 8 MiB UTF-8 input before parsing
on a worker. Current scene edits do not modify imported snapshots. The comparison UI/report names
that distinction explicitly. Raw imports can make project/Undo snapshots larger; the existing total
project size and history limits still apply.

## Retained tests/results

`fixtures/` contains explicitly **synthetic** manufacturer-shaped, measurement-shaped and engine
packages. Their source metadata says test fixture; none is an actual vendor export or measurement.
Both reference cases must import/reopen successfully and report `NOT_COMPARABLE`, proving the
synthetic evidence cannot qualify the engine. `results/import-results.json` retains actual shared
parser/workspace outputs, identities and refusal reasons. Unit tests separately exercise matching
pass/fail paths with in-memory test-only declarations, missing levels, measurement calibration,
malformed CSV/units/hash/version/size, native code compilation and save/reopen.

Run:

```sh
python3 validation/comparison-import/test_package_csv.py
cd android
./gradlew :shared-core:jvmTest --tests '*ComparisonImportsTest' --tests '*ComparisonImportPackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*ComparisonImportIntegrationTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh shared output is `android/shared-core/build/reports/comparison-import/results.json`. Tests
never overwrite the reviewed snapshot automatically. CI runs the packaging tests/shared/Android
suites and uploads fresh reports. The existing numerical and manufacturer-data reference scripts
remain separate checks. No new manufacturer-software or controlled-measurement validation is
claimed; real artifacts with reviewed protocols are still required to complete that gate.
