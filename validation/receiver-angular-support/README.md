# Receiver-level angular support and origin overlays — revision 1

In the Android viewport, choose **Show receiver support**. The listener and all saved alignment
reference receivers receive two world-space diamonds: **outer = angular support, inner = origin**.
Three orthogonal diamond planes support plan, front/side and perspective camera views. Markers
retain normal scene depth/occlusion and world-space size; the scrollable panel remains available
when markers overlap or become small. Select a receiver's row to inspect its source/element/band
entries. [Marker picking and viewport labels](../receiver-markers/README.md) were added in the following batch.

The overlay inspects the **selected band and its actual width**, including when the application is
in Spectrum mode; it is not an assessment of the weighted broadband total or an audience heatmap.
It represents the committed scene; live object-drag previews are assessed after gesture commit.

## Shared calculation

`PredictionRequest.receiverAngularSupport` freezes receiver identities/world coordinates and uses
`SourceSnapshot.emitters` and the exact prediction `SoundSource.toward` transform. Arrays are
inspected per physical element, including its offset, yaw and mechanical tilt. Electronic steering
changes delay, not the coordinate frame. Metadata retain the real source ID and element index.

`FrozenResponse` owns copied magnitude grids and immutable declarations; caller mutation cannot
alter dispatched work. Complex-frequency ordering is cached once per frozen response. Magnitude
band lookup reuses the existing measured-band interpolation/gap rules. Complex-band inspection
requires the bracketing frequency endpoints and all intervening declared knots. Exact-tone-only
complex data are displayed as information, without claiming phase support across the band.
Magnitude rows are informational when declared complex-band data are the required response.

Support aggregation is conservative across **all configured emitters**, with no level weighting:

- Red: missing frequency/source/direction data or a coincident receiver whose direction is undefined.
- Amber: sparse interpolation, azimuth replication or a possible missing sector.
- Blue: ordinary between-knot interpolation; teal: a stored direction; grey: unassessed/generic,
  custom-without-grid, disabled-directivity or no-source cases.

Origin is separate: teal declared MEASURED, purple SYMMETRY, pink INTERPOLATED, gold MIXED,
grey UNDECLARED. Unknown contributing origins are retained rather than hidden behind interpolation.
A teal stored/measured marker is **not an accuracy pass**. Declarations are not authenticated.
All row details retain contributor origins, actual local angles, source frequency and requested data
cell. Between-frequency provenance is represented by its measured-grid endpoints, not an invented
measurement at the requested frequency.

These are **direct-ray angular-data diagnostics**, not total prediction-validity checks. They do
not assess reflected/diffuse departure paths, obstacles, gain weighting, drive compatibility, phase
accuracy, interference quality, near-field validity or SPL accuracy. Muted/low-level configured
emitters are not removed from the metadata screen. Existing physics/interpolation/refusal rules are
unchanged; overlays do not turn unsupported legacy azimuth replication into missing acoustic data.

## Recovery and stale work

The visibility setting is an optional project field, defaulting off for legacy projects and Clear All.
Autosave observes it directly, without launching an acoustic recalculation. Trace-input identity
excludes the visual flag. Receiver selection is now persisted by the subsequent marker-selection batch; detail pagination
remains temporary UI state. Saved
receiver positions, source declarations and the visibility option survive disk reopen. Diagnostics
are recomputed from restored inputs, not trusted from a cached verdict. No archive migration is needed.

The Android UI freezes inputs on Main, evaluates shared metadata off Main and checks scene revision
and enabled state before publishing. Previous-revision markers are cleared synchronously; worker
cancellation is checked between emitter/probe rows. Failure publishes no partial marker set. Work is
bounded to listener plus 64 saved references and 20,000 detail rows. Oversized requests show an error;
they are not silently sampled or labelled complete. Receiver-to-Float conversion must remain within
10 micrometres per component, consistent with the existing comparison-capture policy.

## Reproduce retained evidence

```sh
cd android
./gradlew :shared-core:jvmTest --tests '*ReceiverAngularSupportTest' --tests '*ReceiverAngularPackageTest' --tests '*PublicMeasurementComparisonTest' :app:testDebugUnitTest --tests '*ReceiverAngularOverlayTest' --offline
```

Fresh diagnostics: `android/shared-core/build/reports/receiver-angular-support/results.json`.
The focused final run passed **73 tests** (42 shared, 31 Android), Android debug assembly and iOS
simulator shared-test compilation. The original real-measurement comparison report is byte-for-byte
unchanged. Reviewed evidence and execution details: [results/](results/).

The pinned real PRX635 training models and training-only 5° storage variants are inspected at the
actual held-out receiver coordinates, without snapping to nominal measured angles. Both band
capture and metadata evaluation share the original geometry; response replay remains within
0.0001 dB of the independent reference. All held-out origins remain INTERPOLATED, including at
upsampled stored knots. Original measured accuracy screens remain **FAILED**: 20° RMS/max
0.615063/2.615989 dB; 10° 0.500084/2.655764 dB. No threshold or measured reference is changed.

Tests cover yaw/tilt, electronic steering, array-element offsets, multiple source IDs, colocated
receiver identities, generic/missing/disabled data, elevation limits, complex endpoints, stale frozen
inputs, cancellation/budgets, shared three-plane marker geometry and GLB structure. Android tests
verify visibility/declarations/receiver detail recovery, identical levels, legacy defaults and Undo/Redo.
Device visual/gesture acceptance is not claimed by these automated checks.

Next: direct selection/labels for receiver markers, per-path reflected/diffuse support assessment,
and the separately reviewed policy for refusing unsupported legacy extrapolation. Full-sphere,
independent-session and manufacturer-software validation remain open.
