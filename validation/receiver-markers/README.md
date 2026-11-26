# Receiver marker selection and labels — revision 1

Enable **Show receiver support**, then use the **Select** tool to tap a receiver marker or its
label in Plan, Section or 3D. The existing support panel displays that receiver's detail; the list
and viewport share one stable selection. The selected marker gains a white halo and label border.
Selection is inspection only: it does not move a receiver, change the active alignment reference,
add an acoustic Undo checkpoint or launch another prediction.

Labels begin with stable identifiers (`L` for the listener, `R0`, `R1`, … for saved references),
so identical names and colocated receivers remain distinguishable. Repeated marker-centre taps
cycle receivers whose projected centres coincide within one density-scaled pixel. Separate label
taps select their exact ID. Other nearby markers select by screen distance, then depth and ID.

Speaker manipulation handles keep touch priority. In Select mode, receiver-label/marker hits are
handled before speaker-body/floor picking. Placement, face editing, camera drags and pinch gestures
continue through the existing viewport gesture detector; the new text view does not capture touches.
Hiding receiver support restores the previous picking behavior.

## Shared geometry and display policy

`ReceiverMarkers` uses the existing `GizmoCamera` projection, viewport pixels and density. It owns
hit testing, depth/viewport clipping, deterministic collision layout, leader-line endpoints and label
bounds. Native Android code measures/ellipsizes/paints text; scientific/selection geometry stays in
shared-core for another renderer to reuse. The projection matches Filament's vertical perspective
FOV and 0.1–200 m clipping interval. Behind-camera, offscreen and invalid projections cannot be picked.

Marker hit radius is 22 dp. Labels fan out and stay inside the viewport. Selected labels have
priority; crowded layouts may omit lower-priority labels while retaining marker targets and the
complete receiver list. Label placement avoids other labels and protects marker centres when space
allows. Text may be ellipsized, retaining its ID prefix. Layout is cached until camera, report or
selection changes; protected marker rectangles are computed once per layout, not per candidate.

Labels are **screen annotations**, not mesh-occlusion tests: they may show through scene geometry.
The world-space support/origin diamonds retain normal depth behavior. Native UI panels can cover
annotations; all receivers remain selectable in the support list. [Receiver-specific framing](../receiver-framing/README.md) was added in the following batch;
receiver dragging and reflected/diffuse-path assessment remain separate.

## Persistence and stale targets

The optional `receiverAngularSelection` project field stores a selected saved-reference ID. Listener
selection is the legacy/default state and is omitted from serialization. Autosave observes selection
without changing acoustic revision; trace-input hashing excludes this display field. Shared selection
helpers validate IDs and reconcile a deleted/missing receiver to the listener. Undo/Redo of receiver
deletion restores the corresponding saved selection. Name/position equality never substitutes for ID.

Project reopen restores visibility and valid selection; stale IDs and legacy files select the listener.
Clear All resets visibility and selection. Viewport taps are revalidated against current receivers, and
labels/picking use only the current completed support report. Obsolete/disabled reports are cleared;
a stale pick cannot select a deleted receiver. Selection updates both panel and native highlight.

## Validation and reproduction

```sh
cd android
./gradlew :shared-core:jvmTest --tests '*ReceiverMarkerTest' --tests '*ReceiverMarkerPackageTest' :app:testDebugUnitTest --tests '*ReceiverMarkerRecoveryTest' --offline
```

Fresh geometry results: `android/shared-core/build/reports/receiver-markers/results.json`.
The focused final run passed **60 tests** (36 shared, 24 Android), Android debug assembly and iOS
simulator shared-test compilation. Reviewed reports and test execution details: [results/](results/).

Synthetic camera cases cover plan, front/section, side and perspective projections, exact label
picks, coincident cycling, deterministic ordering, viewport/near/far clipping, dense 65-receiver
collision handling and density scaling. These are geometry tests, not acoustic measurement evidence.
Android tests cover marker/list identity, duplicate names/positions, saved selection, deletion and
Undo/Redo, stale/legacy IDs, unchanged alignment state and revision, and selected-halo GLB generation.
Existing speaker manipulation, trace and project recovery regressions are included.

The original real-measurement comparison is rerun and remains byte-for-byte unchanged, including
failed accuracy screens. Device visual/gesture acceptance is separate and is not claimed by the
JVM/Android unit tests or shared iOS compilation.

Next: receiver-specific camera framing and on-device label/gesture acceptance, then per-path
reflection/scattering support and the reviewed policy for unsupported legacy extrapolation.
