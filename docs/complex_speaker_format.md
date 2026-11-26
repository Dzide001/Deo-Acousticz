# Complex speaker response extension

The portable decoded speaker JSON accepts an optional `complexResponse` object. Existing
speaker identity, magnitude patterns, sensitivity and calibration fields remain unchanged.
Only explicitly supplied, consistently referenced complex measurements qualify for phase traces.

```json
{
  "version": 1,
  "convention": "positive-delay-propagation-removed-1m-v1",
  "provenance": "Measurement source and calibration procedure",
  "referenceDrive": "1 V rms at cabinet input",
  "patterns": [
    {
      "frequency": 1000,
      "azimuths": [0],
      "elevations": [-90, 90],
      "real": [[100, 100]],
      "imaginary": [[0, 0]]
    }
  ]
}
```

This object is illustrative synthetic data, not a measured loudspeaker. Put it under the
`complexResponse` key of a decoded speaker file to exercise the format. Real and imaginary
entries represent pressure divided by 20 µPa at 1 m for the stated drive. In the example,
magnitude 100 corresponds to 40 dB SPL. The pressure reference, distance, propagation
removal, electrical timing reference and drive normalization must be established by the
measurement/decoder; the app cannot infer or verify them from magnitude data.

Positive delay multiplies the imported pressure by exp(+j 2π f t), consistent with this app’s
phasors. Convert data recorded under the opposite convention before import. Remove measured
propagation delay before supplying intrinsic cabinet phase, so the solver does not add it twice.
Unreferenced, magnitude-only or arbitrary phase offsets are not valid substitutes.

Grids follow the existing polar convention: azimuth/elevation in the cabinet’s own frame.
Azimuth wraps; real and imaginary components interpolate separately. A singleton azimuth
asserts rotational invariance, not an on-axis-only measurement. Elevations outside the supplied
range are unavailable. Frequencies must be unique positive integers; no interpolation between
frequency samples is performed. Patterns are bounded to 128 frequencies and the object to
32 MiB. Missing or unsupported version/convention and non-finite grid values are rejected.

Current phase traces support coherent direct and first-order specular propagation through
Blocks and imported Faces. Ideal rigid materials supply explicit zero phase; other nonzero
reflections need measured material phase. Material exp(+iwt) coefficients are conjugated into
the source positive-delay convention. Global ground uses its absorption curve and optional measured reflection phase. Partially
absorbing ground without that phase, diffuse scattering models, and EQ requiring an unknown
complex transfer remain unsupported. Exact nulls and
near-total cancellation (relative amplitude ≤ 1e-9 of summed emitter amplitudes) have undefined
phase. Saved traces carry `coherent-direct-specular-ground-tone-positive-delay-v3`, wrapped degrees and missing
reasons. Raw captures remain exact-tone wrapped phases, not broadband phase or impulse responses.
The shared core now derives nearest-turn unwrapped segments and interval group-delay estimates
from these persisted samples. Missing samples and ambiguous 180° steps break continuity.
With the positive-delay convention, delay in seconds is ΔphaseDegrees / (360 × ΔHz).
This is a linear-frequency secant, not differentiation on the logarithmic display axis.
It assumes less than 180° of true phase change per interval; the corresponding alias limit
is ±1/(2ΔHz) seconds. Sparse octave captures cannot establish this assumption or recover
hidden turns. Single-tone captures cannot provide delay. No complex samples are interpolated.
Each segment has an independent wrapped anchor, so absolute unwrapped turn counts are unknown.
Comparisons require matching frequency grids and supported matching propagation conventions.
Derived displays are recomputed from saved raw data, preserving older projects without a schema change.

Reference: [SciPy group-delay definition](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.group_delay.html).
Its negative derivative applies to the usual negative-delay phasor; this engine uses the conjugate convention.


Dense capture uses actual frequency rows (up to 4,088) from imported complex responses.
Trace phase keys are independent of band-power keys; at most 4,096 phase samples are retained.
No complex frequency interpolation is performed. Missing contributing-source data stays missing.
New captures save `delayReferenceS`: the earliest geometric emitter propagation plus DSP delay.
This reference is subtracted before unwrapping and restored in the reported phase and total group
delay. The ±1/(2ΔHz) limit applies to the residual delay, not the removed propagation/DSP reference.
Occlusion and interference can make this reference differ from the actual dominant arrival.
Legacy captures without the field use zero. The reference does not imply an impulse response.


Saved traces may include `manualDelayReferenceS`, independent of `delayReferenceS`. Null/absent
uses the original automatic reference; explicit zero remains a manual zero. Values must be finite
and within ±3,600 seconds. The trace library's optional `relativeToReference` defaults to false.
Relative mode reports wrapped/unwrapped phase and group delay with the effective reference removed;
total mode restores it after unwrapping. Raw complex-derived samples are never rewritten.
