# OnePlus coverage-control acceptance

Final compact layout installed and tested on OnePlus OPD2415 (50eb54c9). All checks in
`compact-events.json` passed. This is UI/workflow acceptance using synthetic speaker data,
not physical acoustic validation.

- One Manual/Auto button beside Calculate/Recalculate in the header. Its text bounds and
  adjacency were checked against the actual native UI hierarchy.
- The same button toggles calculation mode. Calculate remains available in Auto.
- Manual frequency edits clear results and wait for explicit recalculation; Auto updates them.
- Broadband hides all eight frequency buttons. Returning to Band restores them.
- Explanatory paragraphs and duplicate map-panel calculation controls/status are absent.
- Auto/Broadband and Manual choices survive separate restarts without another scene edit.
- Final manual calculation completes with finite coverage statistics.
- The temporary calculation-start snackbar was removed from the top action. Settings reports
  a calculation request rather than claiming it has completed.

`compact-*.png` show the final UI; status/navigation bars are cropped and images reduced for
storage. Other screenshots and `events.json` are earlier observations before the final compact
layout. Initial band checks on the fixed-colour-scale synthetic scene gave 63.2 dB area mean at
125 Hz and 80.0 dB at 8 kHz. Those values demonstrate frequency-dependent calculation, not
calibration or manufacturer accuracy. The scene was also manipulated during the UI session;
final compact checks assert finite output and control behavior rather than those exact means.

The final `scene.json`/`recovery.current` fixture includes explicit third-octave samples from
25 Hz to 20 kHz. Earlier fixture attempts had only octave centres and insufficient endpoint/
interpolation coverage for full-width integration, yielding unavailable broadband values.
No missing data were filled by the engine; the synthetic fixture was corrected. Follow-up:
independently qualify nominal 31/32 Hz interpolation aliases and improve the distinction between
fresh but unavailable results and valid finite results in the status display.

Original data were backed up outside the repository. Test-only asset files were archived privately
and removed during restoration. All nine original files matched byte for byte before launch, with
no extra files; after reopening, original scene fields (allowing schema version migration) and
imported speaker assets matched (`restoration.json`). No original project contents are included here.

`test-build.log` records 15 focused passing tests and the final Android build. Detailed JUnit results
are retained under `validation/coverage-controls/results/`.

`device_check.py` retains the final `compact` workflow and supporting OnePlus-specific utilities.
Before using setup/install/compact, make a fresh verified backup and provision the synthetic fixture.
Never reuse an old backup against a newer working design. The script is a session acceptance aid,
not a general project migration tool; private backup paths and APK paths are specific to this workspace.
