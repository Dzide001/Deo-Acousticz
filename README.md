# DroidAcoustic Pro

A 3D sound-system design application in development, with an Android tablet
prototype. The first dependable release targets **live sound: PA coverage,
line arrays, subwoofer configurations, fills, and delay systems**.

Soundvision, MAPP 3D, and ArrayCalc are workflow benchmarks. Comparable prediction
accuracy is a long-term validation goal, not an established capability.

## Current validation scope

The release plan is now [validation first](docs/implementation_plan.md#validation-first-release-sequence-supersedes-earlier-milestone-ordering).
Predictions use the shared direct-sound and first-order boundary model; reflections/scattering
remain experimental. Geometric blocking has no edge diffraction or transmission. The venue
envelope is visual context, not an automatically reflecting room. Guessed-room RT60 and heuristic
intelligibility results are no longer published, and the legacy optimizer is disabled pending a
shared-engine replacement and validation. There is no headroom or certified rigging prediction.
Numerical regression tests do not establish manufacturer equivalence or field accuracy.
The [retained numerical validation package](validation/numerical/README.md) provides independent
reference scripts, explicit tolerances, convergence checks and reviewed engine results.
The [speaker-data validation package](validation/speaker-data/README.md) records provenance and
calibration declarations plus matched manufacturer-data checks.
[Real calibrated PA measurements](validation/public-measurements/README.md) now test held-out
horizontal PRX635 responses; both coarse-grid accuracy screens fail and are retained. Manufacturer
prediction-software comparisons, independent-session measurements and field acceptance remain pending.
[Comparison import packages](validation/comparison-import/README.md) now retain manufacturer-export
or measurement CSV/JSON snapshots with explicit protocols, matching checks and Android save/reopen.
[Current-scene engine capture](validation/engine-capture/README.md) freezes shared-core predictions
at reference samples, derives actual scene conditions and preserves results through save/reopen.
[Explicit relative-response normalization](validation/relative-response/README.md) adds per-frequency
reference receivers, denominator safety checks and retained diagnostics.
[Weighted broadband snapshots](validation/broadband-capture/README.md) add finite octave-band A/C/Z
power sums with actual scene weighting and retained per-band diagnostics.
[Relative weighted broadband normalization](validation/relative-broadband/README.md) adds explicit
reference receivers, weighted-total safety checks and retained denominator band powers.
[Receiver-specific framing](validation/receiver-framing/README.md) now frames selected receivers in Plan, Section and 3D, with saved framing intent and panel-aware label space.

[Receiver marker labels and selection](validation/receiver-markers/README.md) now share a persisted receiver selection across viewport labels, markers and the support panel.

[Receiver support overlays](validation/receiver-angular-support/README.md) now show direct-ray sampling support and declared origins at the listener and saved reference receivers.

[Explicit angular origins](validation/angular-domains/README.md) distinguish declared measured, symmetry-derived and pre-interpolated data; legacy origins remain undeclared.

[Angular sampling checks](validation/angular-support/README.md) now warn about sparse magnitude/complex data, limited elevations and possible missing sectors without certifying accuracy.

[Validation workflow](validation/validation-workflow/README.md) adds protocol mismatch review, residual plots and standalone comparison reports with original inputs.

[Explicit comparison source spectra](validation/source-spectrum/README.md) add equal-band, flat-PSD
and custom fixed-total-drive allocation while preserving the existing per-band-drive mode.
Android displays these limits and exports the same shared model contract in version-2 project
reports. Saved project formats remain compatible; historical reflection-order and room-material
fields are retained, while active controls describe the implemented model.

## Project status

Reviewed on **2026-11-12**. The app has a Compose editor, Filament viewport,
speaker placement, audience polygons, coverage maps, CLF readers, project JSON,
and acoustic unit tests. Several production calculations remain approximate.
The acoustic classes now live in a Kotlin Multiplatform `shared-core` module.
Android listener levels and coverage maps use its array geometry, atmospheric
model, band-power summation and optional ground image sources. Imported per-band
sensitivity feeds the production level path. Shared calibration metadata and
bounded measured-band lookup are integrated; missing data makes predictions
unavailable rather than silently substituting a generic source. Verified electrical
normalization of manufacturer imports, measured phase and model validation remain
unfinished. Shared persistence now preserves exact imported-data revisions in
portable exports and atomic local recovery checkpoints, with visible missing-asset
states and backup recovery. Listener and coverage calculations use immutable shared
requests; revision checks reject obsolete results, and edits visibly require
recalculation in manual mode. Shared geometry preprocessing now clips receiver meshes
inside flat, raked, non-planar and stacked audience polygons, including enclosed
holes and surface-specific exclusions. Android heatmaps and contours use
that same topology, retaining independent floor/balcony levels and listening heights.
Manual probes are additive; the preview grid covers the full venue when no audience
surfaces exist. Geometry and obstruction calculations run from frozen inputs on the
prediction worker. Shared statistics now weight actual surface area: mean dB,
standard deviation, area percentiles and target coverage exclude independent probes
and explicitly account for unavailable area. Android summaries, contours and JSON
reports use these results. A bounded, thread-safe shared mesh cache reuses geometry
across acoustic edits and checks all geometry/sampling inputs, including cutouts,
before reuse. Android supports drawing/removing cutouts, transforming them with
their zone, and saving/reopening them in scene schema 17. Boundary elevation offsets
and interior height points define continuous triangulated surfaces in the shared core;
receiver heights, outlines, contours and physical-area statistics follow those facets.
Explicit breaklines connect boundary vertices or interior height points to preserve
ridges and slope changes. Android supports adding/removing these constraints and
displays them on the surface, clipped around holes and exclusions. Triangulated OBJ
imports preserve original audience faces and intrinsic holes. Use **Calculation &
project → Import audience mesh**, choose file units and Y/Z up, then select the OBJ.
The shared core validates topology and embeds the mesh in project saves; source files
are not needed to reopen projects. OBJ `g` declarations become named face groups.
Repeated names reuse their group; multiple names on one line form a combined group
such as **Balcony + Left**, with every face counted once. Names do not automatically
assign seated, standing or excluded roles. Connected 3D meshes now support vertical faces,
overhangs, overlapping elevations and closed surfaces. Coverage uses XYZ coordinates
and actual triangle area. Choose the **Faces** viewport tool and tap an imported triangle,
then use **Single**, **Add/remove**, **Flat patch**, or **Group** in the **Face assignments** inspector.
The nearest source face is highlighted, including excluded faces, walls and undersides;
orbit to reach another side. Face ranges and connected patch previews remain available.
Create named groups from selected faces to recall them, move membership, and apply
**Zone**, **Seated**, **Standing**, or **Excluded** assignments together. Group names and
membership survive save/reopen, transforms, duplication and undo/redo. Each face belongs
to at most one group; moving or deleting a group preserves the face's assignment.
Excluded faces stay visible as reference geometry and contribute no coverage area.
Active faces retain their own vertical listening offset; shared boundaries keep separate
receivers when assignments differ. Named groups have separate area-weighted summaries
combining their active faces, including mixed seated/standing assignments. Ungrouped
faces retain their assignment-based summaries; excluded and empty named groups remain
visible with zero coverage area. Older projects retain their existing assignments and
open without named groups. Plan cutouts still remove all assigned elevations in their zone.

Imported faces and groups also have independent **Acoustic boundary** roles:
**No boundary**, **Opaque blocker**, and **Rigid reflector**. Blockers stop crossing
paths; rigid faces also add finite, first-order specular reflections in shared coverage
and listener calculations. These two-sided boundaries support assigned first-order material reflection/scattering,
but do not model diffraction, transmission or multiple bounces. Audience exclusion only removes coverage;
it does not open a physical hole. Red/blue edges identify blockers/reflectors. Roles
survive save/reopen and default to **No boundary** in older projects. With global ground
reflection enabled, coplanar rigid faces use the global absorption once; opaque ground
patches suppress that bounce. The legacy inferred-room reflection list is no longer calculated.

Blocks and the built-in stage now use the same boundary model and role controls as
imported Faces. Their outlines and acoustic facets share geometry, including rotation,
slope and slab thickness. Coincident rigid surfaces reflect once; opaque surfaces suppress
reflection where they overlap a rigid surface. The old approximate Block attenuation has
been removed. Older projects open with opaque Blocks/stage, so recalculated predictions
can change. Blocks do not automatically create audience coverage areas. Scene schema 17
and venue-package version 2 preserve the roles, with backward-compatible reading.

Boundary materials now provide frequency-dependent absorption for Blocks, stage,
and imported faces/groups. Choose a material in the inspector; it affects reflections
when the boundary role is Rigid. Presets include concrete, carpet, gypsum, acoustic
panel, ideal rigid and ideal absorber. They are representative assumptions, not
calibrated product data. Coincident materials select the lowest specular reflected energy per band
without adding reflections. Custom measured curves can be entered or pasted from the material menu, with source
and mounting notes. Angle-dependent curves accept angle, Hz, absorption rows (0° normal,
90° grazing); reflection paths evaluate the actual incidence angle. Curves are embedded
in the project. Optional measured reflection phase can be added in the material controls,
using unwrapped degrees and explicit measurement reference notes. Material scattering can be entered as frequency/angle curves; it reduces specular
reflection energy separately from absorption. Scattered energy now reaches receivers through
a first-bounce Lambertian model with adaptive, surface-targeted sampling and visibility checks.
Visible small/distant surfaces are included without relying on fixed-ray hits. Receiver shadows are clipped geometrically, with extra refinement near receivers and across
detected directivity changes. Measured angular grids also bound variation across each patch,
so narrow represented lobes trigger refinement even between probes. Custom analytic
responses can supply validated angular ranges through `BoundedRadiator`; immutable
`RadialDirectivity` curves compute their own bounds and support frozen requests. Responses without
angular bounds and lobes absent from the measured data still need convergence checks;
multi-bounce reverberation is not simulated. Current formats are scene 22, mesh 10 and venue 7. Existing projects retain their previous reflection behavior
until scattering is assigned.

The most urgent corrections are calibrated speaker data, reliable project/data
migration/lifecycle coverage, complete prediction-engine consolidation and measured loudspeaker phase support,
plus large-scene profiling and broader venue import support.

- [Project review and evidence](docs/project_review.md)
- [Implementation plan and release gates](docs/implementation_plan.md)
- [Build instructions](docs/build_instructions.md)
- [CLF format research](docs/clf_format_notes.md)

## Platform direction

**The current application builds for Android only.** There is no iOS application
target. The recommended extension shares acoustic code, geometry, project schema,
and tests through Kotlin Multiplatform, with platform adapters for rendering and
file access. The shared core compiles for iOS Simulator; the iPad UI, platform
adapters and runtime validation remain planned work.

Retain the Android UI while extracting shared logic. Evaluate Compose Multiplatform
for shared controls and a native Filament/Metal viewport on iPad in an early
feasibility milestone. iPhone layout is a separate later scope.

Cross-platform updates and feature expansion are core design requirements:
implement shared behavior once, keep platform adapters small, version project
formats and speaker data, and verify compatible behavior in automated platform
checks. See the [maintenance requirements](docs/implementation_plan.md#keeping-updates-and-new-features-maintainable).

## Implemented stack

| Layer | Current implementation |
|---|---|
| Android UI | Kotlin, Jetpack Compose, Material 3 |
| 3D viewport | Filament with generated GLB geometry |
| Production prediction | Shared band-power engine; Android scene/data adapter in `SceneViewModel` |
| Acoustic core | Kotlin Multiplatform `shared-core`, JVM and iOS targets |
| Native acceleration | C++/JNI, Vulkan distance calculation, CPU fallback |
| Persistence and speaker data | Shared scene/asset codec and recovery policy; atomic Android file adapter; TAB and experimental binary importers |
| Tests | JUnit and Google Test |

Assimp venue import and Protocol Buffers were listed in the original roadmap;
they are not integrated features in this checkout. GPU distance computation does
not constitute a GPU acoustic solver.

## Repository structure

```text
droidacoustic/
├── android/       Android application and Kotlin tests
├── engine/        C++ distance engine and DSP/math utilities
├── shaders/       Vulkan distance shader
├── tests/         C++ tests
├── data/          Seed speaker data
├── docs/          Review, implementation plan, build and CLF notes
└── tools/clf/     Corpus inventory and development validation tools
```

## Licensing status

The original README declared Apache 2.0, but this checkout has no `LICENSE` file.
Restore or confirm the intended project license before distribution. Track
third-party library notices and speaker-data permissions separately. The local
manufacturer CLF corpus is excluded from source control and app assets.

### Reference-receiver alignment

In the Android **Listener** inspector, **Save listener as new reference** or **Use named
probe** adds an independent saved position. Select, rename or remove receivers; each
keeps its own reference speaker. **Compare all saved receivers** shows the direct-arrival
differences at every position. Arrays retain their first-to-last element arrival spread.

**Preview delay** shows before/after arrivals and timing differences at every saved
receiver. **Apply delay** accepts a still-current proposal within 0–200 ms, with undo
support; **Cancel** leaves DSP unchanged. Speaker delay changes affect every receiver, so aligning
one location does not imply alignment everywhere. Saved positions, speaker choices and
the active receiver survive save/reopen; older single references migrate automatically.
The shared core owns receiver selection, comparisons and versioned reference serialization.

These timing tools do not evaluate blocked-path audibility, reflected arrivals or
frequency-dependent cabinet/filter phase. A deleted reference speaker must be reselected.

### Saved response traces

**Listener → Saved traces / design comparison** captures a current calculated listener
response under a design name. Recalculate before capture. Spectrum mode saves eight
octave-band levels; single-band mode saves its selected band. Choose baseline A and
candidate B to see an overlay and B−A level differences. Saved snapshots survive project
save/reopen; they never stand in for live results. Comparisons require the same receiver,
bandwidth and solver convention. Unknown bands and zero energy remain explicit. These
are response snapshots, not restorable design variants or measured phase/IR traces.

### Saved design variants

**Listener → Saved design variants** captures the current acoustic design. Name and capture
variants before changing speaker placement, DSP, geometry, materials or analysis settings.
Select a snapshot and choose **Restore selected design** to switch back. Restore recalculates
results and supports Undo/Redo; saved response traces and the variant library are retained.
Variants survive project save/reopen and include available decoded speaker data, including
different revisions of the same speaker model. Missing assets remain explicitly flagged.
The library supports up to 16 variants (64 MiB). Snapshots do not change with later edits;
capture a new variant to preserve those edits. External visual-model paths and camera/editor
state are not embedded design assets.

### Coverage difference maps

Under **Listener → Saved design variants → Coverage difference maps**, choose baseline A
and candidate B, then **Calculate difference map**. Both saved designs are calculated without
restoring either into the open design. Blue means B is quieter, orange means B is louder,
and white means no change. Positive differences do not automatically mean better coverage.
Use the surface filter and 3D/plan/front views to inspect floors, balconies and vertical faces.
Gray indicates unavailable or zero-energy comparisons; the summary weights valid triangles
by actual surface area. Face colors show mean vertex differences.

Both snapshots must use the same audience mesh, receiver heights, exclusions, sampling
profile, frequency bands, bandwidth and weighting. Incompatible designs show an explanation;
they are not interpolated onto different geometry. Selections survive save/reopen, while maps
are recalculated from saved designs. Undo restores selections cleared by variant deletion.

### Traces at multiple reference receivers

Save reference receivers under **Listener → System alignment**, then use **Saved traces /
design comparison → Capture saved reference receivers** to select individual receivers or
select all. Enter a design name and capture the selected traces. The app calculates them
from one design state without moving the listener and saves the complete batch together.
Spectrum mode captures eight octave bands; band mode captures the selected band.

Use **Compare receiver** to filter historical traces, then select baseline A and candidate B
for that receiver. Comparisons still require matching positions, bandwidth and prediction
conventions. Captured names and positions remain unchanged if a reference is later edited or
deleted. Receiver selections and traces survive save/reopen; a complete batch supports Undo.
The existing limit is 64 saved traces total, so make room before capturing a large batch.

### Phase-response traces

Saved listener and reference traces now include wrapped phase **when explicit complex speaker
data is available**. The comparison panel shows phase samples and wrapped B−A differences at
the same receiver. These are exact-frequency coherent direct and first-order specular values, separate from the
band-averaged magnitude traces. Sparse samples are not unwrapped or converted into an IR.

Generic and magnitude-only speakers show phase as unavailable. The phase implementation
requires dispersion enabled and no nonzero EQ whose phase is unknown. Global ground
reflection needs an explicit measured phase curve for partially absorbing floors. Blocks and imported Faces use the same visible reflection paths. Ideal rigid
materials have explicit zero phase; other reflecting materials require measured reflection
phase as well as absorption. Active scattering models remain unavailable for coherent phase. All contributing speakers must provide complex data at
the requested frequency/direction with compatible reference drives. Nulls and missing data
remain unavailable. Existing broadband coverage still uses its established magnitude model.

Complex data can be imported through the portable decoded speaker JSON format; see
[the complex speaker format](docs/complex_speaker_format.md). Existing CLF imports are not
assumed to include valid phase. Speaker data and saved phase traces survive save/reopen.

Reflected phase captures use a new model convention. Earlier direct-only captures remain
readable and comparable to each other; recapture both designs to compare under the reflected
model. Coincident Blocks and imported Faces contribute one physical reflection, and missing
complex data along any contributing path prevents a misleading partial phase result.

### Global ground phase

Under **Settings → Ground model**, enable **Reflecting floor**, then add a measured ground
reflection phase curve. Enter rows of angle from normal, frequency in Hz and unwrapped phase
in degrees using exp(+iωt), with at least two frequency samples per angle. The curve combines
with the existing floor absorption settings and affects both phase traces and band interference.
It is saved with projects and design variants, and edits support Undo.

Without measured ground phase, partially absorbing ground remains unavailable in phase traces;
magnitude retains its existing real-coefficient approximation. Missing complex speaker data
along a contributing reflected direction also makes phase unavailable. Global ground replaces
coincident rigid ground Faces/Blocks once; opaque patches still block its bounce. Sources and
receivers must be at or above y=0. Diffuse phase and higher-order reflections remain unsupported.

### Unwrapped phase and group delay

Saved-trace comparisons now show unwrapped phase segments and interval group-delay estimates.
Capture with valid complex data at multiple frequencies; a single complex sample has no
group delay. Missing data breaks the traces. These nearest-turn estimates assume less than
180° of true phase change between samples; sparse octave bands may alias. Each interval shows
its delay alias limit. The app does not recover unknown whole turns or infer an impulse response.


Dense phase capture now uses all available measured complex frequencies, including frequencies
between the analysis bands. It works for listener and saved-reference captures. Each trace saves
an automatic propagation/DSP delay reference for unwrapping; displayed group delay includes that
reference. Missing complex samples remain gaps, and the remaining phase variation must still be
sampled densely enough. Full plots are shown with a 64-row numeric preview.


Saved-trace comparisons include editable **A/B delay references** in milliseconds, with **Reset
to automatic** and **Total / Relative to references** controls. Relative mode removes each trace's
selected delay from phase and group delay; total mode restores it after unwrapping. Changes are
saved and undoable and do not alter speaker DSP or the captured data. Residual sampling limits
still apply; changing a reference can select another unwrap branch when samples are sparse.


**Match B reference to A…** previews an automatic delay-reference adjustment from the valid
phase-difference slope. Review the proposed delay, sample count and fit error, then apply it.
Application switches to relative comparison and supports Undo. Missing data is not bridged,
constant phase offsets remain, and sparse samples can still hide complete phase turns.


Automatic reference matching now has an inclusive **Matching range** in whole Hz. Select a band
or reset to **All frequencies** before previewing. The preview reports the actual fitted sample
range; missing data is not bridged and endpoints are not interpolated. The selection is saved
with the project and supports Undo. Full trace plots and captured samples remain available.


Automatic matching now rejects inconsistent phase samples and previews a **confidence score**,
retained sample counts and rejected frequencies. The score is a diagnostic quality indicator,
not a probability or guarantee against phase aliasing. Heavily contaminated or poorly supported
fits cannot be applied. Rejection affects only the fit; raw traces and speaker DSP stay unchanged.


Automatic-match previews now show **phase-difference and residual plots**. Blue circles mark
retained samples, red crosses mark rejected samples, and white lines show the fitted model.
Missing-data segments stay separate. Plot values come from the shared matcher and reproduce
after save/reopen; raw trace samples remain unchanged.


Tap a marker in either fit-diagnostic plot to inspect its frequency, retained/rejected status,
observed phase, fitted phase, residual and segment. The selection is highlighted in both plots.
Previous/Next controls support dense samples. Selection is saved with its trace pair and resolves
fresh details after reopening; deleting a trace or excluding the frequency clears it safely.

Automatic delay-reference matching supports manual sample exclusions from the fit inspector. Exclusions are saved per A/B trace pair, split phase-unwrapping segments, and can be included again or undone without changing raw traces.

Frequency-range exclusions are also supported per saved A/B comparison. Inclusive ranges combine with individual exclusions, persist with projects, and can be removed independently or undone.

In fit diagnostics, enable range selection and drag horizontally on either plot to highlight captured frequencies. Choose Exclude selected range to save it and update the fit; draft selections do not change matching.

Fit plots also support editing existing exclusion bands: select a band in edit mode, drag either yellow edge, and save the resized range. Changes are undoable and persist with the project.

Choose Move whole range in the fit-plot exclusion editor to drag a selected band while preserving its width in Hz. Movement stops at plot limits; save the edit to apply it or cancel the draft.

Fit diagnostics include Undo exclusion edit and Redo exclusion edit for the current A/B pair. Saved exclusion history survives project reopening; undo/redo refreshes the fit and discards any unsaved range draft.

Undo/Redo buttons in fit diagnostics describe the next exclusion action, including sample frequency or old/new range bounds. Labels follow the saved history across project reopening.

The expandable exclusion history panel in fit diagnostics lists available states and the current position. Select an earlier or redoable state to preview its changes before explicitly applying the navigation; history position survives save/reopen.

History-state previews show exclusion differences and current/proposed fit results with candidate plots. Cancel leaves saved state untouched; applying a stale preview is rejected.

History previews overlay current (solid blue) and proposed (dashed orange) fits on shared phase/residual axes. Shared-core comparison aligns whole-turn phase branches and preserves missing-data gaps.

Overlay previews support sample inspection, current/preview numerical values and fitted/residual differences, plus changed-sample filtering. Selection and filter preference persist with the project; inspection does not invalidate an otherwise-current history preview.

### Complex-band coverage and digital filters

Declared version-2 complex speaker responses now feed shared band coverage through bounded,
converged integration. Version-1 exact-tone data retains its legacy magnitude coverage behavior.
Shared low-pass, high-pass and parametric biquad chains supply magnitude and phase; Android
provides add/remove controls, undo/redo and schema-23 save/reopen. Missing data is never filled
with assumed phase. Mixed magnitude/complex sources now work in explicit Energy mode at all frequencies.
Compatible version-2 complex emitters now interfere on a common first-bounce scattering mesh.
Large measured arrays reuse bounded geometry data and use an error-bounded linear-cost incident
sum for narrow delay spans, retaining analytic pair sums for wider spans. Direct/specular
arrival sums use the same bounded-error integration with measured material phase slopes included
in the effective delays; independent groups and coincident-path cancellation are preserved. Whole-scene evaluation
also reuses exact measured-response intervals and independently converges the diffuse spectrum.
Full-venue requests share bounded incident spectra and source-only sampling work across receivers,
while retaining receiver-specific shadows, outgoing weights and air loss. See the
[performance fixtures](docs/performance_benchmarks.md) for the qualified 1 kHz venue and outstanding
high-frequency diffuse performance gates. Scratch memory remains bounded and missing-data checks
are unchanged. Magnitude-only sources
retain independent diffuse energies. Outgoing diffuse sound remains an
energy result; diffuse phase and multiple-bounce transport remain unsupported. See [the implementation plan](docs/implementation_plan.md) for the data contract,
validation evidence and remaining limits.

## Source licensing

The original application source is proprietary, with rights reserved; see [LICENSE](LICENSE).
Third-party reference data has separate terms; CLF samples were removed from the current checkout and historical-copy permissions remain unresolved. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
