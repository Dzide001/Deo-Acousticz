# External CLF import handedness

This package verifies import orientation against the **CLF Group's official asymmetric
format example**, with direction labels taken independently from its specification.
It is an external format-conformance test, not a manufacturer prediction or real-world
acoustic accuracy comparison.

## Independent evidence

Source: [official CLF downloads](https://www.clfgroup.org/clfdocuments.htm),
[authoring kit v2.1b](https://www.clfgroup.org/CLF_Authoring_v2.1b.exe), retrieved
2026-11-26. `provenance.json` retains source, specification and fixture SHA-256 hashes.
The included specification is dated 4 March 2020. Figure 1, page 12, explicitly
labels the rear-view arcs: **0° up, 90° left, 180° down, 270° right**; default arc
order advances counterclockwise from up. Format footnote 8, page 11, separately
identifies the cabinet axes as X front, Y left, Z up. Cabinet geometry declarations
do not change the balloon's relative directions.

The external sample is `app/sample text/clf2_passive_point.tab`, paired with
`app/sample distribution/Passive2.CF2`. The retained TAB adaptation contains only
its original 72 magnitude arcs at 1000 Hz, with a test header. Its reference level
is an arbitrary test normalization; all checks use equal-radius relative levels.
The binary fixture is unmodified. These are validation fixtures, not app assets.
The official example's fictitious manufacturer/model is not a calibrated product.

At 60° off-axis and 1000 Hz, the external sample says **left −5.02 dB, right
−2.88 dB**. These unequal values, combined with the independently labelled arcs,
make a swap detectable. The earlier XD12 comparison is exactly left/right
symmetric and could not establish either importer's handedness.

## Corrections

The TAB parser now lives in `shared-core`, including coordinate conversion,
normalization of full-circle arc order and `<ROTATE>`. Native positive azimuth
means right, so it reads CLF arc 270° rather than 90°. The binary adapter no longer
unconditionally reverses arcs: the official pair establishes default CLF order
for this CF2 layout. Both adapters use the same shared mapping.

The subsequent [magnitude/symmetry cleanup](../clf-magnitude/README.md) extends the
shared parser and records importer v3; the results here retain the v2 handedness baseline.
The later [CF1 reference-slot batch](../cf1-reference/README.md) records importer v4.

The old binary arc reversal and old azimuth sign cancelled for this CF2 layout.
Removing both therefore preserves its existing normalized predictions while fixing
the independently wrong operations. An old **TAB** import can contain mirrored
normalized data. This batch recorded `clf-subset-v2:TAB` / `:CF2`; assets marked
`clf-subset-v1:TAB` receive a shared warning to reimport their original TAB. Saved
native JSON, history and traces are not silently mirrored. Older assets with no
importer provenance cannot be classified automatically.

## Reproduce

```sh
python3 validation/clf-handedness/reference.py --check
cd android
./gradlew :shared-core:jvmTest --tests '*ClfHandedness*'
./gradlew :app:testDebugUnitTest --tests '*ExternalClfHandednessTest'
```

To audit the external origin, download the kit above, verify its hash in
`provenance.json`, then extract it without executing the Windows installer:

```sh
innoextract -d /tmp/clf-official CLF_Authoring_v2.1b.exe
python3 validation/clf-handedness/extract.py --extracted-dir /tmp/clf-official
```

`reference.py` uses only Python's standard library. It selects samples by the
specification's labelled cardinal arcs and constructs world coordinates with
independent rotation matrices. The 64 reference rays cover left/right/up/down,
four off-axis angles, three pans and a combined pan/mechanical tilt. Tolerance is
declared as **0.001 dB** before testing. This is numerical tolerance, not physical
accuracy.

The shared JVM test checks 256 predictions: the external default TAB subset plus
equivalent reversed, rotated and reversed/rotated encodings. It repeats after
portable speaker serialization and includes the former mirrored mapping as a
negative control: all 128 horizontal cases must fail. Common tests independently
pin the direction labels, rotation and legacy-asset warning behavior. Android
checks 128 production coverage predictions across TAB/CF2, saving and freshly
reopening each pose and its imported asset. The entire Android reference scene is
translated upward 12 m so its downward test rays clear the default stage/floor;
source-relative distances and angles are unchanged. CI checks fixtures and uploads reports.

## Retained results and remaining gates

`results/reference.json` retains the independent oracle;
`results/engine-jvm.json` and `results/android.json` retain per-case residuals,
round trips and negative controls. `results/verification.json` records the build
and test scope. Fresh reports go into Gradle `build/reports/clf-handedness/`.

This qualifies the tested full-circle, relative-magnitude TAB subset and official
CF2 layout at 1000 Hz. Other symmetry/order combinations, sign/absolute-reference
variants, binary CF1 handedness, complex phase/filter/multipart containers and
manufacturer-specific variants need separate qualification. CF1 electrical level
offsets and matched manufacturer/measurement accuracy remain open. No OnePlus
gesture or physical-device check is claimed for this batch.
