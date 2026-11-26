# Measured-array performance and accuracy fixtures

These are reproducible **synthetic measured-data** checks, not comparisons against a
manufacturer's proprietary measurements or an industry application's output.

## Full-venue diffuse coverage

### Shared-core phase batching and refinement allocation reduction — 2026-11-17

The exact-tone illumination kernel now prepares correlation groups once per surface/chunk and
uses checked eight-lane phase recurrence within at most 32 tones. It re-anchors each chunk and
falls back to direct trigonometry for irregular or insufficiently accurate frequency spacing.
Frequencies, quadrature weights, material/filter responses and convergence tests are unchanged.
A complete regular chunk reduces phase trigonometric pairs from 32 to nine per source; this
operation count is not an end-to-end speedup claim. Independent scalar, near-null, mixed-group,
large-delay and cache-saturation tests pass.

The incident cache now charges shared interned visibility/loss arrays once through its bounded
registry instead of repeatedly as full copies. Power payload remains capped at 32 MiB; the key
accounting budget remains 64 MiB with conservative 192-byte entry charges plus unshared packed
arrays. The entry cap is 262,144. Actual heap/map/registry and rendering overhead is additional.
Adaptive refinement no longer constructs temporary boxed level/edge lists for every source and
band; its seven probe values and acceptance comparisons are identical.

Phase batching plus cache accounting alone observed 229.7 s on the OnePlus, essentially unchanged
from 229.8 s. Removing the refinement allocations then completed the same 64-emitter, 333-receiver,
1 kHz venue within **162.4 s**, displaying **56.7 dB** (about 29% below the earlier observation).
The computer first-calculation observation was 74.80 s with mean **56.7142013963 dB**, and exact
save/reopen statistics and all receiver levels passed. Timings are individual observations, with
native completion bounded by roughly five-second UI polling; they are not thermal/peak guarantees.
A live memory snapshot during the final run reported 248,058 KiB PSS / 384,876 KiB RSS / 191 KiB
swap PSS; it is not a peak measurement. Cold reopen completed within **162.0 s**, again displaying **56.7 dB**. The final 4/8 kHz OnePlus scenes reported calculation failure within **96.3 s / 65.1 s**, with no
partial area average. These are safe failure observations, not complete coverage timings. The
saved final scene retained its four arrays and 8 kHz selection. The debug build and all nine
original saved files were restored, checked byte-for-byte with no extra assets, and the original
project reopened. Full-venue high-frequency coverage remains unqualified.

Final computer full-venue checks still stop safely: **4 kHz exceeds the per-receiver work budget**,
and **8 kHz exceeds 262,144 coherent surface samples**. No budget, accuracy or convergence limit
was relaxed. Dense 4/8 kHz acoustic references pass, but that does not qualify complete high-frequency
venue coverage. Next work must address coupled spatial/frequency integration, rather than infer
qualification from a faster exact-tone kernel.


Post angular-bound allocation patch (2026-11-17): the same 1 kHz full-venue save/reopen test
passed, retaining a 56.7142013963 dB mean; its first computer calculation took 97.46 s. The
OPD2415 optimized build completed within 229.8 s and displayed 56.7 dB. The earlier 234.4 s
observation is close enough that these individual UI-poll timings do not demonstrate a meaningful
speedup. High-frequency whole-venue qualification and interactive latency remain open.


`PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics`
uses four 16-element arrays at 6 m height, 0.2 m element spacing, a 60 × 40 m venue and 333 receivers.
The irregular complex directivity fixture includes 875/1000/1125 Hz knots, and computes the 1 kHz
third octave. A 2 × 2 m stage at (-10, 0), 0.5 m high, has absorption 0.2 and scattering 1.0.
All reflected energy is diffuse; disabling its acoustic role must measurably reduce coverage.
The complete project embeds measured responses and materials, and requires exact save/reopen,
matching coverage/statistics and 100% valid audience area. The expected mean is 56.7142013963 dB.

Shared-core changes reuse incident power independently of receiver propagation. Before the phase/refinement batch, the cache stored
up to 32 MiB of spectral doubles, with separately bounded packed keys (64 MiB), 131,072 spectral
entries, 32,768 profile entries (up to 1 MiB of floats) and 65,536 angular-bound entries.
Object/map overhead is additional; context, frequency and canonical-vector registries are also
bounded. There is no eviction churn or inter-request cache. Overflow recomputes exact values.
Receiver-dependent distance/air loss and outgoing weights are never reused as incident power.
Canonical physical-edge subdivision changes split order, not spatial acceptance criteria.

Development-machine runs observed 95.7 and 109.4 seconds for the first complete calculation;
the full save/reopen check also passed. These timings include the full prediction adapter and
statistics and are not isolated kernel benchmarks.

On the connected OPD2415 tablet, the optimized release build completed within **234.4 s**, with
**56.7 dB** displayed. This is a roughly five-second UI-polling completion upper bound, including
startup, rather than a kernel timer. A live memory snapshot reported 169,574 KiB total PSS,
249,268 KiB total RSS and 46,300 KiB swap PSS; this is not a peak-memory measurement.
Cold reopen completed within **245.1 s**, again displaying **56.7 dB** with the four arrays,
1 kHz third octave and scattering material retained. The validated debug build was restored;
all nine original saved files matched the fresh backup byte-for-byte, with no extra test assets.
This workload remains too slow for
interactive adjustments; cross-receiver reuse is a correctness-preserving foundation, not a claim
of general interactive full-venue performance.

Run the fixture with:

```sh
ACOUSTIC_BENCHMARK_EXPORT_DIR=/tmp/diffuse-venue-benchmarks ./gradlew :app:testDebugUnitTest --tests '*PhaseTraceIntegrationTest.fullVenueThirdOctaveDiffuseCoverageReopensWithAllReceiversAndStatistics' --offline
```

Full-venue 4/8 kHz diffuse coverage remains a performance gate. An exploratory 4 kHz, 32-emitter
case reached 72,520 surface samples at its first receiver and exhausted the work budget before
the final reuse changes. The budget was not raised and accuracy tolerances were not relaxed.
The unchanged 20-million-operation limit now applies separately to geometry preparation and each
receiver's entire spectrum; cancellation still checks work throughout, and no partial result is
published. Large receiver counts can therefore take substantial total time even within that limit.

## Large-venue direct-field coverage

`PhaseTraceIntegrationTest.largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly`
uses the production Android request adapter and the shared prediction engine:

- 60 × 40 m venue; Fast analysis spacing (3 m), 333 coverage receivers.
- Two or four arrays of 16 elements, 0.2 m spacing, 6 m centre height; arrays separated laterally.
- Coherent third-octave coverage at 4 and 8 kHz for 32 emitters, plus 8 kHz for 64 emitters.
- Complex Cartesian data at 3500/4000/4500/7000/8000/9000 Hz; interpolation limited to 1000 Hz gaps.
- Irregular 13 × 8 angular grid, a narrow feature around 12° azimuth, elevation-dependent magnitude,
  direction-dependent phase and frequency-dependent real/imaginary components.
- No global ground reflection or acoustic stage boundary in this whole-venue fixture.

The test records calculation time without asserting a machine-dependent deadline. It requires finite
coverage at every receiver, coverage statistics, exact embedded-project JSON after save/reopen and
matching coverage levels and area-weighted statistics after reopening, with 100% valid coverage area. Set `ACOUSTIC_BENCHMARK_EXPORT_DIR` to export
self-contained test projects for device testing; leave it unset for ordinary CI runs.

From `android`, with the project JDK configured:

```sh
ACOUSTIC_BENCHMARK_EXPORT_DIR=/tmp/large-venue-benchmarks ./gradlew :app:testDebugUnitTest --tests '*PhaseTraceIntegrationTest.largeVenueNonuniformMeasuredArraysAtFourAndEightKilohertzReopenExactly' --offline
./gradlew :shared-core:jvmTest --tests '*LargeVenueMeasuredArrayTest*' --offline
```

If tests are already up to date and exports are needed, rerun the selected test task with
`--rerun-tasks`. Always back up the device's project and assets before loading a benchmark;
restore and compare the saved files after testing. Optimized and debug build timings are separate
measurements. Device UI polling gives completion upper bounds, not exact kernel timings.

## Independent acoustic references

`LargeVenueMeasuredArrayTest` exercises the shared core directly:

- Four 16-element measured arrays, 4/8 kHz, receivers at 15, 40 and 75 m with on/off-axis positions.
  An independent complex-pressure sum is integrated at 8192 frequency midpoints. A 4096-point
  reference must converge within 2e-5 relative; production results must agree within 5e-5 relative.
  Missing frequency coverage remains unavailable; serialization preserves complex responses.
- At 8 kHz, 32 emitters illuminate a fully scattering 4 × 4 m face from 29 m away. The array aims
  the narrow directivity feature at the face. An independent 240 × 240 surface integral is checked
  against 120 × 120 sampling (0.3% convergence) and the adaptive result (0.5% tolerance).
  This includes incidence flux, atmospheric loss, coherent illumination and Lambertian propagation.
- The scattering fixture exceeds the 65,536-entry geometry cache: repeated work must reuse stored
  entries while recomputing overflow entries, and cached/uncached power must agree within 1e-11.

The scattering reference is a single receiver and exact frequency. It does **not** qualify the cost
of third-octave scattering at every receiver in a large venue. That workload, additional boundary
counts, 16 kHz, finer audience meshes and sustained tablet thermal behavior remain separate gates.

## Observations — 2026-11-14

Tablet: OPD2415, Android 16, optimized Android build. Computer results below were recorded during
the full validation build and are not isolated microbenchmarks. Each table entry is one observation;
there is no claim of a statistically stable speedup or a sustained thermal rating.

| Emitters | Third-octave band | Computer coverage calculation | Tablet completion upper bound | Tablet area mean |
| --- | --- | --- | --- | --- |
| 32 | 4 kHz | 2.91 s | 7.8 s | 49.1 dB |
| 32 | 8 kHz | 6.71 s | 12.7 s | 52.0 dB |
| 64 | 8 kHz | 24.36 s | 74.5 s | 55.9 dB |

Tablet completion is observed through fresh UI snapshots approximately every five seconds;
unavailable UI dumps are discarded. The application also calculates its listener result, so these
numbers should not be treated as the same timing boundary as the computer's coverage-only test.
The 32-emitter 8 kHz project reopened at 52.0 dB within 12.7 s. The 64-emitter observation identifies
a remaining interactive-performance limit, rather than qualifying all large measured arrays.

The 8 kHz scattering reference produced 4779.256319697408 normalized mean-square pressure units;
the adaptive result was 4779.232190164533 (about 0.000505% difference). Cached geometry evaluations
were 316,512 cold and 250,976 on repeat: exactly 65,536 entries were reused, with overflow recomputed.

A targeted repeat also checked full valid area and identical area-weighted statistics after reopen.
Computer area means were 49.05937877, 52.03731159 and 55.87139387 dB respectively, matching the
corresponding tablet displays after rounding. Its calculation times were 2.66, 6.04 and 23.65 s;
these repeat observations do not replace the full-build measurements in the table.

The 64-emitter project reopened on the tablet within 69.2 s, retaining 55.9 dB. Both native 8 kHz
save/reopen checks passed. After testing, the debug build was restored and all nine original project
files matched the pre-test backup byte-for-byte; the temporary benchmark asset was removed.

## Summation optimization — 2026-11-14

The direct/specular band sum now uses the same bounded-error integration kernel as incident
scattering, with the material phase derivative included in each arrival's effective delay.
For at least 24 active arrivals per correlated group and no more than one cycle across a slice,
eight positive Gauss samples replace pairwise cross terms. Wider spans retain the analytic sinc
formula. The independent dense-frequency test now covers 64 emitters, and randomized analytic
checks separately cover material phase slopes, group isolation, cancellation and fallback.

After this change, the same full-build computer observations were 1.64 s / 2.86 s / 6.47 s for
32 emitters at 4 kHz / 32 at 8 kHz / 64 at 8 kHz. Area means were unchanged at the printed precision.
The full validation suite passed 922 tests, plus both Android builds and shared iOS compilation.

The initial optimized tablet observation was 20.1 s at 55.9 dB, with the notification panel covering
the app during the initial polls. It is retained as a completion upper bound, not a clean benchmark.

After reconnection, the unobstructed cold reopen completed within **18.4 s** at **55.9 dB**.
The same optimized-build fixture previously completed within 74.5 s and 69.2 s. All are fresh-UI
completion upper bounds, sampled about every five seconds; they include listener work and do not
establish sustained thermal performance or performance for other scenes. The saved 64-emitter
project retained its result after reopening. Native acceptance is complete for this fixture.

The validated debug build and original project were restored afterward. All nine saved files
matched `/tmp/summation-original.tar` byte-for-byte, and the temporary benchmark asset was removed.
