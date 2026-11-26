# OnePlus data-validity acceptance — 2026-11-26

Installed the final debug build on OnePlus OPD2415. All checks in events.json passed:

- Available full-domain synthetic speaker data produce Results available.
- Restricted elevation support produces a partial mesh and Results partial with source/frequency.
- Missing required broadband cells produce Results unavailable; no misleading up-to-date claim.
- Each state survives save/reopen through a fresh calculation.
- Selecting the supported narrow band clears the old warning and produces available output.
- Moving the test-only speaker asset out of the cache and reopening produces an unavailable warning.

Screenshots contain synthetic scenes only, with status/navigation bars cropped. The partial scene
has real unavailable audience triangles around the source; the warning does not mean every 1 kHz
sample is missing. It identifies the response frequency at which some directions are unsupported.
Compact Band/Broadband and single Manual/Auto controls remain unchanged.

The original app data were backed up outside the repository before installation. Test assets and
checkpoints were archived privately, extra test files removed, and all nine original files verified
byte-for-byte before launch. Reopened original scene fields and imported speaker assets matched
(restoration.json). The tested build remains installed.

The three named .json/.current fixtures and OnePlus-specific device_check.py retain the session
workflow. Back up fresh working data before any test installation/provisioning; never reuse this
session's backup against a newer project. The helper refuses non-test scenes for interactive edits.
It is an acceptance aid with workspace-specific paths, not a general project migration tool.

This is workflow/data-availability verification, not physical acoustic validation. See
[the shared validation package](../../data-validity/README.md) for semantics and arithmetic checks.
