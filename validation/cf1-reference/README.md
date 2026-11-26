# CF1 sensitivity-row references and missing-slot recovery

The later [powered/axial qualification batch](../powered-reference/README.md) records
importer v5 and an explicit reference basis. Results below retain the v4 baseline.

This package verifies the **byte offset and per-band identity** of the CF1
sensitivity row against independently supplied text. It does not establish
electrical calibration or asymmetric binary handedness.

## External evidence

Source: the [official CLF authoring kit v2.1b](https://www.clfgroup.org/CLF_Authoring_v2.1b.exe).
`provenance.json` pins the kit, original TAB sources, retained numerical subsets and
unmodified binaries. The Active and Passive1 pairs provide 13 declared sensitivity
values across 125–4000 Hz and 125–8000 Hz respectively. Those values occur at
`0x1240 + 4 × octaveSlot` in the corresponding binary files. `reference.py` derives
expected levels from the text rather than from the production decoder and checks
the original byte positions separately. The predeclared tolerance is 0.0001 dB.

This establishes that these are **sensitivity** values, unlike the separate
third-octave axial-spectrum row. It does not establish a universal 1 W, 2.83 V or
manufacturer-preset contract. The fixtures are fictitious format examples, not
controlled measurements. The text subsets retain all original sensitivity and
balloon numerical rows with a validation header. The binary fixtures are unchanged.

`Powered.CF1` has zero placeholders in this row and supplies no sensitivity data.
The test requires an empty reference map and unavailable prediction, not a zero-dB
reference or generic speaker substitution. Powered/axial conversion needs its own
drive convention and octave integration before that input can become supported.

## Shared correction

The TAB parser previously removed `<nodata>` entries before matching frequency
columns, discarding otherwise usable reference values. It now preserves slot
positions and keeps missing bands unavailable through serialization and disk reopen.
Explicit sensitivity holes cannot be filled from the distinct axial-spectrum row.
When sensitivity is absent, CLF2 axial fallback uses all 30 declared third-octave
positions, retaining holes. CF1 does not pretend this row is an octave table.
Malformed counts and invalid/non-finite reference tokens fail explicitly.

This batch introduced `clf-subset-v4`. Existing native assets remain unchanged; reimport
an original TAB to recover reference slots lost by an older importer. Valid existing
fully populated rows retain their levels. Android and iPad use the shared parser.

## Tests and reproduction

```sh
python3 validation/cf1-reference/reference.py --check
python3 validation/cf1-reference/extract.py --extracted-dir /tmp/clf-official
cd android
./gradlew :shared-core:jvmTest --tests '*ClfReferenceSlotsTest'
./gradlew :app:testDebugUnitTest --tests '*Cf1ReferenceValidationTest'
```

The extraction audit is optional and requires the original kit extracted using
`innoextract`. Offline tests use only retained fixtures; Python scripts require the
standard library. CI checks the references and uploads fresh Android results.

Common tests cover missing sensitivity at an interior octave, no cross-row filling,
sparse third-octave axial fallback, rejected malformed rows and portable recovery.
Android compares 13 reference values and production band predictions through both
TAB and CF1 import paths, verifies unknown electrical calibration, and repeats after
disk save/fresh reopen. It separately reopens a sparse sensitivity asset and verifies
that its missing band stays unavailable while valid bands remain usable.

`results/reference.json` retains expected source values and raw byte locations;
`results/android.json` retains decoded values, prediction comparisons and recovery.
`results/verification.json` records build/test scope. Numerical tolerance is not
physical accuracy. No physical-device or iOS runtime test is claimed.

The **tested CF1 sensitivity offset is now qualified**. Electrical drive/level
normalization, powered/axial conversion, independently asymmetric CF1 handedness,
other binary variants and manufacturer/measurement acceptance remain open.
