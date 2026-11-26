# Asymmetric directivity orientation validation

This package exercises the **production shared-core prediction request**. The synthetic
cabinet is 15 dB louder toward its left at 45° than toward its right. It is not a
real speaker or a manufacturer accuracy comparison.

The normalized coordinate contract is X forward, Y up and Z right. Positive azimuth
turns toward speaker-right, viewed from behind the cabinet along its forward axis.
Positive elevation points up; positive mechanical tilt points down. At yaw 90°,
forward is world +Z and right is world −X. Importers must convert other conventions.

## Reproduce

```sh
python3 validation/asymmetric-directivity/reference.py --check
cd android
./gradlew :shared-core:jvmTest --tests '*Asymmetric*'
./gradlew :app:testDebugUnitTest --tests '*AsymmetricDirectivityRecoveryTest'
```

`reference.py` uses Python's standard library, explicit rotation matrices and cyclic
bilinear dB interpolation independently of engine code. Run without `--check` to
intentionally regenerate `speaker.json` and `results/reference.json`. CI checks both
and uploads a fresh engine report without replacing reviewed results automatically.

The 198 comparisons cover three frequencies (500 Hz, 1 kHz, 4 kHz), six translated
and elevated cabinet poses, ±90° pan, equivalent 179°/−181° pans, positive/negative
mechanical tilt, unequal upper/lower response, stored/interpolated directions,
signed unsorted grids, and front/rear azimuth seams. Each prediction is normalized
to an equal-distance on-axis receiver, cancelling common propagation loss. The
predeclared numerical tolerance is **0.002 dB**, not physical accuracy.

Each case repeats after portable speaker JSON serialization/reopening. Deliberately
mirrored data must match the mirrored reference and fail the original reference in
all **180 off-axis cases**. The 18 on-axis cases cannot reveal horizontal handedness.
Common tests add hard-coded world-axis checks and independently verify each frame
in a three-box array, including invariance of angular frames under electronic
steering. Android adapter tests import the same fixture, check production capture
at 0°/90° pan in all three bands, and repeat after disk save/fresh reopen.

## Evidence and limits

`results/engine-jvm.json` retains actual levels, residuals, reopened results and
mirrored negative controls with fixture/reference hashes. `results/verification.json`
records the local test/build gate.

This verifies normalized-data handedness through the engine and Android adapter.
It **does not certify external CLF TAB/CF1/CF2 arc handedness**, binary arc-order
inference, CF1 level offsets, intrinsic phase orientation or manufacturer accuracy.
The symmetric XD12 reference cannot establish left versus right. The subsequent
[external CLF package](../clf-handedness/README.md) uses the official specification's
labels and asymmetric example to correct and qualify the tested TAB/CF2 subset.
Asymmetric CF1/other variants and physical manufacturer comparisons remain open V2
gates; synthetic native tests alone cannot establish external import signs.

Primary references consulted: the [CLF Group FAQ](https://www.clfgroup.org/faq.htm)
and [official downloads](https://www.clfgroup.org/clfdocuments.htm). The full
text-format specification is distributed in the authoring kit. It was extracted
and inspected in the external CLF batch; its Figure 1 establishes the labels.
