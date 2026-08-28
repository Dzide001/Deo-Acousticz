# CLF CF1/CF2 container — reverse-engineering notes

Status: **reconnaissance only. No parser has been written against this.**
Phase 2 is waiting on the official CLF Group reader tooling (see
`docs/clf_sdk_request.md`). These notes exist so that (a) the knowledge is not
lost, and (b) whatever the SDK returns can be checked against something.

Everything marked *verified* below was measured against the bundled corpus and,
where stated, against published ground truth. Everything marked *inferred* is a
working hypothesis that has not been proven and should not be relied on.

## Why this was not simply read from a spec

The CLF Group publishes the **TAB text format** openly. CF1 and CF2 are
deliberately closed "secure binary" distribution formats with tamper-evident
features, and the reading tools are available on request rather than published.
See <http://www.clfgroup.org/faq.htm>. The format definition itself is described
as freely available, but the binary layout is not in any public document I could
find.

## Corpus

`corpus/clf` — gitignored, inventoried with checksums in
`tools/clf/inventory.json` (714 files, 448 MB). 679 are CF1/CF2; the rest are
release notes, DXF cabinet geometry, TAB sources and zips.

| Vendor dir | Files |
|---|---|
| ev | 262 |
| dab | 162 |
| jbl | 118 |
| martin_audio | 84 |
| eaw | 43 |
| qsc | 42 |
| db | 2 |
| martin | 1 |

## Container layout (verified)

Little-endian throughout. Offsets are from the start of file.

| Offset | Type | Meaning |
|---|---|---|
| `0x00` | u32 | Magic `0x000ABD41`. Constant across all 679 files. |
| `0x04` | u32 | `1` in every file. Container version. |
| `0x14` | char[] | Version string: `v1.0h`, `v2.0c` or `v2.1a`. |
| `0x34` | char[] | Manufacturer, NUL-padded (`"d&b audiotechnik, Backnang, DE"`). |
| `~0x13c` | char[] | Model name (`"16C"`, `"XD12"`). Offset varies; not yet pinned. |
| `~0x1000` | f32[30] | Max SPL per third-octave band. |
| `~0x1210` | u32, u32 | MINBAND / MAXBAND as **slot indices**, not frequencies. |
| `~0x138c` | f32[30] | Horizontal coverage angle per band. |
| `~0x1404` | f32[30] | Vertical coverage angle per band. |

The 30 band slots are the third-octave centres 25 Hz … 20 kHz:

    25 31.5 40 50 63 80 100 125 160 200 250 315 400 500 630 800 1000 1250
    1600 2000 2500 3150 4000 5000 6300 8000 10000 12500 16000 20000

The MIN/MAXBAND pair indexes into that table. Martin Audio XD12 stores `(3, 29)`,
and its TAB source declares `<MINBAND> 50` / `<MAXBAND> 20000` — slots 3 and 29.
`29 - 3 + 1 = 27`, which is exactly the number of `<BAND>` blocks and the length
of the `<SENSITIVITY>` row in that file. Three independent confirmations.

## The balloon (verified)

Contiguous `float32` little-endian, attenuation in dB, ordered
**`[band][azimuth][elevation]`**.

| | CF2 | CF1 |
|---|---|---|
| Resolution | 5° | 10° |
| Bands | 30 (third-octave) | 10 (octave) |
| Azimuths | 72 | 36 |
| Elevations | 37 | 19 |
| Payload | 319,680 bytes | 25,920 bytes |

This matches the CLF Group's own description of the two formats: 10°/octave for
CLF1, 5°/third-octave for CLF2.

Bands outside `[MINBAND, MAXBAND]` are present as slots but filled with exact
zeros. Values are normalised relative attenuation: on-axis sits at 0 dB, and a
small positive excursion (up to about +2.8 dB) occurs where an off-axis lobe
exceeds the reference axis.

### Ground truth

`martin_audio/CLF2_XD12.tab` is the open-text source for
`martin_audio/Martin Audio-XD12.CF2`. Decoding the binary at offset `0x3798`
and comparing all 27 published bands against the text gives a worst deviation of
**4.77e-06 dB** — that is float32 quantisation of the text's single decimal
place. The decoding is exact, not approximate.

### Coordinate system (corrected)

The balloon is **not** azimuth/elevation. It is axis-relative spherical, as the
file's own `<CABINET-SYSTEM> <on-axis> <+x> <up> <+z>` line says:

- **theta** — polar angle from the on-axis direction, 0…180° in grid steps
  (37 samples at 5°). Runs *along* each arc. Index 0 is dead ahead, not a pole
  in the geographic sense.
- **phi** — rotation about the on-axis direction, 0…355°, measured from *up*.
  One arc per phi. phi 0/180 is the vertical plane, phi 90/270 the horizontal.

An earlier draft of this document described the arc axis as ±90° elevation.
That was wrong. At 8 kHz the XD12's first arc runs 0.0 dB monotonically down to
−33.7 dB across its 37 samples, which is a sweep from on-axis to behind the box,
not a sweep from one pole to the other through the front.

The phi reference was fixed by measurement, not assumption: at 4 kHz the
−6 dB angle along phi 90/270 gives 45° + 45° = 90°, matching the XD12's
published 90° horizontal pattern, while phi 0/180 gives the narrower and
frequency-dependent vertical figure. So phi 0 is up.

Converting from the app's convention, with on-axis +x and up +z:

    theta = acos(x),  phi = atan2(y, z)

### The pole check

Index 0 and index 36 of every arc are the same two points in space — dead ahead
and dead behind — so all 72 arcs must agree on them. They do, to 0.0000.

This is worth keeping as a permanent integrity assertion. It is also the only
thing that caught two separate false-positive locators during this work: a
naive "find a run of plausible dB values" scan reported 671 of 679 files
decoded, and it was wrong — the region before the balloon contains other
small-valued float arrays that pass a range test. Enforcing the pole identity
dropped that to a truthful 197.

## What is NOT solved

- **The balloon offset is not stored anywhere.** Every u32 in every file was
  searched; none holds the offset, the offset minus the header, or the payload
  length. It is positional, derived from the metadata block's layout. 182 files
  share `0x3798`, but that is an observation, not a rule.
- **`<BALLOON-SYMMETRY>`** can be `<none>`, or quarter/half, in which case fewer
  arcs are stored and mirrored on read. Unhandled. This is the most likely
  reason ~480 files do not currently decode.
- **`<BALLOON-ARC-ORDER>`** is `<reversed>` in the XD12 TAB, yet the binary
  matches in direct order. The flag is presumably normalised at encode time, but
  this has been confirmed on exactly one file.
- Multipart files, optional phase data, and filter blocks (CLF2 v2 features) are
  entirely unexplored.
- 8 Martin Audio ceiling CF1 files match no grid tried. Likely half-space
  radiation with a reduced elevation range.

## Resolved: the corpus no longer ships

The corpus was 430 MB inside `assets/`, which put the debug APK at 317 MB
against Google Play's 150 MB base limit. It has been moved to `corpus/clf/`,
outside the Android module, as a development and test fixture. The debug APK is
now 62 MB. All 714 files were verified against `inventory.json` after the move.

This also settles the licensing question that the binary format raised. The app
no longer redistributes anyone's measurement data. Users supply their own files,
which is how every other tool in this category works.

## The supported route: TAB, not CF2

`ClfTabParser` reads the **published** TAB text format. Nothing in that path is
reverse engineered and there is nothing to license. It is wired into
`SceneViewModel.importClfText`, which now detects a `<CLF…>` header and routes
to the TAB reader instead of the old `key=value` sketch.

The CF2 work in this document remains useful — it is how the coordinate system
and grid were established, and TAB and CF2 share both — but a CF2 reader is not
required for the app to use real measured directivity. It would only be a
convenience for users who hold CF2 rather than TAB.

## Wired into the SPL path

`PlacedSpeaker.presetId` records which preset a box was placed from, which is
what links it back to measured data in the CLF registry. Before that field
existed the lookup passed the placed box's integer id against a registry keyed
by model, so it never matched and the synthetic fallback ran every time - the
TAB reader and the file picker were both inert.

Two things the measured path must switch off, because a balloon already contains
them and stacking synthetic terms on top corrupts real data:

- `verticalAimAttenuationDb` - was already handled.
- `beamShadowPenalty`, a flat 12 dB step applied once the synthetic model's
  attenuation passes 14 dB. This was NOT handled, and it silently added 12 dB to
  every measured off-axis prediction. Found by a test asserting that a fixture
  with a 30 dB cliff produces a 30 dB drop; it produced 42.

Verified on device with the real XD12: same venue, same speaker position, avg
audience level 55.5 -> 63.6 dB and uniformity +/-16.8 -> +/-9.0 dB when the
measured pattern replaces the synthetic one.
