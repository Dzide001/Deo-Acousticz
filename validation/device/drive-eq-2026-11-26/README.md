# OnePlus EQ and broadband drive acceptance

Final build installed on OnePlus OPD2415. All checks in events.json passed using a flat,
full-domain synthetic magnitude-response speaker; this is not a physical speaker calibration.

- Sparse legacy EQ: 125 Hz area mean remains 80.2 dB; 1 kHz changes from 80.1 to 86.1 dB.
- Old schema-30 EQ settings survive reopen with their exact stored entry.
- An old project without a drive field loads per-band reference and labels that assumption.
- Selecting Fixed total through the real Settings control changes broadband area mean from
  89.0 to 80.0 dB. Values are rounded to 0.1 dB; tolerance against 9.0308998699 dB is 0.11 dB.
- Fixed-total and legacy drive each survive save/reopen.
- A drive change supports real top-bar Undo and Redo with matching levels and saved policy.
- An explicitly fixed-total schema-31 project opens with the normalized result.

The compact Band/Broadband and Manual/Auto controls remain unchanged. The convention label adds
only total drive / per-band drive. Screenshots contain synthetic scenes, with system bars cropped
and image size reduced for storage.

Original app data were backed up outside the repository. After testing, extra test assets were
archived privately and removed; all nine original files matched byte-for-byte before launch. After
reopening, original scene fields (allowing version migration) and imported speaker assets matched
(restoration.json). The new build remains installed. Original data are excluded from these artifacts.

Named JSON/checkpoint fixtures and the session-specific device_check.py retain the workflow.
Back up fresh working data before provisioning test scenes; never reuse this session's backup
against a newer project. Paths and device IDs are specific to this workspace. See the shared
[numerical package](../../drive-eq/README.md) for semantics, migration and reference tolerances.
