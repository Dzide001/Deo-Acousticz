# Numerical data availability and nominal-frequency aliases

2026-11-26. Passed: 609 shared JVM tests, 15 focused Android tests, Android debug build,
and iOS simulator shared-core test compilation. [OnePlus acceptance](../device/data-validity-2026-11-26/README.md)
covered available, partial and unavailable coverage, save/reopen, supported-band recovery and a
missing speaker asset. Original app data were restored and verified.

## Availability contract

PredictionRequest reuses the existing DirectField.predictBand result and missing-band set; no extra
source or acoustic prediction pass is added. Actual project source IDs are retained, rather than
internal summation group indices. Required integration data frequencies and unavailable requested
bands are distinct: a 1 kHz octave needs data outside its 1 kHz centre. A gap may also be an
unsupported direction at a listed frequency, not absence of the entire frequency from the file.

- AVAILABLE: finite results for all requested receivers and available audience area.
- PARTIAL: only some requested receiver values or audience area are available.
- UNAVAILABLE: no finite results, no receivers, or no available audience area even if isolated
  zero-area probes have finite values.

Whole triangles with unavailable vertices remain excluded by the existing area-weighted statistics.
Broadband totals with required missing bands remain unavailable; no band is silently dropped.
Zero power is valid numerical data; explicitly unexcited bands do not require a response. When the
engine returns no detailed missing-band set (for example incompatible processing), warnings say
check response/processing rather than inventing a missing-speaker cause. The report contains full
source/frequency sets, affected bands, counts and area. The UI limits the displayed list to three
frequencies and one source ID, with ellipses. These are **numerical availability states**, not
calibration, angular-provenance approval, manufacturer validation or physical-accuracy claims.

The worker output owns its warning data. Android publishes it through the same latest-ticket gate
as levels, clears it on edits, and recomputes it on reopen. State messages for stale/running/failed
calculations take precedence over old availability. The compact controls are unchanged.

## 31/32 Hz check

The old on-grid tolerance evaluated 32 Hz about 0.103 third-octave cells from its exact base-two
centre, exceeding the 0.09 tolerance and rejecting it before alias lookup. A 32 Hz endpoint therefore
could not participate in otherwise valid adjacent-octave interpolation.

The supported integer aliases 31/32 and 63/64 now map to the engine's existing 31.25/62.5 Hz
base-two cell centres for alias lookup and interpolation. Tests cover both directions, endpoint
combinations, exact stored-sample precedence, non-finite data, missing interior cells, arbitrary
off-grid endpoints and rejected extrapolation. `alias_reference.py` independently calculates the
expected dB values with Python's log function; `results/alias-reference.json` and JUnit reports
retain the expected values and engine checks.

Reference context: [MathWorks' octave filter-bank documentation](https://www.mathworks.com/help/audio/ref/octavefilterbank-system-object.html)
distinguishes base-two and ANSI-preferred base-ten definitions; [NTi Audio's fractional-octave
reference](https://www.nti-audio.com/Portals/0/data/en/1-n-Octave-band-filter-data.pdf) explicitly uses
base ten under IEC 61260-1. This fix preserves the engine's existing base-two compatibility convention;
it does not implement IEC base-ten bands, accept arbitrary fractional JSON frequencies or claim
filter-standard compliance.

## Reproduce

From android/ with JDK 21:

```sh
./gradlew :shared-core:jvmTest :shared-core:compileTestKotlinIosSimulatorArm64 \
  :app:testDebugUnitTest --tests '*PredictionValidityRecoveryTest' \
  --tests '*PredictionConcurrencyTest' --tests '*CoverageModeControlsTest' \
  :app:assembleDebug -Pkotlin.compiler.execution.strategy=in-process --offline
```

Detailed suite counts and selected JUnit reports are in results/. These checks use production
prediction requests and project recovery, not the retired Android room/optimizer helpers.
