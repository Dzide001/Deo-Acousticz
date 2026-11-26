# Explicit broadband source spectrum and total-drive normalization — revision 1

Android Settings → Imported comparisons now has a **Broadband capture excitation** setting:

- `PER_BAND_REFERENCE`: existing behavior; every band uses the full model reference drive.
- `TOTAL_EQUAL_BAND_POWER`: one fixed total mean-square drive, split equally across declared bands.
- `TOTAL_FLAT_PSD`: one fixed total mean-square drive, split in proportion to actual band width in Hz.
- `TOTAL_CUSTOM_BAND_POWER`: explicit nonnegative relative band powers normalized to sum to one.

Preset buttons select the first three; the custom editor accepts the versioned JSON configuration.
It restores the actual persisted custom values. For example:

```json
{"mode":"TOTAL_CUSTOM_BAND_POWER","relativeBandPowers":{"63":8,"125":4,"250":2,"500":1,"1000":1,"2000":2,"4000":4,"8000":8}}
```

This is a **comparison-capture setting**, independently saved with the scene. Coverage maps,
ordinary listener calculations and old per-band snapshots retain their existing drive behavior.
Selecting a profile does not recalculate or rewrite an existing frozen comparison snapshot.
The reference and captured snapshot identify their actual excitation modes separately.

## Meaning of total drive

The allocation is across **frequency bands for each existing model/emitter reference**, not a
single total shared among all speakers/array elements. Source gain, balance, EQ, filters and taper
remain explicit. Total normalization is at the model input before frequency-dependent EQ/filters
and taper, with existing flat source/global gain retained. It does not renormalize output after DSP,
limit amplifier power or predict compression/headroom.

For a watts reference the shares allocate input watts. For RMS-voltage references they allocate
mean-square voltage: each band's voltage ratio is `sqrt(share)`. There is no guessed impedance or
watts/volts conversion. Unknown electrical calibration remains unknown; numerical allocation does
not qualify absolute physical output.

For total-drive modes, each band's mean-square contribution is multiplied by its allocation
fraction before receiver weighting and summation. One common profile applies to all emitters.
In the existing linear model this is equivalent to scaling every emitter by `sqrt(share)` before
coherent propagation, reflections and scattering. It does not approximate different per-source
spectra by scaling a mixed total. Distinct source spectra would require a separate extension.

Equal-band drive gives `1/N` per band, so eight bands produce **9.03089987 dB less** than the legacy
full-reference-per-band sum for the same source/settings. Flat PSD uses actual octave-cell widths,
not centre labels or an equal-octave assumption. Within each band the existing flat-PSD integration
convention remains; equal octave powers are not claimed to implement a continuous 1/f noise curve.
The declared finite band range remains explicit and does not extend to a full-range meter signal.

## Validation and missing bands

All custom band keys must match the actual declared band set. Powers must be finite, nonnegative,
and have positive total. Normalization scales by the largest entry first to avoid sum overflow;
positive entries that underflow during normalization are rejected. Fractions sum to one within
floating-point precision. Raw relative powers, resolved fractions and the mode are retained.

An explicit **zero share is an unexcited band**: its modeled contribution is exactly zero, no
response/directivity is required there and unnecessary diffuse work is skipped. A missing response
in any **active** band still makes the result unavailable; there is no redistribution of that band's
share over the remaining data. Receiver weighting never affects the input-drive allocation.
Raw band diagnostics contain the allocated mean-square contributions; the definition retains the
fractions needed to audit them. Relative weighted capture uses the same allocation for numerator
and reference before forming the ratio, with its existing denominator safety checks.

## Matching, import and recovery

Comparison-import **version 5** extends the broadband definition with `sourceSpectrum` and
`driveFractions`, and drive convention `fixed-total-mean-square-drive-over-declared-bands`.
It supports absolute or relative weighted broadband using the existing respective level conventions,
units and reference receiver. Parsed fractions are checked against the resolved profile; exact zeros
remain exact zeros. Legacy per-band definitions retain their original version-3/4 encoding.

The entire mode/configuration/allocation is included in canonical matching. An imported reference
cannot configure this setting or silently match legacy per-band drive. Different input recipes are
retained for review even when proportional custom entries produce equivalent allocations. Source
origin, calibration, frequency edges, weighting and processing still require independent agreement.

Scene schema **30** retains the selected profile, original snapshot and diagnostics through Undo/Redo
and disk save/fresh reopen. Older projects load with legacy per-band drive; older apps reject the
new scene schema. Invalid configuration is rejected before changing state. Profile edits invalidate
in-flight capture publication, leaving the previous frozen snapshot intact.

## Retained numerical checks

The independent reference script allocates the retained scalar band powers from
`validation/broadband-capture` to equal-band, flat-PSD and custom spectra, then computes A/C/Z totals.
Eighteen absolute cases cover 10/20 m. The shared test also checks eighteen weighted-relative ratios
against differences of the independently allocated totals. Acceptance limits are **1e-5 dB absolute**
and **1e-7 dB relative**, numerical tolerances only. Complete absolute/relative snapshots, allocations,
raw band powers, residuals and script/reference hashes are retained under `results/`.

```sh
python3 validation/broadband-capture/reference.py --check
python3 validation/source-spectrum/reference.py --check
python3 validation/comparison-import/test_package_csv.py
cd android
./gradlew :shared-core:jvmTest --tests '*SourceSpectrumTest' --tests '*SourceSpectrumPackageTest' --offline
./gradlew :app:testDebugUnitTest --tests '*EngineComparisonCaptureTest' :app:assembleDebug --offline
./gradlew :shared-core:compileTestKotlinIosSimulatorArm64 --offline
```

Fresh raw results go to `android/shared-core/build/reports/source-spectrum/results.json`. Tests
also cover conservation, flat-density width ratios, legacy/total level differences, explicit zero
excitation, missing active data, invalid/tampered allocations, ordinary-prediction isolation,
preset/custom persistence and stale profile edits. CI checks the independent reference chain and
uploads fresh reports. No new manufacturer export or controlled measurement was available;
physical validation and real excitation/calibration matching remain pending.
