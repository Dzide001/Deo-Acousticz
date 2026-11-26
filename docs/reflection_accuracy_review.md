# Reflection accuracy review

2026-11-17. Scope: how the shared-core engine predicts reflected sound, and
whether the reflection-related parts of the app give results a system designer
can rely on. This replaces a review given in conversation the same day. That
review overstated several points; the corrections are listed at the end.

## What the engine models

`DirectField.predictBand` sums, per receiver and band:

- the direct arrival from every element;
- an optional reflection from a global floor at y = 0 (off by default);
- first-order specular reflections from boundary facets whose role is
  `RIGID_REFLECTOR` (stage, venue blocks, imported meshes);
- first-bounce diffuse energy from facets whose material declares scattering.

Specular arrivals take the image path length and delay, read the radiator in the
direction the ray leaves the box, lose air absorption over the whole path, and are
weighted by the material's specular energy fraction. Arrivals are summed with the
band-averaged pair formula, so band levels are exact for constant amplitudes and
flat spectral density. Phase conventions are consistent across radiators, DSP
filters and measured reflection phase (all in the engine's positive-delay
convention). Diffuse energy is Lambertian and energy-conserving.

There is no diffraction, no reflection beyond first order and no reverberant
field.

## Verified: specular reflection from a finite rigid plate

`shared-core/src/jvmTest/.../acoustics/SpecularReflectionReferenceTest.kt`
compares engine band levels with a physical-optics (Kirchhoff) integral over the
same plate, computed independently in the test. Omni source at 100 dB / 1 m,
octave bands, 20 °C / 50 % RH.

The reference is checked first:

| Check | Result |
|---|---|
| 20 m × 20 m plate at 500 Hz (about 40 λ), reflected field only, vs image source | 77.01 vs 77.01 dB |
| Doubling mesh density (λ/10 → λ/20) and frequency samples (32 → 64 per third) | change < 0.01 dB |

Engine against reference, receivers inside the geometric reflection zone:

| Geometry | Band | Receiver positions | Largest difference |
|---|---|---|---|
| 4 m × 4 m plate 10 m from source, receiver 1 m in front (plate ≈ 6–12 λ) | 500 Hz | z = 0, 0.5, 1.0, 1.5 m (zone edge 2.2 m) | 0.52 dB (at 1.5 m) |
| same | 1 kHz | same | 0.06 dB |
| 12 m × 8 m plate, receiver 3 m in front (plate ≈ 6–9 λ) | 250 Hz | z = 0, 1.5, 3.0 m (zone edge 7.8 m) | 0.09 dB |

The test asserts agreement within 1 dB for these cases, which leaves margin
for the edge ripple the reference contains and the engine does not.

**What this establishes:** for plates several wavelengths across, with receivers
well inside the reflection zone, the engine's specular reflection agrees with
physical optics to about half a decibel or better.

**What it does not establish:** a general frequency or accuracy threshold.
Results depend on reflector size relative to wavelength, incidence angle and
receiver position. Physical optics itself is unreliable when the plate is
comparable to or smaller than a wavelength, or near the zone edge, so it cannot
judge the engine there. That regime needs an exact reference (boundary elements,
or edge diffraction after Biot–Tolstoy–Medwin).

## Findings

### 1. Obstruction is all-or-nothing

`BoundaryGeometry.blocked` removes a direct or reflected arrival when the segment
crosses an occluding or reflecting facet. Nothing bends round the edge. The
stage and venue blocks default to `OCCLUDING`.

Behind an obstacle, the blocked direct contribution is zero. Reflected or diffuse
contributions can still reach the receiver; when none do, the displayed level is
the 0 dB display floor, which is a floor, not a predicted level. The limitation
is the missing diffraction. A Kirchhoff aperture estimate for a 3 m barrier
suggested real levels roughly 6–20 dB below free field in the shadow, but that
estimate was not convergence-checked and is a lead, not a result.

A correct fix has to fit the coherent summation: edge-diffracted arrivals with
amplitude and phase, from an established method such as UTD or
Biot–Tolstoy–Medwin. ISO 9613-2 barrier attenuation is an outdoor
environmental-noise method that gives attenuation without phase. It could only
serve as a clearly labelled approximate mode.

### 2. Reflectors are mirrors up to their edge

A specular arrival exists only when its bounce point lies on the facet, so the
level steps at the geometric zone boundary. In the 4 m plate case at 1 kHz,
moving the receiver from 0.1 m inside the boundary to 0.1 m outside changes the
engine level by 2.33 dB; the reference changes by 0.64 dB. The test pins this as
a known limitation, to be replaced by an agreement test once finite-reflector
diffraction is modelled.

The engine's accuracy for reflectors comparable to a wavelength is **not
established**. Figures at 63 Hz and 125 Hz from the earlier review compared the
engine with physical optics outside physical optics' validity and are withdrawn.

### 3. Complex reflection coefficient of absorbing surfaces

Interference from path-length differences is modelled, including floor nulls, and
measured reflection phase (`ReflectionPhase`) is supported for floors and
materials. What absorption alone cannot supply is the complex reflection
coefficient. In `DirectField.predictBandSamples`, a source with complex
(phase) response data reports the band as missing when an absorbing surface has
no phase data; a magnitude-only source proceeds with zero reflection phase. The
two paths therefore differ in how they treat the same missing information, and
effects that depend on a phase change at reflection, such as the low-frequency
dip over seating at grazing incidence, appear only when measured phase is
supplied.

### 4. The legacy early-reflection list

`SceneViewModel.buildEarlyReflections` predates the engine and does not use it:

- it reflects off a box from `estimateRoomBounds`, at least 20 m wide and
  enlarged to enclose every speaker and audience point, not the drawn venue;
- it applies empirical terms with no stated source: a grazing-incidence loss of
  up to 8 dB scaled with frequency, a per-bounce loss that depends on the
  Fast/Balanced/Precision profile, and an older air-absorption table with an
  order-dependent factor;
- it uses the older level and directivity functions, not the measured radiator.

Its output is stored in `earlyReflections` but nothing in the UI reads it. It
runs after every listener calculation. The **Reflection order** control in
Settings affects only this list, so it has no visible effect.

### 5. Drawn room walls are not boundaries

`BoundaryGeometry.prepare` takes the stage, venue blocks and imported meshes. The
venue's width, depth and wall height are drawn by `VenueGeometryGlb` but do not
become facets, so the drawn walls and ceiling never reflect or occlude in the
engine.

### 6. Scope of the reflection model

First-order specular and first-bounce diffuse only, with no reverberant field.
That is consistent with a direct-field design tool, but the UI should say so.
The RT60 readout is Sabine over the `estimateRoomBounds` box, not the drawn
venue, and the intelligibility score is a weighted heuristic, not IEC 60268-16
STI (the code says so, though the `StiEstimate` fields are still named `sti` and
`alconsPct`).

### 7. Generic presets

`SceneViewModel` hard-codes 48 entries in `SPEAKER_PRESETS` and 36 in
`INDUSTRY_STARTER_PRESETS`, which overlap; separately discovered data files and
imports add more. Imported measured data takes precedence over a generic
fallback. Approximate entries carrying manufacturer names need clear provenance
and presentation as approximations. No conclusion about trademark use is drawn
here.

## Assessment against the product goal

The goal is a tablet design tool comparable to ArrayCalc, Soundvision and MAPP.
Those are primarily direct-field predictors. The engine's direct-field core is
sound in the cases tested here and in `DirectFieldTest`. A code search on
2026-11-17 found no maximum-SPL or headroom calculation and essentially no
rigging or mechanical model, and the engine has not been compared with a
manufacturer's own prediction for the same loudspeaker and venue. Those gaps
matter more to the stated goal than further reflection modelling.

## Recommended order of work

1. Compare the direct field with manufacturer predictions for loudspeakers whose
   makers publish coverage or GLL/EASE results, with documented geometry, data,
   signal and tolerances.
2. Decide what to do about obstruction: phase-aware edge diffraction, or default
   blocks to no acoustic role until it exists.
3. Remove `buildEarlyReflections` and the Reflection order control, or rebuild
   the list from the engine's own specular paths so list and map agree.
4. Decide whether drawn walls and ceiling should become optional engine
   boundaries.
5. Add finite-reflector diffraction, with an exact reference for plates
   comparable to a wavelength.
6. Make the two complex-reflection paths treat missing phase the same way, or
   label the assumption where they do not.

## Corrections to the conversational review

- *"Accurate above about 300 Hz", and 2–5 dB errors below.* Based on one
  geometry, with the low-frequency figures compared against physical optics
  outside its validity. Replaced by the scoped results above.
- *"The engine predicts silence (0 dB) behind obstacles."* 0 dB is the display
  floor; other contributions can still reach the receiver. The limitation is
  missing diffraction (Finding 1).
- *"Absorbent floors reflect in phase and cannot show the seating dip."*
  Path-length interference is modelled and measured reflection phase is
  supported; the gap is the complex coefficient when only absorption is known
  (Finding 3).
- *"ISO 9613-2 barrier attenuation as the fix."* It provides attenuation
  without phase and is suitable only as a labelled approximation (Finding 1).
- *"91 branded presets with invented data; a trademark problem."* 91 was a raw
  count of constructor calls. The hard-coded lists hold 48 and 36 overlapping
  entries, and no trademark conclusion can be drawn from names alone
  (Finding 7).
