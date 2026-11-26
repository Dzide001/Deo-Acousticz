# CLF relative magnitudes and declared symmetry

The subsequent [reference-slot batch](../cf1-reference/README.md) records importer
v4 and preserves missing sensitivity columns. Results below retain the v3 baseline.

This batch fixes two import errors found in the official CF1 TAB examples:
`<SIGN> <reversed>` positive losses were treated as gains, and reversed quarter
sectors were expanded before their stored order was corrected. The parser also
guessed symmetry from row count, conflating vertical and horizontal sectors.

The shared parser now honors the declared none/horizontal/vertical/full/rotational
symmetry, normalizes each stored sector's order, expands it, then applies rotation.
It converts reversed-sign relative magnitudes to negative attenuation. Conflicting
arc counts, unknown declarations and non-finite magnitudes are rejected explicitly.
Absolute/arbitrary references are rejected with a normalization error because their
drive/reference and axis-normalization rules are not implemented. This replaces
silent misuse; it does not claim support for those modes. Missing declarations keep
the previous relative/actual/full-circle defaults for existing simple imports.

This batch introduced importer provenance `clf-subset-v3`. Old native JSON, traces and variants
are preserved. Shared warnings flag v1 TAB handedness and v2 TAB symmetry/sign
decoding, advising reimport of the original file. Unsupported reimports must leave
the existing project unchanged.

## External evidence

Source: [CLF Group authoring kit v2.1b](https://www.clfgroup.org/CLF_Authoring_v2.1b.exe).
The specification dated 4 March 2020, section 12.2 and footnotes 1-4, defines the
sector sequences. Figure 1 labels cardinal directions independently of the engine.
`provenance.json` pins the kit, original text sources and retained numerical subsets.
The fixtures retain the original 1000 Hz numeric rows from `clf1_active_point.tab`
(horizontal/default/reversed-sign) and `clf1_powered_point.tab`
(full/reversed-order/reversed-sign), with a test-only 100 dB reference header.
These are fictitious format examples, not calibrated speaker products.

At 60° off-axis, the active example gives up −11.3 dB, left/right −4.8 dB and down
−12.3 dB. The powered example gives up/down −4.8 dB and left/right −11.3 dB. The
positive values stored in the files represent loss because SIGN is reversed.
Treating the active upper sample as a gain produces a 22.6 dB error.

The independent standard-library Python oracle uses literal cardinal row identities
from those declarations. Shared tests check 16 production band predictions at
0°/90° yaw, before/after portable speaker serialization. Common tests cover 40
combinations of CLF1/CLF2 resolution, five symmetry declarations, two orders and two
signs, plus rejected inputs and legacy warnings. Android checks eight production
predictions, disk save/fresh reopen and atomic rejection of unsupported imports.
Tolerance is predeclared at 0.001 dB; it is numerical tolerance, not physical accuracy.

## Reproduce and retained results

```sh
python3 validation/clf-magnitude/reference.py --check
python3 validation/clf-magnitude/extract.py --extracted-dir /tmp/clf-official
cd android
./gradlew :shared-core:jvmTest --tests '*ClfMagnitude*'
./gradlew :app:testDebugUnitTest --tests '*ExternalClfHandednessTest'
```

The extraction check is optional and requires the original kit, extracted with
`innoextract`; offline tests use the retained fixtures. Reference generation requires
only Python's standard library. CI checks the oracle and uploads current reports.
`results/reference.json`, `results/engine-jvm.json`, `results/android.json` and
`results/verification.json` retain the oracle, residuals, recovery and build gate.

Both official CF1 examples are left/right symmetric. They validate text magnitude
decoding and sector order, **not asymmetric binary CF1 handedness or electrical
level offsets**. Those gates remain open, alongside absolute/arbitrary normalization,
polar-only interpolation, complex containers and manufacturer/measurement accuracy.
