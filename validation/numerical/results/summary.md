# Numerical validation results — revision 1

18/18 scenarios passed: **14 numerical verification cases and 4 model-limitation regressions**.
The latter verify current approximations, not physical accuracy. Two JUnit package tests exercise
all fixtures, reference-integrity/convergence checks and engine comparisons.

Runtime: `21.0.9+10-LTS`; platform: `Mac OS X aarch64`.
Inputs, references and shared Kotlin sources are hashed in the JSON reports.

Maximum normalized engine power error: **1.24494143e-06**.
Maximum normalized complex-component error: **1.37873732e-07**.
Maximum final normalized reference-quadrature error: **5.72306469e-08**.
Errors use incoherent path-power normalization; these are not universal dB accuracy claims.

| Case | Evidence type | Normalized power error | Result |
|---|---|---:|---|
| direct-1m | Numerical | 1.79617e-12 | Pass |
| direct-2m | Numerical | 3.59544e-12 | Pass |
| direct-10m | Numerical | 1.79694e-11 | Pass |
| air-and-distance-8khz-60m | Numerical | 3.89254e-09 | Pass |
| quarter-period-delay | Numerical | 2.76442e-07 | Pass |
| half-period-null | Numerical | 7.3996e-31 | Pass |
| polarity-null | Numerical | 0 | Pass |
| eight-emitter-asymmetric | Numerical | 4.01377e-07 | Pass |
| band-comb-short-delay | Numerical | 1.74914e-11 | Pass |
| band-comb-long-delay | Numerical | 1.79287e-11 | Pass |
| hard-ground-double-pressure | Numerical | 3.59387e-11 | Pass |
| absorbing-ground-free-field | Numerical | 1.79694e-11 | Pass |
| ground-destructive-interference | Numerical | 7.9064e-14 | Pass |
| finite-edge-inside | Model limitation | 6.43035e-09 | Pass |
| finite-edge-outside | Model limitation | 1.95634e-09 | Pass |
| barrier-shadow | Model limitation | 0 | Pass |
| barrier-above | Model limitation | 8.98375e-12 | Pass |
| wideband-frequency-refinement | Numerical | 1.24494e-06 | Pass |

## Wideband engine convergence

Continuous-frequency independent reference power: `5258848.80461`.

| Frequency segments | Normalized error |
|---:|---:|
| 1 | 0.00160430708 |
| 4 | 0.000540738892 |
| 16 | 0.000834111705 |
| 64 | 2.04932124e-05 |
| 256 | 1.24494143e-06 |

Final error meets `3e-6` and improves by more than 100×. Intermediate refinement is
not monotonic. This checks the segment-centre approximation against a continuous-frequency
limit for this fixture; it does not guarantee convergence for arbitrary measured directivity.

## Platform regression checks

- 16 focused Android tests pass (cleanup, stale-result handling and save/reopen).
- Android debug APK assembles.
- iOS simulator shared test sources compile; no numerical iOS execution was performed.
- No device installation, tablet performance run or field/manufacturer comparison was performed.

See [package instructions](../README.md) for equations, tolerances, limitations and reproduction.
