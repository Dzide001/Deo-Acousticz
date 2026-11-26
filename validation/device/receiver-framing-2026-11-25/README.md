# Receiver viewport acceptance — OnePlus, 2026-11-25

Synthetic scene only: one generic point speaker, listener, two coincident raised Balcony
receivers with distinct stable IDs, and a Floor receiver. This checks viewport/recovery behavior,
not acoustic accuracy. Original user data is excluded from this directory.

- Plan, Section and 3D: Frame, one-pointer camera drag, Frame again. Screenshots retained.
- Two Wider actions: context increased from 8 to 32 m. Restart preserved receiver selection,
  PERSPECTIVE mode and span; Fit to venue cleared focus (`events.json`).
- Coincident centre tap selected reference:1 from reference:0 in Plan. Final Section check
  selected reference:0 from reference:1, retaining reference:1 as the pinned camera target.
- Final label-size/redraw correction checked immediately after restart and in Section
  (`final-labels.png`, `final-selection.png`). Earlier screenshots precede the font-size fix.
- Focused panel no longer overlaps conventions. Labels use scaled display text metrics.
- 596 shared JVM tests and five Android framing/marker recovery tests passed (`tests.json`).
  Final debug build/install passed. Shared tests include the new unassigned-source checkpoint
  regression; null sourceId is valid, null required numeric coordinates remain invalid.
- Original app data backed up outside the repository; all nine restored files matched byte for
  byte before launch, with no extra files. Reopened original scene fields matched except schema
  version upgrade (25 to 30), and imported speaker assets matched (`restoration.json`).

Not exercised: multi-pointer pinch, device rotation/resize, physical iPad rendering, or complete
on-device Undo/Redo. Automated recovery/history regressions are distinct from those device gates.
The initial post-launch restore comparison differed in two UI settings; repeating exact restoration
and reopening verified all original fields except version. No original project contents are retained
in these public artifacts.

`scene.json` and checksum-wrapped `recovery.current` are synthetic fixtures. `device_check.py`
uses ADB on the specified OnePlus and assumes the fixture has already been loaded after a verified
private backup. Its acceptance modes refuse scenes that do not match the fixture receiver layout.
It does not back up or restore app data. Do not run it against a working design. Screenshots are
cropped to omit system status/navigation bars and reduced for storage. Log paths are build evidence,
not portable toolchain configuration.
