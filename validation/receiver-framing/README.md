# Receiver-specific camera framing — revision 1

With receiver support enabled, select the listener or a saved receiver and choose **Frame selected**
(**Frame** in compact mode). The camera frames that receiver in the current **Plan, Section or 3D**
preset. The detail panel collapses and moves to the bottom-right, leaving room for the framed point.
Open **Details** for **Closer** / **Wider**, which halve/double the requested context within 2–64 m.
The default context is 8 m. The existing **Frame all** control leaves receiver framing and restores
normal venue framing in the current view; receiver selection itself is retained.

Selecting another receiver does not jump the camera. Press Frame to change the framing target.
Framing remains active across view changes, receiver movement, viewport resize and project reopen.
Manual camera gestures remain available. Framing uses canonical preset directions; it does not save
arbitrary subsequent orbit/pan poses or continuously lock the camera to the receiver.

## Shared geometry

`ReceiverCameraFocus` is immutable, versioned framing intent: stable receiver ID, view enum and
context span. `ReceiverCameraFraming.fit` calculates a `GizmoCamera` and an unobscured label region
from receiver coordinates, viewport size, density, vertical FOV and measured panel obstructions.
Android only applies the returned pose to its existing orbit manipulator.

The solver reserves viewport margins, subtracts up to eight obstruction rectangles and chooses the
largest remaining usable rectangle (bounded to 32 candidates per subtraction). It conservatively
fits a camera-space cube around the receiver, accounting for aspect ratio, off-axis placement and
the cube's nearest face. Plan uses a near-nadir direction with a nondegenerate up basis; Section and
3D retain their respective side/elevated working directions. Camera aim can be offset to place the
receiver outside panels; this is framing, not a promise that manual orbit pivots exactly at the receiver.

The actual Float camera is verified by projecting all eight context corners. Near/far bounds match
the renderer's 0.1–200 m range. Invalid/degenerate viewports, mismatched targets, unsupported FOV,
insufficient unobscured space or unstable projection return no frame. Android leaves the previous
camera in place and shows guidance rather than applying a partial or invalid pose. Close Details,
reduce context or enlarge the viewport to retry; resize automatically retries active intent.

The usable region also constrains receiver-label placement. Text may still be ellipsized, but the
selected marker and its label can use the free viewport region instead of being hidden by the support
panel. Labels remain screen annotations, not mesh-occlusion or acoustic-support evidence.

## Recovery and acoustic isolation

Optional `receiverCameraFocus` JSON stores intent only. It restores the same view/receiver/span and
recomputes a pose for the current viewport/panel dimensions, rather than replaying obsolete pixels.
Legacy projects default to ordinary venue framing. A deleted or stale receiver clears the focus;
Undo/Redo of deletion restores the corresponding intent. Clear All resets it. Invalid framing schema
or span fails project import before live state is changed. No archive migration is needed.

Repeated Frame presses use a transient request token, so the same target can be reframed after
manual camera movement. View/span/target changes are visual state, autosaved without advancing
acoustic scene revision or launching predictions. The framing field, overlay visibility and receiver
inspection selection are excluded from trace and system-adjustment preview input identity. Framing
cannot move a receiver/source, alter alignment settings or make a valid DSP preview stale.

## Validation and retained results

From `android/`:

```sh
./gradlew :shared-core:jvmTest --tests '*ReceiverCameraFramingTest' --tests '*ReceiverFramingPackageTest' :app:testDebugUnitTest --tests '*ReceiverFramingRecoveryTest' --offline
```

Fresh results: `android/shared-core/build/reports/receiver-framing/results.json`.
The focused final run passed **79 tests** (40 shared, 39 Android), Android debug assembly and iOS
simulator shared-test compilation. Reviewed geometry and execution details: [results/](results/).
`python3 validation/receiver-framing/check_report.py` independently checks the retained cameras,
context corners and clipping with the Python standard library.

Synthetic framing cases cover all three views in landscape/portrait, a raised receiver outside the
usual venue centre, panel avoidance, context sizing, translated targets, label bounds, clipping,
invalid intent and deterministic reconstruction. These are geometry checks, not physical evidence.
Android tests cover save/reopen, view/span/target persistence, repeated frame requests, deletion
Undo/Redo, legacy/stale targets, atomic rejection, unchanged levels/revision/alignment and applying
a previously captured DSP preview after framing. Existing selection, gesture-model, trace and
recovery regressions are included.

The original real-measurement comparison is rerun unchanged; its failed accuracy screens remain
failed. Device visual/gesture acceptance is separate from the unit tests and shared iOS compilation.

Next: on-device framing/gesture acceptance, followed by reflected/diffuse-path angular-support
assessment and the reviewed policy for legacy extrapolation. Manufacturer-software and
independent-session/full-sphere validation remain open.
