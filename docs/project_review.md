# Project review and scope assessment

Review date: **2026-11-12**. Baseline: commit **`7b02551`**, including the existing
uncommitted changes in `ClfBinaryReader.kt`, `ClfData.kt`, and `ClfTabParser.kt`.
Those changes were inspected and preserved. This document records a source review,
not certification of acoustic accuracy or evidence of an iOS port.

## Assessment

DroidAcoustic Pro has a useful Android application foundation and a substantial
prototype of the sound-system design workflow. Its displayed acoustic predictions
are not yet ready to support professional deployment decisions. The main gap is
the connection between trustworthy speaker data, one validated prediction engine,
correct 3D receiver geometry, and reproducible projects/results.

Continue this project rather than restart it. Rescope the first release around the
user's stated priority: **live sound—PA coverage, line arrays, subwoofers, and delays**.
Preserve 3D venue editing, measured speaker data, and Android tablet usability.
Make the domain and calculation code portable now so an iPad application can follow.
The sequenced work and acceptance gates are in the [implementation plan](implementation_plan.md).

Room-acoustic simulation, STI, rigging certification, and automatic optimization
should not determine whether this first release can ship. They require separate
data, models, and validation. Existing experimental features do not establish
completion of the old README's numbered phases.

## What to retain

| Existing work | Value and qualification |
|---|---|
| Compose shell, inspector, and Filament viewport | Good tablet-oriented editing foundation; preserve the interaction work. |
| Speaker placement, aiming, per-joint splay, DSP controls | Useful design objects and controls; their acoustic interpretation needs correction. |
| Audience polygons, height/rake, stage, and blocks | Useful editor primitives; replace duplicated derived geometry with shared surface geometry. |
| CLF TAB/binary ingestion and polar interpolation | Useful measured-data route; retain provenance and explicitly declare supported capabilities. |
| Scene JSON, revision counter, undo/redo | Useful starting point; durable assets, atomic loading, and result revisions are missing. |
| Heatmaps, contours, absolute display scales | Useful presentation code; retain only after fixing sampling and surface identity. |
| Standalone Kotlin acoustic classes and existing tests | Best starting point for one portable reference solver; tests alone do not prove production integration. |
| C++ math/DSP and distance backend | Reusable components; the native API is not a completed acoustic prediction engine. |

## Benchmark and realistic product target

These products are workflow benchmarks, not interchangeable algorithms or data
libraries. Their manufacturer integration is part of their value; reproducing a
similar interface does not reproduce their validated models.

| Benchmark | Relevant capability | Implication for this project |
|---|---|---|
| [L-Acoustics Soundvision](https://www.l-acoustics.com/products/soundvision/) | 3D sound-system design, acoustic coverage, and mechanical system design. | Build a coherent venue → system → coverage workflow; defer mechanical certification. |
| [Meyer Sound MAPP 3D](https://meyersound.com/product/mapp-3d/) | 3D prediction using manufacturer loudspeaker data, with virtual measurement and system-design tools. | Receiver response and time alignment matter alongside a colored coverage map. |
| [d&b ArrayCalc](https://www.dbaudio.com/global/en/products/software/arraycalc/) | Loudspeaker-system planning, level prediction, alignment, and array/subwoofer workflows. | Make mains, fills, delays, and subwoofer arrangements first-class design objects. |

The achievable first target is a cross-brand direct-field planning application
with clearly identified data quality and model limits. It should compare coverage,
array geometry, subwoofer configurations, and delay settings reproducibly. Do not
claim manufacturer-tool equivalence, maximum-output accuracy, or validated rigging
from nominal coverage angles, generic sensitivity, or magnitude-only measurements.

## Confirmed implementation defects

Evidence references use `VM` for
[`SceneViewModel.kt`](../android/app/src/main/kotlin/com/droidacoustic/pro/scene/SceneViewModel.kt).
Other Kotlin paths below are relative to `android/app/src/main/kotlin/com/droidacoustic/pro/`.
Line numbers refer to the review baseline and may move as changes land.
**P1** means correct before relying on predicted results; **P2** means address during
the foundational release work. These are code-review priorities, not security ratings.

### P1 — Production uses the older calculation path

The listener and map call `directPathSplDb` and `coherentSumDb` in `VM:4392–4418`
and `VM:4565–4578`. They do not use the newly added `acoustics/DirectField.kt`.
The newer atmospheric and band-summation work therefore does not automatically
improve what the application displays. Select and validate one production engine,
then route listener, coverage, reports, and eventual optimization through it.

### P1 — Absolute level lacks a complete calibration contract

`VM:4699–4708` uses a scalar sensitivity for every band, and `VM:4806` adds the
value named `signalLevelDbu` directly. The imported per-band reference levels in
`scene/ClfData.kt:76` onward are not consumed by that production level calculation.
Define input reference, source spectrum, per-band response, gain, and any amplifier
relationship explicitly. Until calibrated, distinguish relative coverage from
absolute SPL; do not infer headroom or maximum output from sensitivity.

### P1 — Array distance and phase are not preserved correctly

`VM:4802` passes the already calculated 3D source distance as `horizDistM`.
`VM:4750` then adds the element's vertical offset again. This changes both spreading
and phase for elevated arrays. Derive each element-to-receiver vector directly.

`VM:4784–4785` reduces the internally summed array to a magnitude. `VM:4914–4917`
then gives that magnitude a new phase based on the array center. The array's actual
complex phase is lost before combination with another array, fill, or subwoofer.
Keep elemental complex contributions through the final coherent group sum.

### P1 — Measured-data interpolation fixes are not connected

The existing uncommitted `ClfPolarPattern.at()` at `scene/ClfData.kt:23` handles
the azimuth seam, but `splAtDirection()` at `:93–95` still calls the old clamping
interpolator. Production callers therefore retain the seam discontinuity.
`patternAt()` at `:87–90` also uses a fixed ±200 Hz tolerance, which is unsuitable
across octave/third-octave ranges. Consolidate interpolation, use a documented
log-frequency policy, and distinguish unavailable data from synthetic substitution.

### P1 — Mechanical aiming and electronic steering are conflated

`VM:78–91` combines aim and steering into element angles; `VM:4777–4779` uses those
angles again as electronic phase steering. Mechanical cabinet rotation and splay
must change position/orientation; electronic steering must change electrical delay.
Apply them separately, using a common geometry definition for calculation and drawing.

### P1 — Recovery loses imported measured speaker data

`ui/shell/AppShell.kt:183–198` deliberately omits the CLF registry from autosave.
`VM:1772–1790` retains imported measurements in memory, while the file picker in
`ui/shell/SettingsSheet.kt:83–107` does not persist the file or its access reference.
After process restart, the speaker identity can survive while its measurements do
not; `VM:4817–4818,4857–4860` falls back to synthetic directivity.
Store imported assets separately, referenced by stable ID/hash, and resolve them
before prediction. Missing measured data must be visible, not silently substituted.

### P1 — Tilted speakers sample measured directivity in the wrong frame

`VM:4973–4980` separately subtracts yaw and elevation instead of rotating the
receiver vector into the cabinet's local frame. A box tilted 45° downward with a
receiver directly to its side should see local elevation 0°; this path uses +45°.
Use a full orthonormal orientation transform, including roll when supported, and
test asymmetric balloons at tilted/off-axis receiver positions. The new
`SoundSource.toward()` provides a useful basis for the correction.

### P1 — Coverage is restricted to a fixed 24 m square

`VM:4502–4516` samples x/z −12…12 m regardless of venue or audience-zone extents.
A valid zone beyond those bounds receives no grid samples. `VM:4486–4501` also
switches off polygon-grid sampling whenever explicit audience points exist.
Generate receivers per audience surface and calculate named probes independently.

### P1 — Stacked audience surfaces are merged in the renderer

`ui/FilamentSurface.kt:550–567` groups cells by x/z, averages their dB values, and
draws that average at the highest Y. A floor and balcony become one invented map.
`ui/HeatmapGlb.kt:89–106` likewise constructs topology without surface identity.
Preserve surface IDs, receiver heights, and triangle connectivity through the pipeline;
never interpolate between unrelated levels or across a zone boundary.

### P1 — Old results can be exported with new calculation settings

With automatic calculation disabled, `VM:3013–3018,3220–3224` changes settings while
retaining previous results. `VM:2626,2637–2658` exports those results beside current
settings. Associate each result with its immutable input snapshot and revision.
Show stale state and ensure export either uses recorded result settings or requires
a fresh calculation. The current “Recalculate” button is not a provenance mechanism.

### P2 — Displayed surface height can differ from the evaluated receiver

Outside explicit zones, `VM:4518–4532` draws cells on stage/block tops but evaluates
them at a fixed global 1.2 m height. Use the actual receiver surface plus ear offset,
or show an explicitly separate floor-level slice. Share this geometry with rendering.

### P2 — File operations and state boundaries need restructuring

CLF parsing returns to the UI thread in `ui/shell/SettingsSheet.kt:99–107`; complete
project serialization/loading also runs synchronously at `509–533`. The import size
limit is checked after unbounded `readBytes()` at `85–91`. Bound reads while reading,
parse on workers, then commit validated state atomically.

`VM` mixes domain models, file formats, acoustic calculations, undo, and UI state in
5,664 lines. Workers read live flows throughout calculation (`VM:4375–4427,4469–4591`).
Capture one immutable request, add cooperative cancellation, and publish one result
only for its matching revision. Avoid expanding this class with iOS or new solver logic.

## Model limitations requiring explicit decisions

The standalone engine is promising, but integration alone is insufficient:

- **Signal definition:** `DirectField.kt:167–209` averages segment powers equally.
  State whether input represents equal band energy, flat spectral density, pink
  noise, or a supplied spectrum. Define weighting/integration and normalization
  consistently so changing bandwidth or partitioning does not invent source power.
- **Coherence groups:** `DirectField.bandPower` has one coherent/incoherent switch.
  Correlated sources, independent feeds, and group-specific signal routing need an
  explicit model. `SoundSource.group` currently indexes loss, not coherence policy.
- **Complex transfer:** magnitude, delay, and polarity are not a full model of
  crossover/filter phase or inter-model phase alignment. Add complex response and
  DSP transfer where data exists; label geometric-phase approximations where it does not.
- **Unsupported data:** `DirectField.kt:174–176,228–236` turns missing frequency data
  into zero contribution and ultimately a numerical floor. Return data availability
  separately so missing measurements cannot be mistaken for valid quiet coverage.
- **CLF capabilities:** treat this implementation as magnitude-oriented. The
  [CLF FAQ](https://www.clfgroup.org/faq.htm) describes optional phase data; do not
  claim that the format can never carry phase, or that this parser supports every variant.
- **Binary calibration:** `ClfBinaryReader.kt:78–82` explicitly identifies the CF1
  sensitivity offset as inferred without matching ground truth. Balloon integrity
  alone cannot validate absolute level. Mark that calibration experimental until
  independently verified.
- **Line-array validity:** isolated cabinet magnitude balloons do not establish
  manufacturer-equivalent near-field interaction, coupling, or maximum output.
  Specify validated distances/configurations and dataset capability before making claims.

Legacy floor/obstruction/reflection heuristics must be separately selectable and
identified. They should not silently alter a free-field PA prediction. The score in
`VM:4654–4685` explicitly is not STI, yet reports export `sti` and `alconsPct`
(`VM:2631–2634`). Remove those professional metric claims from the first release.
Defer room acoustics and the legacy optimizer until their assumptions, objective,
constraints, and shared production-engine integration can be independently validated.

## Android today; an incremental iPad route

Only an Android application is configured: Android/Kotlin plugins, `arm64-v8a`,
and `:app`. There is no iOS application, Apple target, or demonstrated iPad build.
It is not too late to add one. The recommended route is a shared Kotlin domain and
prediction core with platform-specific viewport and operating-system adapters.
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
supports sharing logic while retaining platform-specific applications.

Extract shared models, geometry, prediction, parsers, and project schemas first.
Replace Java `Math`/`Double` calls in `acoustics/`, `org.json` in `ClfData.kt`, and
`java.nio`/JVM text helpers in binary readers and mesh builders. Keep Android file
pickers, preferences, lifecycle, clipboard, and assets behind platform interfaces.

Filament has an iOS/Metal path, but `ui/FilamentSurface.kt:3–27` is an Android
`SurfaceView`/gesture/Java-binding adapter. An iPad adapter is new work, not a build
flag. Prove a small shared scene and matching calculation on iPad early, using the
[Filament platform documentation](https://github.com/google/filament/blob/main/README.md).
Choose shared versus native UI after that spike; keep renderer-neutral scene data.

Do not rewrite the solver in C++ solely to obtain iOS support. The current C++ API
mostly exposes lifecycle and distances; speaker/SPL APIs remain commented stubs in
`engine/AcousticEngine.h`. Retain optional native acceleration behind an interface
and move validated hotspots only when profiling justifies the additional implementation.

## Validation boundary and immediate evidence to obtain

This assessment uses source inspection and the existing repository structure.
It does not claim a fresh device test, measured acoustic comparison, or iOS validation.

The host test attempt used cached Gradle 8.14.3 with `testDebugUnitTest --offline
--no-daemon`, Android Studio's JBR, a temporary writable Gradle home, and a
read-only dependency cache. Gradle failed before building with
`FileLockContentionHandler: java.net.SocketException: Operation not permitted`.
The elevated retry did not complete. Consequently, this review reports **no
fresh Kotlin or C++ test pass and no APK build**. Existing test XML artifacts
were not counted as new verification. Documentation whitespace was checked with
`git diff --check`.

CI is already configured to run C++ tests, Kotlin unit tests, and a debug APK
build. Re-run that baseline in an authorized build environment, then add the
end-to-end and independent acoustic evidence below; CI coverage is not absent.

Corpus-dependent tests in `ClfBinaryReaderTest.kt:21–22` skip when untracked
manufacturer files are unavailable, so a clean CI checkout does not reproduce
the local corpus validation. Add small distributable fixtures and maintain
separately authorized real-data comparisons. Release preparation also needs the
missing `android/app/proguard-rules.pro` referenced by the release configuration,
the confirmed project `LICENSE`, and device tests. No release-build failure is
claimed here because that build was not run.

Required regression scenarios include: a 60 m venue; a zone outside ±12 m; stacked
floor/balcony zones; an independent probe beside audience surfaces; an elevated array;
two phase-related arrays; a subwoofer polarity/delay sweep; cold restart after CLF
import; and export after editing settings with automatic calculation disabled.
Run analytical fixtures and versioned reference projects before declaring prediction
accuracy. Field/reference comparisons need documented geometry, data, signal,
environment, and tolerances, not visual similarity between heatmaps.
