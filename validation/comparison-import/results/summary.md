# Comparison import results — revision 1

**60 JUnit tests passed** (31 shared-core, 29 Android), zero failures/skips,
plus **2 Python packaging tests**. The existing 18 numerical comparison fixtures still pass.
Android debug assembly and iOS simulator shared-test compilation pass.
No device installation/UI acceptance or numerical execution on iOS was performed.

| Check | Result |
|---|---|
| Synthetic manufacturer-shaped CSV import and workspace restore | Passed; NOT_COMPARABLE as required |
| Synthetic measurement-shaped CSV import and workspace restore | Passed; NOT_COMPARABLE as required |
| Quoting, BOM, CRLF, semicolon and explicit coordinates | Passed |
| JSON samples, null/missing levels and identity hashes | Passed |
| Malformed CSV, units, version, hash, row and size rejection | Passed |
| Matched/mismatched snapshots and unknown measurement calibration gates | Passed with test-only in-memory declarations |
| Atomic failed imports / corrupted-project rejection | Passed |
| Scene Undo/Redo, clear/restore and disk save/fresh reopen | Passed |
| Legacy projects, persistence and design-restoration regressions | Passed |
| Original CSV UTF-8/BOM/CRLF preservation and malformed UTF-8 refusal | Passed |

The retained `import-results.json` records sample counts, hashes, refusal reasons and synthetic
scope. The numerical package's refreshed snapshot records the current shared Kotlin source-tree
hash. Original synthetic example packages remain in `fixtures/`. Their import success is not a
manufacturer-software or measurement validation pass. No genuine new external artifact was
available; physical validation remains pending.
