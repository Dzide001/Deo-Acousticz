# DroidAcoustic Pro implementation plan

Updated **2026-11-26** after the [project review](project_review.md). This replaces
the original numbered-phase timetable in the README. The first release priority
is **live sound: PA coverage, arrays, subwoofers and delay systems**. The existing
application is Android-only; an iPad path is included as a recommended extension
following the platform review. The progress entries record implemented work; milestone lists also include outstanding release gates.

## Validation-first release sequence (supersedes earlier milestone ordering)

The first dependable release remains a live-sound design tool. Existing feature progress below
records implementation, not manufacturer equivalence or field accuracy. The older M0–M9 gates
remain a backlog; execute the following sequence before expanding room-acoustic features.

1. **V0 — Truthful model and controls (first cleanup batch).** Stop hidden legacy room-reflection
   jobs and inferred-room RT60/intelligibility publication. Replace inactive reflection-order choices
   with the actual first-order capability; describe receiver-grid spacing accurately. Hide global
   wall/ceiling absorption controls that do not affect coverage. Preserve old project fields without
   treating them as active physics. Publish one shared model-scope contract in Android and reports.
   Disable the legacy optimizer, whose approximate objective ignores delay, until replaced/validated.
   Exit: focused regressions for successful predictions, absent unsupported metrics, old-project
   reopen with identical levels and optimizer DSP isolation; Android build and iOS-core compile.
2. **V1 — Reproducible numerical validation pack (baseline implemented; see revision 1 below).** Version fixtures and reference implementations
   for inverse-square propagation, phase/delay/polarity, bandwidth integration, analytic ideal-ground
   interference and arrays. Retain scripts, raw results, tolerances and convergence checks. Add finite
   reflector and barrier cases that expose the current clipped-image and binary-shadow limitations;
   do not claim a universal frequency cutoff. Separate numerical verification from physical validation.
3. **V2 — Speaker-data and external-validation gate (provenance/data consistency and first real angular holdout implemented; external acceptance pending).** Inventory generic estimates, user imports and
   manufacturer-supplied assets separately. Verify calibration, phase reference, operating preset,
   distance and angular coverage. Match manufacturer exports and controlled measurements under
   identical settings; retain permission/provenance and error reports. Qualify near-field/array use
   explicitly rather than assuming isolated-cabinet data covers every arrangement. Define and justify
   acceptance tolerances per use case before claiming professional prediction accuracy.
4. **V3 — Output capability and dependable workflows.** Add frequency-dependent maximum-output /
   headroom only with documented amplifier/DSP, duration, crest-factor and compression/limiting
   assumptions. Validate aiming, sub/delay alignment, coverage reports and restore workflows. Replace
   optimizer objectives with shared-engine calculations before re-enabling automatic application.
   Require practical OnePlus latency/memory results for each supported calculation mode.
5. **V4 — Bounded extensions after validation.** Keep existing reflection/scattering features
   experimental and pause expansion. Gate phase-aware edge diffraction, finite-reflector transitions
   and ground impedance independently, with interference and double-counting references. An ISO
   attenuation approximation must be identified separately from a complex transfer-function model.
   Full-venue 4/8 kHz diffuse coverage remains unqualified; budget failures are not completed results.

Mechanical geometry/splay assistance and iPad rendering feasibility may proceed without certified
rigging claims. Certified loads require a separate manufacturer/hardware/engineering validation
track. Preserve the platform-independent core throughout; do not move physics back into Android.

## Immediate priorities after the November review

1. **Maintain the source-control checkpoint.** This main-branch checkpoint records the shared
   core, Android integration, validated fixes and retained evidence previously held only in the
   working tree. Delivery target is `origin/main`, using a normal push without rewriting history.
   Internal-drive recovery snapshots remain an additional safeguard. Keep subsequent validated
   batches in regular commits; the STI branch remains unmerged.
2. **Discrete EQ leakage fixed.** Shared exact-band lookup replaces nearest populated gain in
   production snapshots and the remaining Android source adapter. Unset bands are neutral; old
   saved offsets are retained and evaluated correctly. See the completed batch below.
3. **Broadband normalization implemented with explicit compatibility.** New projects use fixed
   total relative drive split equally across the eight octave bands. Old projects without the new
   field retain per-band reference drive until explicitly changed. The selected convention is
   visible in the map legend and Settings. Neither mode implies watts without electrical calibration.
4. **Complete physical-validation and production-test gates.** Native decoded-data left/right
   validation is implemented (see batch below). Manufacturer-matched acceptance,
   external asymmetric CF1/other binary variants and electrical CF1 calibration remain open.
   The CF1 sensitivity byte offset is now verified against the official text pairs.
   The official asymmetric TAB/CF2 subset is now qualified (see batch below). The retained
   PRX635 holdout is not an engine-wide pass. Summation/array adapter tests now use production
   snapshots. Unpublished room-metric/reflection calculators and their tests are now retired;
   the source is archived outside the build, while active boundary/ground checks use the shared solver.
   Test counts alone do not establish prediction accuracy.
5. **Qualify performance and release.** Keep large diffuse/high-frequency modes experimental until
   they meet a device budget. Address the slow phase-trace test, adaptive grid/preview latency and
   release signing/device acceptance before release; resolve distribution licensing and versioning.
   Maximum-output/headroom needs verified speaker/amplifier/DSP data. Certified rigging remains a
   separate manufacturer/engineering track.

Branch decisions: **keep the STI branch as preserved experimental work; do not merge its current
publication path** into the live-sound product. It conflicts with the explicit RT60/STI gate.
The reflection-reference test and review report were included in checkpoint `b7a472e`;
their files matched `claude/reflection-reference-test`, so no additional branch merge is needed.
The legacy optimizer is retained but `runOptimization` returns immediately while the shared scope
contract disables it; it is not currently allowed to run/apply the legacy objective.

## Validation/import cleanup checkpoint — 2026-11-26

Checkpoint the validated batches after `b7a472e`: native/external asymmetric
orientation, CLF sign/symmetry and missing-reference corrections, reference-basis
calibration guards, migration of summation tests to production requests, and
retirement of unpublished room calculators. Retain fixtures, source provenance,
scripts, historical archives and raw results; exclude build outputs, the local
manufacturer corpus and device/user project backups. Publish with a normal push
to `origin/main`; do not merge or alter `claude/serene-mclaren-0dfd96`.

Run a fresh complete shared/Android test gate before publishing, plus Android
assembly and iOS arm64 core/simulator test compilation. Retain the consolidated
gate in `validation/checkpoints/2026-11-26/`, separately from historical batch
results. Check all eleven independent reference scripts, the archive audit and
six CSV-packaging tests. The push is a source checkpoint, not signed release or
device acceptance. Physical/external validation, performance, platform completion
and release gates remain open as recorded above.

The fresh consolidated gate passed: **644 shared JVM tests and all 449 Android
tests, zero failures/errors/skips**; Android debug assembly and iOS arm64
core/simulator test compilation passed. All reference/audit/CSV checks above passed.
This supersedes the prior batch's split full-run/fixture-repair test evidence as the
current complete regression checkpoint; historical batch records remain unchanged.

## Retired room-model cleanup — 2026-11-26

Archive the historical RT60/image-source tests and removed ViewModel helpers with
hashes in the [retirement package](../validation/retired-room-model/README.md).
Remove unused room-bounds inference, guessed RT60, higher-order image generation,
empirical reflection-air loss and the obsolete floor adapter from active code.
Retain compatibility empty/null metric state, old project material/order fields and
the unmerged STI branch. These archived tests no longer inflate the active suite.

Move `SurfaceMaterial` and six catalogue compatibility checks into shared-core,
preserving IDs, lookup/clamping and coefficient values. Shared production-request
tests pin analytical global-ground energy. Android tests cover actual ground
materials, isolation of inactive room settings, reports without unsupported metrics,
disk save/reopen and legacy scalar/custom materials. Existing shared boundary and
Block/face/reflected-phase regressions validate the active first-order implementation.
Retain audit results and exact test/build scope; fewer Android tests are intentional.
All **644 shared JVM tests and 77 targeted Android tests pass**, with no skipped
tests. The Android gate includes reflected-phase traces, Block/face integration,
directivity, report scope and project recovery. Android debug assembly and iOS
arm64 core/simulator test compilation pass. No device/runtime acceptance is claimed.

Remaining validation gates are physical/external acceptance, qualified speaker
reference conversions, other binary variants and release/performance qualification.
This cleanup adds no room RT60/STI capability or manufacturer-accuracy claim.

## Production summation test migration — 2026-11-26

The [retained migration package](../validation/production-summation/README.md) replaces
25 legacy-helper calls in Android summation/array tests with immutable production
prediction requests. Remove arbitrary +3..+6 dB blend assertions: require analytical
coherent/independent gains, energy cancellation and finite-band delay residuals.
Four shared common tests pin the request-level physics and within-array coherence
when inter-source interference is off. Android tests cover actual poses, gain,
polarity, taper, steering, generic splay, batched receiver grids and DSP save/reopen.

This is validation cleanup; no solver change or manufacturer accuracy claim is
introduced. Retain the baseline/call inventory and full shared/Android suite results.
Remaining RT60/reflection helper tests describe unpublished/disabled code and do not
qualify the live prediction path. Keep compatibility methods until those remaining
call sites are migrated or explicitly archived.
The full Android run also found eight outdated coverage-edge fixtures with one
sensitivity value for eight bands. Correct all three fixture builders and assert
successful imports before checking edges, avoiding silent generic fallback. Retain
the initial full-suite inventory and post-fix targeted rerun separately; the slow
phase-trace class passed in the full run and is not repeated for this fixture-only fix.
All **636 shared JVM tests pass**. The complete Android run covered **477 tests**:
469 passed initially; the eight fixture failures were corrected and the full
coverage-edge class plus migrated summation classes passed a targeted rerun.
Android debug assembly and iOS arm64 core/simulator test compilation pass. Do not
describe this as a second clean full Android run; exact run scopes are retained.

## Powered/axial reference qualification — 2026-11-26

The [reference-basis package](../validation/powered-reference/README.md) adds shared
portable sensitivity/axial/unspecified source-row metadata and data-quality reports.
Unknown legacy basis is omitted from canonical JSON so default old asset hashes
remain stable. Persist explicit bases through Android disk save/fresh reopen.

Fixed calibration leakage: axial fallback and powered input settings cannot inherit
a watt normalization from sensitivity notes. Axial provenance uses its own information
field. Only a nonpowered sensitivity row can use the existing narrow file-declared
watt-reference rule. Explicit native-format user calibration remains separate.
Importer v5 records the change; old assets/traces/variants are retained, so original
CLF sources need reimport/review to apply these guards to prior declarations.

Reject automatic powered CF1 axial-only conversion with a specific shared error and
preserve the existing project after failure. A viewer's octave display alone does
not qualify an engine input-spectrum/drive conversion. Test four synthetic reference
fixtures, legacy hash stability, unknown/declared calibration, save/reopen and atomic
failure. Retain per-case results and exact build/test gate in the package.
All **632 shared JVM tests and 51 focused Android tests pass**, with no skipped
tests. Android debug assembly and iOS arm64 core/simulator test compilation pass.
Predictions are identical after reopen in all four cases. No physical-device or
iOS runtime check was performed.

Remaining: independently calibrated powered/octave conversion, electrical reference
qualification, asymmetric binary CF1 and actual manufacturer/measurement acceptance.
This batch qualifies metadata and guards; it does not close physical validation.

## CF1 reference-row validation and missing slots — 2026-11-26

The [CF1 reference package](../validation/cf1-reference/README.md) pins 13 binary
sensitivity slots against the official Active/Passive1 text examples. This qualifies
the tested `0x1240 + 4*octaveSlot` address, not electrical drive normalization.
Powered zero placeholders remain unavailable. Retain input hashes, original numeric
text subsets/binaries, independent reference/extraction scripts and Android results.

Fixed shared TAB reference parsing to preserve `<nodata>` column positions. Explicit
sensitivity holes stay missing and cannot borrow the distinct axial spectrum. CLF2
axial fallback preserves all third-octave slots; CF1 does not guess an octave drive
conversion. Invalid reference rows fail explicitly. Importer v4 identifies the fix;
old native projects/assets are retained and need original-file reimport to recover
previously discarded source slots. Test production TAB/CF1 predictions, sparse
assets, unknown calibration and disk save/fresh reopen; retain exact gate results.
All **629 shared JVM tests and 50 focused Android tests pass**, with zero skipped.
Android debug assembly, iOS arm64 core compilation and iOS simulator test
compilation pass. No physical-device or iOS runtime test was run in this batch.

Next external gates: independently asymmetric binary CF1, explicit electrical
normalization and powered/axial conversion. Manufacturer/measurement acceptance
remains mandatory; official format examples cannot establish physical accuracy.

## CLF relative magnitude and declared-symmetry cleanup — 2026-11-26

The [retained CF1 text examples](../validation/clf-magnitude/README.md) expose ignored
reversed magnitude signs and incorrect reversed quarter-sector expansion. Corrected
both in shared-core. Symmetry now follows declared none/horizontal/vertical/full/
rotational sectors rather than guessing from row count. Rotation follows expansion.
Reject inconsistent/unknown declarations, non-finite samples and unsupported
absolute/arbitrary references rather than interpreting them as relative attenuation.

Retain the official numeric source subsets/hashes, extraction/oracle scripts,
production prediction and recovery results. Common tests cover CLF1/CLF2 resolution,
sector/order/sign combinations and invalid inputs. Android checks save/reopen and
atomic rejection of unsupported imports. Importer v3 identifies the correction;
v2 TAB assets receive a shared reimport warning without silently changing saved
native data, design history or traces. Exact build/test scope is in package results.
All **626 shared JVM tests and 48 focused Android tests pass**, with no skipped
tests. Android debug assembly and both iOS core/test compilation checks pass.
No device installation or iOS runtime execution was performed in this batch.

The official CF1 examples are horizontally symmetric and cannot establish binary
CF1 left/right handedness. That gate and electrical CF1 calibration remain open.
Absolute/arbitrary normalization, polar-only interpolation and optional complex
container support remain separate work; manufacturer/measurement acceptance is
still required. This is a focused import-correctness batch, not a new acoustic model.

## External CLF handedness correction — 2026-11-26

The [official CLF sample package](../validation/clf-handedness/README.md) combines
independently labelled directions from specification Figure 1 with the authoring
kit's asymmetric TAB/CF2 pair. This exposed two errors that the exactly symmetric
XD12 could not reveal: native right was mapped to CLF left, and binary arcs were
unconditionally reversed. The binary errors cancelled for the tested layout;
the TAB mapping did not. Corrected both, moved the entire TAB parser into shared-core,
and implemented its declared full-circle `<ROTATE>` normalization.

Retain official source/fixture hashes, a reduced numeric TAB fixture, unmodified
sample CF2, independent reference/extraction scripts and per-case results. Shared
tests check 256 predictions across default/reversed/rotated encodings with 128
rejected old mappings. Android tests check 128 TAB/CF2 production predictions with
disk save/fresh reopen for each pose. CI checks references and uploads fresh reports.
Build/test evidence and exact residuals are in the package verification result.
All **622 shared JVM tests and 47 focused Android tests pass**, with no skipped
tests. Android debug assembly, iOS arm64 core compilation and iOS simulator test
compilation pass. Maximum orientation residual is below 0.000006 dB against the
predeclared 0.001 dB gate. No physical-device or iOS runtime execution is claimed.

New assets identify importer v2. Existing v1 TAB assets receive a shared warning
to reimport their originals; saved native data, historical traces and variants are
not silently mirrored. Imports without provenance cannot be classified reliably.
This closes handedness for the tested full-circle relative-magnitude TAB and CF2
subset only. Independently asymmetric CF1 and other binary variants, unqualified
symmetry/sign/reference modes, optional phase/filter/multipart, physical calibration
and manufacturer-software comparisons remain separate V2 gates.

## Asymmetric directivity validation — 2026-11-26

Implemented the [retained orientation package](../validation/asymmetric-directivity/README.md)
for normalized speaker data. An independent Python oracle defines 198 left/right,
upper/lower, signed-grid, seam, frequency and rotated/tilted production-prediction
comparisons. Mirrored negative controls must fail all 180 off-axis cases. Portable
speaker reopening must preserve predictions exactly. Shared common tests pin world
axes and individual array frames; electronic steering cannot rotate those frames.
Android adapter tests cover import, production coverage capture and disk save/reopen.
CI verifies the independent references and retains fresh engine reports.

No runtime correction was needed in the normalized engine path. This closes the
synthetic native handedness gate only. The subsequent external CLF batch above
qualifies the official TAB/CF2 subset; the symmetric XD12 pair alone cannot prove
handedness. Other binary variants and CF1 handedness/level calibration,
manufacturer-matched acceptance and intrinsic phase orientation remain unqualified.
See the package's verification result for the exact tests and platform build scope.

## Source-control checkpoint — 2026-11-26

The checkpoint includes the validated working tree accumulated since `7b02551`: shared-core
migration, production Android integration, geometry/receiver/trace workflows, validation-first
cleanup, numerical references, project recovery, availability warnings, EQ isolation and explicit
broadband drive migration. Retained evidence includes failures of physical-accuracy screens;
committing this work is not a release or manufacturer-equivalence claim.

No STI merge is included. The preserved experimental branch remains at
`5b58c7e97819332518f227df4d894a107b9d1c90`; STI/RT60 publication remains disabled. Reflection-reference
files already present in the working tree are included as validated evidence. Private device-data
backups, build outputs, downloaded restricted corpus files and Python caches are excluded.

Checkpoint validation passed: 615 shared JVM tests, 471 Android tests and the debug build. Six
independent Python reference packages and six comparison-CSV packaging tests also passed.
The old weighting test now explicitly distinguishes legacy per-band drive from fixed total. Current detailed results are
retained in `validation/checkpoint-2026-11-26/`. Remote CI is an additional check after delivery.

## Current batch: EQ isolation and broadband drive

Shared `DiscreteBandEq` uses only stored frequency keys; an absent EQ band is 0 dB. This corrects
legacy-project predictions without discarding EQ settings or treating the offsets as validated
parametric-filter phase data. Validated DSP filter behavior remains separate.

Shared `BroadbandDrive` reuses the established source-spectrum power allocation. New projects and
Clear/New scenes default to `TOTAL_EQUAL_BAND_POWER` (1/8 mean-square drive in each octave).
`PER_BAND_REFERENCE` remains an explicit compatibility choice. Missing fields always decode as
legacy, even when a new app's default differs. Project schema 31 records the policy and prevents
older apps from silently reading fixed-total projects with the wrong drive semantics. Undo/Redo,
variants, autosave, scene/report export and reopen carry the explicit field. Comparison capture
retains its separate, explicitly declared source-spectrum settings for matched protocols.

Band maps are unchanged by the broadband policy. Broadband weighting is applied after source
excitation allocation; coherent/direct/reflected/diffuse band powers share the same positive scale.
Complex and validated-filter phase are unchanged. Missing response bands remain unavailable;
normalization never renormalizes surviving data or invents missing responses.

The viewport keeps its compact controls; the existing convention label adds only “total drive” or
“per-band drive”. Settings → Signal → Broadband relative drive offers Fixed total / Per-band
reference. Existing projects require an explicit choice to change their historical broadband levels.

Passed 615 shared JVM tests, 19 focused Android tests, Android build and iOS shared-core test
compilation. OnePlus verified sparse legacy EQ (+6 dB at 1 kHz, no change at 125 Hz), the expected
9.03 dB broadband correction (9.0 dB at UI precision), settings, Undo/Redo, and reopen of both
policies. All nine original app-data files matched before launch; reopened scene and imported
assets matched after restoration. The tested debug build remains installed.

[Retained numerical and device evidence](../validation/drive-eq/README.md).

## Completed validity batch: numerical data availability and frequency aliases

The shared prediction output classifies available, partial and unavailable numerical results using
finite receiver values and existing area-weighted statistics. It reuses `DirectField.predictBand`'s
missing-source/frequency reports during the existing calculation, with no extra acoustic pass.
An unavailable broadband total remains unavailable; missing bands are not silently omitted.
Only the existing status line changes, so the compact Band/Broadband controls stay uncluttered.
Warnings clear on edits and stale results cannot publish them. Reports include source IDs, missing
integration frequencies, affected requested bands, receiver counts and area availability. Derived
warnings are recomputed after reopen rather than persisted as authoritative results.

The 31/32 Hz edge case was reproduced: a narrow on-grid tolerance rejected 32 before alias lookup.
Supported 31/32 and 63/64 integer aliases now share the engine's existing base-two cell centres for
lookup and interpolation. Exact stored samples keep precedence; arbitrary off-grid endpoints,
missing cells and extrapolation are still rejected. This does not migrate the engine to IEC's
preferred base-ten bands or certify physical accuracy.

Verification and device evidence: [data-validity package](../validation/data-validity/README.md).
Passed 609 shared JVM tests, 15 focused Android tests, Android build and iOS shared-core test
compilation. OnePlus checks passed for all three states, reopen, supported-band recovery and
missing assets. The original nine app-data files and reopened project/assets were restored and
verified; the tested debug build remains installed.
Completed-batch detail now lives in [implementation history](implementation_history.md), preserving
its references while keeping this active plan shorter.

## Product scope

Build a dependable 3D direct-field sound-system design tool for tablets. Users
must be able to define audience surfaces, place speakers and arrays, set routing,
gain, delay and polarity, inspect coverage and receiver responses, compare
designs, and reopen/share a project without changing its acoustic meaning.

**Cross-platform maintainability and easy feature expansion are core requirements.**
Implement platform-independent features once, keep Android and iPad behavior
consistent, and preserve project compatibility as the application evolves.

Soundvision, MAPP 3D and ArrayCalc set the workflow ambition. Each supported
loudspeaker/array model must earn its accuracy claims through data and validation.
Generic layout simulation remains useful when explicitly labeled approximate.

### First live-sound release

- Android tablet workflow, with shared-core architecture and an early iPad proof.
- Flat and raked audience polygons, balconies and multiple elevations, stage and
  reference geometry, independent virtual microphones, plan/section/3D views.
- Point sources, fills and delay speakers; explicit reference drive and signal
  spectrum; valid measured response/directivity where available.
- Subwoofer configurations with gain, delay, polarity, grouping and response
  comparison. Complex-response data required for supported real-system phase claims.
- Explicit line-array element poses, splay, aim and gain shading. Generic arrays
  labeled approximate; validated product support gated per model/configuration.
- Per-band and broadband maps with stated bandwidth/weighting; receiver magnitude
  and arrival information, phase only where source/filter phase is supported.
- Coverage statistics by surface, target thresholds, design variants, portable
  project files, deployment reports and offline operation with installed data.

### Separate later capabilities

Full room-acoustic simulation, STI/STIPA, environmental noise compliance,
auralization, manufacturer processing emulation, automatic array optimization,
hardware control, and certified rigging/load calculations require separate
scope and evidence. Visual array assembly must not imply a mechanical approval.
iPhone layout and full desktop applications are also separate follow-on work.

## Architecture and platform decision

Adopt a single shared reference solver and a shared project/domain model. Start
from the existing Kotlin acoustic package, correct its contracts, and move it
into Kotlin Multiplatform. Keep Android working during extraction. Evaluate
Compose Multiplatform for common controls; isolate 3D viewports and OS services.
KMP permits incremental sharing with native or shared UI, and Filament supports
both platforms. ([KMP](https://kotlinlang.org/docs/multiplatform/kmp-overview.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Filament](https://github.com/google/filament/blob/main/README.md))

Proposed module boundaries, not directories already implemented:

| Module | Responsibility | Platform constraints |
|---|---|---|
| `shared-domain` | SI coordinates, units, project state, commands/undo, source/surface/receiver IDs | No Android, renderer or filesystem types |
| `shared-acoustics` | Source transfer, propagation, summation, bands, analysis | Shared deterministic CPU implementation and common tests |
| `shared-geometry` | Element poses, polygon triangulation, receiver meshes, picking math | Geometry shared by renderer and solver |
| `shared-data` | Speaker schemas, parser validation, content hashes, project migrations | Common decoding/serialization; platform storage interfaces |
| `shared-application` | Immutable calculation requests/results, cancellation, provenance | No Android ViewModel dependency in domain operations |
| `shared-ui` | Optional reusable Compose inspectors, controls and plots | Extract after the iPad viewport proof |
| `android-app` | Existing shell, Android storage/share adapter, Filament view adapter | Android APIs remain here |
| `ios-app` | iPad shell, native storage/share adapter, Filament Metal view adapter | Swift/Objective-C++ bridging as needed |
| `native-compute` | Optional later acceleration of identified hot paths | Same result contract and numerical parity tests |

Replace JVM-only math/byte-buffer/JSON dependencies in shared code with common
implementations. Preserve byte order and parser fixtures during migration.
Define scene/render descriptions independently of Filament objects; native view
adapters own GPU resources, touch input, frame scheduling and lifecycle.

```mermaid
flowchart TD
    A[Android editor] --> D[Shared project and commands]
    I[iPad editor] --> D
    D --> S[Immutable prediction request]
    L[Versioned speaker assets] --> S
    G[Shared surface and array geometry] --> S
    S --> P[One reference acoustic solver]
    P --> R[Result with input and model provenance]
    R --> V[Platform viewport adapters]
    R --> C[Receiver plots and reports]
```

Keep the portable C++ utilities, but do not implement a second acoustic solver
in parallel or make Vulkan a requirement for correctness. The Android manifest
currently requires Vulkan hardware despite the CPU fallback; revisit that
requirement when the reference CPU path and viewport backend are established.
For iOS, prove native Filament/Metal integration rather than assuming the Android
JNI and Vulkan code will transfer unchanged. A full C++ or Flutter rewrite is
not justified solely by the need for iOS support.

## References for face assignments, OBJ groups and viewport picking

- [Wavefront OBJ specification, Grouping](https://paulbourke.org/dataformats/obj/obj_spec.pdf):
  `g` applies to subsequent elements and may list several group names. The shared importer
  follows that membership state, then deliberately partitions complete name sets to fit the
  app's exclusive native groups. The bare/absent-group mapping and label disambiguation are
  documented app conventions. No external parser code or dependency was copied.
- [Three.js BufferGeometry documentation](https://threejs.org/docs/pages/BufferGeometry.html):
  disjoint group ownership informed a review of the group-partition design. Our groups are
  named acoustic face partitions and may leave faces ungrouped; they are not Three.js draw
  groups. No Three.js implementation or dependency was copied.
- [Möller and Trumbore, Fast, minimum storage ray/triangle intersection](https://doi.org/10.1145/1198555.1198746):
  the authors' published ray/triangle method informs the shared barycentric intersection
  calculation. Our Kotlin implementation adds two-sided editor picking, stable ties,
  clipping and selection semantics; no external source code or dependency was copied.
- [MAPP 3D user guide](https://docs.meyersound.com/products/en/user-guide---mapp-3d.html):
  face selection for predictions, surface Prediction controls, and listening-height
  Offset informed explicit face eligibility and separate receiver offsets. Its Group and
  Duplicate editing workflow also informed reusable group operations, without assuming
  that MAPP object groups have our per-face statistics semantics.
- [ArrayCalc overview](https://www.dbaudio.com/global/en/products/software/arraycalc/):
  audience listening planes inform the distinction between imported geometry and
  selected listening surfaces. Our arbitrary triangle workflow is our implementation,
  not a claim of identical ArrayCalc behavior or manufacturer-validated accuracy.
- [Open3D TriangleMesh documentation](https://www.open3d.org/docs/release/python_api/open3d.t.geometry.TriangleMesh.html):
  vertex/triangle attribute maps support the design choice to attach assignments to
  original faces independently of topology. No Open3D dependency or source was copied.
- [ArrayCAD public repository](https://github.com/stoatworks-labs/arraycad): the README's
  CAD preparation and role-assignment workflow was reviewed as a workflow reference;
  no code was copied. Triangulated OBJ remains our bounded import contract.

## Keeping updates and new features maintainable

- **Use established references before inventing new machinery.** For new features,
  consult relevant official MAPP 3D, ArrayCalc, Soundvision and other industry documentation,
  then useful public GitHub implementations and geometry libraries. Record the sources,
  what is adopted, and differences from our calculation contract. Check licenses before
  reusing code or data; retain attribution when required. Prefer adapting proven concepts
  into the shared core over adding a platform dependency solely to copy a workflow.

- **One implementation of shared behavior.** Acoustic calculations, parameter
  validation, geometry, project commands, serialization and feature availability
  belong in shared code. Platform adapters supply rendering, input, files,
  sharing and lifecycle services through small interfaces. Keep feature rules
  out of platform screens.
- **Focused feature boundaries.** Add a feature through its domain model,
  application operation, tests and UI integration. Enforce dependency direction:
  shared domain/solver code cannot depend on an app or platform adapter. Start
  with logical packages and split build modules when useful; a module per screen
  or a general plugin framework is not required.
- **Reusable presentation.** Share controls, units/formatting, plot definitions,
  palettes and design tokens where practical after the iPad UI proof. Native
  views consume the same state and commands so they do not reimplement business
  logic. Platform capabilities are explicit when a feature is unavailable.
- **Versioned compatibility.** Version the project schema, engine and speaker
  datasets separately. Each schema change includes migration fixtures. Preserve
  older projects; detect unsupported newer capabilities before editing or saving.
  Keep the original file intact rather than silently dropping unknown data.
  Speaker-library additions use the documented data schema instead of requiring
  edits to platform-specific source code.
- **One repository and repeatable releases.** Centralize shared dependency and
  toolchain versions. Build supported platform apps from the same tagged shared
  core, with platform-specific packaging/signing. Run shared tests on JVM and
  Kotlin Native, platform adapter tests, and common project smoke tests in CI.
  Record the core/schema versions and platform feature availability in releases;
  Android and iPad store release dates may differ without corrupting projects.

**Acceptance for each new shared feature:** implement its model, validation,
calculation and persistence once; exercise the same reference project on both
supported platforms; verify numerical parity, migration and save/reopen behavior.
A platform released later must identify an unsupported feature without changing
the project's meaning. For features with no numerical output, verify equivalent
commands and saved state. Keep these checks scoped to the affected behavior.

## Acoustic and data contracts

### Source model

Every model needs stable ID/version/hash, provenance, supported frequency range,
coordinate convention, measurement distance, reference drive, on-axis spectrum,
directional magnitude, and phase availability. Preserve optional phase/filter
and multipart metadata even when a reader cannot yet support its use.

Separate four states: generic approximation, measured magnitude only, measured
complex response, and unavailable/invalid data. Model names and branded cabinet
graphics do not establish measured capability. Do not silently substitute a
generic radiator for missing imported data. Do not convert missing data to 0 dB.

Sensitivity, voltage sensitivity, electrical input dBu, amplifier gain, and maximum
linear output are distinct quantities. Implement only conversions supported by
the model's metadata. Initially expose relative gain when calibration is absent;
do not manufacture maximum SPL/headroom from sensitivity alone.

Implemented JSON speaker metadata (inside each `ClfData` object):

```json
{
  "measurementGrid": "THIRD_OCTAVE",
  "referenceCalibration": {
    "distanceM": 1.0,
    "driveUnit": "VOLTS_RMS",
    "driveValue": 2.83,
    "provenance": "Documented test reference"
  }
}
```

`driveUnit` is `UNKNOWN`, `VOLTS_RMS` or `WATTS`; omit `driveValue` for unknown.
`distanceM` describes the reference of the supplied response levels, not an
unadjusted microphone distance. Levels must already have measurement air loss
removed; normalization applies geometric spreading only. Legacy metadata defaults
to a 1 m, electrically unknown reference. `measurementGrid` is `THIRD_OCTAVE`
(default) or `OCTAVE`; it limits permitted interpolation and does not fill holes.
CLF1/CLF2 adapters declare the corresponding frequency grid. The CLIO exporter
shows measurement distance and input-voltage fields separately from sensitivity;
we do not assume those raw fields can be reapplied to a normalized sensitivity row
without verifying the exporter contract. ([CLIO manual, directivity export](https://www.audiomatica.com/wp/wp-content/uploads/clioman12qc.pdf))

### Project storage and recovery contract

- Portable exports retain the existing scene JSON shape plus
  `speakerAssetManifest[id] = { "version": 1, "sha256": "…" }` and embedded
  `clfRegistry` data. Missing references remain explicit even when payloads are absent.
- Local files live under Android `filesDir/projects`: `assets/<sha256>.json`,
  `recovery.current`, `recovery.previous`, `project_A.current` and `project_A.previous`.
  Snapshot filenames are independent of scene schema versions. Temporary files are
  never used as completed checkpoints.
- Assets are written once per content revision before a referencing checkpoint can
  commit. File writes flush their contents and use an atomic rename. The shared
  storage interface requires atomic replacement; other platforms supply that adapter.
- Hashes detect content mismatch; they do not certify manufacturer accuracy or
  authenticate the author. Asset format changes require explicit codec migration.
- No asset garbage collection is performed yet, so older revisions remain available
  for backup and undo. Raw TAB/CF bytes are not yet retained; decoded acoustic data
  is preserved. Very old recovery snapshots that omitted data cannot reconstruct
  it and require reimport.
- File-backed unit tests exercise the Android JVM adapter and failure simulations;
  process-kill/power-loss testing on a physical tablet and iPad runtime tests remain
  required release checks.

### Propagation and summation

For each emitter, evaluate its transfer function in the local cabinet frame and
apply propagation, air loss, DSP and delay. Sum complex pressures within each
correlated signal group; sum powers between independent groups. Keep a source's
own reflected paths in its correlated group. Integrate power over the selected
band against an explicit input power spectrum. Apply frequency-dependent A/C
weighting to spectral or per-band power before broadband summation; Z weighting
is unity. Convert the resulting power to the reported logarithmic level.

Use the existing closed-form sinc calculation only when its constant-amplitude,
flat-spectrum segment assumptions apply; use verified numerical integration for
varying complex responses. Define whether a result is an integrated band level
or a normalized transfer quantity. Display resolution must not change the
physical answer for the same inputs.

Mechanical tilt/splay change element positions and orientations. Electronic
steering changes delays. Maintain complex phase until system summation completes.
Define acoustic-centre and near-field validity per model; generic point emitters
cannot certify real cabinet coupling or near-field array behavior.

### Geometry and results

Use metres internally, Y-up and a documented forward/yaw/tilt convention. Full
3D orientation should include roll in the domain model. Store geometry once;
derive source poses, acoustic surfaces and render meshes from it.

Each audience surface owns its receiver mesh, height/ear offset, triangles and
area weights. Bound sampling by the actual surface. Never merge stacked surfaces
by plan coordinates. Probes are independent of area grids. Acoustic boundaries
and visual/reference meshes are distinct roles. Begin with explicit free-field
prediction and separately selectable, validated ground reflection.

Results carry scene revision, model hashes, engine/schema version, physical
settings, sample coordinates, validity flags and calculation quality. A changed
project invalidates the result; an old map can be shown only with its stale
status and original settings. Reports serialize result provenance.

## Milestones and acceptance gates

These are dependency-ordered milestones. Re-estimate calendar effort after M0/M1
using observed migration and device performance. Data acquisition and external
validation run in parallel and may control the release date. The old fixed
22-month MVP estimate is retired; it mixed independent products and omitted
the data/validation dependencies.

### M0 Establish a reproducible baseline and iPad feasibility

1. Preserve current CLF work and record baseline tests/builds and known failures.
2. Create common domain/acoustic fixtures and start the KMP module boundary.
3. Build the same small acoustic fixture for Android/JVM and iOS/Kotlin Native.
4. Prove an iPad screen containing a Filament scene, camera gestures, selection,
   two receiver-surface heatmaps and a shared control. Verify suspend/resume and
   resource cleanup on a real device before selecting the shared-UI approach.
5. Record supported tablet/OS/toolchain matrix, license file status and reproducible
   dependency setup. Centralize shared versions and establish the cross-platform
   CI matrix as targets become available. Retain current Android behavior during
   the experiment.

**Android/core exit:** reproducible Android baseline and shared module boundary
with passing JVM fixtures. **Parallel iPad gate:** the same numerical fixture
passes on Kotlin Native and rendering integration/costs are demonstrated. Keep
Android/data/solver corrections moving while this platform gate is completed;
retain shared-core boundaries if iPad delivery needs a later date.

### M1 Fix data and project trust

1. **Partially implemented:** shared reference-drive/distance records, per-band
   response, angular seam interpolation, explicit measurement-grid lookup and
   missing-band propagation are integrated. Verify each importer's electrical
   normalization and add capability/provenance certification and complex transfer
   support. Keep inferred CF1 calibration experimental until verified.
2. **Partially implemented:** decoded assets are persisted separately with hashes
   and format versions; portable JSON can embed them. Retain original source files,
   add asset lifecycle/garbage collection and verify importer provenance. Support
   TAB and a documented tested binary subset; reject unsupported variants clearly.
   Replace optimistic parser labels with capability/provenance records.
3. Parse bounded inputs in the background; validate before atomic import.
4. **Partially implemented:** schema-17 scene/asset manifest with audience cutouts/elevations/breaklines/imported meshes, portable embedded JSON,
   atomic local checkpoints, backup recovery and visible missing-asset states.
   Finish the shared typed scene model, complete migration fixtures and define
   packaged interchange/sharing controls. Respect each asset's sharing permissions;
   external assets retain stable references and a visible missing-data state.
5. **Partially implemented:** immutable shared listener/coverage requests/results,
   revision/sequence publication gates, stale-state UI, invalidated report suppression
   and controlled concurrency tests. Shared audience preprocessing and obstruction
   evaluation are implemented; finish immutable auxiliary models and expand report
   provenance to full solver/input hashes.
   Remove STI/ALcons claims from live-sound output.

**Exit:** import a measured speaker, save, kill/restart, reopen on a clean install,
and obtain identical source data and predictions. Missing assets are visible.
Changing frequency in manual mode cannot relabel an old result. Old project
fixtures migrate without losing speaker references or geometry.

### M2 Integrate and validate one direct-field engine

1. Establish drive/spectrum/band semantics and source coherence groups.
2. Add measured radiator adapters, full coordinate transforms and explicit
   unavailable-band handling. Validate interpolation on asymmetric balloons.
3. Integrate corrected candidate atmosphere/direct-field code through a
   `PredictionEngine` interface used by probes, maps and reports.
4. Replace legacy heuristic summation and unconditional ground correction.
5. **Partially implemented:** gain/delay/polarity plus validated low-pass, high-pass and peaking
   biquad chains now affect band coverage and valid phase traces. Explicit version-2 complex
   band interpolation is integrated for direct/specular paths. Independent mixed data and complex-source diffuse energy are now supported. Broader filter
   families, measured cross-angle scattering matrices and explicit input spectra remain open. Independent per-band
   gain offsets remain magnitude-only.
6. Remove superseded prediction paths after migration tests pass. Keep a
   deterministic CPU reference for future acceleration checks.

**Exit:** analytical spreading, pressure/power addition, delay/polarity, rotated
directivity, air loss, bandwidth and input-spectrum fixtures pass through the
actual Android app prediction path. No undisclosed synthetic fallback occurs.
**Parallel iPad gate:** shared-core fixtures agree with Android within declared
numerical tolerances before iPad prediction is accepted; this does not block
Android corrections while the iPad toolchain/integration is being established.

### M3 Make audience coverage truly three dimensional

1. **Implemented for authored height fields and imported 3D meshes:** shared
   triangulation, boundary/interior elevation controls, concavity, rake, ear height,
   full venue preview and independent stacked surfaces, including interior holes
   and unioned boundary-crossing exclusions. Explicit breaklines now preserve ridges
   and slope changes. Triangulated OBJ surfaces preserve imported faces and holes,
   including vertical faces, overhangs and closed meshes. Per-face audience roles and
   exclusions now preserve original topology, role-specific receivers and group statistics.
   Explicit independent acoustic boundary roles now support opaque occlusion and finite
   rigid first-order reflections. Broader venue import remains deferred.
2. **Implemented:** surface IDs/topology reach Android heatmaps and shared contour
   generation; floor and balcony levels at identical x/z stay independent.
3. **Implemented:** independent named probes alongside area maps; shared physical-area
   mean/deviation, continuous area percentiles, target coverage and unavailable-area
   accounting; per-surface Android summaries and reports with documented conventions.
   Configurable target tolerance and energy-average statistics remain future options.
4. **Partially implemented:** cancellable bounded preprocessing, revision-safe mesh
   and statistics publication, and bounded cross-request mesh caching. Add measured
   response caching, large-scene profiling, per-surface incremental reuse and explicit
   preview/final-result labels.
5. **Partially implemented:** triangular OBJ audience import with units, axes,
   topology validation and portable embedded geometry. Add scaled drawing/reference
   import, origin controls and a broader OBJ/glTF venue subset. Per-face audience
   assignments/exclusions, direct viewport face picking and named face groups are
   implemented, including OBJ group-name mapping and explicit acoustic boundary roles.
   Extend with calibrated boundary materials, diffraction and broader venue import.

**Exit:** a 60 m venue, a zone outside ±12 m, stacked floor/balcony, rotated/raked
zones and separate probes produce correctly located independent results.
Editing during calculation never publishes a result for an obsolete scene.

### M4 Complete live-sound design tools

1. **Partially implemented:** saved reference receivers, per-receiver reference speakers,
   geometric arrival differences and cross-receiver delay previews with explicit Apply/Undo.
   Combined gain/delay/polarity previews across saved receivers now include explicit Apply and
   scene Undo/Redo. Finish source routing/mute/solo/linked workflow validation and main/fill/delay
   roles; extend timing tools with validated complex-response alignment.
2. Build subwoofer helpers using actual positions and DSP, including broadside,
   gradient/cardioid and end-fire layouts. Evaluate rear rejection and useful
   audience coverage over frequency, rather than at one point alone.
3. Use one element-pose generator for line-array drawing and prediction. Add
   per-element splay/gain/delay and model-specific acoustic centres and constraints
   where verified data is available. Keep generic arrays clearly approximate.
4. **Partially implemented:** saved listener magnitude traces, A/B overlays and per-band
   differences with provenance, restorable design variants, coverage difference maps and
   save/reopen, multi-receiver capture and exact-tone phase plots for valid complex data.
   Extend complex propagation and add IR displays only with sufficient complex
   data; magnitude-only CLF must not invent intrinsic cabinet phase.
5. Validate a small supported speaker set and array configurations against
   independent calculations and controlled measurements. Use vendor predictions
   only when model data, drive and environment are available and comparable.

**Exit:** complete and compare a main-plus-fills-plus-delay design and subwoofer
layout; array-as-one-object agrees with the equivalent explicit-element scene;
supported real-model accuracy is documented with measured error and validity
bounds. A magnitude-only array preview does not establish vendor parity.

### M5 Ship a usable tablet beta and complete the iPad client

1. Finish named projects, Save As/Open, portable share, model attribution and
   deployment reports with geometry, inventory, DSP and result provenance.
2. Complete iPad controls/adapters using the M0 architecture decision. Verify
   touch, keyboard/pointer, file import/export, lifecycle and thermal behavior.
3. Run Android/iPad project interchange and numerical parity suites, plus
   real-device acceptance with representative large venues and arrays. Exercise
   older-project migrations and safe handling of newer unsupported capabilities
   when platform releases are staggered.
4. Profile before optimizing. Initial performance targets to validate: interactive
   camera at 30 fps or better; cancellation acknowledged within 200 ms; a 10,000
   receiver/32-emitter/one-third-octave final map within 5 s on each nominated
   baseline tablet. These are proposed targets, not current measurements. Record
   peak memory and a sustained ten-minute interaction run; revise budgets with evidence.
5. Set app versioning, restore the confirmed license/notices, and build release
   artifacts with smoke tests; resolve the referenced but missing ProGuard rules
   file. Maintain separate platform readiness if Android and iPad completion
   dates differ.

**Exit:** a sound engineer can build, evaluate, save, reopen, exchange and report
a complete live-sound design offline on each platform claimed as supported.

### M6 Add constrained optimization and advanced acoustics

Enable optimization only when it calls the same validated solver, changes real
delay/phase, and has level/headroom/coverage constraints. A variance-only objective
must not win by making the system quieter. Provide before/after preview, explicit
acceptance, and undo. Rename the current optimizer to avoid implying it implements
d&b ArrayCalc's proprietary behavior.

Ground/early reflections beyond the initial model, room metrics, STI, manufacturer
processing and structural/rigging support each need separate requirements,
datasets and validation plans. They are not prerequisites for the live-sound beta.

## Validation matrix

| Layer | Required evidence |
|---|---|
| Analytical maths | −6.0206 dB per distance doubling with air disabled; +6.0206 dB equal coherent pair; +3.0103 dB equal independent pair; expected delay/null positions |
| Spectrum and phase | Fixed input spectrum under changing display bandwidth; energy integration/weighting; phase sign/reference delay; filter complex response; independent signal groups |
| Geometry | Rotation invariance; tilted off-axis asymmetric directivity; element pose equivalence; actual 3D path distances; stacked surfaces and ear offsets |
| Speaker import | Small versioned redistributable TAB/binary fixtures; invalid/truncated inputs; seam/poles/symmetry; reference spectrum; unsupported variants and bands |
| Persistence | Cold-start measured-model recovery; missing assets; old-schema migration; atomic failed import; portable project exchange |
| Application integration | Same solver for probes/maps/reports; manual stale mode; concurrent edits/cancellation; report metadata matches result inputs |
| Real models | Controlled measurements with calibration, coordinates, environment and drive; error by frequency/distance/angle; known model limits |
| Platform/performance | Shared core on JVM and Kotlin Native; Android/iPad viewport/storage tests; fixed scene benchmarks, memory, thermal and lifecycle behavior |

Set numerical tolerances before implementations. A proposed starting gate is
0.05 dB for simple analytical fixtures and platform parity outside deep nulls;
use pressure/absolute error near cancellation. Measurement acceptance needs
its own uncertainty budget and per-model criteria. Unit-test tolerance is not
real-world acoustic accuracy.

## Original first implementation slice (historical; use V0–V4 above)

The original baseline slice was: **one imported measured point source, one audience surface, one virtual
microphone, saved/reopened with identical data, calculated by the shared engine
and rendered with explicit result provenance**. Add a second delayed source and
a stacked balcony next. Run the same numerical fixture on iOS early. This tests
the architecture before expanding the speaker library, optimizer or interface.
