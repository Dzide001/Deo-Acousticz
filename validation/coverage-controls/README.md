# Band/Broadband controls and calculation status

15 focused tests passed; Android debug APK built and installed on the OnePlus OPD2415.
[Device acceptance](../device/coverage-controls-2026-11-25/README.md) passed for the compact layout.
The original project and imported assets were restored and verified afterward.

The viewport exposes Band and Broadband SPL map modes. Frequency buttons appear only in Band
mode and select coverage; Broadband SPL hides the frequency row. Its
aiming-ray/angular-support inspection frequency is available separately in Settings.
One Auto/Manual toggle sits beside Calculate/Recalculate in the top bar; Calculate remains visible
in both modes. Explanatory paragraphs and duplicate calculation controls/status were removed
from the map panel to keep the screen compact.
Calculation-mode changes trigger autosave without requiring a scene edit.
Mode-specific copy and calculation action/state decisions live in shared CoveragePresentation.
The selected mode/frequency continue to use existing project fields and engine snapshots.

Tests retain actual production behavior:

- CoverageModeControlsTest verifies single-band output keys, identical broadband band powers/level
  when inspection frequency changes, clearing old maps on manual edits, automatic updates, and
  mode/frequency restoration through the file store. Reopen starts a fresh calculation even with
  automatic updates disabled; its running state is not presented as a completed result.
  Switching from Auto to Manual stops automatic updates after edits; both settings survive file-store
  save/reopen.
- CoveragePresentationTest distinguishes inspection/coverage controls and verifies manual stale,
  initial, automatic pending, running and automatic-failure recovery states in both modes.
- Existing PredictionConcurrencyTest rejects late results, canceled work and obsolete failures.

Reproduce from `android/`, using the configured JDK 21:

```sh
./gradlew :shared-core:jvmTest --tests '*CoveragePresentationTest' \
  :app:testDebugUnitTest --tests '*CoverageModeControlsTest' \
  --tests '*PredictionConcurrencyTest' :app:assembleDebug \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

`results/` retains JUnit reports, build log and summary. These are workflow regressions, not
manufacturer comparisons or new physical-accuracy claims. Existing extrapolation, acoustic
validation and full-venue performance gates remain open.

## Manufacturer workflow check

Checked official documentation on 2026-11-25:

- [ArrayCalc quick guide 1.4, page 4](https://www.dbaudio.com/assets/products/downloads/software/arraycalc/dbaudio-quick-guide-arraycalc-software-and-licenses-1.4-en.pdf):
  audience maps can use individual bands or summed broadband levels with linear/A/C weighting.
- [MAPP 3D user guide, Evaluation / Pressure plots and Measurement View](https://docs.meyersound.com/products/en/user-guide---mapp-3d.html):
  selected-band pressure maps (up to one octave) are separate from virtual-microphone frequency
  response plots and broadband/headroom analysis.

Design inference: call the summed map Broadband SPL; a spectrum/frequency-response plot shows
frequency-specific values separately. Hiding the Band selector in Broadband does not turn the
map into a spectrum graph. The existing persisted value SPECTRUM is unchanged for compatibility;
no new graph/engine or maximum-output capability is introduced by this cleanup.
