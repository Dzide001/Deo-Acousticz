# Speaker-data provenance and matched comparisons — revision 1

**Orientation update, 2026-11-26:** the [external CLF handedness package](../clf-handedness/README.md)
uses the official specification and asymmetric example to correct TAB azimuth and
remove the binary reader's unconditional arc reversal. The XD12 data below is
exactly left/right symmetric and never established handedness. Its independent
reference script now uses the corrected convention; directional numbers are unchanged.

This package separates **data consistency**, **declared calibration**, **manufacturer-software
comparison** and **controlled-measurement validation**. Passing one does not imply the others.
The shared core implements provenance records, calibration-declaration checks and condition/sample
matching; Android only adapts imports, displays warnings, persists assets and runs the file-based test.

## Provenance and calibration

Decoded speaker JSON optionally records origin, source label/URL/hash, importer identifier,
operating preset and normalization notes. These are declarations, not trusted signatures or
manufacturer certification. Unknown legacy provenance remains unknown. Original binary inputs
are SHA-256 hashed; TAB text inputs are hashed as UTF-8 at the parser API boundary (not an assertion
about an unknown original file encoding). JSON preserves supplied provenance without inventing it.
Generic fallback remains explicitly identified by the existing Android warnings.

Metadata is part of decoded-asset identity, survives save/reopen and appears in project reports.
The default empty record is omitted from serialization so old decoded assets retain their canonical
payload/hash. A metadata change creates a new asset revision, rather than altering an old reference.

Electrical units remain distinct: watts and RMS volts cannot be converted using guessed impedance.
A deliberately narrow, positive-valued CLF normalization declaration may identify an already
normalized 1 W/1 m row. This does **not** reapply the raw measurement distance or voltage, and remains
labelled declared/unverified. Other wording and binary reference normalization stay unknown.
An operating preset and source URL alone do not establish that a real manufacturer's DSP was used.

## Matched manufacturer-data case

The official [Martin Audio data page](https://martin-audio.com/support/gll-and-clf-data) links the
[XD archive](https://martin-audio.com/downloads/ease/GLL-XD.zip). Retrieved 2026-11-17. The XD12 TAB
and CF2 files from that archive match the existing local corpus byte-for-byte. Exact input hashes
are pinned in `reference.py` and `results/reference.json`; no complete third-party data file is
added to the repository or app by this batch.

The independent standard-library Python script reads the TAB directly, verifies its grid/arc order,
and computes 36 relative responses (six frequencies and six directions) without importing Kotlin
or using the binary decoder. It also retains the two distinct 27-band reference rows. Acceptance
limits are **0.001 dB** for relative directional response and **0.0001 dB** for decoded source-row
values. These are importer/numerical tolerances, not real-world prediction tolerances.

The Android test imports both files and uses the shared comparator against these independent
references. The result's evidence scope is `DATA_CONSISTENCY_ONLY`. The measured source is not
replaced by synthetic polars and no fitted level offset is permitted.

**Important distinction:** TAB `<SENSITIVITY>` and the binary axial-spectrum row are different
measurements. They are checked against their corresponding published text rows, rather than
forcing the two to match. The TAB declares normalization to 1 W/1 m. The binary reader does not
decode an equivalent electrical contract, so its absolute-drive comparison returns
`NOT_COMPARABLE`. Both preserve the same response levels they previously supplied to the engine.

This is not a matched ArrayCalc/MAPP/Soundvision output test. No suitable software-export or
controlled-measurement artifact was available locally when this original package was recorded.
The later [public calibrated-measurement package](../public-measurements/README.md) now supplies
a real PRX635 angular holdout benchmark, with retained failed accuracy screens. It does not change
this package's original `PENDING_MATCHED_EXPORT` result or establish manufacturer-software equivalence.

## Reproduce

With the existing matching corpus in `corpus/clf/martin_audio`:

```sh
python3 validation/speaker-data/reference.py --check
cd android
./gradlew :app:testDebugUnitTest --tests '*ManufacturerDataComparisonTest' --offline
```

Alternatively download the public archive locally and extract only the pinned files:

```sh
curl -fL https://martin-audio.com/downloads/ease/GLL-XD.zip -o /tmp/GLL-XD.zip
python3 validation/speaker-data/prepare_sources.py /tmp/GLL-XD.zip /tmp/xd12-validation
python3 validation/speaker-data/reference.py --source-dir /tmp/xd12-validation --check
cd android
ACOUSTIC_MANUFACTURER_SOURCE_DIR=/tmp/xd12-validation ./gradlew :app:testDebugUnitTest --tests '*ManufacturerDataComparisonTest' --offline
```

Fresh results go to `android/app/build/reports/speaker-data/testDebugUnitTest.json`. The retained
reviewed snapshot is `results/engine.json`. Tests never overwrite it automatically. If manufacturer
source files are absent, JUnit marks this case skipped and writes an explicit `NOT_RUN` report.
It is not counted as a successful manufacturer-data validation. CI uploads these reports when the
case runs; CI without the private/local corpus cannot qualify the real-data case.

Regenerate reference results only after reviewing changes to inputs/equations and acceptance
criteria; compare raw results before updating snapshots. Source hashes pin the data revision and
script. Existing `validation/numerical` fixtures continue to check propagation, phase and convergence.

## Protocol for future external comparisons

`SpeakerComparisons` requires explicit model/data revision, operating preset, geometry/frame,
frequency/band convention, level convention, atmosphere, ground and electrical drive. Sample IDs,
frequencies and coordinates must match completely and in order; no intersection-only comparison,
best-fit offset or inferred volts/watts conversion is applied. Missing/non-finite samples, unknown
absolute drive, differing conditions and synthetic/engine-generated references return
`NOT_COMPARABLE`. A declared tolerance produces `PASSED` or `FAILED` only after those gates pass.
External artifact origin/conditions are declarations requiring review, not automatically authenticated.

To qualify a vendor-software result, retain its original export and hash, software/data version,
full setup/preset and source/receiver geometry, electrical drive, frequency centre/edges and level
metric, environment and propagation mode. Choose tolerances before collecting the comparison.
Record raw per-sample residuals. For absolute checks, independently establish the imported asset's
normalization and actual system processing first. A labelled pass applies only to those conditions;
it does not grant global manufacturer equivalence, headroom, rigging or near-field certification.
