# Implementation history

Completed-batch records archived from the plan on 2026-11-26. These record implementation and
scoped checks, not manufacturer equivalence or release qualification. Use the
[current plan](implementation_plan.md) for priorities and remaining gates.

## Implementation progress

### Clear Band/Broadband controls and calculation status (2026-11-25)

Viewport controls now expose Band and Broadband SPL map modes beside the frequency buttons.
Band labels identify the coverage centre frequency. Frequency buttons appear only in Band mode;
Broadband SPL hides the frequency row. Explanatory paragraphs have been removed from the viewport;
its diagnostic inspection frequency for aiming rays/angular support remains in Settings, clearly
separate from broadband coverage. Settings uses the same mode names; the Band
convention label includes Hz. Switching modes preserves the previously selected frequency.
The legacy persisted `SPECTRUM` value is retained for compatibility, but the user-facing map
label is Broadband SPL. The manufacturer documentation review distinguishes a summed map
from a spectrum/frequency-response graph; see the retained workflow notes. A graph displays
levels separately across frequency. Review existing receiver-trace entry points for this use case
before adding another analysis mode.

One Auto/Manual toggle sits beside Calculate/Recalculate in the top bar. Calculate stays visible
in both modes, and repeat submission is disabled during calculation. The map panel contains only
the mode title, Band/Broadband selector and Band frequency buttons. The choice remains available
in Settings under Calculation. Toggling it explicitly triggers
autosave even when no scene revision changes, preserving a Manual choice after reopening.

A shared presentation contract provides consistent calculation status and action semantics.
The analysis strip shows recalculation-required, automatic-pending, running, current and failure
states; the top action highlights when calculation is needed. Manual stale results highlight Recalculate; active calculations disable repeat
submission, and automatic-mode failures expose Retry calculation. Existing revision gating and
clearing of outdated values remain in force. Reopening still recalculates once, including in manual
mode, and shows its running state. No physics or persistence format changed.

Validation: three shared presentation tests, five Android mode/recovery integration tests and
seven existing concurrency regressions passed (15 total); Android debug build passed.
[Retained evidence](../validation/coverage-controls/README.md) covers actual per-band versus
broadband request output, manual/automatic edits, save/reopen and late-result rejection.
OnePlus OPD2415 [device acceptance](../validation/device/coverage-controls-2026-11-25/README.md)
passed for the final compact layout: one toggle beside Calculate, explicit Manual recalculation,
Auto updates, hidden Broadband frequencies, returning to Band and both saved calculation modes.
Removed a lingering calculation-start snackbar so it cannot contradict completed live status.
The original project was restored with all nine files byte-identical before launch; reopened scene
fields and imported assets matched. The final debug build remains installed. Validation-first
physical accuracy gates are unchanged.

### Receiver viewport device acceptance and recovery cleanup (2026-11-25)

Tested the synthetic receiver scene on the OnePlus OPD2415 in Plan, Section and 3D:
framing, single-pointer camera drags, returning to the receiver frame, context widening,
selection persistence and save/reopen. Coincident marker taps switched stable receiver IDs
without moving the pinned frame target. Fit to venue cleared the saved focus. Retained
[device evidence](../validation/device/receiver-framing-2026-11-25/README.md) records the scope.

Fixed overlapping receiver/conventions panels by stacking focused controls; explicitly sized
native overlays, invalidated receiver labels when diagnostics complete, and corrected TextPaint
density shadowing so labels respect display/text scaling. Shared project validation now accepts
the null source ID emitted for unassigned reference receivers, while rejecting invalid required
numbers. A checkpoint save/reopen regression covers this recovery case.

Validation: 596 shared JVM tests and five focused Android recovery tests passed; the final
Android debug APK built and was installed. Original app data was restored with all nine files
byte-for-byte verified before launch; after reopening, original scene fields (except the schema
version upgrade) and imported assets matched. Multi-touch pinch, device resize/rotation and
full device Undo/Redo acceptance remain separate checks; these were not exercised here.
Next: reflected/diffuse-path angular support under the validation-first scope, with the legacy
extrapolation policy reviewed separately. Physical accuracy acceptance remains open.

### Receiver-specific camera framing in all views (2026-11-20)

Shared [receiver framing](../validation/receiver-framing/README.md) computes canonical Plan,
Section and 3D camera poses around a stable receiver ID. Aspect ratio, measured panel obstructions,
context span, near/far clipping and Float projection are checked; all context corners must fit the
usable viewport. Labels use the same safe region. Invalid or obstructed frames leave the prior
camera unchanged with guidance. Android applies the pose to its existing manipulator and retains
manual gestures. Frame selected collapses the detail pane; Closer/Wider adjust 2–64 m context and
Frame all returns to venue framing.

Versioned optional intent (receiver/view/span) survives save/reopen, view changes and viewport
resize; exact manual orbit/pan poses are not saved. Stale/deleted IDs clear focus, deletion Undo/Redo
restores it and malformed intent fails atomically. Display actions neither advance acoustic revision
nor alter receiver/source/alignment data. Trace and adjustment-preview input identity exclude these
visual fields, so framing does not invalidate an otherwise current DSP preview.

Retained tests cover three views and two aspect ratios, panel/label bounds, clipping, translated or
raised targets, context sizing and intent reconstruction, plus Android persistence and compatibility.
Next: on-device framing/gesture acceptance, then reflected/diffuse-path support and the separately
reviewed legacy extrapolation policy. Physical validation acceptance remains open.


### Direct receiver-marker selection and labels (2026-11-19)

Shared [receiver marker layout/picking](../validation/receiver-markers/README.md) projects receivers
with the production camera frame, rejects clipped targets, fans labels apart and cycles coincident
marker-centre picks. Stable ID prefixes distinguish duplicate names/positions. Native Android paints
labels and routes Select-tool taps through the existing gesture detector; speaker handles retain
priority, and placement/face tools and camera gestures keep their existing routing. A white halo and
label border identify the selected receiver; the viewport and support panel share selection.

Optional project selection survives save/reopen and autosave without acoustic revision/recalculation.
It does not change alignment settings. Deleted/stale IDs reconcile to the listener; deletion Undo/Redo
restores selection. Listener selection remains the legacy default and is omitted from serialization.
Layout/hit testing and highlight geometry are shared; native code owns text measurement and painting.
Crowding may omit nonselected labels; screen annotations do not assert mesh visibility or occlusion.

Retained geometry cases cover all camera directions, label picking, coincident identities, clipping,
dense collisions and density scaling. Android recovery and existing speaker/trace regressions are
checked. Acoustic comparisons and original failed accuracy screens remain unchanged. Next: device
visual/gesture acceptance and receiver-specific framing, then per-path reflected/diffuse support and
the separately reviewed legacy extrapolation policy.


### Receiver-level angular support and origin overlays (2026-11-19)

Shared [receiver diagnostics](../validation/receiver-angular-support/README.md) use immutable response
snapshots and the exact production source/element mechanical frames. They retain local directions,
source IDs, array indices, frequency cells, sampling support and declared contributing origins.
Magnitude interpolation/gap rules and complex-band endpoint coverage remain distinct. Electronic
steering does not rotate element frames; missing/generic/disabled data cannot become a measured pass.

Android adds world-space support/origin diamonds for the listener and saved reference receivers,
plus selectable detail rows in a viewport panel. Three orthogonal marker planes support all existing
camera presets. The overlay covers the selected band only, uses committed scene geometry and
assesses direct rays only; reflections/scattering, energy weighting and total SPL/phase accuracy are
not inferred. Source/receiver movement cancels or rejects outdated work, and previous-revision
markers are cleared. Visibility is saved/autosaved independently of acoustic recalculation and is
excluded from trace-input identity. Restoring a project recomputes diagnostics; no migration is needed.

Retained real-data tests inspect original/upsampled source storage at actual held-out receiver
coordinates. Original physical accuracy failures remain unchanged. Recovery, immutable inputs,
mechanical/electronic distinctions, missing data, bounded work and native marker structure are tested.
Next: direct receiver-marker picking/labels, per-path reflection/scattering support and a reviewed
compatibility policy before refusing unsupported legacy extrapolation. Manufacturer-software,
independent-session/full-sphere and on-device visual acceptance remain open.


### Explicit measured, symmetry and interpolated angular domains (2026-11-19)

Shared [angular-domain declarations](../validation/angular-domains/README.md) add optional per-pattern
MEASURED/SYMMETRY/INTERPOLATED provenance, separately for magnitude and complex data. Regions
select existing knots only; overlap, absent selectors, unsupported methods and symmetry chains/cycles
are rejected. Symmetry must resolve directly to declared measured source knots. Legacy files remain
UNDECLARED, with unchanged serialized identity. Declarations are versioned asset metadata, not
response generation or authenticated acquisition evidence.

Shared queries distinguish stored origin, runtime interpolation and contributor origins. Schema-2
angular-support reports and Android's existing warnings disclose undeclared, symmetry-derived and
pre-interpolated storage; stored-knot/pole labels no longer imply measured acquisition. JSON import
and project recovery preserve declarations and their asset revision; no migration or new editor is
introduced. Prediction/interpolation/refusal behavior remains unchanged.

Retained PRX635 tests annotate known training angles and upsample storage from training data only.
The added 5° points remain INTERPOLATED, never relabelled measured; all original held-out errors
and failed acceptance screens remain unchanged. Synthetic magnitude/complex declarations test
save/reopen, stable hashes, warnings, levels and invalid-import atomicity. Next: receiver-level support
and origin overlays, then a reviewed compatibility policy before rejecting legacy extrapolation.
Independent-session, full-sphere and manufacturer prediction-software acceptance remain open.


### Angular sampling support and sparse-data warnings (2026-11-18)

Shared [angular-support diagnostics](../validation/angular-support/README.md) report real axis
spacing, sampled elevation bounds, singleton axes, possible missing azimuth sectors and per-direction
measured/interpolated/sparse/unsupported statuses. Magnitude and complex grids remain separate.
The 5° spacing advisory is explicitly not an accuracy guarantee; possible missing sectors use a
published heuristic. Android reuses its existing warning panel and exports the shared metadata.
No propagation values, speaker identities, interpolation rules or project schema are changed.

Tests exercise seam wrapping, endpoints, nonuniform axes, poles, limited elevations, invalid inputs,
complex/magnitude distinctions, persisted assets and actual held-out PRX635 directions. Both retained
10°/20° measurement screens remain FAILED, with unchanged RMS/max errors. Warnings disclose
limitations rather than correcting or certifying the data. Existing single-column azimuth repetition
and interpolation across large gaps remain available, with explicit warnings. Outside-elevation
queries already return unavailable. Android warnings are asset-level, not a receiver overlay.
The focused final run passed 64 tests (40 shared, 24 Android), Android debug build and iOS shared-test
compilation. Disk reopen preserves warnings, report metadata, asset hash and levels; the complete
public-measurement comparison report remains byte-for-byte unchanged. On-device visual checks
were not run.

Next: explicit measured/symmetry/interpolated angular-domain declarations, receiver-level support
visualization and a reviewed migration policy before refusing legacy azimuth extrapolation. Obtain
denser independent data for finer-grid validation; do not consume these fixed held-out samples.


### Public calibrated PA measurement comparisons (2026-11-18)

The [public-measurement package](../validation/public-measurements/README.md) retains licensed,
unchanged Princeton 3D3A PRX635 horizontal IR data, calibration/setup provenance, independent
Python preprocessing, disjoint 20°/10° training assets and 72 held-out measurements per model.
Production shared-core scene capture is compared against the real measured per-band on-axis ratios,
with independent interpolation replay as a separate numerical check. This is a one-session angular
holdout, not manufacturer-software equivalence, full 3D validation or independent-session evidence.

Both predeclared accuracy screens fail: 20° RMS/max 0.615063/2.615989 dB; 10° RMS/max
0.500084/2.655764 dB. Finer data improve RMS but do not eliminate worst-case error. Do not tune
acceptance limits or silently mark V2 complete. Numerical replay agrees within 0.000001 dB.
Original/raw and reviewed protocols, residuals, portable import pairs and model assumptions are
retained. Shared protocol annotations require pinned original hashes/values and reasons, preserve
original envelopes and cannot alter samples, calibration, drive or level metric. They are declarations,
not certificates. The focused run passed 59 automated tests (36 shared, 23 Android), including
import/Undo/Redo/save/reopen, plus Android debug build and iOS shared-test compilation. Both real
accuracy screens remain FAILED; passing regression tests does not change those verdicts.

Next: honest sparse-data limits and denser independent angular data for finer-grid tests (do not
reuse these fixed held-out directions as training), followed by independently sourced
same-model/preset measurements and actual manufacturer prediction exports. Unknown electrical drive,
original system preset and atmosphere restrict this case to equal-radius relative magnitude. Full
sphere, absolute SPL, phase, arrays, venue propagation and OnePlus visual acceptance remain open.


### Validation workflow: protocol review, residuals and reports (2026-11-18)

Shared [validation workflow](../validation/validation-workflow/README.md) produces protocol field
and structured-leaf differences, explicit missing/moved/unavailable sample statuses, signed residuals,
RMS/bias/max error and normalized plot data. Android adds paginated review, sample-order and
per-receiver log-frequency residual plots, plus standalone JSON export through the document picker.
Export freezes the selected workspace, includes original envelopes, hashes and model limitations,
and can be reconstructed from its inputs. Derived diagnostics reproduce after project reopen;
existing persisted documents require no archive migration. Synthetic/incompatible comparisons stay
unqualified even when diagnostic residuals are small. Strict matching is preserved, including sample
order and raw protocol encoding, with finite-residual overflow rejected explicitly.

The focused run passed 54 tests (32 shared, 22 Android), Android debug assembly and iOS simulator
shared-test compilation. Tests cover diagnostics, metric math, original-input reconstruction,
Undo/Redo and disk save/reopen. Retained synthetic reports illustrate the workflow; they are not external validation.
Next V2 gate: obtain a permitted, independently sourced manufacturer export or calibrated measurement,
record its full matched protocol and justify tolerances, then retain and review the resulting report.
Actual manufacturer agreement, OnePlus visual/export acceptance and professional accuracy remain
unverified by this batch; shared iOS compilation is not a delivered iPad interface.


### Explicit source-spectrum and total-drive normalization (2026-11-18)

Shared [source-spectrum capture](../validation/source-spectrum/README.md) adds legacy per-band,
fixed-total equal-band, flat-per-Hz PSD and normalized custom band-power profiles. Resolved fractions
allocate mean-square model input across the actual frequency bands, before receiver weighting.
They apply as one common excitation profile across existing emitters; they do not distribute a
single power budget among all speakers or renormalize after EQ/filter/taper. Watts and mean-square
voltage retain their distinct calibration meanings, with no inferred impedance conversion.

Fractions conserve total drive; custom keys/finite powers/positive total and declared fractions are
validated. Explicit zero bands contribute zero without requiring unavailable response or diffuse
work. Missing active bands remain unavailable and never redistribute their share. Allocated band
powers, source recipe and fractions are retained for absolute and relative weighted snapshots.
Eight equal-power bands are 9.03089987 dB below the legacy full-reference-per-band sum.

Android exposes comparison-only preset/custom controls and identifies current/reference/captured
excitation separately. Coverage maps and ordinary listener predictions preserve previous behavior.
Profile edits participate in history/revision guards; original snapshots remain frozen. Import
version 5 carries total-drive spectrum semantics; legacy version-3/4 per-band definitions remain
unchanged. Scene schema 30 preserves preset/custom state and snapshots through Undo/Redo and disk
save/reopen, with legacy defaults for older projects.

Eighteen independent absolute references and corresponding relative ratios, full snapshots and
residuals are retained. [Results](../validation/source-spectrum/results/summary.md): **71 JUnit tests
pass (32 shared-core, 39 Android; zero failures/skips)** plus **6 Python packaging checks**.
Eighteen absolute totals agree within **1.08902185048e-08 dB** (1e-5 dB limit), and eighteen relative ratios
within **3.02684100006e-09 dB** (1e-7 dB limit). Conservation, explicit zeros/missing active bands, invalid
allocation rejection, ordinary-prediction isolation, preset/custom recovery and stale-profile
rejection pass. Android debug assembly and iOS simulator shared-test compilation pass. No device
installation, on-device UI acceptance or numerical iOS execution was performed. No genuine export
or measurement is newly available; physical validation remains pending. Next: acquire actual
excitation/reference-drive records and matched data, verify band PSD and processing independently,
and only then consider per-source spectra, alternative within-band shapes or physical headroom.


### Relative weighted broadband normalization (2026-11-18)

Shared [relative weighted-total capture](../validation/relative-broadband/README.md) now normalizes
one actual weighted broadband total to an explicit reference receiver's weighted total. Both use
the same immutable scene, coefficients, frequency cells and per-band drive convention. This is a
ratio of weighted sums, not averaged band dB ratios or a fitted offset. A/C/Z labels, ranges, method,
reference coordinates and safety floor are part of canonical matching conditions.

Missing required numerator/reference bands cannot produce a partial aggregate; invalid, zero or
below-threshold reference totals leave normalized levels unavailable. Raw numerator/reference band
powers and the weighted reference level/validity flag are retained. The threshold is applied to the
weighted total, without clipping numerators. No on-axis/array-centroid inference is made.

Comparison-import version 4 combines an explicit broadband definition with the weighted-total
normalization method and distinct relative A/C/Z units. Older per-frequency/absolute methods remain
supported separately. Scene schema 29 preserves original packages and diagnostics through Undo/Redo
and disk save/reopen and prevents older readers ignoring new semantics. Existing revision/sequence/
reference guards reject scene, weighting or imported-basis changes during capture. Android uses the
current scene's weighting and displays the imported basis rather than silently changing settings.

Independent weighted-total reference ratios, synthetic 0/+12 dB captures and raw residuals are
retained. [Results](../validation/relative-broadband/results/summary.md): **74 JUnit tests pass
(36 shared-core, 38 Android; zero failures/skips)** plus **5 Python packaging tests**.
Twelve synthetic A/C/Z captures at 0/+12 dB agree with six independent ratios within
**5.27251131643e-10 dB** (1e-7 dB bound). Weighted-sum versus band-ratio semantics, invalid/missing
denominators, protocol refusal, stale weighting edits, Undo/Redo and disk save/fresh reopen pass.
Android debug assembly and iOS simulator shared-test compilation pass. No on-device acceptance
or numerical iOS execution was performed. No new real export or
measurement is available, so the physical-validation gate stays pending. Next: review actual external
weighted normalization, excitation, band edges and reference/noise controls, retain matched residuals,
and qualify those exact protocols before extending spectrum assumptions or claiming meter equivalence.


### Weighted broadband engine snapshots (2026-11-18)

Shared [broadband capture](../validation/broadband-capture/README.md) now computes the current
spectrum's weighted sum of independent octave-band powers at imported receiver targets. A/C/Z
coefficient tables move to the shared core with their existing Android values. Actual band cells,
width, coefficients, source/DSP/geometry and per-band drive convention are independently derived
from the immutable scene; imported weighting/range cannot configure or relabel engine output.
Raw per-band powers and aggregate levels are retained without a display floor. Any missing required
band leaves the aggregate unavailable, even if its weight is small.

Comparison-import version 3 adds a mandatory canonical broadband definition and explicit aggregate
sample marker (frequency 0 with aggregate units), distinct from per-frequency/relative data.
Weighting/range/drive definitions must match; unsupported or contradictory metrics, overlapping
fractional bands and unknown electrical references cannot qualify comparisons. Initial scope is the
existing finite octave-sum spectrum convention, not a continuous/full-range calibrated meter or a
fixed-overall-drive spectrum. Weighted relative normalization is not inferred.

Android requires Spectrum mode for broadband capture, shows reference/engine weighting, and retains
existing cancellation/stale-publication checks including weighting edits. Undo/Redo, original
packages and raw diagnostics persist through disk save/reopen. Scene schema 28 protects the new
semantics while loading older projects. Capture retains required band powers without generating
unused phase traces; normal acoustic summation and existing weighting values are unchanged.

Six independent scalar A/C/Z references, synthetic snapshots and per-band residuals are retained.
[Retained results](../validation/broadband-capture/results/summary.md): **74 JUnit tests pass
(37 shared-core, 37 Android; zero failures/skips)** plus **4 Python packaging checks**.
Six independent aggregate values agree within **7.65723484619e-09 dB** (1e-5 dB bound). Missing-band,
metric rejection, gain, stale weighting edits and original weighting regressions pass. Original
snapshots and diagnostics survive Undo/Redo and disk save/fresh reopen. Android debug assembly and
iOS simulator shared-test compilation pass. No on-device acceptance or numerical iOS execution
was performed. No real external broadband artifact is
newly available; physical validation remains pending. Next: review real export excitation, actual
band edges, PSD/drive and weighting implementation before matching, then retain measured/vendor
residuals. Add alternative source-spectrum or relative-broadband conventions only as separately
specified and validated metrics.


### Explicit relative-response normalization (2026-11-18)

Shared [relative-response capture](../validation/relative-response/README.md) now divides total
field power by an explicitly requested per-frequency reference receiver, using the same frozen
scene and production band calculation for both. RELATIVE_TO_REFERENCE supports general systems;
RELATIVE_TO_ON_AXIS additionally verifies one physical emitter and the mechanical axis instead of
guessing an array axis. There is no fitted offset, distance correction or implicit normalization of
external absolute exports. The basis, safety threshold and raw per-frequency denominator diagnostics
are retained and included in canonical matching conditions.

Missing/nonpositive/non-finite or below-threshold reference power makes that frequency unavailable;
missing source bands never borrow a denominator. Numerators remain unfloored. Common gain cancels
above the safety threshold, while processing/geometry/calibration protocol differences still block
matching. Android reads the explicit imported basis, displays it, captures through shared logic and
retains results with existing stale-publication guards and Undo/Redo.

Comparison-import version 2 adds the mandatory normalization contract. Absolute version-1 captures
are unchanged. Old relative documents remain readable but unqualified without an explicit basis.
Scene schema 27 preserves normalization/diagnostics and prevents older apps from ignoring the new
semantics; older scenes continue to open. The CSV packager supports both envelope versions.

Independent point-source ratio references, synthetic snapshots/residuals and safety/recovery tests
are retained. [Results](../validation/relative-response/results/summary.md): **53 JUnit tests pass
(34 shared-core, 19 Android; zero failures/skips)** plus **3 Python packaging tests**.
Four independent relative values and +12 dB captures agree within **2.58675747489e-10 dB** (1e-7 dB bound).
A separate equal-distance −6 dB off-axis case passes; denominator invalidity, protocol rejection,
Undo/Redo, stale-result handling and disk save/fresh reopen pass. The earlier 18 numerical fixtures,
Android debug build and iOS simulator shared-test compilation pass. No on-device UI acceptance,
physical measurement or numerical iOS execution was performed. No genuine external
artifact or measurement is newly available; external physical validation stays pending. Next: use
reviewed real relative exports, inspect their actual normalization/noise-floor conventions, and
retain matched residuals before expanding to weighted totals or more elaborate reference schemes.


### Current-scene engine snapshot capture for comparisons (2026-11-18)

The [engine-capture package](../validation/engine-capture/README.md) adds a shared immutable
request-to-comparison path. Imported references supply target IDs/coordinates/frequency labels;
source levels and protocol conditions come from actual scene inputs. Canonical model/data identity,
calibration/directivity, array/world geometry, steering, gain/delay/polarity/EQ/filters/taper,
summation, actual band segments, atmosphere, boundary/material fingerprint and global ground
settings are independently derived. Generic estimates stay synthetic; missing measured assets stay
unavailable. Raw power produces unfloored band dB; zero/missing/non-finite power stays null.

Android exposes explicit capture/copy controls, running/error messages and the existing comparison
summary. Capture prepares source inputs before background evaluation and applies only current
revision/sequence/reference results. Scene edits, new captures, replaced imports and clearing reject
late work. One applied snapshot creates one Undo checkpoint. Scene schema 26 already retains the
raw engine package through Undo/Redo and disk save/reopen; derived results are recomputed on restore.

Initial scope is absolute unweighted band capture, 512 targets/64 integer 25–20kHz labels maximum.
Actual bandwidth/data cells are recorded. Relative normalization and weighted spectrum totals are
not inferred. A common effective electrical drive is declared only when all source references agree;
otherwise absolute qualification remains blocked. Requested receiver coordinates are preserved with
an explicit checked 10-micrometre Float conversion bound. Canonical engine conditions do not overwrite
external protocol declarations; mismatches must be independently reviewed and mapped, not auto-fitted.

Retained independent point-source references and raw synthetic captures check level accuracy, +6 dB
response and package recovery. [Retained results](../validation/engine-capture/results/summary.md):
**81 JUnit tests pass (56 shared-core, 25 Android; zero failures/skips)**,
including the previous 18 numerical fixtures. The independent synthetic capture residual is
**5.17303533343e-10 dB**, within the predeclared 1e-5 dB numerical tolerance. Original packages survive
Undo/Redo and disk save/fresh reopen; changed/superseded/cleared captures cannot overwrite current
results. Android debug assembly and iOS simulator shared-test compilation pass. No device installation,
on-device UI acceptance or numerical iOS execution was performed. No real
external artifact or controlled measurement is newly available, and external-validation status remains
pending. Next: review actual manufacturer/measurement protocols against these derived records, retain
matched per-sample residuals, and add relative or multi-reference-drive capture only with explicit
normalization and acceptance evidence.


### Manufacturer-export / measurement import and matched snapshots (2026-11-17)

V2 now includes a shared [comparison import package](../validation/comparison-import/README.md).
Versioned envelopes retain original UTF-8 CSV tables or normalized JSON samples, source/producer/
version declarations, SHA-256 identities, explicit units, complete comparison protocols and reference
acceptance tolerances. CSV mapping is explicit, with fixed-receiver or coordinate-column modes,
quoted fields/BOM/CRLF support, bounded sizes and rejection of ambiguity/non-finite data. Missing
levels stay unavailable. Measurement instrument/method/smoothing/window and calibration declarations
are preserved; unknown calibration cannot qualify comparisons. No source normalization or metadata
is silently inferred from vendor names, and proprietary binary/project formats are not decoded.

Android Settings exposes two import slots, comparison status/residuals and clear/report controls.
Parsing happens on a worker and atomic commits merge one prepared artifact into the current shared
workspace. The UI labels these as imported snapshots independent of current scene calculations.
Scene schema 26 preserves documents/results through disk save/reopen, recovery and scene Undo/Redo;
restore recomputes results from original documents rather than trusting persisted pass flags.
Older scenes open with empty imports; corrupted workspace documents fail before mutation.

A retained CSV packaging tool preserves original payload bytes/hash and explicit protocol metadata.
Synthetic manufacturer-shaped, measurement-shaped and engine examples verify ingestion and restore
but are barred from external-validation passes. [Retained results](../validation/comparison-import/results/summary.md):
**60 JUnit tests pass (31 shared-core, 29 Android; zero failures/skips)**,
plus **2 Python packaging checks**. The previous 18 numerical fixtures still pass. Android debug
assembly and iOS simulator shared-test compilation pass. No on-device UI acceptance or numerical
iOS execution was performed. No matched real software export or controlled measurement is newly available;
this batch supplies the ingestion/comparison workflow without changing the global pending status.

Next: capture/export this app's own immutable engine results against an explicitly reviewed import
protocol, with geometry/calibration/preset identity derived from the real scene rather than copied
from the reference. Then import real external artifacts, inspect every declared condition and retain
per-sample results before deciding whether any physical-accuracy acceptance gate has passed. Add
vendor-specific text adapters only from actual versioned examples and independently verified units.


### Speaker provenance, calibration declarations and matched data comparison (2026-11-17)

V2 is **partially implemented**. Shared speaker assets now retain declared origin, source
label/URL/hash, importer scope, operating preset and normalization notes. Empty legacy provenance
is omitted on serialization, preserving canonical old asset identities; nonempty metadata is part
of the exact recovered revision. Android imports capture binary-input or UTF-8 text hashes and
surface shared provenance/calibration warnings and report records. Manufacturer names and known
electrical units never automatically confer independently verified calibration.

A narrow explicit CLF 1 W/1 m normalization declaration is recognized without reapplying raw
measurement distance/voltage. Other declarations and binary normalization remain unknown. Shared
matched-comparison gates require complete identical operating conditions, artifact identities and
all sample IDs/frequencies/coordinates, with explicit absolute drive and no fitted offsets or
volts/watts guessing. Invalid/missing data or synthetic references cannot become validation passes.

The retained [speaker-data package](../validation/speaker-data/README.md) pins a matched Martin
Audio XD12 TAB/CF2 pair, freshly verified byte-for-byte against the official public XD archive.
An independent Python script reconstructs 36 relative-direction samples and 27 bands in each of
the two reference rows. The shared comparator distinguishes DATA_CONSISTENCY_ONLY evidence from
manufacturer-software and measurement evidence. The TAB sensitivity and CF2 axial-spectrum rows
differ by up to 1.1 dB and are deliberately checked against their corresponding references.
TAB electrical normalization is file-declared; unresolved binary drive blocks absolute validation.
No complete third-party source data is redistributed. Download/extraction/hash verification scripts,
reference values and raw comparison results are retained; missing local assets produce NOT_RUN,
not a successful manufacturer-data check. CI uploads comparison reports when run.

Validation: **47 tests pass (15 shared-core, 32 Android; zero skipped)**, including all 18 numerical
fixtures and the real-file manufacturer-data test. Maximum directional residual is
**5.34057615909e-7 dB**; source-row decoding error is **3.05175781534e-6 dB**, both within predeclared
importer tolerances. [Retained results](../validation/speaker-data/results/summary.md) include raw
residuals and calibration refusal reasons. Android debug assembly and iOS simulator shared-test
compilation pass. No device installation or physical calibration was performed. No matching manufacturer prediction-software export or controlled
measurement was available: these release gates remain **pending**, and the global external-validation
status stays pending. Next: acquire and retain a matched export/measurement with actual speaker/data
revision, electrical drive, processing preset, geometry, bandwidth/level convention, atmosphere,
ground/summation settings and predeclared tolerances. Verify absolute normalization against that
independent evidence before claiming manufacturer prediction accuracy or expanding headroom models.


### Reproducible numerical validation baseline, revision 1 (2026-11-17)

V1 now has a retained [validation package](../validation/numerical/README.md): 18 versioned
fixtures, independent standard-library Python equations/quadrature, raw reference results and
shared-core engine evaluation. A JVM adapter performs file I/O and emits comparison reports;
the evaluation harness stays in commonTest and compiles for iOS simulator tests. No production
solver, Android calculation path or project-save format changes are introduced.

Fourteen numerical fixtures cover spreading/full-path air loss, real/imaginary phase, delay,
polarity cancellation, an asymmetric eight-emitter sum, rectangular-band interference, ideal
reflecting/absorbing ground and frequency refinement. Four separately labelled limitation cases
record finite-mirror clipping and binary barrier shadows; their agreement is not physical validation
of edge diffraction. Tolerances, units, source normalization and phase convention are explicit.
Nulls use incoherent-path power normalization rather than division by tiny received power.

The reference script retains exact pair-integral results plus four quadrature resolutions. A separate
continuous-frequency air-loss calculation uses 65,536 samples, checked against four lower resolutions.
Engine segment refinement uses 1/4/16/64/256 segments. All final errors must meet predeclared bounds
and improve at least 100× over the first grid unless already at roundoff; intermediate errors need
not decrease monotonically. The magnitude-only centre-amplitude approximation is documented.

The package checks fixture/reference identity, hashes fixtures/reference script/shared Kotlin sources,
and retains runtime/platform and raw per-case comparisons. Tests write fresh engine reports under
build/reports without overwriting the reviewed snapshot. CI verifies reference reproducibility,
runs the tests and uploads the numerical report plus shared/Android JUnit XML.

Validation: **18/18 fixture comparisons pass** in two package JUnit tests, and all independent
reference reproducibility/convergence checks pass. Maximum normalized engine power error is
**1.24494143349e-6**, within the predeclared bound. Wideband refinement improves from
**0.00160430708** (one segment) to **1.24494143349e-6** (256). The
[reviewed results](../validation/numerical/results/summary.md) retain every case and raw refinement
values. The **16 focused Android regressions**, debug APK assembly and iOS simulator shared-test
compilation pass. No device installation or numerical execution on iOS was performed.

This numerical baseline does not qualify manufacturer data, near-field cabinet coupling, measured directivity,
DSP/material/diffuse physics or real-world prediction tolerances. Existing regressions remain in
place; extend the retained independent reference set as these capabilities are qualified. The next
release gate is V2: data provenance/calibration and matched manufacturer/controlled-measurement
comparisons, with conditions and acceptable errors declared before collecting results.


### Validation-first model transparency cleanup (2026-11-17)

Implemented V0: the shared `PredictionModelScope` defines first-order capability and explicit
limits (geometric shadows, clipped reflectors, no room metrics/headroom and pending external
validation). Android settings, expanded results and exported project reports use this contract.
Project reports move to version 2 because unsupported room-metric fields are removed; scene-save
schema and old project settings remain compatible. Reports state the effective reflection order.
Removed the hidden legacy reflection job and automatic guessed-room RT60/heuristic intelligibility
publication. Removed those metrics from the results strip and report fields. Legacy helper routines
remain isolated for historical regression tests; they are not a production propagation path.

Reflection-order selection is replaced by a first-order readout. Fast/Balanced/Precision settings
retain their persisted identifiers but display their actual 3/2/1 m coverage-grid spacing. Global
surface controls expose only the optional ground absorption; wall/ceiling settings and legacy order
remain readable in old projects. Blocks/imported faces retain their explicit roles and materials.
The legacy optimizer cannot run or apply DSP settings until its shared-model replacement is validated.

Validation: **70 focused tests pass (54 shared-core, 16 Android)**, including five new cleanup
regressions plus direct-field/array/ground/boundary, stale-result and scene round-trip checks.
Android debug APK assembly and iOS simulator shared-core compilation pass. Tests confirm old
reflection-order/material fields reopen without altering effective model order or predicted levels,
unsupported metrics remain absent from successful predictions/reports, and optimizer calls leave
existing DSP delays unchanged. No device installation or manual UI acceptance was performed in
this batch. No manufacturer or field validation is claimed by this cleanup.


### Full-venue diffuse phase/refinement optimization and 4/8 kHz limits (2026-11-17)

Shared-core exact-tone illumination now prepares correlation groups once per surface/chunk and
uses checked eight-lane propagation-phase recurrence within at most 32 tones. Each chunk is
re-anchored; irregular or poorly rounded frequencies fall back to direct trigonometry. Complex
responses, DSP/material interpolation, Gauss samples/weights and convergence criteria are unchanged.
Scalar/dense references, near-null interference, independent groups, large common delays,
cache-enabled/disabled/saturated integration and cancellation checks pass.

Incident cache accounting now recognizes bounded interned visibility/loss arrays as shared rather
than charging a full array copy for each spectrum. The power payload stays at 32 MiB and key
accounting at 64 MiB, with conservative per-entry metadata charges and a 262,144-entry cap.
Unshared packed arrays are fully charged. Actual heap/map/registry overhead remains additional.
Refinement checks now use the same seven scalar probes/extrema directly, eliminating temporary
boxed level/edge lists for each emitter/band/node. Spatial and frequency accuracy limits are retained.

Validation: **949 tests pass (516 shared-core, 433 Android)**, both Android builds and iOS simulator
shared-core compilation. The 64-emitter, 333-receiver, 60 × 40 m, 1 kHz third-octave fixture retains
its **56.7142013963 dB** mean and exact save/reopen results. Dedicated computer first-calculation
observations were 92.37 s (phase batching), 87.01 s (cache accounting) and 74.80 s (refinement
allocation removal); build loads differed, so these are not isolated speedup ratios.

On the OnePlus optimized build, phase/cache changes alone observed 229.7 s, essentially unchanged
from 229.8 s. The final build completed within **162.4 s**, then **162.0 s** after cold reopen,
both displaying **56.7 dB**: roughly 29% below the earlier observation. These are individual
roughly five-second UI-poll completion bounds, not sustained thermal guarantees. A final-run memory
snapshot reported 248,058 KiB PSS, 384,876 KiB RSS and 191 KiB swap PSS (not peak memory).
This is an improvement but remains too slow for interactive iteration.

Final computer whole-venue checks still stop at unchanged limits: **4 kHz exceeds the work budget**;
**8 kHz exceeds 262,144 coherent illumination samples**. On the OnePlus, the same 4/8 kHz scenes displayed calculation failure within **96.3 s / 65.1 s**,
with no partial area average published; both app processes remained alive. These are failure
observations, not completed coverage timings or high-frequency qualification. The saved 8 kHz
project retained four arrays and its band selection. The validated debug build was restored,
all nine original saved files matched the fresh backup byte-for-byte with no extra assets, and
the original project was reopened. Dense high-frequency accuracy tests do not qualify
full-venue high-frequency coverage. Next performance work must address the coupled spatial and
broad-delay frequency integration, with independent error/convergence evidence and bounded memory.


### Typed numeric entry and Pan commit correction (2026-11-17)

Reproduced typed Pan remaining unapplied when the keyboard was dismissed with Back. Numeric
fields now commit valid edits on keyboard dismissal as well as Done, Apply and focus loss.
A visible Apply control makes pending edits actionable; duplicate commit paths create one edit,
non-finite input is rejected, and selection changes reset the inspector's editing state.
Android build passed. OnePlus checks verified Back with 135°, Done with 90.5°, Apply with -45.5°,
invalid NaN rejection, Undo/Redo and unchanged second-speaker pan. All nine original saved files
were restored byte-for-byte and the original project reopened; the corrected build remains installed.


### Block and audience-zone transform handles (2026-11-17)

Blocks and audience zones now expose move and yaw handles using the same shared camera
projection, separated touch targets and immutable drag sessions as speakers. Plan exposes X/Z
and Rotate, section Y/Z and Rotate, and 3D X/Y/Z and Rotate. Y changes Block top elevation while
preserving thickness, or the zone base elevation. Zone yaw turns the full surface around its
vertex-mean anchor and updates the rake direction consistently with the existing inspector.

Shared SceneGeometryTransforms preserves holes, exclusions, elevation offsets, breakline indices,
imported mesh topology, vertical/overhanging geometry, face groups, assignments, boundary roles
and materials. Preview geometry does not enter predictions, persistence or history. Release commits
one checkpoint; cancellation, view/selection changes and teardown discard the preview. Edits affect
the selected Block or zone and leave other objects unchanged.

Validation: 510 shared-core tests and eight focused Android integration tests passed, including
speaker regressions, rigid transform height consistency, imported topology/metadata, no-op/invalid
input rejection, pure previews, one-step Undo/Redo and save/reopen. Android debug build and iOS
simulator shared-core compilation pass. On the OnePlus OPD2415, all 20 available move/yaw
handles across the two object types and three views passed with Undo/Redo. Six cancelled drags
left the saved geometry unchanged. Live previews left recovery storage untouched; edits to one
object left the other unchanged. Saved rotations of both objects survived force-stop/cold reopen.
All nine original saved files were restored byte-for-byte with no extra assets, and the original
project was reopened. The validated debug build remains installed.


### Speaker manipulation with shared geometry (2026-11-17)

Speaker manipulation now uses shared-core camera projection, view-specific handle layout/hit
selection and immutable drag sessions. Handles fan apart at overlapping projections, remain inside
the viewport and retain a usable control when a world axis is foreshortened. Position is bounded
to Android's existing limits, pan wraps and mechanical tilt clamps independently of steering.
Plan exposes X/Z/pan, section Y/Z/pan/tilt and 3D X/Y/Z/pan/tilt.

Android previews the actual speaker meshes and aim rays during dragging, including linked-source
motion. Saved state, predictions and undo history change only once on release. Cancellation,
second-finger input, selection/view/size changes and viewport teardown discard the preview.
Picking and projection remain consistent; transient previews are never exported as saved designs.

Validation: the shared-core suite passed 505 tests before one additional ray/projection consistency
check was added; the focused six geometry tests then passed, as did four Android integration tests
covering every transform, preview isolation, cancellation, one-edit Undo/Redo, independent cabinets, linked motion and
save/reopen. Android debug build and iOS simulator shared-core compilation pass.

Native acceptance completed on the OnePlus OPD2415: all 12 available handles across Plan,
Section and 3D changed the intended selected-speaker value, with Undo and Redo verified for
individual edits. Cancelled drags in all three views retained the original values; live preview
kept inspector values unchanged until release. A committed pan edit survived force-stop/cold
reopen. An isolated pan and Undo/Redo check from the restored original scene confirmed the
second same-source speaker's saved position and pan stayed unchanged. The original nine saved
files were restored byte-for-byte, with no extra assets, and the original project was reopened.
Two cabinets sharing one acoustic source now move and aim independently; only an explicitly
linked other source follows edits. A regression test covers independent position/pan/tilt,
Undo/Redo and save/reopen while preserving both explicit link motion modes. The platform-independent geometry is ready for an iPad adapter;
there is no iPad gesture/runtime acceptance yet. Blocks, audience zones and imported meshes remain
outside this speaker-specific batch.

### Diffuse profiling and speaker rotation (2026-11-14)

Tablet debug sampling of the 64-emitter, 1 kHz diffuse venue found initial surface refinement
and measured angular bounds prominent in the cold sample (9.66 s worker CPU). A later sample
(23.65 s worker CPU) showed incident summation and collection access prominent. These are sampled
CPU observations, not release timings or speedup measurements. Shared polar-grid bounds now
avoid temporary boxed candidate lists while evaluating the same endpoints and interior knots.
A randomized 2,000-case independent enumeration comparison and the shared-core suite pass.
No improved whole-venue tablet timing or high-frequency qualification has been established yet;
the next performance batch above remains open.

Android numeric dragging no longer restarts on every value update, and imported speaker mesh yaw
and tilt now match acoustic aim. Android debug build passed; on-device gesture verification remains
outstanding. The user's nine original project files were restored byte-for-byte after profiling.
Post-patch full-venue 1 kHz save/reopen passed with the same 56.7142013963 dB mean; the first
computer calculation took 97.46 s. On OPD2415 the optimized build completed within 229.8 s and
displayed 56.7 dB, compared with the previous 234.4 s UI-poll observation. These individual
measurements do not establish a meaningful speedup. The user's current nine project files were
restored byte-for-byte after this measurement. Full-venue 4/8 kHz and faster interactive performance
remain open; the patch's accuracy is verified but the larger performance gate is not complete.


### Full-venue third-octave diffuse coverage (2026-11-14)

Shared-core coherent scattering now reuses request-local incident spectra, source-only angular
bounds and sampling profiles across audience receivers. Coherent surface refinement uses a
physical longest-edge tree so receivers can share exact sample locations; receiver geometry,
visibility, angular bounds and all existing acceptance tolerances still determine refinement.
Outgoing Lambertian weights and receiver-leg air absorption are evaluated separately for every
receiver. Cache keys include frozen sources, materials, visibility, frequencies, atmosphere,
coherence groups and additional losses. Missing complex data never becomes a valid cached result.
Caches are bounded; saturation falls back to calculation, without reducing accuracy.

The frequency integrator retains positive eight-node Gauss quadrature and successive convergence
checks. Its initial interval bound now uses actual incident delay spans at the validated spatial
samples, including independent correlation groups. Geometry preprocessing and each receiver's
complete spectrum have separate 20-million-operation budgets; adding receivers no longer spends
one global boundary budget. Cancellation and all-or-nothing result publication remain intact.

Validation: **924 passing tests (499 shared-core, 425 Android)**, Android debug/release builds and
iOS simulator shared-core compilation. Dense independent 4/8 kHz references, narrow-lobe/shadow
checks, energy conservation, double-counting and missing-data tests pass. New cache tests compare
cached/uncached receiver distance, air and path losses, group losses and bounded overflow. A new
60 × 40 m, 333-receiver, 64-emitter **1 kHz third-octave** diffuse fixture requires exact project
save/reopen, matching receiver levels/statistics, 100% valid area and a nonzero diffuse contribution.
Its area-weighted mean is **56.7142013963 dB**; development-machine runs took **95.7–109.4 s**
for the first calculation. These are observations under different build loads, not speedup ratios.
The optimized tablet build completed within **234.4 s**; cold reopen completed within **245.1 s**,
both displaying **56.7 dB**. The validated debug build and all nine original saved files were
restored, verified byte-for-byte with no extra assets. This is still too slow for interactive changes.
Measurement limitations are recorded in [performance benchmarks](performance_benchmarks.md).

Remaining gate: full-venue high-frequency diffuse performance. An exploratory 4 kHz, 32-emitter
fixture produced 72,520 spatial samples at its first receiver and exhausted that receiver's work
budget before these reuse changes. It has not been qualified after the changes. Single-receiver
high-frequency accuracy checks do not qualify full-venue 4/8 kHz performance. Multiple diffuse
bounces and coherent outgoing diffuse phase remain out of scope.

Next performance batch: profile cold and warm surface-node evaluation on the tablet, reduce
remaining receiver traversal and measured angular-bound costs without relaxing convergence,
then repeat this saved 1 kHz venue and qualify full-venue 4/8 kHz cases with narrow directivity,
shadow edges and cache saturation. Record latency and peak memory separately from acoustic
accuracy; do not label this workload interactive until device measurements support it.


### 64-emitter direct/specular summation optimization (2026-11-14)

Profiled the 64-emitter, 8 kHz, 60 × 40 m fixture on the tablet's debug build. The non-overflowed
19.45-second sampling window contained 19.90 seconds of worker CPU; 96.8% of inclusive worker
time was inside `DirectField.predictBandSamples`. `Math.abs` accounted for 62.6% inclusively,
consistent with the per-pair sinc-delay checks in the inspected summation loop. Sampling shares
identify the hot path; they are not optimized-build speedup measurements.

Shared `ArrivalBandSum` now expresses each direct/specular arrival as a band-centre complex
phasor and an effective delay that includes measured material phase slope. It reuses the tested
`IncidentBandSum` kernel: correlated groups with at least 24 active arrivals and at most one cycle
of effective delay spread use positive eight-node Gauss integration. Wider spans/smaller groups
retain the analytic sinc pair sum. The existing absolute error bound is 2e-10 × (sum |amplitude|)²;
this is not a relative-error guarantee at a null. Exact coincident-path merging remains in place.
Source phase, material phase, polarity, independent-source groups and image-path coherence retain
their original meanings. No inter-request response cache or project schema change was introduced.

Boundary work is charged in batches proportional to the actual summation operations, with
cancellation checked between batches and an overflow-safe counter. The existing boundary-work
limit and its applicability are preserved; free-field sums do not newly acquire a boundary budget.
The regular single-operation geometry check remains available without a default-argument bridge.

New independent analytic tests cover direct/reflected phase slopes, fast/fallback boundaries,
multiple groups, exact/near cancellation, common delay shifts, frozen inputs, operation counts,
cancellation and budget overflow. The near/far/off-axis dense-frequency reference now exercises
64 measured emitters at 4/8 kHz. Existing ground/material reflection, missing-data, diffuse energy,
full-scene save/reopen and coverage-statistics checks remain required.


Validation: **922 passing tests (498 shared-core, 424 Android)**, Android debug/release builds
and iOS simulator shared-core compilation. The existing whole-venue 333-receiver save/reopen
and area-statistics tests pass. Computer coverage observations changed from 2.91/6.71/24.36 s
to **1.64/2.86/6.47 s** for 32 emitters at 4 kHz, 32 at 8 kHz and 64 at 8 kHz respectively.
Area-weighted means remain 49.05937877 / 52.03731159 / 55.87139387 dB. These are build-run
observations, not isolated benchmark guarantees.

An optimized debug sample also showed the workload moving into the shared integration kernel
and measured-response lookups; the original per-pair absolute-value hotspot no longer dominates.
Profile windows differ and are not used as timing ratios. The first optimized release observation
completed at 55.9 dB within 20.1 s, but a notification panel initially obscured the app.
After reconnection, an unobstructed cold reopen completed within **18.4 s**, preserving **55.9 dB**,
versus the earlier optimized-build observations of 74.5 s and 69.2 s. These are roughly five-second
UI-polling completion upper bounds for the same 64-emitter, 8 kHz, 333-receiver fixture, not exact
kernel timers or a general performance guarantee. Native save/reopen acceptance is complete.
The validated debug build and the user's original project were restored; all nine saved files
matched the original backup byte-for-byte, with the temporary benchmark asset removed.

### Large-venue high-frequency measured-array validation (2026-11-14)

Added reproducible shared-core accuracy checks and production-request performance fixtures for a
60 × 40 m venue, 333 receivers and separated 16-element measured arrays. Irregular angular grids
include a narrow lobe, direction-dependent phase and frequency-dependent complex magnitude;
these cannot use the constant-grid shortcut. Full-venue direct-field coverage is exercised at
4/8 kHz with 32 emitters, and 8 kHz with 64 emitters. Every receiver must remain finite and match
a saved/reopened project, including embedded complex speaker data.

Shared-core validation adds an independent 8192-frequency complex sum at near, far and off-axis
receivers (with a separate 4096-frequency convergence check), and an independent 240 × 240 surface
integral for coherent 8 kHz scattering with a narrow directivity feature. The latter agrees with
adaptive propagation within approximately 0.000505%; cached and uncached results also agree.
The scattering workload exceeds the 65,536-entry cache: 316,512 cold geometry evaluations become
250,976 on a repeat, demonstrating bounded reuse plus correct recomputation of overflow entries.
Missing frequency intervals still produce unavailable results. No production accuracy tolerance,
work budget or acoustic model was changed for these tests.

Validation: **918 passing tests (494 shared-core, 424 Android)**, Android debug/release builds and
iOS simulator shared-core compilation. Additional cache-overflow, full-valid-area and reopened area-statistics assertions passed in targeted
reruns after the full suite. Computer whole-venue observations were 2.91 s (32 emitters, 4 kHz),
6.71 s (32, 8 kHz) and 24.36 s (64, 8 kHz). These are local timing observations, not CI deadlines.

On the OPD2415 tablet (Android 16, optimized build), fresh UI completion upper bounds were
**7.8 s / 49.1 dB** for 32 emitters at 4 kHz, **12.7 s / 52.0 dB** for 32 at 8 kHz,
and **74.5 s / 55.9 dB** for 64 at 8 kHz. Polling is approximately five seconds; these are single
whole-app observations, including listener work, not isolated coverage kernel benchmarks.
The 32-emitter 8 kHz project reopened at the same 52.0 dB. The 64-emitter cost remains too high
for interactive iteration and is explicitly not marked performance-qualified.

The 64-emitter 8 kHz project also reopened successfully on the tablet: **69.2 s**, unchanged
**55.9 dB** area mean. Both 8 kHz scene sizes were therefore checked across native app restarts.
The debug build and the user's original project were restored afterward; all nine original files
matched the backup byte-for-byte, and the temporary benchmark speaker asset was removed.

See [performance fixtures and reproduction](performance_benchmarks.md) for geometry, source data,
reference tolerances, export commands and qualification limits. Full-venue diffuse third-octave
coverage, more boundaries, finer meshes, 16 kHz and sustained thermal testing remain release gates.
The observed 32-to-64-emitter scaling makes profiling the direct/specular finite-band summation
the next performance priority; any acceleration must retain an independent accuracy reference.


### Whole-scene profiling and spectral reuse (2026-11-14)

Profiled the actual 32-emitter tablet calculation (8×8 m venue, 2×2 m scattering stage,
two measured 16-element arrays, 1 kHz third-octave band). A 20.09-second Android sampling
window contained 37.17 seconds of worker CPU across the listener and coverage jobs. Inclusive
worker samples placed 99.7% inside coherent diffuse evaluation, 54.6% inside measured complex
response lookup and 24.9% inside incident summation. Float boxing/conversion and collection
iteration dominated self samples. These are diagnostic profile shares, not a timing speedup.

Shared-core changes:

- Polar grids own primitive arrays with binary axis lookup. Exactly constant grids use a constant
  path only after validating angular coverage; missing directions and invalid values remain missing.
- Incident groups use reusable primitive index chains, preserving source order without boxed lists.
  Exact-frequency complex summation is linear in emitter count for every group size.
- Surface-major evaluation reuses geometry, path losses, flight times and exact Cartesian response
  endpoints across frequency slices. Only explicitly declared linear intervals qualify; measured
  knots and maximum interpolation gaps remain binding. Custom responses retain scalar evaluation.
  Filter responses are evaluated at the actual frequency. Scratch is bounded to 32 slices at a time;
  there is no emitter × patch × frequency tensor or cross-request response cache.
- Measured coherent diffuse energy is integrated independently of direct/specular refinement using
  positive eight-node Gauss quadrature. The initial subdivision bounds phase variation using the
  aperture and DSP-delay spread within each correlated group. Two consecutive refinements must meet
  the existing 1e-5 relative / 1e-10 absolute criterion. Cancellation, work limits and the 16,384-node
  frequency limit remain enforced; nonconvergence cannot publish a partial result. Independent groups
  do not acquire extra work from unrelated delay offsets. Direct/specular propagation keeps its
  analytic finite-band treatment; unsupported custom complex data keeps its prior refinement path.

Validation includes independent bilinear lookup at knots and the cyclic seam, partial constant-grid
coverage, immutable inputs, reusable-group changes, measured interior knots and chunk boundaries,
filters, missing frequency gaps, and a dense 800-frequency reference with interference and a narrow
parametric filter. A new production test calculates the entire 32-emitter venue and requires identical
coverage after save/reopen; existing polarity Undo/Redo and missing-data tests remain in the suite.
No project schema change is needed; schema 25 and exact speaker/material assets are preserved.

Validation status: **915 passing tests (492 shared-core, 423 Android)**, both Android build
variants (including release lint/R8) and iOS simulator shared-core compilation. The 800-tone
independent reference includes a 10 ms interference delay and a 12 dB / Q20 parametric filter;
its diffuse band power agrees within 2e-5 relative tolerance. No spatial sampling thresholds,
material energy partitions, maximum interpolation gaps or missing-data rules were relaxed.

Same-fixture debug observations during this batch: the intermediate surface-major build completed
within **271.0 s**; the final allocation-reduced, independently integrated build completed within
**91.1 s**, both showing **84.3 dB** area average. These are launch-to-fresh-UI completion upper
bounds with roughly five-second polling, not exact kernel timers. The earlier uncompleted 170 s
run is not used as an exact baseline. Debug remains slow for interactive use; optimized-build
qualification is recorded separately.

The optimized build completed the same normal-polarity scene within **23.5 s**, still showing
84.3 dB. This is a separate build-mode observation, not a 271/23.5 optimization claim.
A subsequent tablet polarity test exposed work-budget exhaustion at exact cancellation with the
fixture's high calibrated amplitude. The shared direct/specular sum now combines correlated paths
only when delay, phase and phase slope are exactly equal, before forming powers. This removes
large-power subtraction at a null without merging nearby paths or loosening convergence tolerances.
The whole-scene regression now uses the tablet amplitude and checks inverted-array cancellation
and its save/reopen result. Android calculation failures also retain exception diagnostics.

After the cancellation fix, all **915 tests passed again**, including the high-amplitude whole-scene
regression; Android debug/release builds and iOS simulator shared-core compilation also passed.
On the tablet the saved inverted-array scene reopened and completed successfully within **18.3 s**,
showing the expected **0.0 dB display floor**. This is an exact cancellation check, not a physical
noise-floor prediction. Broader high-frequency, nonuniform measured-directivity and larger-venue
benchmarks remain a release gate; this 32-emitter fixture does not establish interactive performance
for every large measured array.

Final optimized-build save/reopen check: normal polarity was restored in the UI, the app was
force-stopped, and the reopened project completed within **23.2 s** at **84.3 dB**. Both normal
and inverted saved states therefore completed on-device. The final debug build was reinstalled,
and the user's original project and assets were restored: all **nine files matched the original
backup byte-for-byte**, with no extra files in the saved-project directories.

The Android optimized-build path also now has its referenced ProGuard file, retaining JNI entry
names. Release lint checks run rather than being disabled. Debug and optimized Android timings are
reported separately; changing build modes is not counted as a shared-core optimization speedup.

### Large measured-array coherent scattering optimization (2026-11-14)

Optimized the shared incident-scattering hot path without changing the project format or the
measured-complex-data contract. Correlation groups with at least 24 active emitters use a positive
eight-point Gauss frequency integral when bandwidth × delay spread is at most one cycle. This
reduces interference work from pairwise O(N²) to eight O(N) sums per patch. The absolute integration
error is bounded by 2×10⁻¹⁰ × (sum of incident amplitude magnitudes)²; this is not a relative-error
promise at cancellation nulls. Wider delay spreads and smaller groups retain the analytic sinc
pair sum. No source, shadow, interference term or frequency band is silently discarded.

Receiver-local geometry/material factors and speaker-direction coordinates are reused across
frequency refinements. The cache holds at most 65,536 emitter/patch entries (2 MiB of packed
Double payload plus collection overhead), resets when the material data band changes, and is
discarded with the calculation. Remaining entries use the same uncached calculation. Air loss,
filter transfer and source gain are prepared once per frequency sample. Propagation-phase refinement now uses an O(N) gradient enclosure per correlation group,
conservatively bounding every original pair without visiting all pairs. Randomized 128-emitter
checks verify enclosure, separate groups and coincident-position behavior; the sampling threshold
is unchanged. Measured-frequency interpolation uses an immutable sorted index and binary search
instead of sorting/scanning the frequency keys at every directional lookup. No cross-request cache can retain stale speaker or material settings.

Validation: **906 passing tests (484 shared-core, 422 Android)**, Android debug build and iOS
simulator shared-core compilation. New checks compare against an independent analytic integral
for 23–128 emitters, multiple correlation groups, zero/near-zero cancellation, common delay shifts,
and both sides of the fast/analytic threshold. Deterministic work checks verify eight evaluations
per emitter at 32–256 emitters and the exact pair-count fallback for wide delay spreads. A 32-emitter
surface case verifies cached/uncached parity and material-band changes. Android production tests
exercise two measured 16-element arrays, save/reopen, polarity cancellation, Undo and Redo.

Development-machine benchmark (JVM, warmed kernel, 100 runs of 256 emitters): **4.39–9.71 ms optimized
versus 59.74–67.99 ms analytic pairwise** across two recorded runs (about 7–14× for this kernel). This is not an end-to-end coverage
speedup or a tablet timing guarantee. The 32-emitter surface test reduced repeated geometry
computations from 187,264 to 121,728, saving the full 65,536-entry cache allowance. Timing is
reported rather than used as a flaky test threshold; deterministic work and accuracy checks gate regressions.

Tablet stress result: the debug build remained responsive, with no AndroidRuntime errors, but
an 8×8 m scattering-stage coverage scene with two measured 16-element arrays had **not completed
at the 170-second observation** and was stopped shortly afterward. This is a failed interactive
whole-scene performance gate, not a native coverage/save-reopen acceptance pass. Automated
32-emitter production save/reopen and Undo/Redo tests pass. The original tablet project was
restored, with all nine files verified byte-for-byte and no extra files. Next performance work
should profile the full native request (directional response evaluation, adaptive mesh size,
frequency refinement and listener/per-source work), then establish a device timing budget before
calling large measured-array coverage production-ready. Kernel benchmarks alone are insufficient.

Remaining performance gates: broad/high-delay cases still use pairwise summation, shadow partitioning and other direct/specular source-pair work can remain quadratic, and large high-frequency venue/array
scenes still require device profiling and sampling-budget qualification. This batch preserves the
existing spatial error/work limits and the first-bounce scattering approximation. Outgoing diffuse
phase, measured cross-angle scattering matrices and multiple-bounce transport remain separate work.

### Coherent illumination at scattering surfaces (2026-11-14)

Implemented in the shared core for correlated emitters with compatible **version-2 complex
speaker responses**. Incident complex fields now combine on a common surface mesh before
scattered power is propagated to receivers. Intrinsic response, DSP phase, gain, polarity,
propagation delay, air attenuation and finite-band delay decorrelation participate. Energy mode
keeps different speaker groups independent; measured elements within an array remain correlated.
Magnitude-only/v1 sources retain their previous independently summed diffuse energy: cabinet
phase is not available to support this new surface-interference contract. This is an explicit
model boundary, not a fallback when a measured calculation exceeds its budget.

The first-bounce model couples each incident field into a shared Lambertian scattering channel
using sqrt(cos incidence × scattered fraction). It conserves the material's absorbed/specular/
scattered partition for each normalized incident channel; a canceled incident channel cannot
radiate scattered energy. Coherent illumination can change incident power, so its result is not
limited to the sum of isolated-emitter powers. This is a flux-normalized rough-surface approximation,
not a measured multi-angle scattering matrix or an exact general wave-boundary solution. Surface
patches and outgoing diffuse radiation remain energy-summed; total diffuse phase traces remain unavailable.

Receiver and individual emitter shadows are clipped before sampling. Canonical Block/Face
ownership and global-ground exclusions are reused. Adaptive refinement bounds propagation-phase
variation and measured directional response; a positive seven-point triangle rule resolves smooth
interference and preserves exact solid-angle weights. Geometry is reused across frequency refinement.
Missing data, cancellation and work/sample limits cannot publish a partial result. Dense coherent
high-frequency scenes may require wider receiver spacing; production performance qualification
across large measured arrays remains a release gate.

Schema 25 prevents older clients silently recalculating these new semantics. Speaker assets,
scattering materials, correlation mode and DSP state persist through save/reopen. Android data
status explains incident coherence separately from outgoing diffuse energy. Android UI and iPad-ready
shared compilation are preserved.

Validation: **899 passing tests (478 shared-core, 421 Android)**, Android debug build and iOS
simulator shared-core compilation. Coverage includes reinforcement, polarity cancellation,
complex phase and finite-band delay, independent groups and correlated array elements, material
energy partition, duplicate boundary ownership, missing directions, per-emitter shadows, legacy
magnitude behavior and production save/reopen with polarity Undo/Redo. Separated-source diffuse
power agrees with an independent 160,000-cell surface integral within 0.2%.

Tablet acceptance: installed the debug build and used a temporary two-emitter measured-complex
fixture with a scattering stage. In-phase area average was **75.2 dB**; inversion produced the
**0.0 dB display floor**. Undo restored 75.2 dB. Redo saved the inverted state, and reopening on
the final build reproduced 0.0 dB with results up to date. The saved schema-25 recovery envelope
passed its checksum. All nine original project/preference files were restored byte-for-byte,
with no extra files left behind.

Remaining: measured directional scattering phase / cross-angle scattering matrices, outgoing
diffuse phase, multiple-bounce transport, large-array performance qualification and external
measurement/industry-reference validation. These remain separate from mechanical certification.

### Mixed source data and complex-source diffuse energy (2026-11-14)

Implemented mixed magnitude-only and band-capable complex sources under the explicit **Energy**
(independent speaker groups) setting. Energy mode now applies at every frequency; the former
adapter override above 163 Hz is removed. Arrays retain correlation among their own elements,
and each emitter remains correlated with its specular/ground paths. Coherent mode still rejects
mixed/unknown phase or incompatible complex reference drives within a correlated group; it never
silently assumes cabinet phase or switches summation modes. Legacy magnitude-only coherent
predictions remain approximate. Android now labels this setting “Source summation · all frequencies”.

Complex-source diffuse propagation uses measured directional |H(f)|², DSP filter energy, gain,
path attenuation, material absorption/scattering partition and the existing receiver-visible
first-bounce Lambertian quadrature. Missing directional/frequency data invalidates the total.
Zero complex power remains zero. Material phase, source polarity and absolute DSP delay do not
create a coherent diffuse phase. Diffuse arrivals and different emitters' diffuse contributions
were independently summed energy in this batch. Coherent measured-emitter illumination is
implemented by the subsequent batch above; measured directional scattering phase and multiple-bounce
transport remain unimplemented. Exact-tone total phase traces remain unavailable when diffuse scattering is active.

The complex angular refinement bound encloses real/imaginary polar interpolation over a spherical
cap and bounds centre-response deviation across each declared Cartesian frequency interval. It
uses the exact minimum amplitude along the centre's complex frequency segment, covering lobes
between spatial probes and potential frequency cancellations without treating frequency-only
variation as angular structure. Unresolved/missing bounds cannot silently bypass work/depth limits.
Spatial quadrature is reused across frequency refinements; cached entries include the actual complex
frequency intervals. Both total and diffuse power must satisfy the existing convergence criterion.

Scene schema 24 protects the corrected summation semantics from older clients. Existing Energy-mode
projects now use independent speaker groups across the full spectrum; their high-frequency answers
can change because the previous coherent override was incorrect. Saved mode, exact speaker assets,
filter chains and scattering materials survive save/reopen and scene Undo/Redo. No material or speaker
phase is invented during migration.

Validation: 891 passing tests (471 shared-core, 420 Android), Android build and iOS simulator
shared-core compilation. New coverage includes mixed independent sums, correlated-group rejection,
phase/delay invariance of diffuse energy, frequency integration against an independent dense reference,
absorption/scattering energy partition, coincident Block/Face ownership, filter effects, cache parity,
missing diffuse directions, cancellation, angular bounds and production save/reopen.

Tablet acceptance: the 1 kHz mixed-source Energy-mode fixture produced 69.3 dB area average
with the scattering boundary, 69.0 dB with no boundary, and 69.3 dB after Undo and cold reopen.
The original project/preferences files were restored byte-for-byte (nine files); the checked Android
runtime log contained no errors. Numerical fixtures validate the defined model, not manufacturer
accuracy, coherent diffuse phase or real-device iPad behavior.


### Measured complex band coverage and digital filter phase (2026-11-14)

Implemented in shared-core: `ComplexSpeakerResponse` version 2 explicitly declares
`frequencyInterpolation: "cartesian-linear-hz"` and `maximumInterpolationGapHz` (positive,
at most 2000 Hz). The importer retains provenance, common reference-drive identity and
propagation-removed 1 m complex pressure. Cartesian pressure is interpolated only between
adjacent measured frequencies whose gap meets that declaration, never extrapolated. This
contract is an assertion by the data author, not proof that sparse samples resolve a real
speaker's phase. Version-1 exact-tone data retains its previous magnitude-only band behavior;
no interpolation policy is silently inferred. Exact-tone phase capture still uses actual samples.

Band-capable complex data is authoritative for band magnitude and intrinsic phase, without
requiring or multiplying a separate sensitivity table. Common-drive complex sources are summed
through actual emitter/direct/specular/ground paths. Measured material phase is required for
non-ideal reflectors. The initial band batch rejected mixed sources and complex diffuse transport; the subsequent batch
above adds independent mixed-source sums and complex-source diffuse energy. Legacy nonzero per-band
EQ offsets on complex sources remain unavailable because their filter phase is unknown.

Validated shared digital biquads support low-pass, high-pass and parametric peaking responses,
Q 0.1–20, up to 16 cascaded filters, and explicit 32–192 kHz sample rates. Coefficients follow the
[W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/); transfer phase is conjugated
to the engine's positive-delay convention. Android adds filters at 48 kHz and displays imported
sample rates. Filters affect magnitude coverage, valid exact-tone phase traces and system
adjustment previews. Legacy per-band EQ is still explicitly magnitude-only; it is not converted
to an invented filter. Two Butterworth biquads can form each fourth-order Linkwitz–Riley branch.

The solver splits at measured knots and filter features, then doubles frequency subdivisions
until two successive power differences satisfy 1e-5 relative or 1e-10 absolute pressure-squared
tolerance. Propagation-delay interference retains analytical sinc integration inside each
subdivision. Limits are 12 refinements and 16,384 subdivisions; nonconvergence returns unavailable.
Cancellation checks and boundary work limits remain active. These are convergence criteria, not a guaranteed error bound or a claim of
real-world speaker accuracy. Large-scene performance needs profiling.

Scene schema 23 stores versioned filter chains; older projects default to no filters, and invalid
chains fail shared preflight before scene mutation. Embedded measured data, filters, traces and
design snapshots retain their values across save/reopen. Android exposes add/remove filter
controls with range validation and scene undo/redo. Tests cover dense-reference band integration,
subdivision invariance, intrinsic phase/polarity/delay, rigid-ground interference, independent
powers, missing data, filter cutoff gain/phase, reciprocal peaking filters, fourth-order crossover
summation, high-Q convergence, cancellation, atomic invalid import and production save/reopen.

Validation: all 882 tests pass (464 shared-core, 418 Android), as do the Android APK build
and iOS simulator shared-core compilation. On the tablet, adding a 1 kHz Butterworth low-pass
to the declared-complex fixture changed area-average coverage from 60.2 to 57.2 dB. Undo/Redo
restored each result; cold reopen retained the filter, exact speaker asset and 57.2 dB result.
All eight original project/preferences files were restored byte-for-byte after testing; the checked
Android runtime log contained no errors. These fixtures validate numerical behavior, not a real
manufacturer model or an iPad client runtime.


### System-adjustment previews (2026-11-14)

Implemented combined absolute gain, delay and polarity proposals for one selected speaker,
compared across every saved reference receiver. Shared `SystemDsp` validates finite editor-range
settings; `SystemAdjustment` provides compare-and-set and inverse commands. Shared
`SystemAdjustmentComparison` evaluates frozen before/after requests using the production
prediction engine and reports total/band level differences plus geometric arrival comparisons.
Missing power and exact zero-power nulls yield unavailable logarithmic differences, not invented
zeroes. Receiver identity is stable, and a missing timing reference does not suppress valid level
comparisons. This is a user-authored adjustment, not automatic gain/polarity optimization.

Android snapshots both requests before worker dispatch, checks cancellation, and rejects results
or Apply after changes to the serialized project inputs. Preview never edits live DSP. Apply writes
all three settings in one scene undo checkpoint and invalidates existing predictions; Undo/Redo
restores the complete edit. The listener's System alignment section exposes source selection,
absolute gain/delay fields, polarity, comparison dialog and scene Undo/Redo controls.
Applied DSP and saved receivers use the existing project format and survive reopening. Unsaved
preview drafts/results and the existing session-only scene undo stack are not persisted.

Band levels retain the existing coverage model and its magnitude-only/generic approximations;
these previews do not establish measured cabinet phase alignment or add complex-response band
integration. Broader routing/linking, subwoofer helpers and validated crossover phase remain open.
Automated validation covers combined gain/delay/polarity, interference, unavailable/null levels,
multiple receivers, cancellation, stale rejection, Apply equivalence, atomic Undo/Redo, and
save/reopen. All 874 tests pass (458 shared-core and 416 Android), alongside the Android
build and iOS simulator shared-core compilation. Tablet acceptance verified a combined −3 dB,
8 ms, inverted-polarity edit: at 1 kHz the front receiver changed by −3.15 dB and the rear by
+1.06 dB. Apply, in-panel Undo/Redo and cold reopen preserved the expected settings and both
receivers. No runtime errors were reported in the checked Android log. All eight original
project/preferences files were restored byte-for-byte after testing.

### Current priorities after the preview batches

- Add explicit input spectra and broader DSP families; coherent mixed-data phase and measured directional scattering matrices require additional model contracts.
- Finish routing/mute/solo/linked workflow validation and subwoofer layout helpers.
- Validate a small measured speaker/array set and document model-specific limits.
- Prove the iPad viewport/client on a device; core compilation alone is not client readiness.
- Finish shared typed project state, importer provenance/source retention, release reports and large-scene acceptance.


The first implementation slice extracts `android/shared-core` with common Kotlin
acoustics and JVM/iOS targets. Android consumes its JVM artifact. JVM-only math
and shared mutable atmospheric caching have been removed. Existing kernel tests
move with the module; portable smoke tests exercise the common source set.
Production air absorption now uses the shared atmospheric formula, and imported
per-band sensitivity feeds point-source and array-element levels. Array mechanics
and narrowband complex pressure now use shared-core geometry and summation. Physical aim/splay determine emitter positions; steering is a
relative electronic angle represented by spatially derived delays. Android maps
and listener totals retain array phase instead of reconstructing centroid phase.
Cabinet meshes and axis rays use the shared physical poses.

Android listener readouts and coverage maps now use shared `DirectField` band-power
integration. Delay-dependent interference is integrated analytically across each
frequency segment, replacing the heuristic pressure/power blend. Segment powers
are weighted by their actual Hz widths, and fractional-octave edges are clipped
without rounding to whole third-octave bands. The current convention is **fixed
total reference drive with flat power spectral density inside each requested
band**; spectrum mode sums eight independent octave-band powers. This is not yet
a calibrated broadband input-spectrum model. Gain, directivity and air absorption
are held constant within each legacy magnitude segment; declared version-2 complex responses and digital filters now use converged subdivision as described above.
Material reflection phase now follows its own measured frequency/angle curves, as described below.

Ground is explicitly selectable and saved in projects: free field is the default,
including for older project files without the setting. A reflecting floor uses
an image arrival for each element, with its own distance, air loss, departure
angle and delay; pressure reflection magnitude is `sqrt(1 - absorption)`. Each
emitter remains coherent with its image even when speaker groups are independent.
The old scalar floor correction and its +/-3 dB clamp are removed from production
prediction. The model is an infinite horizontal plane at y=0 with a real positive
reflection coefficient; complex ground impedance, roughness and diffraction are
not modeled. Receivers or emitters below y=0 use free field in the Android adapter;
the settings panel warns about cabinets below that plane.

Speaker-data calculations now also live in shared-core: `ReferenceCalibration`,
`MeasuredBandTable`, `MeasuredRadiator` and validated `PolarGrid`. Explicit reference
distance normalizes levels to 1 m once; voltage and power references have separate
scaling rules and unsupported conversions return unavailable. Android retains
relative drive gain; the legacy JSON key `signalLevelDbu` is retained for project
compatibility but does not represent electrical input dBu. JSON speaker data and
embedded project assets preserve calibration provenance and measurement grid.
Existing TAB/binary CLF sensitivity rows retain their imported 1 m reference and
are labeled electrically unverified; exporter normalization has not been certified.

Measured frequency lookup now interpolates dB in log frequency only between adjacent
cells on the declared third-octave or octave grid. It never extrapolates or bridges
missing cells. Required missing/invalid sensitivity or directivity produces a
`BandPrediction` with unavailable power and affected source-group/band diagnostics.
It does not substitute generic response, skip a source, or convert unknown data
to 0 dB. Android suppresses incomplete totals/map cells, displays unavailable source
readouts and model warnings, and stamps warnings on exports. Generic presets remain
usable and visibly approximate. Sources that require measured data keep that marker
on save/reopen; losing embedded data cannot silently reuse the previous scene's
registry or turn those sources generic. Replacing/removing imported data clears
old analysis even in manual mode.

Project persistence now uses shared `ProjectArchive`, `ProjectStore` and a shared
CLF JSON codec. Scene schema **17** retains exact decoded-speaker revisions in a
manifest (asset format 1, SHA-256 over the canonical versioned payload). Portable
scene JSON embeds the assets; autosave and named saves reference separately stored
immutable assets. Calibration, measurement grid, response and polar data survive
fresh-instance reopening. Missing, corrupt, mismatched or unsupported assets retain
their references and produce explicit unavailable states. Recovery of a known
missing revision requires matching content, not just a matching speaker name.

Android's file adapter writes and flushes asset files first, then atomically replaces a
checksum-protected checkpoint. A previous complete checkpoint is retained; damaged
primary scene records fall back visibly, while missing assets keep the latest
geometry and report the asset problem. Newer scene/checkpoint formats stop recovery
instead of silently rolling back and autosaving over them. Failed startup recovery
pauses autosave and preserves the existing files. Large asset decoding/hashing runs
on a worker; the prepared scene/data are applied on Main. Legacy preference saves
are read newest-first across known schema versions, without deleting the old values.
Undo/redo now resolve exact in-memory asset revisions instead of clearing the
registry when applying a compact snapshot. Scene structure/version preflight runs
before live state changes. A fully typed, immutable shared scene model remains
planned; Android still adapts the scene fields into its existing state flows.

Listener and coverage jobs now execute shared immutable `PredictionRequest` objects.
Source poses, measured polar grids/response, synthetic settings, DSP/EQ, atmosphere,
bands, weighting, floor coefficients, fixed receiver positions, audience polygons
and obstruction solids are captured before dispatch. Collections/arrays are privately copied;
workers do not read Android state flows to calculate direct-field results. Shared
`PredictionOutput` values own their arrays and carry the originating request ticket.

Shared `PredictionGate` accepts only the latest request for each calculation channel
at the current scene revision. Sequence numbers also reject older requests started
at the same revision; cancellation remains an optimization, not the acceptance rule.
Scene edits invalidate outstanding work and clear old listener/map/derived values.
Manual mode explicitly requests recalculation; automatic mode coalesces edits and
refreshes both listener and coverage channels. UI states distinguish not calculated,
changed, running, current and failed. Reports include prediction state/input revision
and omit invalidated totals. Undo/load invalidate in-flight work; catalogue data
publication is serialized on Main. Experimental reflections are guarded by their
parent listener ticket as well as their own; optimizer completion is also gated.

Audience preprocessing and obstruction evaluation now run in shared core on the
prediction worker. `CoveragePlan` snapshots world-space polygons and probes;
`PreparedAudienceGeometry` owns receiver identities and clipped triangle topology.
Simple concave polygons are triangulated, then clipped against a world-aligned grid.
Each surface has separate vertices even at identical x/z. Its base height is anchored
at the input vertex mean; rake direction is world-space clockwise from +Z. Listening
height is physical surface elevation plus 1.2 m seated / 1.6 m standing, without the
previous 30 m receiver clamp. Android outlines, heatmaps and shared contours use the
same surface definition. Missing sample values remove affected triangles rather than
bridging gaps. Old x/z averaging onto the highest surface has been removed.

Manual probes add to surface maps; legacy generated area points are replaced by the
mesh. With no audience surfaces, preview sampling covers the full venue rectangle at
seated height over the y=0 floor, rather than a fixed ±12 m square. Areas may extend
outside venue bounds. Solids no longer silently lift preview receivers onto their
tops: define an audience surface explicitly for a raised floor. Existing plane fields
remain unchanged; scene schema 17 retains authored fields and embeds full 3D audience meshes,
as described below.

Preprocessing checks cancellation during polygon validation, triangulation and grid
clipping, and caps receivers at 50,000, triangles at 150,000 and candidate grid cells
at 1,000,000 (500,000 per source triangle). Invalid geometry or excessive work fails
the coverage request; it does not publish a partial map. Cached plane gradients and
solid transforms avoid repeated trigonometry.

Shared `SurfaceElevationMesh` adds continuous piecewise-planar audience height fields.
An optional `vertexOffsetsM` list supplies one height offset per outer boundary vertex;
optional ordered `elevationPoints` supply x/z and offset metres at interior locations.
Offsets add to the original base/rake plane. Empty fields preserve the existing planar
model. The core triangulates the boundary and inserts interior points into containing
facets, splitting both faces when a point lies on a shared edge. Collinear boundary
vertices are retained when elevations are explicit, so height changes along straight
edges are not discarded. The outer-vertex mean remains the base/rake anchor; interior
points and holes never move it. Boundary winding is normalized with elevations still
associated with their positions. Triangulation is deterministic for ordered inputs;
explicit breaklines constrain that triangulation. Each segment references existing
boundary vertices or interior height points by kind and index. The shared core splits
constraints at existing collinear points, then recovers their edges by flipping
unconstrained mesh edges. Recovered edges are locked against later flips. Every
constraint must be present before a mesh is returned; invalid geometry fails visibly.
User-specified triangle indices remain unsupported.

Breaklines preserve continuous ridges, valleys and changes in slope. Crossing lines
require an existing shared height point. Junction detection allows Float-coordinate
roundoff (a minimum 10 nm or 2e-7 times the largest endpoint coordinate in metres),
retaining the existing point position/elevation; it never invents a crossing point.
T junctions and collinear overlapping segments are supported. A segment must stay within the outer polygon. Holes and exclusions
clip the resulting coverage, not the underlying constraint or height field. The core
also clips displayed breaklines at cutouts. Limits are 256 declared segments, 4,096
subdivided constraint edges and a cancellable two-million-operation recovery/validation
budget per surface, in addition to the existing mesh limits.

Each source facet is independently grid-clipped and cutout-clipped. Its barycentric
height function supplies every generated vertex, so coarse grids cannot flatten peaks
or bridge a change of slope. Receiver height remains a vertical seated/standing offset,
not a distance along the surface normal. Shared line tracing splits non-planar outlines
and cutout drafts at facet edges and clips them to the outer domain; contours use the
result mesh as before. Statistics integrate actual 3D triangle area, including after
holes/exclusions. Shared height lookup also drives auxiliary optimizer samples.

Android exposes per-boundary offsets and **Add interior height point** (tap a location,
then edit its offset), removal, and **Reset to base and rake**. New interior points
initially interpolate the existing offset, preserving the shape until edited. Rotate,
mirror and duplicate preserve elevation controls. Rotation/mirroring now transform the
base rake direction too, preserving physical heights under those rigid transforms.
Invalid outer-edge edits or malformed elevation data leave the existing scene unchanged.
Android provides endpoint selectors to connect vertices and height points, segment
removal, and cyan overlays on the surface. Deleting an interior point removes its
incident constraints (including segments split at that point) and remaps remaining
indices; resetting elevations clears all breaklines. Rotation, mirroring and duplication retain endpoint identities.
Save/reopen and undo/redo preserve elevation data and constraints. Geometry cache keys
include boundary and interior offsets/positions plus breakline references, so acoustic
edits reuse meshes while elevation or constraint changes rebuild them. Arrays and point lists are privately copied before dispatch.

Authored polygon surfaces support one continuous height per x/z. Use imported 3D
meshes for vertical faces and overhangs, or separate authored surfaces for stacked decks.
Height lookup outside an authored elevated surface
returns unavailable rather than inventing an extrapolated elevation. Interior controls
must be strictly inside the outer boundary (they may lie under a measurement cutout),
be distinct from boundary/other control points, and number at most 256 per surface.
Offsets must be finite and within ±10,000 m; array sizes must match the boundary.
Validation and insertion support cancellation, and existing mesh/work budgets apply.

Shared `AudienceMesh` supports immutable connected, orientable manifold 3D triangles,
including vertical faces, overhangs, overlapping elevations and closed surfaces. Original
faces and holes remain authoritative; no hull, projection-based hole filling or automatic
retriangulation replaces them. Face winding is made consistent through adjacency without
changing triangle membership. Boundary loops come from topology; closed surfaces need
no outer loop. Legacy plan coordinates are retained only for editor bounds/base anchors,
not as the definition of the imported surface.

`MeshValidation3D` checks finite bounded XYZ coordinates, indices, unused/coincident
vertices, duplicate/zero-area faces, edge and vertex manifoldness, orientability and
connectedness. A 3D bounding-box sweep and segment/triangle contact tests reject actual
self-intersections, coplanar overlaps, T junctions and contacts without shared topology.
Overlapping projections at separate elevations are valid. Limit: 4,096 vertices, 8,192
triangles, up to 65 boundary loops and four million cancellable validation work steps.
Float receiver precision is checked in 3D; sampling fails instead of merging distinct
layers that fall below coordinate resolution. No partial repaired mesh is published.

`ImportedSurfaceGeometry` clips each original facet into world XYZ grid cells using
successive axis slabs, avoiding a full bounding-volume scan. Physical Y participates in
receiver keys, so equal x/z at different heights retain independent samples and acoustic
results. Coarse coordinate buckets retain multiple distinct 3D samples. Sub-micrometre
Float grid slivers coalesce only within the same source facet or shared topology; orphan
vertices from discarded zero-area slivers are removed. Separate layers are never joined.
Every cell stays on its source facet. Existing 50,000-receiver, 150,000-triangle
and one-million-work budgets apply, with cancellation. Sampling uses grid spacing along
world axes; maximum cell diagonal is sqrt(3) times that spacing. Refinement preserves
true area and linear-field statistics. Base/rake adjustments add to imported heights.
Active imported faces contribute their actual area, including assigned walls and
undersides; receiver ear offsets remain vertical (+1.2 m seated, +1.6 m standing),
not face-normal. Per-face assignments can remove unwanted faces from coverage.
These remain measurement surfaces, not automatically acoustic reflectors or obstructions.

Imported `holes` and `exclusions` are plan masks whose vertical prisms subtract from
all active faces/elevations in the same zone. Coplanar vertical faces on a mask boundary are
excluded; cutout boundary vertices remain on adjacent surviving faces to close their
triangles. Intrinsic openings are simply absent faces and are never filled. Use separate
zones when a partial-face plan cutout should affect only one layer; whole individual
faces can now be excluded directly. Scalar x/z height lookup considers active faces and returns
unavailable for multiple layers or a vertical facet rather than selecting an arbitrary
height; Android auxiliary sampling enumerates shared 3D receivers. Shared cross-sections
trace all layers, and shared contour ribbons use each face's 3D tangent plane, including
purely vertical contour segments. Imported wire edges retain true XYZ endpoints.

Shared `AudienceMeshObj` accepts triangulated OBJ geometry and named groups with x/y/z vertices
and three vertex references per face, including slash notation and relative negative
indices. Vertices must precede faces that reference them. Units and Y-up/Z-up are
explicit options; Z-up maps `(x,y,z)` to `(x,z,-y)` before metre scaling. OBJ `g`
declarations map to named face groups. Object names, materials, textures and normals do
not alter geometry or audience assignments; referenced files are never opened. Quads, freeform geometry, line/point primitives and disconnected
surfaces fail with a reason. The text limit is 8 MiB characters. The importer does not
weld vertices, repair seams, infer scale, or guess which faces are audience zones.

Shared `ObjFaceGroups` maps the active OBJ group-name set to original face indices before
winding normalization, which retains face ordering. A single name becomes a named group;
repeated declarations accumulate members. Group state persists until the next `g`, including
across `o`, `s`, and material statements. Faces before any `g`, or after a bare `g`, remain
ungrouped; an explicitly declared `g default` becomes a group named `default`. Declarations
without member faces do not create empty groups or consume IDs. IDs follow first face use.

OBJ supports overlapping memberships (`g A B`). The native model uses disjoint groups,
so each distinct complete name set becomes an editable partition, such as `A + B`.
Names are deduplicated and sorted within that set; `g B A A` reuses `A + B`. Faces are
never copied, and overall statistics count each facet once. `A` and `A + B` have separate
summaries; the importer does not preserve overlapping source sets as independent native
selection sets. Whitespace separates names, so use a single token (for example an underscore)
for multiword OBJ names, then rename the imported native group if desired.

Source names are case-sensitive. Native labels must be unique ignoring case, so colliding
display names get deterministic suffixes: `Seats`, `seats (2)`, `SEATS (3)`. Each repeated
source set keeps its assigned label. Source names and generated labels must fit the existing
80-character limit; overlong names, control characters, more than 128 names on a declaration,
or more than 128 face-bearing combinations fail with an OBJ line number. Names are never
silently truncated or merged. Parsing/mapping supports cancellation and publishes only
validated complete meshes. Names such as `EXCLUDED` or `STANDING` have no acoustic meaning:
all imported faces initially follow their zone until explicitly assigned in the inspector.

Android reports both triangle and named-group counts after import and explains the combined
group rule in the import controls. Imported groups use the existing selection, rename,
assignment, transforms, undo/redo, statistics and save/reopen paths. Named groups originally shipped in scene schema 15 / mesh payload 4; the current
schema 17 / mesh payload 5 also preserves independent acoustic boundary roles for
imported faces, Blocks and the built-in stage.

Android's **Calculation & project → Import audience mesh** reads bounded UTF-8 text
and parses/validates it on a worker, with cancellation, before adding a zone. The inspector
shows vertex/triangle/boundary-loop counts, vertical listening offsets and cutout scope.
Authored height controls/breaklines remain hidden for imported geometry; they cannot be
combined with imported topology. Base/rake, rotation, mirroring, duplication, cutouts,
selection, predictions and area reports use shared surfaces. Changing face coordinates
or topology still requires editing and reimporting the source file. Heatmaps and contours
use only active facets and true 3D topology, including vertical faces.

Per-face audience assignments are implemented in the shared core. Each original triangle
has one immutable `MeshFaceAssignment`: `FOLLOW_ZONE`, `SEATED`, `STANDING`, or `EXCLUDED`.
Follow-zone faces inherit the parent audience type; explicit assignments retain their
listening offset when the parent type changes, including when it becomes non-audience.
Excluded faces preserve the original topology and remain selectable reference geometry;
they contribute neither requested area nor unavailable area. Active faces with missing
predictions still contribute to unavailable area and the target-coverage denominator.
The complete mesh retains its original validation rules; disconnected active subsets
are allowed without altering that mesh. A fully inactive mesh yields empty coverage,
retains a zero-area identity, and never enables the venue preview floor accidentally.

Sampling keys include named-group membership, assignment and XYZ, so adjacent faces
keep independent listening heights and group boundaries. For ungrouped faces, shared
statistics identify inherited faces by `area:<id>` and explicit audience roles by
`area:<id>:seated` / `area:<id>:standing`; each has its own physical-area summary,
including zero-area role summaries after cutouts. Overall statistics count each active
facet once. Assignment values are part of equality/cache keys and frozen prediction
requests. Edits invalidate current results and go through the existing revision gate.

Android's **Face assignments** inspector previews original OBJ face numbers (one-based
ranges), all faces, or a connected coplanar patch around the first face. Patch selection
is shared topology traversal, constrained to within 0.00001 m of the seed face's plane.
Selection is transient and does not invalidate predictions. Apply performs one undoable
batch edit. Preview edges are yellow; inherited edges cyan, seated green, standing purple,
and excluded gray. Excluded faces remain visible even under plan cutouts. Arbitrary
per-face listening offsets and automatic acoustic-role assignment from OBJ names/materials
remain future additions; named OBJ group mapping is implemented.

Named face groups are implemented in shared `MeshFaceGroup` and `AudienceMesh`. A group
has a positive integer ID, a trimmed name of 1–80 characters without control characters,
and immutable original triangle membership. Names are unique within a mesh ignoring case;
at most 128 groups are supported. A face belongs to at most one named group, or remains
ungrouped. Disconnected members are allowed. Empty groups are retained for later editing.
Rename preserves the group ID. Creating, adding to or replacing a group's membership moves
those faces out of other groups atomically; malformed overlapping persisted groups fail
validation. Deleting a group or removing members preserves faces and their assignments.
Group-wide assignment applies to current members in one operation; it is not an inherited
group default, so subsequently moved faces keep their own seated/standing/excluded roles.

Named groups use `area:<zone-id>:group:<group-id>` identities and a parent/group display
name. Each summary combines all active member faces, including mixed audience types;
receivers still retain their individual assignments and ear heights. Adjacent groups never
share receiver indices. Excluded members contribute no area; unavailable active predictions
remain in missing area and the target denominator. Empty or fully excluded groups have
named zero-area summaries. Overall statistics integrate each active facet once, with no
extra parent-area bucket duplicating named-group area. Names and membership are part of
immutable prediction inputs and geometry cache keys; edits invalidate results and labels.

Android's inspector creates groups from the current selection, recalls a group's faces,
renames it, moves selected faces into it, replaces membership, ungroups selected faces, or
deletes a group while keeping faces and assignments. The assignment controls can apply to
the current selection or the explicitly chosen named group. Each edit uses one undo
checkpoint. Viewport **Group** mode selects the tapped face's whole group (including
excluded members), falling back to one face when ungrouped. Recall/highlight changes are
transient and do not invalidate coverage. Transforms, duplication and cold reopen retain
group IDs/names/membership; IDs are scoped to the owning mesh/zone.

Direct viewport picking is implemented. The dedicated **Faces** tool sends a camera ray
to shared `ImportedMeshPicking`, then selects the nearest original imported triangle
across zones. The inspector's **Single**, **Add/remove**, **Flat patch**, and **Group** modes use
shared selection rules; switching zones starts a new selection. A miss clears the face
highlight without a floor-placement action. Normal **Select**, speaker/block/listener
placement, and orbit/pan/zoom keep their existing routing. The Faces tool targets imported
source meshes only; speaker and other venue visuals do not occlude its editable targets.

Shared `ViewportRay.perspective` converts top-left screen coordinates using the camera
pose, aspect ratio and vertical field of view. It accounts for camera near/far planes
using axial depth, including off-center rays. Android uses the same shared projection for
face, speaker and floor taps. Two-sided Möller–Trumbore tests use actual base/rake-transformed
XYZ vertices, never ear heights or coverage triangles. Distance is measured along a
normalized ray; shared-edge/vertex and coincident-mesh ties resolve by zone ID then original
face index. Invalid, behind-camera, parallel/coplanar, out-of-viewport and clipped rays
return no face. Geometry queries support cancellation and scan the existing bounded
source meshes on taps, not per frame; spatial acceleration remains a large-scene option.

Picking includes excluded faces and faces under measurement cutouts, allowing their
assignments to be restored without first computing coverage. Intrinsic openings have no
source triangles and are not selectable. The nearest face wins even if a wire-only mesh
shows faces behind it; orbit to select the other side. Highlight and tap mode are transient
and do not change prediction revisions, geometry caches or project schema. Applying the
selection uses the existing single-checkpoint assignment command and stale-result gate.

### Overlay inspection, numerical differences and changed-sample filtering batch

Shared-core compares classifications, observed/fitted/residual availability and numerical values with
an absolute 0.000001° tolerance. Segment renumbering and whole-turn display alignment do not count
as changes. Missing values remain unavailable rather than becoming zero differences. Filtered sample
navigation is ordered and bounded; nearest-marker hit tests share the actual rendered coordinates,
including separate unavailable-data tick rows, with deterministic frequency/series tie breaking.
Filtering retains the common axes and removes connecting lines across hidden samples.
Android overlays support taps in either plot/series, synchronized selection highlights, Previous/Next,
and side-by-side current/preview values with preview-minus-current fitted/residual differences.
All samples / Changed samples controls filter both plots and navigation, explain empty results and
clear a selection hidden by a filter change. Selection reuses the saved pair/frequency inspection state;
the filter is a backward-compatible saved preference. These display preferences are excluded from
history-preview staleness checks, while calculation/history changes still invalidate Apply.

Tests cover values/signs, unavailable data, change tolerance, segment identity, both plot/series hit
targets, missing ticks, deterministic ties, empty/filtered navigation, gap-safe filtered lines,
selection/filter save/reopen, legacy defaults, history apply and Android scene undo.

Batch validation (2026-11-14): all 868 tests passed (455 shared-core, 413 Android),
with the debug APK build and iOS simulator shared-core compilation successful.
On the Android tablet, tapped phase and residual markers, inspected equal fits and a rejected
sample with a 100° residual, filtered 21 frequencies down to 6 changed samples, and navigated
between filtered samples. A cold restart restored the changed-only preference and 960 Hz
selection with identical details. Applying a history preview after changing inspection selection
successfully navigated history. No Android runtime errors were reported in the checked log.
Restored all eight original project/preferences files byte-for-byte from the backup; the original
two-speaker project reopened with up-to-date results and its 74.9 dB area average.

### Overlaid current and previewed history fits

Shared-core compares immutable current/candidate fit samples on common axes, with per-frequency
fitted/residual differences only where both values exist. It aligns overlapping segment observations
by whole 360-degree turns, including when exclusions split one fit into several segments; offsets
are applied equally to observed and fitted phase so residuals do not change. Conflicting branches
or different traces/references/windows disable the overlay with a reason. No missing, excluded or
insufficient values are filled with zeros. Shared fitted-line topology breaks at gaps/segment changes.
Android previews overlay solid blue current and dashed orange proposed fitted curves, with different
marker sizes, crosses for rejected samples and separate bottom tick rows for unavailable samples.
Phase and residual panels use shared scales; raw saved trace/history data remains unchanged.

Tests cover phase-branch alignment, residual invariance, shared axes, missing/excluded/insufficient
samples, gap-safe lines, unavailable fits, incompatible references, and comparison after save/reopen.
Validation passed: 862 tests (450 shared-core, 412 Android), Android debug build and iOS simulator
shared-core compilation. Tablet visual verification confirmed both series on shared phase/residual
axes with distinct styles and preserved gaps. The original project was restored and verified.

The planned overlay-inspection/differences/filtering batch is now implemented above and validated together.

### Previewing exclusion history states

Shared-core creates a transient history-state preview without modifying the saved library. It
calculates current and candidate fits, state counts, and the captured frequencies newly excluded
or included (accounting for overlapping range/sample exclusions). Applying validates a fingerprint
of the source library plus the history snapshot; changes to history, traces, references or settings
reject a stale preview atomically. A candidate with an unavailable fit can still be reviewed and
applied as an intentional history state. Delay references and speaker settings are never applied.
Android history selection opens a separate preview dialog with current/proposed summaries and
read-only candidate phase/residual plots. Cancel preserves saved state and drafts; Apply history
state commits one undoable navigation, refreshes the parent fit and discards unsaved range drafts.
Preview dialogs are transient; only applied history states persist through save/reopen.

Tests cover non-mutating previews, effective exclusion differences, matching-window behavior,
current/candidate fit accuracy, unavailable fits, stale apply, navigation, save/reopen and Android undo.
Validation passed: 858 tests (446 shared-core, 412 Android), Android debug build and iOS simulator
shared-core compilation. Tablet previews showed the expected frequency differences and candidate fit
plots; saved data stayed unchanged before applying and Cancel retained state 2. Explicit Apply moved
to state 1, retained redo, and left captured traces/references unchanged. The applied 940–960 Hz
exclusion survived force-stop/reopen. The original project was restored and verified against its backup.

### Exclusion history panel and state navigation

Shared-core exposes a pair-scoped immutable history view with chronological state positions,
descriptions, sample/range counts and the current position. The first row is the state before the
available edits, which may already contain exclusions when older history has expired. No-history
projects show one current state. Navigation validates the history snapshot and target, then applies
all required undo/redo steps as one immutable result. Stale or invalid navigation cannot partially
modify the library. Other pairs' exclusions, trace data and references stay unchanged.
Android fit diagnostics provide an expandable history panel with current/earlier/redo-available rows.
Choosing a state opens a preview; explicitly applying it recalculates the fit, clears unsaved drafts,
and creates one scene undo checkpoint. Current rows are disabled; the full retained history can be scrolled. Panel expansion is
transient; saved history and its current position reopen through existing persistence.

Tests cover multi-step navigation, sequential undo/redo equivalence, pair isolation, stale views,
invalid/deleted targets, branching, bounded/empty history, save/reopen and one-step Android scene undo.
Validation passed: 852 tests (441 shared-core, 411 Android), Android debug build and iOS simulator
shared-core compilation. Tablet checks navigated from state 2 to 0, forward to 2, then to state 1.
After force-stop/reopen the panel correctly marked state 1 Current and state 2 Redo available.
The original project was restored and verified byte-for-byte against its backup.

### Descriptive exclusion history labels

Shared-core derives descriptions from each history entry's saved before/after states. Labels identify
sample inclusion/exclusion and Hz, range addition/removal and bounds, and moves/resizes with old and
new bounds. Equal-width replacements are described as moves; width changes as resizes. Replacement
with an already-existing range is described truthfully as removal of the redundant range. Compound
imported changes receive deterministic count summaries. Descriptions are generated rather than
persisted, so existing history files gain labels without migration or stale saved text.
Android shows the next pair-specific action directly in Undo/Redo buttons, arranged vertically so
long range descriptions can wrap. Empty histories retain disabled generic labels.

Tests cover all edit descriptions, duplicate/no-op behavior, pair isolation, history navigation and
branching, legacy/compound history, deletion and save/reopen, including Android integration.
Validation passed: 846 tests (436 shared-core, 410 Android), Android debug build and iOS simulator
shared-core compilation. Tablet checks verified move descriptions, the preceding action after Undo,
redo descriptions after reopening, and the restored Undo description after Redo. The original
tablet project was restored and verified byte-for-byte against its backup.

### Exclusion undo and redo in fit diagnostics

Shared SavedTraces maintains bounded exclusion history (64 undo and 64 redo entries), with each
entry scoped to its ordered A/B pair and carrying sample/range states before and after the edit.
Add/remove, resize and movement all record one saved edit; no-ops preserve redo. Undo/redo applies
only to that pair, retaining other pairs, raw phase data, delay references and matching settings.
New edits clear only that pair's redo branch; trace deletion removes its history. History is saved
with the library, legacy files default to empty history, and invalid/discontinuous history rejects
imports atomically. Shared validation prevents stale state replay.
Android fit diagnostics provide enabled/disabled Undo exclusion edit and Redo exclusion edit buttons,
refresh the open preview immediately and discard unsaved range drafts when history is navigated.
These actions also participate in the existing scene undo system.

Tests cover pair isolation, sample/range operations, movement, no-ops, branching, bounded history,
trace deletion, stale previews, malformed imports, unchanged traces/references, save/reopen and Android undo.
Validation passed: 840 tests (431 shared-core, 409 Android), Android debug build and iOS simulator
shared-core compilation. Tablet Undo restored six excluded samples and Redo removed them with the
preview open. A pending redo survived force-stop/reopen and worked from the reopened fit plot.
The original project was restored and verified byte-for-byte against its backup.

### Moving whole plot exclusion ranges

Shared geometry translates both endpoints by the same whole-Hz offset, preserving exact Hz width
including off-grid bounds. It clamps the translation at the matching plot limits without shrinking
the interval. Invalid/non-finite drags, starts outside the band and ranges not fully visible are
rejected. A range as wide as the plot stays fixed. Movement is measured from the gesture's original
range, avoiding cumulative rounding drift. Android edit mode offers Move whole range separately
from Resize edges, including for narrow bands. Draft movement appears in both plots; Save range edit
uses the existing atomic replacement, undo and persistence path. Cancel keeps the original range.

Tests cover movement in both directions and plots, off-grid widths, clamping, invalid starts,
full-width and hidden ranges, unrelated exclusions, alignment accuracy, stale previews and save/reopen.
Validation passed: 835 tests (427 shared-core, 408 Android), Android debug build and iOS simulator
shared-core compilation. Tablet phase-plot dragging changed 930–980 Hz to 967–1017 Hz, retaining
50 Hz width; vertical scrolling also worked. Remaining native checks subsequently passed:
left-boundary clamping in the phase plot produced 900–950 Hz; right-boundary clamping in the residual
plot produced 1050–1100 Hz. Both preserved the 50 Hz width. Saving updated the fit immediately, and
1050–1100 Hz appeared in the exclusion list after force-stop/reopen. The original project was restored
and its recovery file verified byte-for-byte against `/tmp/move-final-original.tar`.

### Editing plot exclusion ranges

Shared geometry selects existing bands deterministically (narrowest overlap, then lowest bounds),
hit-tests visible edges and snaps only the dragged endpoint to captured frequencies. The fixed
endpoint retains its exact value, including off-grid bounds. Crossing/equal endpoints are rejected;
outside drags clamp to the plotted data. Edges outside the matching window require widening it.
Shared immutable replacement validates the original still exists and preserves all unrelated ranges
and individual exclusions; identical resulting ranges deduplicate. Android edit mode shows bands
and yellow selected edges in both plots, offers explicit selection for overlaps, and saves a resize
as one undo checkpoint with immediate preview recalculation. Cancel discards the draft.

Tests cover selection, both edges/plots, overlap behavior, clamping/crossing, off-grid fixed bounds,
stale edits/previews, alignment accuracy, save/reopen and Android undo.
Validation passed: 831 tests (424 shared-core, 407 Android), Android debug build and iOS simulator
shared-core compilation. Tablet checks selected an existing band directly, resized its upper edge
in the phase plot and lower edge in the residual plot, saved 930–980 Hz with immediate fit updates,
and confirmed that range after reopening. The original project was restored afterward.

### Interactive plot range selection

Shared plot geometry snaps horizontal drag endpoints to the nearest captured frequencies, clamps
outside endpoints, normalizes either direction and rejects non-finite or single-frequency spans.
Ties choose the lower frequency. Shared selection validation requires both endpoints in the current
preview before creating a pair-scoped exclusion. Missing/excluded samples remain selectable.
Android offers an explicit range-selection mode on phase and residual diagnostic plots. Both plots
highlight the draft span; Clear or switching back to inspection discards it. Exclude selected range
saves the existing shared range-exclusion model, recalculates the open preview, and supports undo
and save/reopen. Draft highlights are transient and never silently change matching behavior.

Tests cover both plot coordinate systems, reverse drags, clamping, snapping/ties, degenerate spans,
invalid endpoints, inclusive exclusions, alignment accuracy, stale previews and save/reopen.
Validation: 827 tests passed (421 shared-core, 406 Android), Android debug build and iOS simulator
shared-core compilation passed. Tablet checks verified forward phase-plot and reverse residual-plot
drags, vertical scrolling, immediate preview recalculation and saved 940–960 Hz exclusion data.
The original tablet project was restored after testing.

### Frequency-range matching exclusions

Shared-core stores inclusive whole-Hz exclusion intervals per ordered A/B trace pair. Their union
with individual exclusions is resolved against captured frequencies before unwrapping and robust
fitting. Overlaps never count a sample twice; removing an interval preserves overlapping intervals
and individual exclusions. Off-grid endpoints select captured samples without interpolation.
Ranges survive save/reopen, reference changes and matching-window changes; deleting either trace
removes its ranges. Changed effective exclusions invalidate stale fit previews. Legacy projects
without ranges reopen with none; malformed intervals or missing trace IDs reject imports atomically.
Android provides add/remove range controls beside the matching window. Sample inspection identifies
range-covered samples and directs users to remove covering intervals. Undo is supported.

Validation covers known-delay accuracy, inclusive/off-grid endpoints, overlapping and individual
exclusions, pair isolation, no-data/all-excluded ranges, persistence, deletion, invalid imports and undo.
All 824 tests passed (418 shared-core, 406 Android), alongside the Android debug build and iOS
simulator shared-core compilation. Tablet checks verified inclusive 940–960 Hz exclusion (three
samples), unchanged +1.655 ms matched correction, saved range data, and range removal. The original
tablet project was restored afterward.

### Manual automatic-match sample exclusions

Manual exclusions are stored in shared-core by ordered A/B trace pair and exact captured frequency.
They survive save/reopen, reference and range changes, and are removed when either trace is deleted.
Excluded samples split unwrapping segments before fitting, cannot affect robust seeds or rejection
statistics, and are displayed separately from automatically rejected samples. Fewer than three
consecutive usable samples cannot produce a fit. Raw phase captures and speaker delays are unchanged.
Changed exclusions invalidate older apply previews. Android sample inspection offers Exclude/Include,
recalculates the preview immediately, and uses orange markers for excluded frequencies; undo is supported.

Tests cover known-delay accuracy across an excluded phase discontinuity, pair isolation, re-inclusion,
all-excluded failure, stale previews, range/deletion handling, invalid imports, save/reopen and Android undo.
Validation passed: 819 tests (414 shared-core, 405 Android), Android debug build and iOS simulator
shared-core compilation. Tablet checks verified exclusion, immediate plot updates, saved recovery data
and reinclusion. The original project was restored and the final tested APK installed.

### Interactive fit-sample inspection

Shared `MatchPlotGeometry` now owns plot scaling, marker coordinates and nearest-marker hit testing
for phase-difference and residual views, including missing-data markers. Selection uses a bounded
pixel radius with deterministic lower-frequency tie breaking; blank/outside taps do not invent
samples. `MatchSampleInspection` provides exact-frequency selection and previous/next navigation,
including missing and insufficient samples. `MatchSampleSelection` stores only trace IDs and Hz,
resolving current status/observed/fitted/residual values from a fresh shared preview.

Android highlights the selected marker in both plots and shows frequency, classification, observed
phase difference, fitted phase difference, residual and segment. Previous/Next and Clear controls
provide alternatives to touch selection for dense samples. Shared geometry drives both rendering
and taps. The library persists the selection; the inspector defaults to its trace pair after reopen.
Selections reconcile when an involved trace is deleted or the matching range excludes the frequency.
Reference changes retain the frequency and recompute its details. Invalid stored selections fail
atomically. Inspection does not rewrite raw traces or adjust speaker DSP.

Validation covers retained/rejected/missing hit targets, empty/outside taps, deterministic ties,
frequency navigation, pair mismatch, save/reopen, matching after selection, range/deletion cleanup,
invalid imports and Android undo. All 815 tests passed (411 shared-core and 404 Android), together
with the Android debug build and iOS simulator shared-core compilation. On the Android tablet,
marker taps showed rejected and missing sample details, Next selected a retained sample, and the
selected trace pair/frequency appeared in saved recovery data. The original tablet project was
restored afterward; its prediction results remained available and no Android runtime crashes were logged.

### Visual automatic-match fit diagnostics

Shared-core previews now expose immutable per-frequency diagnostics: unwrapped relative phase
difference, fitted phase, residual, original missing-data segment identity, and retained/rejected/
missing/insufficient status. Predictions use the actual refitted slope and independent segment
intercepts, including residual evaluation at rejected samples. Points without a valid segment/model
have no invented fit value. Diagnostic sample counts and rejected frequencies agree with matching
results; rejected fits still expose diagnostic data where a model could be computed.

Android previews include phase-difference and residual plots on a linear-Hz axis, with blue retained
circles, red rejected crosses, white fitted lines and grey missing/insufficient markers. Model lines
join retained endpoints only within the same original segment, never across missing-data gaps.
These are fitted model lines, not interpolation of rejected measurements. Legends, units, ranges
and accessibility summaries identify the plots. The preview body scrolls while Apply/Cancel remain
outside the scrollable content. Matching decisions and persistence remain in shared-core.

Diagnostics are recomputed from raw traces, references and selected range after save/reopen; no
plot pixels or redundant fitted values enter project storage. Tests check the displayed model slope,
residual/RMS agreement, retained/rejected counts, gap boundaries, selected ranges, insufficient and
rejected fits, and diagnostics before/after apply and reopen. All 810 tests passed (407 shared-core,
403 Android), plus Android debug assembly and iOS simulator core compilation. Tablet screenshots
confirmed blue retained samples, red rejected crosses at 950/1050 Hz, separated fitted segments
around missing 1000 Hz data, and the expected 100° outlier residuals. Both plots scroll with
Apply/Cancel accessible. No Android runtime crashes were reported; the original project was restored.

### Confidence diagnostics and robust matching

The shared matcher now seeds its slope with deterministic median pairwise slopes (at most 64
uniformly indexed seed samples per original segment), then uses each segment's median intercept
to detect inconsistent phase samples. Its rejection threshold is three times the scaled median
absolute deviation, floored at 5° and capped at 15°. Retained samples are refitted with independent
segment intercepts. Original missing-data segments remain separate; no samples are synthesized.
Rejected points are omitted only from regression, not deleted or replaced in saved traces. Segments
with fewer than three retained points are dropped from fitting. More than 25% rejected eligible
samples, a residual fit above 10° RMS, or insufficient remaining support prevents application.

The preview reports rejected frequencies and a heuristic 0–100 confidence score. The score is
`100 × min(retained/12,1) × (retained/selected) × (retained/eligible) × exp(-RMS/10) × margin`,
rounded and bounded to 0–100. `selected` includes missing/unsupported samples in the chosen grid;
`eligible` counts original segments with at least three valid samples. `margin` is
`min(1,4 × max(0,1-|correctionMs|/aliasLimitMs))`, using retained within-segment frequency spacing.
Failed matches score zero. Labels are High ≥80, Moderate ≥50 and Low otherwise. These are
engineering diagnostics, not statistical probabilities or calibration claims. Even high confidence
cannot detect hidden complete phase turns; sparse or corrupted unwrapping can still be ambiguous.

Diagnostics are deterministic and recomputed from saved raw samples, references and selected range;
they need no new persistence schema. Applied references retain the existing save/reopen and undo
behavior. Android displays the score, retained/eligible counts and rejected frequencies before apply.

Method reference: [SciPy's Theil–Sen definition](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.theilslopes.html).
The bounded sampling, segmented offsets, rejection limits and confidence formula are this app's
implementation choices, not SciPy confidence intervals or an industry certification.

Validation covers clean, noisy, sparse, isolated/endpoint outliers, widespread contamination,
missing-data segments, unbiased delay recovery, preservation of rejected raw values, repeatable
save/reopen diagnostics and Android apply/reopen behavior. All 806 tests passed (403 shared-core,
403 Android), plus Android debug assembly and iOS simulator core compilation. Tablet acceptance
identified synthetic outliers at 950/1050 Hz, retained 18 of 20 eligible samples across a missing-data
gap, reported 77/100 confidence and recovered the expected +1.655 ms adjustment. Applying the
match preserved rejected raw values and the missing sample in recovery storage. No Android runtime
crashes were reported; the original tablet project was restored.

### Frequency-limited automatic reference matching

Shared `MatchingFrequencyRange` validates positive whole-Hz inclusive bounds with minimum below
maximum. A null selection retains all captured frequencies. `TraceReferenceMatching` filters
samples before phase-difference unwrapping and fitting, so outside-range slopes, missing samples
and ambiguities do not influence the selected fit. No endpoints are synthesized. Each valid segment
still needs three common samples; missing data inside the range still splits segments. Previews
report the selected bounds and actual minimum/maximum fitted frequency, along with existing fit
diagnostics. Apply rejects a preview if the selected range has changed.

The saved-trace library persists the selection through capture, deletion, reference edits, matching
and save/reopen. Legacy libraries default to all frequencies; invalid stored ranges fail atomically.
Android adds **Matching range · Edit** with minimum/maximum Hz and **Use all frequencies**. Range
changes are undoable and clear any old preview. Only matching is restricted: full trace displays,
raw captured data and speaker DSP remain unchanged. A fit over one band may not align other bands.

Validation covers independent delays in different frequency regions, inclusive endpoints, absent
boundary samples, insufficient/out-of-range data, internal missing-data gaps, stale previews,
persistence, reset, Android undo and invalid project imports. All 800 tests passed (398 shared-core,
402 Android), plus Android debug assembly and iOS simulator core compilation. Tablet acceptance
verified that 1000–1004 Hz selected exactly three of five captured samples and produced the expected
+1.655 ms match. The recovery file retained the range and applied reference without dropping raw
samples. No Android runtime crashes were reported; the original project was restored.

### Automatic matching of saved-trace delay references

Shared `TraceReferenceMatching` previews a candidate B reference that matches its relative phase
slope to baseline A. It fits the unwrapped B-relative minus A-relative phase difference in linear
Hz, using a shared slope and independent intercepts across contiguous valid segments. Each fitted
segment needs at least three samples. Missing/omitted samples split segments; no frequency
interpolation or bridging is used. Samples have equal weight because band powers are not exact-tone
confidence measurements. The matcher rejects incompatible captures, self-matching, insufficient
data, exact half-turn ambiguity, out-of-range references and fits exceeding 10° RMS residual error.
Constant phase offsets and polarity are retained, not mistaken for delay.

Android adds **Match B reference to A…** with a preview of B's reference/change, sample and segment
counts, fit error and residual alias limit. Applying changes B's manual reference and selects
relative display in one undoable operation. Raw samples, baseline reference and speaker DSP
remain untouched. Preview fingerprints cover both captures and reference settings; stale or removed
traces cannot be overwritten. The applied reference uses the existing saved-trace persistence.

This is local phase-slope matching around the selected references, not globally unique impulse
response alignment. Hidden full turns between sparse samples remain undetectable; a low RMS fit
error is not proof against aliasing. Differing phase responses may fail the linear-fit threshold.

Validation covers known positive/negative delays, constant phase offsets, nonuniform grids,
manual-reference offsets, missing data, incompatible/ambiguous/nonlinear rejection, stale previews,
atomic application, undo and save/reopen. All 794 tests passed (393 shared-core, 401 Android),
plus Android debug assembly and iOS simulator core compilation. Tablet acceptance previewed
a +1.655 ms adjustment, applied it and confirmed zero relative phase/group delay for matching
synthetic traces. The recovery file preserved the reference and relative display with unchanged
raw phases. No Android runtime crashes were reported; the original project was restored.

### Adjustable trace delay references

Saved traces now preserve a nullable manual reference separately from their captured automatic
reference. The shared core validates finite values within ±3,600 seconds; null resets to the
automatic reference (or zero for legacy traces). The library persists a common total/relative
display setting. Relative mode subtracts each selected reference from wrapped phase, unwrapped
phase and interval group delay. Total mode restores the reference after unwrapping; raw wrapped
phase and band powers remain unchanged. Residual alias limits and missing/ambiguous sample gaps
still apply. A poorly chosen reference can select a different unwrap branch; this is not a
speaker DSP adjustment or a guarantee of uniquely recovered phase.

Android adds A/B reference editors in milliseconds, reset-to-automatic buttons and total/relative
controls before the phase comparisons. Adjustments are immutable library replacements, undoable
and included in project save/reopen. Editing one trace affects every comparison using that trace;
A and B therefore share the reference if both select the same trace. Original measured samples,
captured delay references and speaker settings are retained.

Validation covers positive/negative/zero/reset references, total versus relative phase and delay,
missing data and half-turn ambiguity, persistence through library mutations, invalid edits/imports,
project save/reopen and Android undo. All 788 tests passed (388 shared-core, 400 Android),
plus Android debug assembly and iOS simulator core compilation. Tablet acceptance verified
the editor and relative display: changing B from its automatic 11.655 ms reference to 10 ms
produced 1.655 ms relative delay while A stayed at zero. The recovery file retained the manual
reference and display mode. No Android runtime crashes were reported; the original project
was restored after testing.

### Dense complex-frequency phase capture and delay-referenced unwrapping

Listener and saved-reference capture now use the union of actual complex measurement frequencies
and requested analysis bands. Complex frequency interpolation is not invented; a frequency absent
from any contributing speaker remains unavailable. Band-integrated powers retain their original
frequency grid and meaning. Imported complex datasets support up to 4,088 measured frequencies;
a trace is limited to 4,096 phase samples (leaving room for eight analysis bands). Oversized unions
are rejected rather than silently dropping measurements. Coverage calculations do not retain dense
phase traces. Ground absorption at measured frequencies uses the nearest supplied band on a log
frequency scale, consistent with a band-based amplitude model; measured reflection phase continues
to interpolate using its existing rules.

Each receiver capture freezes the minimum geometric emitter-to-receiver propagation time plus
emitter DSP/steering delay. This is an unwrapping reference, not a claim that an unobstructed arrival
exists. The shared core subtracts this linear phase before nearest-turn unwrapping and restores it
in the resulting phase and total interval group delay. The residual phase must still change by less
than 180° per interval; interference nulls, missing data and ambiguous residual half-turns break
segments. Legacy traces retain a zero reference. Dense phase and the reference persist independently
of the band powers and survive batch capture, project recovery and save/reopen. Android shows the
saved references and full plots, with numeric previews limited to 64 rows for responsiveness.

Validation covers dense measured grids, known propagation/DSP/cabinet group delay, delay alias
removal, missing contributing-source samples, legacy behavior and dense listener/reference
save/reopen. All 783 tests passed (384 shared-core and 399 Android), plus Android debug
assembly and iOS simulator core compilation. Connected-tablet capture retained 201 supplied
frequencies; the displayed 11.655 ms group delay matched the four-metre synthetic path.
The saved recovery file preserved all samples and the delay reference. No Android runtime
crashes were reported, and the original tablet project was restored.

### Unwrapped phase and group-delay traces

Shared `PhaseDerivedTraces` derives nearest-turn unwrapped segments and interval group-delay
estimates from captured complex phase. It sorts frequencies, uses linear-Hz differences and
the engine's positive-delay sign, and breaks at missing samples or ambiguous 180° steps.
Unsupported conventions and mismatched comparison grids are rejected. Single-frequency
captures have no delay; missing values are never substituted with zero. The raw wrapped
samples remain the persisted source of truth, so save/reopen reproduces the derived views
without a schema migration. Android listener comparisons add unwrapped and group-delay plots
and numeric values with explicit alias limits and the sparse-sampling assumption.

Scope: these are sampled estimates, not uniquely recovered absolute phase turns or arrival
times. Octave samples can alias even with valid complex data; dense measured-frequency capture and delay-referenced unwrapping are implemented in the
next milestone above. No magnitude-derived
phase or complex-frequency interpolation is invented. Group-delay values describe intervals;
plots do not connect them into an unmeasured continuous derivative.

Validation: wrapping in both directions, nonuniform frequency spacing, missing/omitted samples,
half-turn ambiguity, singleton/all-missing captures, convention/receiver/grid mismatch,
known aliasing and save/reopen. All 778 tests passed (380 shared-core, 398 Android),
as did Android debug assembly and iOS simulator core compilation. Connected-tablet acceptance
verified synthetic 1.000/1.500 ms intervals, wrapped-to-unwrapped values and missing-data gaps;
the original project was restored after testing. No Android runtime crashes were reported.

### Global ground-reflection phase traces

The shared global ground model now accepts an optional measured `ReflectionPhase` curve.
The ground image path records its incidence angle, and exact-tone phase combines its complex
arrival with direct and first-order boundary arrivals. Global ground amplitude still comes
from the existing floor absorption curve. Measured phase is converted from exp(+iwt) to the
positive-delay convention, with no cabinet phase inferred from magnitude. A missing ground
phase leaves phase unavailable for nonzero, partially absorbed bounces. A fully absorbed path
requires no complex sample; an explicitly ideal rigid (zero-absorption) core model uses zero
phase. Below-ground source/receiver configurations produce unavailable phase.

The same ground phase affects band-power interference, including frequency-knot subdivision
and phase slope integration. The existing real-coefficient magnitude approximation remains
when no measured phase is supplied. Global ground owns coincident y=0 rigid Faces and Blocks;
opaque patches still suppress it. Coincident ground-only scattering surfaces do not invalidate
the owning global model, while other active diffuse scattering remains unsupported.

Android adds **Settings → Ground model → Add/Edit ground reflection phase**, with frequency,
incidence angle, unwrapped phase and measurement-source fields. Curves use the existing shared
validation/interpolation rules. Project and design-variant captures preserve the curve;
set/remove is undoable and invalidates pending predictions. Legacy projects load no phase.
New trace convention `coherent-direct-specular-ground-tone-positive-delay-v3` prevents silent
mixing with earlier models; older matching-model captures remain comparable with each other.

Validation includes independent complex interference, incidence-dependent phase, narrowband
power agreement, missing complex/ground data, coincident Faces/Blocks, opaque suppression,
scattering ownership, below-ground rejection and save/reopen/variant/undo behavior. All 771
tests passed (374 shared-core, 397 Android); Android debug assembly and iOS simulator core
compilation passed. Connected-tablet acceptance verified the ground-phase editor, variant
restoration and trace capture. A synthetic 0° → 90° ground change produced −3.23 dB and
−38.0° at 1 kHz; both comparisons survived a cold reopen. No Android runtime crashes were
reported. The original tablet project was restored and retained its 74.9 dB(A) area average.

### Reflected-path phase traces for Blocks and imported Faces

Shared `SpecularPaths` now enumerates the direct and first-order reflected arrivals for both
band-power prediction and exact-tone phase. Both paths share occlusion checks, bounce visibility,
coincident Block/Face deduplication and the existing ground ownership rules. `PhaseResponse`
sums measured complex source pressure in the direction of each image receiver, applying path
length/air loss, source DSP delay/polarity, incidence-dependent material absorption and material
reflection phase. Material phases use exp(+iwt), so their sign is converted to the source
positive-delay convention rather than added with the wrong sign.

Ideal rigid boundaries explicitly supply zero reflection phase. Nonzero reflections from
absorption-only materials require measured reflection phase; no partial sum is published when
speaker data is missing along a reflected direction. Fully absorbed reflections need no complex
sample. Occluded paths are omitted; zero energy/cancellation still has undefined phase. Any
active reflecting boundary with a scattering model conservatively marks phase unavailable:
energy-only diffuse scattering has no coherent transfer. The global ground control was initially unsupported here; the ground-phase milestone above
now provides its measured phase input.

New traces use `coherent-direct-specular-tone-positive-delay-v2`. Old direct-only traces remain
readable and comparable with each other, but mixing old and new models requires recapture.
Android explains the reflected model and labels legacy captures. Both listener and saved
reference capture use the shared path; phase values, convention and missing reasons persist.
Magnitude-only coverage retains its legacy integration; declared version-2 complex data now uses
intrinsic cabinet response in the shared band integrator. Higher reflection orders, diffraction and diffuse phase remain out of scope.

Validation covers analytic complex interference at four material phases, Block/Face equivalence,
coincident deduplication, missing reflected speaker/material phase, absorbed and occluded paths,
scattering rejection, legacy comparisons and Android project save/reopen. All 765 tests pass
(370 shared-core, 395 Android), along with Android APK assembly and iOS simulator core
compilation. Tablet acceptance used a synthetic complex speaker and reflector: changing the
material phase from 0° to 90° changed the summed 1 kHz phase from −40.1° to −24.6°
(Δ +15.5°); band-averaged magnitude changed by −0.13 dB. Both saved traces and differences
survived restart. This fixture is not measured product data. The original tablet project was
backed up and restored after testing; no Android runtime crash was logged.

### Phase-response traces from explicit complex speaker data

The shared portable speaker format accepts optional `complexResponse` data: directional
real/imaginary pressure samples at exact frequencies, normalized to 20 µPa at 1 m for a
declared common drive, with measurement propagation removed. A versioned positive-delay
convention and nonempty provenance are mandatory. Unknown conventions and malformed grids
are rejected. This is an explicit decoded JSON extension, not an assertion that existing CLF
imports contain phase. Magnitude-only and generic models never acquire assumed cabinet phase.
Angular interpolation uses Cartesian components; frequency interpolation is not inferred.

Shared `PhaseResponse` coherently sums intrinsic complex samples using actual emitter poses,
propagation, attenuation, DSP delays and polarity. It rejects missing frequency/direction data,
incompatible drive references, unknown EQ phase and cancellation/zero-energy nulls. The initial
phase path was limited to coherent direct propagation with dispersion enabled and
no active acoustic boundaries or ground reflection; the reflected-path milestone above extends
this to supported specular boundaries. Unsupported paths produce explanations,
not a partial sum. Band-averaged magnitude coverage remains on its existing calculation path;
declared version-2 data now supports band coverage and specular reflection integration as described above; version-1 data remains exact-tone only.

Listener and multi-reference captures store wrapped exact-tone phase independently of band
powers, along with the phase convention and per-frequency unavailable reasons. Historical
phase samples and embedded complex speaker revisions survive project/variant save/reopen.
Legacy traces remain readable with phase absent. Shared A/B comparisons require the same
receiver and supported convention and use wrapped candidate-minus-baseline phase differences.
Sparse octave samples are plotted as points; no unwrapping, group delay or IR is invented.
Android adds a phase panel below the existing response comparison with per-frequency values.

Tests cover cabinet phase, delay, polarity, cancellation, missing bands/data, drive mismatch,
phase wrap comparisons, unsupported propagation and speaker/trace/project round trips.
Validation: all 758 tests pass (365 shared-core, 393 Android); Android APK assembly and
iOS simulator core compilation pass. A temporary synthetic complex speaker fixture on the
Android tablet produced a +90.0° difference at 1 kHz after adding 0.25 ms delay, with unchanged
magnitude. Saved phases remained +49.1° and +139.1° after restarting. The fixture is not
measured manufacturer data. Original tablet project files were backed up and restored after
acceptance; no Android runtime crash was logged.

### Trace capture across saved reference receivers

Shared `ReferenceTraceSelection` stores ordered stable receiver IDs, validates selections,
and prunes deleted references. Shared `ReferenceTraceCapture` freezes receiver identity,
position and design metadata, validates result counts/band data, and appends an entire batch
atomically to `SavedTraces`. Capacity is checked before computation and publication; a batch
cannot partially fill the 64-trace library. Receiver name/ID are optional metadata fields so
existing version-1 trace archives still reopen. Unknown and zero-energy bands stay distinct.

Android exposes individual receiver checkboxes, Select all/Clear selection and a named batch
capture in the saved-traces inspector. One immutable prediction request calculates the current
design at all selected saved references without moving the listener. Band powers are retained
without unnecessary per-source calculations. Revision/generation checks reject outdated
results after edits, imports or selection changes. A completed batch is one Undo/Redo action.
Historical traces can be filtered by receiver for A/B overlays and per-band comparisons;
physical positions, bandwidth and solver conventions must still match.

Receiver selection is persisted with the scene and captured design variants. Deleted receivers
are removed from the selection, but their historical traces remain available. Legacy projects
start with an empty trace-receiver selection. Save/reopen retains captured coordinates and
metadata, including receivers that were subsequently renamed, moved or deleted.

Validation includes selection order/identity, duplicate names, batch limits, unavailable data,
real per-receiver gain differences, incompatible positions, stale results, Undo/Redo,
variant restoration and save/reopen. All 752 tests pass (361 shared-core, 391 Android),
including per-receiver checks against independent single-point predictions. Android APK
assembly and iOS simulator core compilation pass. Tablet acceptance captured two references
at different positions in two named batches, filtered the same receiver across designs,
verified eight zero-difference bands for identical designs, and reopened both selections
and all four traces. The original tablet project was backed up and restored; no Android
runtime crash was logged.

### Coverage difference maps between saved variants

Shared `CoverageComparisons` calculates both immutable prediction requests and reports
candidate B minus baseline A in dB from physical total powers, before the SPL display floor.
Zero energy and unavailable data produce unavailable differences, not artificial zeroes or
finite extremes. Difference statistics use the existing triangle-area integration, including
vertical and non-planar surfaces; isolated probes have no area weight.

Comparisons require exact prepared mesh correspondence: receiver XYZ, physical surface
height, assignments and triangle topology. Cosmetic labels may differ. Changed holes,
exclusions, layers or sampling profiles are rejected instead of interpolating across different
audience domains. Frequency bands, bandwidth and weighting must also match. Source
position/DSP, acoustic boundaries and atmosphere may differ: those are design differences.

Android selects two saved variants and prepares isolated requests through the existing scene
import adapter, without restoring into the current editor. Shared code owns geometry checks,
calculation, subtraction and statistics. A separate difference-map viewer provides 3D, plan
and front projections, surface filtering and a symmetric blue/white/orange scale. Face colors
show mean vertex differences; the area summary integrates vertex differences over triangles.
Gray marks unavailable triangles. Current live coverage and its SPL legend remain separate.

The shared versioned comparison selection is saved with the project and preserved on variant
restore. Derived maps are deliberately recalculated after reopening; deleting a selected
variant clears the selection, with Undo recovery. Edits/imports and newer selections invalidate
pending publications using cancellation plus a generation check. Captured snapshots exclude
comparison selection, preventing nested workspace state.

Validation covers positive/negative/identical comparisons, geometry/height/topology and
sampling compatibility, holes and imported exclusions, vertical area weighting, missing data,
sub-floor powers, bandwidth/weighting mismatches, stale selection, unchanged live designs,
Undo and save/reopen. All 744 tests pass (358 shared-core, 386 Android), including
comparison while the live design has no speakers. Android APK assembly and iOS simulator
shared-core compilation pass. Tablet acceptance verified named A/B selection, a zero-delta
map over 784 m², visual legend/layout, persisted selection and identical recalculation after
restart. No Android runtime crash was logged. The original tablet project was backed up and
restored after testing.

### Saved design variants and restoration

Shared `DesignVariants` captures immutable acoustic-design snapshots with stable IDs,
validated checksums and a versioned project archive. Captures include source placement,
DSP, geometry, face assignments/materials, analysis settings, listener and saved reference
receivers. Available decoded speaker assets are embedded per snapshot, so variants with
different data revisions under the same model ID restore the correct revision. Missing
assets retain explicit recovery warnings. External visual-model paths keep their existing
project semantics; camera/editor state is not part of the acoustic-design snapshot.

The bounded library supports 16 variants and 64 MiB, rename, delete and serialization.
Snapshots exclude nested variant/trace libraries. Restoration keeps the current project’s
variant and historical trace libraries, validates before mutation, invalidates predictions
and recalculates. Android exposes capture/select/rename/delete/restore in the Listener
inspector; restore supports Undo/Redo. Legacy projects load an empty library.

Validation: 730 tests pass (351 shared-core, 379 Android), including capture isolation,
A/B switching, restored geometry/DSP/reference receivers and prediction equality,
Undo/Redo, save/reopen, exact speaker-data revisions, missing assets and corrupt archives.
Android APK assembly and iOS simulator shared-core compilation pass. Tablet acceptance
verified capture, changed listener position, restoration, Undo and persistence after restart.
The original tablet project was backed up and restored after acceptance.

Remaining phase work includes complex broadband predictions, higher reflection orders, and validated impulse-response displays.

### Saved listener traces and A/B response comparisons

The shared prediction output can retain per-band physical powers for listener requests,
using the same calculation as the current listener level. Coverage requests do not retain
this extra matrix. Shared SavedTraces copies and validates historical band powers, keeps
zero energy distinct from unavailable data, assigns stable IDs and serializes a bounded
64-trace/version-1 archive. Metadata records receiver XYZ, bandwidth, atmosphere,
calculation-start time, input-scene SHA-256 fingerprint, solver/quantity convention and
speaker-data warnings. The input fingerprint excludes the trace archive itself. It is
provenance, not an embedded restorable project or a guarantee of real-model accuracy.

Android capture requires the current listener ticket and revision; invalidated, failed or
in-flight results cannot be captured as current. Capturing stores a copied result before
the normal persisted-state revision change. Save/reopen retains historical traces, while
derived current results must be recalculated. Missing/legacy trace archives load empty;
malformed trace archives are rejected before scene mutation. Deleting a trace supports undo.

The Listener inspector provides named captures, baseline/candidate selection, a logarithmic
frequency overlay and per-band candidate-minus-baseline dB differences. Differences require
matching physical receiver coordinates, analysis bandwidth and solver/quantity conventions.
Band unions retain explicit unavailable entries; zero/unknown powers do not invent finite
level differences. Atmosphere and source warnings remain visible for each capture. Spectrum
mode captures eight octave bands; single-band mode captures only its analyzed band. Values
are unweighted fixed-total-drive band levels, not phase or impulse-response data.

This is saved response comparison. Restorable design variants are implemented above.
Coverage difference maps, multi-reference capture and exact-tone phase traces are implemented
above. Live measurement import and impulse-response traces remain follow-on work.

Validation: 723 tests pass (348 shared-core, 375 Android). Tests cover immutable captures,
unknown/zero-energy bands, comparison sign, incompatible receiver/bandwidth/conventions,
defensive prediction outputs, invalid archives, a real +6 dB scene change, stale capture
rejection, project save/reopen, legacy projects and delete/undo. Canonical frequency ordering
keeps trace serialization stable across Android JSON round trips. Android APK assembly and
iOS simulator core compilation pass. On the tablet, Design A and Design B were captured,
the overlay and all eight zero-difference band rows were inspected for an unchanged design,
and both names survived app restart. The +6 dB changed-design acceptance is automated.
The original project was restored at 74.9 dB(A), with no AndroidRuntime errors.

### Delay-change previews across saved receivers

Shared alignment comparisons accept validated hypothetical DSP-delay overrides without
mutating emitters or project state. AlignmentReferences.previewDelay derives a feasible
selected-receiver proposal, rounds it to the stored DSP precision, and returns before/after
arrival comparisons for every saved reference. If the changed speaker is another
receiver's reference speaker, all relative arrivals there are recomputed. Missing-reference
comparisons remain explicitly unavailable. Infeasible and no-op proposals have no preview.

Android now opens a scrollable preview before applying a suggested delay. Each receiver
shows source arrival and relative-arrival before/after values and whether absolute timing
mismatch increases or decreases. Cancel makes no change. Apply recomputes and compares
the preview against current inputs, rejecting stale proposals. The dialog is cleared when
receivers, speakers, DSP or atmosphere change. A successful Apply uses one normal DSP
edit with undo; previews are derived state and are not saved in projects. This previews
geometric timing only, not coverage, audibility, frequency response or phase alignment.

Validation: 715 tests pass (343 shared-core, 372 Android), covering unchanged inputs
while previewing, before/after equality after Apply, differing receiver effects, changed
reference-speaker timing, impossible/no-op proposals, stale previews, and full project
restoration through Undo. The undo regression exposed an existing cabinet-import clamp
that changed built-in 0.099 m widths to 0.1 m; the lower accepted cabinet dimension is now
0.01 m so those valid small cabinets survive undo/reopen. Android assembly and iOS
simulator core compilation pass. Tablet acceptance showed both saved receiver comparisons,
Cancel preserved 58.33 ms, Apply produced the previewed 62.11 ms, and Undo restored
58.33 ms. The original project was restored (74.9 dB(A)); no AndroidRuntime errors.

### Multiple saved alignment receivers

Shared AlignmentReferences owns an immutable list of up to 64 saved physical receivers,
stable non-reused IDs, the selected receiver and a reference-speaker ID per receiver.
Names need not be unique. Selection changes no geometry or DSP. Removal selects the
first remaining receiver only when the active receiver is removed; deleting the last
receiver leaves no active reference. Missing speaker references produce no delay proposals.
Shared comparisons evaluate each position independently with the existing direct-arrival
model; there is no implied multi-position optimization.

The Android Listener inspector adds a new reference from the listener or a named probe,
switches saved receivers, renames/removes them and optionally displays comparisons across
all saved positions. Applying a proposal recomputes against the currently selected
receiver. Because the speaker DSP is global, every comparison updates after an adjustment;
a successful match at one receiver does not imply matching at the others. Edits support undo.

Shared version-1 alignment JSON persists stable IDs, the active selection, XYZ, names,
per-receiver speaker IDs and the next ID. The scene stores this in alignmentReferences.
Legacy single alignmentReference metadata migrates to one saved receiver; exports also
retain the selected single-reference projection for older readers (which cannot retain
the full list). Projects without either field load an empty list. No acoustic solver or
speaker-data encoding changes are required.

Validation: 711 tests pass (341 shared-core, 370 Android). Tests cover stable IDs,
selection/removal, duplicate names, per-receiver speaker choices, differing delay
requirements, effects of a global delay change at other positions, shared serialization,
project save/reopen, undo, legacy migration and rejection of invalid selections before
scene mutation. Android APK assembly and iOS simulator core compilation pass. Tablet
acceptance created two saved references, displayed both comparison sets, switched the
active receiver and confirmed both entries plus the selected ID survived app restart.
The original project was restored at 74.9 dB(A), with no AndroidRuntime errors.

### System alignment: reference receivers and direct arrivals

Shared SystemAlignment computes 3D propagation time using the scene atmosphere, plus
base DSP and individual array steering delays. It reports earliest/latest physical-element
arrival, signed arrival difference from a reference speaker, signed delay adjustment and
a proposed total DSP delay only when it is achievable within 0–200 ms. It neither clamps
an impossible suggestion nor approximates arrays as a single centre source. This is
geometric direct-path timing, not an impulse-response peak, obstruction/audibility test,
reflection alignment or cabinet/crossover phase alignment.

The Android Listener inspector can pin the listener or an independent named probe as a
reference position, select the comparison speaker, inspect array arrival spreads, and
apply an explicit delay proposal. Moving the listener does not move the pinned reference.
Apply recomputes from current scene inputs; existing delay edits retain undo and invalidate
acoustic results through the normal revision path. Optional alignmentReference metadata
stores name, XYZ and speaker ID in project JSON. Legacy projects load without a reference;
this additive metadata does not change the acoustic meaning of older project fields.

This is the first M4 alignment slice. Multiple saved reference sets, measured/complex
phase alignment, saved response traces, difference maps and automatic multi-receiver
alignment remain future work.

Validation: 704 tests pass (337 shared-core, 367 Android). Cases cover 3D distance,
existing DSP delay, signed comparisons, correction application, impossible corrections,
array element/steering spread, sound speed, invalid inputs, pinned references,
save/reopen, undo, legacy loading, deleted reference speakers and scene edits before
application. Android APK and iOS simulator core compile successfully. On the tablet,
58.33/62.11 ms arrivals differed by 3.78 ms; the valid proposal aligned both to 62.11 ms,
and Undo restored the original difference. The original project was restored afterward
(74.9 dB(A) area average), with no AndroidRuntime errors.

### Angular bounds for custom directivity

The shared core now provides BoundedRadiator for analytic callbacks with conservative
absolute-level ranges over a speaker-local spherical cap. AngularCap validates coordinates
and radius; AngularLevelRange rejects nonfinite, reversed and overflowing ranges. Null
means unavailable bounds, never a flat response. A callback must provide a mathematical
bound for its entire response over the cap; finite probes cannot certify its correctness.

RadialDirectivity provides a built-in immutable custom model: exact per-band, piecewise
linear dB curves against great-circle angle from a configurable axis. This supports narrow
off-axis beams and annular lobes. Bounds include interval endpoints and every enclosed
curve knot, so a peak between probes cannot be discarded. Cyclic azimuth, poles and
whole-sphere caps use spherical geometry. Missing bands remain unavailable; no implicit
frequency interpolation or renormalization is introduced. Curves are copied and validated
at construction. FrozenResponse.custom accepts this immutable model; arbitrary live
closures are deliberately not admitted to immutable jobs.

Both forms use the existing receiver-aware sampler, energy partition and duplicate
boundary ownership. This is shared-core/API support, not a new Android custom-response
editor or persisted source format. Existing measured and generic workflows are unchanged.
A model-editing/import UI and versioned custom-source project encoding remain separate
work if custom models are to be authored and saved from the app.

Validation: 696 tests pass (333 shared-core, 363 Android), including independent
surface-integration convergence for a narrow custom lobe, source-solid-angle conservation,
duplicate-face prediction equality, ring-peak/pole/seam bounds, missing bands, invalid
ranges and immutable curve isolation. Android APK and iOS simulator core compilation pass.
The updated Android tablet recalculated the existing project successfully at the unchanged
74.9 dB(A) area average, with no AndroidRuntime errors. Custom-source acoustics were
validated by shared-core tests; the tablet check covers existing-workflow compatibility.

### Narrow measured lobes between probes

Measured polar grids now expose a conservative response-variation bound over a spherical
cap. The cap encloses each source-facing quadrature triangle; its latitude/longitude
rectangle includes clipped interpolation-cell corners and internal knots, with cyclic
azimuth handling and full longitude coverage at poles. Bilinear dB interpolation reaches
its extrema at those corners. Adjacent measured frequency-band bounds use the same
validated log-frequency interpolation as the response. Missing bounds remain unavailable.

Both CLF radiators and immutable prediction snapshots carry the copied grid bounds.
A represented narrow lobe can therefore trigger refinement even when vertices, midpoints
and centroids all sample the background. Unresolved bounds use longest-angular-edge
subdivision rather than directional probe bias, ensuring the enclosing cap shrinks.
The older 0.1-degree and tiny-cell stopping rules do not override a known variation bound;
existing depth/sample/work limits fail explicitly if the tolerance cannot be reached.
Exact source-solid-angle weights, receiver shadow clipping, material ownership and
first-bounce energy accounting remain unchanged. Generic speakers incur no bound-query
cost. No persistence schema changes are required.

This resolves lobes represented in supplied measured grids, not features absent from the
measurements. Custom callback radiators can opt into the same bound contract; callbacks
without bounds retain the finite-probe limitation. The dB variation tolerance bounds
represented directional response variation, not total propagation error.

Validation: 692 tests pass (329 shared-core, 363 Android). New tests cover a compact
lobe missed by every coarse sample, tighter-tolerance convergence against an independent
surface integral, exact solid-angle conservation, duplicate faces, azimuth seam bounds,
missing bands, and a frozen measured-grid prediction checked against an independent
angular-response/air-loss integral. Android APK assembly and iOS simulator core compilation
pass. Installed on the connected Android tablet: the existing project recalculated to
Results up to date with its unchanged 74.9 dB(A) area average and no AndroidRuntime errors.
The narrow-lobe acoustic acceptance is automated in the shared core; the device check
is a compatibility smoke test, not a measured-speaker field validation.

### Receiver-aware shadows and directivity refinement

Source-visible physical patches are now cached separately from quadrature. For each
receiver, foreground triangles are projected from the receiver and subtracted from
these patches before sampling; narrow shadow strips are represented geometrically,
not decided by centroid visibility alone. Coplanar endpoint duplicates do not shadow
the reflecting surface. Back-facing patches and global-ground-owned patches are excluded
before directivity probes. Source and receiver angular spans both control subdivision,
resolving large apparent patches near the receiver without changing source-energy weights.

DirectField supplies the actual radiator response and selected material's scattered
fraction for every requested data band. Vertices, edge midpoints and centroids are
probed; cells with more than 1 dB detected variation refine preferentially across the
change, with bounded anisotropy. Discontinuities have finite stopping rules: 0.1-degree
source-angular span or an angular-weight floor of one millionth of the base cell target.
These are numerical resolution bounds, not a universal acoustic-error guarantee. A lobe
narrower than all probes can still be missed; measured-data resolution/convergence checks
remain necessary. Missing contributing radiator data makes the band unavailable rather
than fabricating a response. Global ground precedence is applied before probing.

Receiver/profile samples are not stored in the source-only cache. Geometry patches
remain immutable and are cached by source and receiver position. Immutable prediction
requests refine against all requested data bands once per emitter/receiver and reuse
bounded sample and radiator-probe caches across bands. Standalone fields retain fresh
profile evaluation by default. Receiver moves and new emitter identities cannot reuse
stale responses; missing-band flags remain specific to each requested band. Source weights partition the receiver-visible subset without renormalizing away
shadowed energy. Fragment, sample, probe, depth, work and cancellation limits reject
incomplete calculations. This remains first-bounce incoherent diffuse transport, with
no change to project schemas or the direct/specular coherence model.

Validation: 689 tests pass (326 shared-core, 363 Android), including receiver-only
shadow strips, close-receiver and sharp-profile convergence against independent surface
integrals, conserved solid-angle weights, duplicate/retriangulated surfaces, missing-band
cache isolation and a two-source/six-block scattering-stage regression. Android APK and
iOS simulator core compilation pass. The connected Android tablet completed the scattering
scene with Results up to date; its original project was restored (74.9 dB(A) area average),
with no AndroidRuntime errors. Request-local reuse avoids repeated all-band profile work
without increasing the work budget or weakening refinement tolerances.

### Adaptive surface-targeted diffuse sampling

Diffuse transport now discovers geometry directly instead of relying on hits from a
fixed angular ray lattice. Each physical triangle is partitioned by projected foreground
occluders and coplanar ownership/material boundaries. This produces disjoint visible
convex patches before quadrature. Canonical geometry ordering makes ownership independent
of input order; opaque facets suppress scattering. The existing complete-material
selection rule still applies at each patch sample. Small/distant visible surfaces receive
samples even when no old fixed ray intersects them (subject to existing geometry tolerances).

Each patch triangle carries its analytic source solid angle. Longest-angular-edge
bisection refines until both solid angle and angular edge span are below the resolution
threshold. Thin slivers therefore refine even if their area is small. Resolution 2048
is the default angular precision target, not a fixed ray count. Refinement redistributes
energy weights; it does not add intercepted energy. Shared tests vary resolution from
128 to 8192 and compare receiver energy against an independent area integral. Duplicate
and partial-overlap geometry preserve the visible solid angle to floating-point tolerance;
receiver quadrature can differ slightly with triangulation and converges with refinement.

Source-position/resolution caches retain at most 32 entries and 65536 total samples.
The calculation also bounds polygon fragments, refinement depth, emitted samples and
work; exhaustion fails explicitly, never silently truncates energy or claims convergence.
Receiver visibility remains evaluated at quadrature nodes: narrow receiver-side shadows,
near-surface receivers and sharp radiator lobes can still need further refinement. The
adaptation is source/geometry driven, not an automatic acoustic error tolerance or a
multi-bounce solver. Android uses the default target and displays the revised limitation.
No project-format changes are needed; Android and iPad core share the implementation.

Verification: 318 shared-core tests and 362 Android tests passed (680 total), plus
Android APK assembly and iOS simulator shared-core compilation. New checks include a
2 cm panel at 100 m missed by all old fixed directions, exact visible solid angle after
partial occlusion, duplicate/partial-overlap/retriangulated energy conservation at three
resolutions, convergence against independent 400×400 area integration, resolution-aware
caches and work-limit failures. The high-resolution receiver integral agrees within
0.05% in that benchmark; this is not a global error guarantee. Existing duplicate receiver
power comparisons allow 0.02% total-power quadrature variation while angular-energy
checks remain tight. On the tablet, a Rigid stage with scattering 0.5 at 125/4000 Hz
completed a live adaptive coverage calculation with results up to date and no limit
error. The original scene was restored. No measured-acoustic or iPad runtime validation
is claimed.

### First-bounce diffuse propagation

The shared engine now propagates scattered energy to receivers with a normalized
Lambertian cosine distribution (cos(theta)/pi over the source-facing hemisphere).
This is an explicit assumed lobe, not a measured material radiation pattern. Diffuse
energy is added incoherently between outgoing patches. The subsequent coherent-illumination
batch combines compatible measured emitters at the scattering surface; rough-surface phase remains unknown.
Direct/specular complex summation is unchanged. This is first-bounce transport only,
not a multi-bounce reverberation or diffraction solver. The model follows the established
[diffuse ray-tracing approach in Pyroomacoustics](https://github.com/LCAV/pyroomacoustics/blob/master/pyroomacoustics/libroom_src/room.cpp),
implemented locally in portable Kotlin without an external runtime dependency.

2048 deterministic equal-solid-angle source rays trace the nearest physical boundary
through the shared BVH. Each ray's incident energy is intercepted once regardless of
Blocks/Faces, duplicate facets, partial overlaps, winding or triangulation. The same
material conflict policy selects paired absorption/scattering/phase at each hit; opaque
coplanar boundaries suppress reflection. Scattered energy uses (1-a)s and the specular
image retains (1-a)(1-s). Source directivity/gain/EQ, both-leg atmospheric attenuation,
receiver inverse-square spreading and second-leg occlusion apply. Source inverse-square
spreading is already contained in the intercepted solid angle and is not applied twice.
Global-ground precedence suppresses diffuse emission from coincident y=0 facets when
the global ground model is enabled. Scattering-free projects retain their old behavior.

Source samples are cached per immutable boundary geometry and source position, capped
at 32 emitters / 65536 samples; returned snapshots cannot mutate the cache. Work and
cancellation limits apply to tracing, visibility and energy summation. Unsupported work
loads fail rather than publishing partial sound levels. Missing radiator data in a
contributing diffuse direction also makes the band unavailable. Shared BandPrediction
exposes the diffuse mean-square-pressure component alongside the total for validation.

Ray quadrature is approximate: small/distant surfaces can fall between source rays,
and sharp directivity lobes need convergence testing. Core tests can request 128–32768
even ray counts; Android currently uses 2048 and states this limit in the inspector.
Receiver distances below the existing 0.1 m propagation floor are regularized. No new
project format is needed (scene 22 / mesh 10 / venue 7); existing scattering assignments
now contribute diffuse energy when recalculated. UI text and documentation have been
updated to describe the active model and remaining limits.

Verification: 313 shared-core tests and 362 Android tests passed (675 total). Android
APK assembly and iOS simulator shared-core compilation passed. New tests cover normalized
hemisphere radiation, closed-enclosure intercepted energy, receiver spreading, emitted
diffuse energy, scattering scaling, phase independence, duplicate/retessellated/reversed
faces, partial overlap, source/receiver occlusion, missing direction data, work budgets,
solid-angle convergence and mutation isolation for cached overlapping-material samples.
Existing material tests now inspect the separately reported non-diffuse component;
ground precedence and cold-reopen integration continue passing with diffuse propagation.
On the connected tablet, a stage with Rigid role and scattering 0.5 at 125/4000 Hz
completed a live coverage calculation with results up to date. The original scene was
restored and its 74.9 dB(A) area average verified. This is functional/numerical validation,
not acoustic measurement validation or an iPad runtime test.

### Material scattering: specular energy partition

Blocks, stage and imported Faces/groups now share immutable frequency/angle scattering
curves: 1–19 angles, 2–64 frequencies each, coefficients 0–1 and measurement/mounting
notes. One angle applies to all incidence angles. Frequencies interpolate logarithmically,
angles linearly, with endpoint hold. Older materials default to zero scattering. The
scattering coefficient is the fraction of reflected energy removed from the specular
component; it is distinct from absorption and from a diffusion coefficient. This
interpretation follows [ODEON's room-acoustics description](https://odeon.dk/learn/articles/room-acoustics/)
and the separate material inputs in [Pyroomacoustics](https://pyroomacoustics.readthedocs.io/en/stable/pyroomacoustics.room.html).

For absorption a and scattering s, the shared energy partition is absorbed=a,
specular=(1-a)(1-s), scattered=(1-a)s. These sum to incident energy. DirectField uses
sqrt(specular) for reflected pressure and retains the selected material's measured
phase. Fully scattered boundaries still occlude crossing paths. Direct sound is not
attenuated by material scattering. Diffuse energy is accounted for in the material
partition but is NOT propagated to receivers; current coverage is direct plus supported
specular paths, not a total diffuse-field solution. A scattering coefficient alone does
not specify a scattering lobe. Directional diffuse transport remains future work, and
Android explicitly states this limitation when editing or assigning scattering.

Coincident Blocks/Faces select ONE complete absorption/scattering/phase material with
the lowest specular reflected energy at that frequency/angle; stable serialized material
order resolves exact ties. This extends the previous strongest-absorption policy, which
is unchanged when all scattering is zero. Conflicting overlaps are not blended and their
losses are not multiplied. Global ground absorption continues to own coincident y=0
reflections when enabled; the ground model does not acquire scattering implicitly.

Android supports add/edit/remove scattering; edits preserve absorption and phase.
Changing absorption or phase preserves scattering, while selecting another preset
replaces the material. Scene 22, mesh 10 and venue package 7 embed scattering curves,
validate coefficient definitions strictly, and retain compatibility with older projects.

Verification: 306 shared-core tests and 362 Android tests passed (668 total). Android
APK assembly and iOS simulator shared-core compilation passed. New checks cover energy
conservation, frequency/angle specular levels, zero/full scattering, unchanged direct
sound, mixed Block/face duplicates with paired phase, ground precedence, immutable
curve input, invalid coefficients, mesh/group round trips and Android cold save/reopen
plus venue exchange. On the tablet, scattering 0.25 at 125 Hz and 0.75 at 4000 Hz
survived process restart and reopened correctly in the editor. The original project
was restored afterward. No diffuse-field, measured-acoustic or iPad runtime validation
is claimed.

### Measured material reflection phase

Materials can embed immutable measured pressure-reflection phase with its own reference
notes, 1–19 incidence curves and 2–64 frequency samples per angle. A single angle curve
applies at every incidence angle. Input is explicitly unwrapped degrees with exp(+iωt)
convention: delay is negative phase. Frequency and angle interpolation are linear,
with endpoint hold. Turns are preserved (179 to 181 passes through 180); wrapped phase
must be unwrapped before entry. Data must describe the surface reflection coefficient,
excluding source/receiver travel already modeled by the engine. Measurements and reference
planes remain the user's responsibility; no material phase is inferred from absorption.

Shared prediction selects one paired absorption/phase response per physical reflection.
Highest absorption wins overlaps as before; exact absorption ties select the lexicographically
first stable serialized material record, independent of source order. This is a reproducible
conflict policy, not a physical mixture: conflicting duplicates should be corrected.
The selected phase enters complex interference with the propagation phase, including
self-reflections of independent sources. Spectral segments split at measured phase knots;
the existing analytic sinc integral includes the linear phase slope on each interval.
Magnitude and air loss still use the engine's piecewise-constant segment approximation.
Global floor precedence remains unchanged and uses its existing zero-phase response.

Android supports adding, editing and removing measured phase for Blocks, stage, Faces
and groups. Editing absorption retains phase; choosing another preset replaces the material.
Scene 21, mesh 9 and venue package 6 embed phase curves, reject unsupported conventions,
and read older materials with zero reflection phase. No scattering or multiple-bounce
model is introduced by this feature.

Verification: 301 shared-core tests and 361 Android tests passed (662 total), with
Android APK assembly and iOS simulator shared-core compilation passing. New checks
cover phase sign, interference with independent-source self-reflections, 180° inversion,
phase-invariant isolated reflected power, delay slope across bandwidth, internal phase
knots, paired overlap responses, stable ties, phase interpolation/validation, mesh/group
round trips and Android cold save/reopen/venue exchange. On the tablet, entered 90°
phase samples at 125 and 4000 Hz survived a full restart and reopened in the editor.
The original project was restored. No acoustic measurement or iPad runtime validation
is claimed by these checks.

### Angle-dependent material absorption

The shared immutable material model supports 2–19 measured incidence-angle curves,
each with 2–64 frequency/energy-absorption samples and common source/mounting notes.
Angles strictly increase within 0–90 degrees, measured from the surface normal.
Frequency interpolation remains logarithmic; angle interpolation is linear in degrees.
Both axes hold the nearest measured endpoint outside the supplied measurement range.
No angular behavior is inferred for older presets or frequency-only curves.

Finite reflection paths calculate incidence from the source-to-bounce direction and
unit facet normal, independent of triangle winding. Reflected pressure uses the
frequency-and-angle absorption once. Coincident Block/face boundaries still create
one arrival and use the highest absorption at that frequency and incidence. Existing
global ground absorption precedence is retained. Optional measured reflection phase is
now supported below; directional scattering, transmission and impedance models remain outside scope.

Android provides an angle-dependent curve editor using angle, Hz, absorption rows,
including editing recovered curves. Blocks, stage, Faces and named groups use the
same shared model. Scene 20, mesh 8 and venue package 5 embed the curves and read
older formats without changing their angle-independent absorption behavior.

Verification: 296 shared tests and 360 Android tests passed (656 total). New tests
cover normal/oblique reflection power at multiple frequencies, winding independence,
angle/frequency interpolation and endpoint hold, mixed Block/face overlap order and
deduplication, validation, mesh/group round trips, cold project reopen and venue exchange.
Android APK assembly and iOS simulator shared-core compilation passed. On the tablet,
the angular editor created a material and retained it after process restart; the
original project was restored afterward. This does not constitute acoustic measurement
validation or an iPad runtime test.

### Custom measured material curves

Shared immutable materials now support 2–64 measured frequency/energy-absorption
pairs, a name and measurement/mounting notes. Frequencies must strictly increase
(1–200000 Hz); coefficients must be finite and within 0–1. Invalid data is rejected,
not silently clamped. Interpolation is logarithmic in frequency, with endpoint hold.
Android's material menu provides a paste/edit dialog for frequency, coefficient rows.
Assignments to Blocks, stage, imported faces and named groups embed the full curve;
there is no external file dependency or mutable global material library.

Scene 19, mesh 7 and venue package 4 store custom material objects alongside existing
preset identifiers. Older formats and presets remain readable. Reflection pressure
uses sqrt(1-alpha) once; coincident surfaces use maximum absorption per frequency,
including crossing custom curves, without duplicate arrivals. Global ground precedence
is unchanged. Absorption measurements supply magnitude only. Reflection phase requires separate
measurements; transmission and scattering remain outside scope. Source/mounting notes are user
supplied; importing a curve does not certify its measurement method.

Verification: 292 shared tests and 359 Android tests passed (651 total), including
custom per-band reflection ratios, crossing overlap curves, mixed Block/mesh duplicate
suppression, immutable input copies, invalid coefficients, mesh/group round trips and
Android cold save/reopen plus venue exchange. Android APK and iOS simulator core
compilation passed. The tablet editor rejected an unnamed curve, applied a named
custom curve and retained it after process restart. The original project was restored.
This is functional validation, not measured acoustic validation or an iPad app test.

### Frequency-dependent boundary materials

Blocks, the built-in stage, and imported faces/groups now select shared immutable
material presets. Fixed octave coefficients are reused from the existing Android
SurfaceMaterial catalogue for sealed concrete, heavy carpet, gypsum and acoustic
panel; ideal rigid and ideal absorber provide exact limits. These are representative
construction assumptions, not calibrated product measurements. Persistent material
identifiers denote fixed curves; future coefficient changes require new identifiers.
Custom measured material curves are now supported as described below.

The shared core interpolates energy absorption logarithmically in frequency between
125–4000 Hz and holds endpoint values outside that range. Reflected pressure is
multiplied by sqrt(1-alpha) once per arrival, retaining path phase and band summation.
Direct arrivals remain unaffected. Materials act only on reflecting (Rigid) boundaries;
Opaque still suppresses reflection entirely and No boundary remains transparent.
A fully absorbed reflection needs no radiator sample. Coincident reflecting surfaces
produce one arrival with the maximum absorption per band among materials covering the
bounce. This conservative, order-independent conflict policy is not material blending.
Global ground absorption retains precedence at coincident y=0 reflections when enabled.

Scene schema 18, mesh payload 6 and venue package 3 persist material identifiers.
Legacy absence defaults to ideal rigid, retaining previous reflected levels. New
payloads require material fields; unknown IDs and malformed arrays fail preflight.
Face assignments, groups, transformations and immutable requests preserve materials.
Android exposes material pickers for Blocks/stage and application to selected mesh
faces or named groups. Tests check per-band reflected energy, perfect absorption,
overlap order/duplication, mixed Block/mesh overlaps, migration and cold save/reopen.

Verification: 290 shared-core tests and 358 Android tests passed (648 total). Android
APK assembly and iOS simulator shared-core compilation passed. On the connected Android
tablet, the stage material picker retained Acoustic panel after a full process restart;
the original project was restored afterward. This device check covers the picker and
recovery, not measured acoustic accuracy or an iPad runtime.

### Unified Blocks, stage and imported-face boundaries

`VenueBlock.boundaryRole` and `VenueGeometry.stageBoundaryRole` use the same shared
`AcousticBoundaryRole` enum as imported triangles. New and legacy Blocks/stages default
to `OCCLUDING`; `NONE` preserves visible context without acoustic effects and
`RIGID_REFLECTOR` adds ideal first-order reflection. Block type names do not choose
acoustic behavior. This deliberately changes the old obstruction predictions: there
is no empirical 55 dB cap, grazing penalty or heuristic low-frequency recovery.
Projects retain their geometry and require recalculation under the unified model.
Room outline dimensions alone do not create acoustic walls or ceilings.

Shared `VenueBoundaryGeometry` builds 12 triangles from the physical dimensions,
rotation, local-depth slope and vertical thickness of each Block. Elevated slabs keep
their underside and open space below. The built-in stage extends from y=0 to its
sloping top, clipping its footprint where thickness becomes zero; a flat zero-height
stage is absent. Android wireframes use these same vertices. These are boundary shells,
not a filled-volume transmission model. Independent audience zones are still needed
for area-weighted coverage; Blocks never add receiver area automatically.

Frozen prediction requests snapshot the venue's block list and imported surfaces,
then prepare one bounded boundary tree. Each physical arrival is tested once; duplicate
geometry does not stack attenuation. Coincident rigid facets (including different
triangulations and reversed winding) produce one image arrival. Canonical Block/stage
heights use the same Float representation as imported meshes; coincidence queries use
a 1 micrometre tolerance, matching segment endpoint exclusion. Sloped-roof duplicate
and opaque-over-rigid fixtures specifically guard against precision-induced extra arrivals. An opaque coplanar
patch suppresses the reflection only where its footprint contains the bounce, regardless
of input order or whether it came from a Block or mesh. `NONE` never overrides another
boundary. Nearby distinct surfaces are physical geometry, not auto-merged duplicates.
The existing global-ground absorption controls a coincident rigid y=0 bounce once;
opaque ground patches suppress it. No material averaging or diffraction is inferred.

Scene schema **17** requires `venueGeometry.stageBoundaryRole` and each Block's
`boundaryRole`; mesh payload stays at version **5**. Schema 1–16 absence migrates to
opaque stage/Blocks while legacy imported mesh defaults remain transparent. Venue-only
exports use schema version **2**, with the same roles and shared strict preflight;
version 1 and unversioned legacy venues remain readable. Unknown/missing required roles,
non-finite or invalid physical dimensions, and duplicate Block IDs fail before mutation.
Transforms, duplication, undo/redo, cold save/reopen and venue exchange preserve roles.

Android exposes the role selector in Block and Stage inspectors; opaque outlines are
red, rigid blue and transparent context gray. Imported-face controls use the same
compact labels. Existing audience assignment, picking and coverage statistics stay
independent. Regression tests cover Block/mesh field equivalence, duplicate and
conflicting coincident surfaces, partial overlap, ground precedence, sloped/rotated
geometry, stage equivalence, open underpasses, immutable requests, old-project migration,
invalid imports, save/reopen and unchanged receiver area. Large-scene performance and
calibrated material/diffraction models remain future work.

Unified-boundary validation: **285 shared-core JVM tests and 357 Android unit tests**
pass, with no failures. The former empirical-diffraction coefficient tests have been
replaced by tests for the new geometric boundary contract. Android debug assembly
and iOS simulator ARM64 shared-core compilation pass. Tests include a sloped-roof
case that initially revealed duplicate images from mismatched height precision; both
rigid duplication and opaque suppression now pass for that geometry.

On-device follow-up (2026-11-12, OnePlus OPD2415): installed the final build,
recovered the existing project, placed and selected a temporary Block, checked all
three role controls and outline colors, recalculated, force-stopped and reopened the
app. The test scene showed 74.9 dB(A) with the added Block transparent or opaque and
75.1 dB(A) when rigid; 75.1 dB(A) reproduced after cold reopen. Saved schema-17 data
retained the new Block's `RIGID_REFLECTOR` and the stage's `OCCLUDING` role. These are
UI/recovery observations, not measurement-based acoustic validation. The original
project was restored from backup and its venue geometry, speakers and audience
surfaces were compared for equality (excluding new default role metadata). The
updated APK remains installed. iOS runtime and large-scene performance checks remain
outstanding.

### Explicit imported-mesh acoustic boundaries

Audience assignment and acoustic behavior are independent. Shared `AudienceMesh` owns
immutable per-face `AcousticBoundaryRole` values. Face/group commands apply `NONE`,
`OCCLUDING`, or `RIGID_REFLECTOR`; group changes preserve existing roles. OBJ names
never imply acoustic behavior. Original triangles, base height and rake define physical
boundaries, including excluded audience faces. Plan holes/exclusions only mask receiver
coverage; intrinsic mesh openings have no triangles and remain physical openings.

`BoundaryGeometry` prepares a bounded BVH in the prediction worker. Direct paths and
both reflection legs are tested per physical emitter, with two-sided intersections.
Endpoint contacts within 1 micrometre and coplanar segments do not self-occlude.
Rigid faces use a unit pressure reflection coefficient and a finite specular image path:
source and receiver must be on the same side, the bounce must lie on the assigned
triangle, and both legs must be clear. Tessellation edges and coincident reflectors
produce one image arrival. Spreading, atmospheric loss, departure directivity, DSP delay,
polarity and existing band-integrated complex summation use the reflected path.
Independent sources remain coherent with their own reflections. Completely occluded
sources contribute zero pressure and require no missing-band sample. Broadband sums
physical powers before applying the display floor, so blocked bands add no artificial
energy; the existing
numerical display floor is 0 dB SPL, not a modeled residual/diffracted level.

Global ground reflection keeps its absorption for a coincident rigid y=0 image and is
counted once. An explicit opaque y=0 patch suppresses that ground bounce. Boundaries
also block either leg of noncoplanar ground paths. Blocks and the built-in stage
now participate in this same path evaluation; the empirical obstruction model has
been removed, with no additional stage/block attenuation applied.
The experimental rectangular-room reflection list is still separate and is labelled
as such in Android. No calibrated material transmission/absorption, diffraction,
scattering, multiple reflections or reverberation is claimed by these new roles.

Frozen requests include immutable boundary surfaces. Edits use the existing undo and
stale-result gate and invalidate displayed results. Cancellation or bounded-work
failure publishes no partial field (32,768 boundary facets, 128 visible first-order
images per emitter/receiver, 20 million work steps per request). Boundary metadata is
preserved by transforms, duplication and save/reopen. Area weighting still follows
only audience assignments, never boundary roles. Android offers face/group controls,
selected-role readout and red blocker / blue reflector outlines, including under
measurement cutouts. Larger-scene reflection acceleration and profiling remain open.

Tests cover finite images against an independent analytic interference calculation,
back sides, seams, visible legs, departure direction, per-element occlusion, missing
bands, independent-source coherence, global-ground precedence, base/rake transforms,
cancellation, malformed/legacy payloads, captured requests, area statistics, Android
commands, undo/redo, color rendering and cold local save/reopen without source files.

References: [MAPP 3D user guide](https://docs.meyersound.com/products/en/user-guide---mapp-3d.html)
separates prediction surfaces from its optional ground reflection;
[pyroomacoustics image-source implementation](https://github.com/LCAV/pyroomacoustics/blob/master/pyroomacoustics/libroom_src/room.cpp)
and [the authors' paper](https://arxiv.org/abs/1710.04196) provide public context for
finite-boundary image visibility. This implementation uses original code and adds no
external runtime dependency. These references are not manufacturer-validation evidence.

Scene schema 17 embeds a version-5 `audienceMesh` with vertices `{x,y,z}`, triangles
`{a,b,c}`, required `faceAssignments` in original face order, and required `faceGroups`
objects containing `id`, `name` and zero-based `faces`. The groups array may be empty. Required `boundaryRoles` stores one enum per original
triangle (`NONE`, `OCCLUDING`, `RIGID_REFLECTOR`). Version-1/2/3/4 meshes without
this field default to `NONE`; version 5 requires an exact-length, recognized role array.
Version-1/2/3 mesh payloads and schema-12/13/14 projects remain readable: absent legacy
groups default to none, and absent version-1/2 assignments default to `FOLLOW_ZONE`.
Missing, malformed, duplicate, unknown or out-of-range values fail import atomically;
version-3+ assignments and version-4+ groups are mandatory. Overlapping membership is
rejected rather than silently changing which group owns a face.
Older apps reject the new schema/mesh version. `geometryType` distinguishes `AUTHORED`
from `IMPORTED_MESH`; missing mesh payloads fail preflight. Mesh coordinates/topology are
authoritative over the legacy `vertices` projection and are self-contained in portable
saves and local recovery. Shared cache keys include coordinates, faces, assignments and named groups; immutable
captured requests, transforms, duplication, undo/redo and cold reopen preserve elevations
assignments and named groups without source files.

Audience surfaces now snapshot `holes` and `exclusions` as world-space rings. Holes
must be simple, strictly interior and mutually disjoint (no touching or nesting).
Exclusions may cross/touch the outer boundary, lie outside it, overlap holes or each
other, disconnect the remaining region, or remove it completely. Their union is
subtracted only from the owning surface: other floors/balconies are independent.
Both winding directions and concave outer/cutout polygons are supported. Hole rings
do not affect the original outer-vertex-mean anchor for rake. Explicit probes remain
independent and may be placed in an excluded area; they never contribute area weight.

Shared preprocessing triangulates cutout rings, then subtracts convex cutters from
clipped outer grid cells into disjoint convex pieces before generating receiver
triangles. Small holes survive coarse sampling; no centroid-only filtering can
leave triangles bridging holes. Cutout boundary vertices close the retained mesh
but there are no interior coverage receivers. Heatmaps and contours consume the
same topology. Statistics integrate only the remaining physical surface area:
excluded regions are neither target-coverage denominator nor unavailable area.
A fully excluded surface retains its identity with zero area and unavailable level
metrics; it does not trigger the fallback venue grid. Overlapping exclusions are
not subtracted twice. Additional limits are 64 rings / 4,096 cutout vertices per
surface, 4,000,000 cutter-work steps per preparation and 4,096 fragments per grid
piece, with cancellation checks. Budget/validation failures publish no partial map.

Android's zone inspector offers **Draw hole** and **Draw exclusion**, followed by
viewport vertices and **Apply cutout**, plus individual removal controls. Invalid
cuts preserve the current zone and show a reason. Cutout outlines and draft previews
use the parent's physical plane. Rotate, mirror and duplicate transform all rings;
outer-edge edits that would invalidate holes are rejected. Undo/redo restores the
rings. Fully excluded zones stay selectable through their outer outline. The legacy
optimizer sampler now uses shared cutout membership and listening heights; this does
not complete its immutable-model or acoustic validation migration.

Scene schema 17 stores `holes` / `exclusions` as arrays of objects, each with a
`vertices` array of x/z points. It also stores numeric `vertexOffsetsM` and
`elevationPoints` objects containing x, z and offsetM, and `breaklines` containing
`start`/`end` objects with a `kind` (`BOUNDARY` or `INTERIOR`) and integer `index`.
Shared project preflight checks ring/elevation/constraint types and geometry before
Android state changes. Missing fields in old projects mean no cutouts, elevation
overrides or breaklines. Schema-8/9/10/11/12 recovery slots remain discoverable.
Older apps reject schema 17 rather than silently dropping constraints or flattening
heights. Cutout coordinates and metadata are included in shared geometry-cache keys and privately copied. These are audience
measurement exclusions, not new acoustic barriers or physical openings in the global
ground-reflection plane; explicit solid geometry remains a separate model.

Shared `CoverageStatisticsCalculator` integrates the piecewise-linear dB field over
actual 3D triangle area. It computes overall and per-surface area, arithmetic spatial
mean dB, standard deviation, extrema and ascending P10/P50/P90/P95. The arithmetic
mean describes the spatial dB distribution; it is not an energy average or pressure
sum. Continuous triangle CDFs and exact first/second moments avoid counting dense
boundary samples more heavily. Analytical linear fields remain stable under mesh
refinement; nonlinear acoustic fields still depend on sampling resolution. Raked
surfaces use their physical area rather than horizontal footprint. Distinct surfaces
are summed independently, including stacked or overlapping polygons; their outlines
are not unioned. Surface IDs keep duplicate names separate. Probes have zero area.

Triangles with any missing/non-finite sample contribute to unavailable area, not to
valid mean/percentiles. Summaries report valid, total and unavailable areas; zero valid
area yields unavailable metrics rather than silence/zero SPL. In Android Target mode,
coverage means the inclusive ±3 dB window about the design target, independent of the
visual colour span. Its percentage denominator is all requested area, so unavailable
regions cannot boost the result. Shared APIs accept any explicit target window.
Android's analysis strip, worst-zone ranking, P90–P10 zone spread, automatic contour
reference (P95) and report JSON use shared statistics. The existing intelligibility
heuristic now consumes area mean/deviation; this does not validate it as STI.
Statistics travel with the immutable prediction output and are cleared/rejected with
stale maps. Reports include the numerical conventions, per-surface values and coverage
revision, and omit invalidated statistics.

Shared `GeometryCache` provides a thread-safe LRU of complete immutable audience
meshes, scoped to an Android view model. Structural keys include ordered surface
vertices, IDs, names, zone type/ear height, base/rake/direction, probes and metadata,
hole/exclusion rings, boundary/interior elevations, venue dimensions, spacing and
mesh receiver budget. Speaker placement/drive, DSP,
frequency and target changes reuse geometry but always recalculate acoustics and
statistics. Keys use structural equality rather than a hash-only match. Default
retention is at most 3 entries, 100,000 receivers and 300,000 triangles; oversized
entries are returned without caching. Invalid/interrupted preprocessing inserts no
partial mesh. Builds happen outside the platform lock; equal concurrent builds can
duplicate work but converge on one canonical cached mesh. Cache clear advances an
epoch so earlier in-flight builds cannot repopulate it. JVM and iOS synchronization
are thin adapters; keying, eviction and budgets stay in shared core. Clearing a scene
or disposing its view model releases the cache. Large-scene profiling, persistent
caches, per-surface incremental reuse and measured-response caching remain deferred.

Venue Blocks, the built-in stage and explicitly assigned imported faces now share one
boundary solver. Clear paths, including paths just above a block or under a balcony,
receive no empirical grazing loss. Opaque crossings contribute no direct energy at
any frequency; diffraction is not implemented. Audience zones remain measurement
surfaces unless their imported faces receive explicit boundary roles. Global ground
reflection remains optional. The experimental rectangular-room reflection listing and
optimizer are still separate algorithms, not a complete shared room-acoustics solver;
the legacy room visibility helper now uses the same boundary geometry and omits
fully occluded entries instead of returning finite empirical attenuation.

Generic front-hinge geometry is not manufacturer-specific cabinet or rigging
validation. Prepared-response caching, verified manufacturer reference contracts,
complex response, original-source retention and asset lifecycle management remain
next steps; this slice does not complete the solver.
Prior imported-face boundary validation: 272 shared-core JVM tests and 363 Android unit tests
pass; `:app:assembleDebug` succeeds; `:shared-core:compileKotlinIosSimulatorArm64`
succeeds. On 2026-11-12 the final APK was installed on the connected OnePlus tablet
(OPD2415). Device smoke checks covered recovery of the pre-existing project, OBJ import
with Audience/Wall groups, direct viewport face selection, group recall, opaque group
assignment, rigid selected-face assignment, stale-result clearing, recalculation, and
cold recovery after replacing the APK. The test scene's area mean was 78.8 dB(A) with
no reflection and 78.9 dB(A) with the rigid wall; the latter reproduced after reopen.
Reopened schema-16 data retained `[NONE, NONE, RIGID_REFLECTOR, RIGID_REFLECTOR]`
and both group mappings. This is a UI/recovery smoke check, not acoustic measurement
validation; blocker crossing and analytic reflection correctness are covered by tests.
The original tablet project was backed up and restored; the updated APK remains
installed. iOS runtime tests and large-scene performance acceptance remain outstanding.
Tests cover independent numerical quadrature of band interference, unequal segment
widths, fractional band edges, hard/absorbing ground limits, reflected path losses
and direction, coherent self-reflections, Android bandwidth behavior, project
round trips and older-project defaults. Speaker-data tests add voltage/power and
distance scaling, unsupported conversions, missing bands/directions, unavailable
mixed-source totals, angular seam interpolation, metadata round trips, invalid
calibration rejection, lost assets and manual-mode invalidation. Legacy clipped-floor
tests were replaced; measured directivity fixtures now supply per-band sensitivity
rather than relying on an undocumented scalar fallback. Persistence tests cover
portable and filesystem save/reopen, fresh-instance prediction parity, content
identity and tampering, missing assets, exact reimport, schema compatibility,
interrupted asset/checkpoint writes, backup fallback/preservation, failed-load
state preservation, and measured-data undo/redo. Request tests add deep snapshot
isolation, result-array ownership, cancellation checkpoints, out-of-order completion
at equal/different revisions, stale failures, manual edits, automatic recalculation,
disable/retry behavior and scene replacement with outstanding workers. Android race
tests use controlled completion order rather than timing sleeps. Geometry fixtures
cover a 60 m venue, remote zones, concave boundaries, narrow rotated polygons, stacked
floors, standing/raked balconies above 30 m, independent probes, mesh budgets and
cancellation. Analytical level differences verify each layer's receiver elevation;
GLB tests verify separate heights/colors/contours and missing-value gaps. Save/reopen,
frozen geometry after edits, invalid-polygon rejection and stale mesh publication
are exercised through Android adapters. Statistics tests add analytical rectangular
and triangular distributions, mesh refinement, unequal/overlapping surface areas,
rake, extreme probes, missing triangles, inclusive target endpoints and cancellation.
Cache tests cover structural keys, metadata edits, LRU/count budgets, oversized
entries, cancellation, clear-during-build and deterministic concurrent same-key
builds. Android tests verify acoustic edits reuse meshes while updating statistics,
geometry/profile changes rebuild them, target coverage ignores visual span, reports
match shared output, and stale completions cannot replace current statistics.
Cutout fixtures verify exact net area and analytical ramp statistics, no triangle
intersection with hole interiors, coarse-grid small holes, reversed winding, concave
outer/mask rings, rotated holes, overlapping/duplicate exclusions, boundary cuts,
disconnected/full exclusions, independent stacked layers, raked physical area,
manual probes, zero-area summaries, missing-area denominators, cache isolation and
cancellation. Android fixtures exercise drawing/removal, save/reopen, schema-8
migration, malformed-import rollback, undo/redo, transforms, GLB outlines/meshes,
contours and auxiliary receiver sampling. Non-planar fixtures add analytical pyramid
and warped-corner heights/physical area, unequal facet weighting, refinement-invariant
linear-field statistics, coarse-grid peaks, collinear boundary elevation vertices,
cutouts across facets, crease-following contours/outlines, independent stacked layers,
invalid controls, cache snapshots, cancellation and acoustic level changes at elevated
receivers. Android tests cover elevation editing/reset, schema-9 migration, save/reopen,
undo, malformed-import rollback, rigid transforms with rake, rendering and auxiliary
sampling. Breakline fixtures add analytical forced-diagonal and unequal-strip area,
refinement-invariant statistics, missing-facet accounting, hole/exclusion clipping,
shared-point crossings, T junctions, overlapping constraints, winding and closed-ring
references, concave-boundary rejection, cache snapshots, cancellation, and deterministic
interior-fan stress cases. Android tests cover constraint editing, prediction/statistic
invalidation, frozen requests, undo/redo, save/reopen, schema-10 migration, invalid-import
rollback, point-removal remapping, reset, transforms and clipped colored overlays.
Imported-mesh fixtures cover exact retained diagonals, intrinsic holes, concavity,
cutout union/full exclusion, refinement-invariant area/ramp statistics, unequal slopes,
missing-facet denominators, raised receiver acoustic levels and stacked zones. Validation
fixtures exercise malformed coordinates/indices, duplicates, disconnected/non-manifold
meshes, T junctions, folds/overlaps, winding normalization, size/work/cancellation limits,
precision collapse, OBJ units/axes/references, embedded JSON and cache immutability.
Android tests cover atomic failed imports, missing mesh payloads, save/reopen without
source files, undo/redo, schema-11 migration, transforms/rake, captured requests,
statistics/cache publication, blocked topology edits and GLB/auxiliary sampling.
Full 3D fixtures add vertical rectangles, connected floor/wall/overhang assemblies,
closed meshes, exact physical area/moments/percentiles, missing top-face accounting,
XYZ refinement, layer-specific acoustic distances, vertical cutouts and coincident
mask boundaries. Actual 3D intersections, coplanar overlaps and non-orientable topology
are rejected while projected overlap is allowed. Tests cover ambiguous scalar heights,
vertical contour ribbons, multi-layer cross-sections, precision guards, winding,
transforms, rake and budgets. Android fixtures verify OBJ import, independent layers,
auxiliary receivers, save/reopen, undo/redo, schema-12 migration, cache invalidation,
cutout scope and non-degenerate GLB triangles/ribbons for vertical surfaces.
Per-face fixtures add role-specific listening heights and group areas, excluded versus
unavailable area, exact linear-field moments/percentiles under refinement, shared-edge
receiver separation, parent inheritance and overrides, fully excluded meshes, selective
restoration, plan cuts, immutable requests/cache invalidation, stable face numbering,
connected patch selection, malformed payload rejection and legacy assignment defaults.
Android tests cover transient previews, batch undo/redo, per-group reports, auxiliary
receivers, transforms/duplication and cold reopen, atomic failed import, role/highlight
wire colors, empty excluded coverage, and active-only heatmaps/contours.
Direct-picking fixtures cover exact original face IDs and positions, nearest stacked
surfaces regardless of scene order, vertical and back-facing facets, deterministic
edge/vertex ties, base/rake/mirror transforms, intrinsic holes versus measurement masks,
invalid/parallel/behind-camera rays, camera projection and clipping, cancellation, and
single/toggle/patch selection. Android tests exercise viewport-ray selection through
assignment, area changes, single-step undo/redo, cache/result preservation during previews,
zone switching, misses, transforms, and save/reopen with transient highlights cleared.
Named-group tests cover immutable snapshots, stable identities on rename, group tap/recall,
disconnected members, atomic membership moves, deletion/ungrouping without role changes,
group-wide assignments, validation limits/overlap/name errors, adjacent group receivers,
exact area moments/percentiles across refinement, mixed listening heights, missing-area
accounting, cutouts, zero-area groups, cache invalidation and frozen labels. Android fixtures
verify rendered coverage/reports, recall without invalidation, edit undo/redo, assignment
restore, transforms/duplication, cold reopen, legacy ungrouped migration and atomic failed
imports.
OBJ group fixtures add single/repeated names, independent object/material state, missing
and bare/default declarations, canonical multiple memberships, case-only label collisions,
unused declarations, comments/CRLF/relative references, unit/axis conversion, winding,
explicit role assignment, exact area moments/percentiles under refinement, unequal vertical
face area, missing predictions, portable reopening, cancellation and group/name limits.
Android fixtures verify actual import-to-selection/assignment/report behavior, combined
memberships with no area duplication, cold reopen after rename/transforms, and atomic failed
imports without consuming zone IDs or adding undo history.
Existing numerical/serialization fixtures now inject the test Main dispatcher
for predictions, removing real-worker teardown races; controlled concurrency tests and
multi-threaded cache tests remain separate.
This extraction creates iOS compilation targets, not a working iPad application.
