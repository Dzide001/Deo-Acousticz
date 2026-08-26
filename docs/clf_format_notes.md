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

`android/app/src/main/assets/clf/raw` — gitignored, inventoried with checksums in
`assets/clf/inventory.json` (714 files, 448 MB). 679 are CF1/CF2; the rest are
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

### The pole check

At ±90° elevation, every azimuth arc describes the same point in space, so the
first and last value of all 72 arcs must be identical. They are, to 0.0000.

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

## Practical note

The corpus is 430 MB in `assets/`, which puts the debug APK at 317 MB. Google
Play's base module limit is 150 MB. However Phase 2 resolves the parsing, the
data cannot ship in this form.
