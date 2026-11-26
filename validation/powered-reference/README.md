# Powered and axial reference qualification

This is a **synthetic calibration/provenance regression package**, not a physical
measurement or manufacturer prediction comparison. The four tiny TAB fixtures
isolate passive/powered sources with sensitivity/axial reference rows. Their
expected levels and electrical-reference status are declared in `fixtures/cases.json`;
fixture SHA-256 hashes are checked before testing.

## Corrected behavior

The portable shared speaker model now records `levelReferenceBasis` as
`SENSITIVITY`, `AXIAL_SPECTRUM` or `UNSPECIFIED`. This describes the row that supplied
levels, independently of calibration. Legacy unspecified values are omitted from
serialization, preserving existing default asset hashes. Explicit bases round trip
through saved assets and appear in shared data-quality reports. Axial references
receive a concise warning to match the source's input setting.

The old axial fallback could consume `SENSITIVITY-INFO` even when sensitivity was
absent. A stale “normalized to 1W/1m” note could therefore assign watt calibration
to an axial indication. The shared import policy now accepts that narrow declaration
only from a nonpowered sensitivity row. Axial and powered input settings remain
electrically unqualified; the model does not derive power from line-input voltage.
Axial provenance uses `AXIAL-SPECTRUM-INFO`, not an unrelated sensitivity note.
Explicit calibration in native speaker JSON remains a separate user declaration.

Binary CF1 levels are labelled sensitivity; the tested CF2 levels are labelled
axial spectrum. Their electrical normalization remains unverified. The parser is
now identified as `clf-subset-v5`. Existing native assets, saved traces and design
history are not silently modified; reimport original sources to apply the guards
and record the basis. Previously saved CLF calibration declarations without an
identified basis still need review before external absolute-drive acceptance.

## Why powered CF1 axial-only conversion remains blocked

The [official CLF specification](https://www.clfgroup.org/clfdocuments.htm), dated
4 March 2020, section 12.2 and footnotes 7/10, allows an axial spectrum to indicate
a powered speaker's response with its input documented in an information field.
Its viewer can display octave values, but this does not supply the matched input
spectrum and normalization needed for an independently qualified engine conversion.
The current engine therefore requires explicit normalized octave reference levels
for this case and rejects automatic powered CF1 axial-only conversion with a
specific error. It does not guess an input drive or replace missing data with a
generic speaker. The existing project must survive that failed import unchanged.

## Verification

Common tests cover all source-type/basis calibration combinations, unsupported
watt conversion, portable basis persistence, rejection of unknown bases, concise
report warnings and legacy default asset identity. Android checks all four fixtures,
known per-band reference levels, finite production predictions, exact metadata and
prediction recovery after disk save/fresh reopen, and atomic rejection of the
unsupported CF1 input. The positive control retains a nonpowered sensitivity row's
explicit file-declared watt reference; that declaration is not physical calibration.

```sh
cd android
./gradlew :shared-core:jvmTest --tests '*PoweredReferenceTest'
./gradlew :app:testDebugUnitTest --tests '*PoweredReferenceRecoveryTest'
```

`results/android.json` retains fixture hashes, source reports and prediction/reopen
results. `results/verification.json` and targeted JUnit XML retain the test/build
gate. No new propagation calculation is introduced. No physical-device or iOS
runtime check is claimed. Safe powered/octave conversion, external calibration,
asymmetric binary CF1 and manufacturer/measurement acceptance remain open gates.
