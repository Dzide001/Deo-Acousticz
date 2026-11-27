# Permission-safe current-checkout validation — 27 November 2026

## Public CI

The three CLF fixture packages now contain **project-authored synthetic** TAB data,
reference oracles and newly generated reports. Their radial/directivity data is
created by the visible analytic formula in `generate.py`; binary inputs use explicit
layout constants. Nothing is copied from a kit sample's balloon or sensitivity rows.
Generated `Synthetic*.CF1/CF2` files are ignored and never committed or put in app
assets. Gradle's root `prepareClfFixtures` task creates them automatically for tests.

The tests still exercise asymmetric left/right rays, sign/symmetry/arc order,
per-band reference slots, missing data, failed-import atomicity and save/reopen.
They are **synthetic implementation regressions**, not independently sourced CLF
conformance, manufacturer prediction accuracy or electrical calibration. Scope is
explicit in provenance and every report. Neither missing fixtures nor an unavailable
external root silently passes a selected test.

```sh
python3 validation/fixture-permissions/generate.py --prepare
python3 validation/fixture-permissions/test_workflow.py
python3 validation/fixture-permissions/audit.py       # current working files
python3 validation/fixture-permissions/audit.py --index  # staged/checked-out files
cd android
./gradlew :shared-core:jvmTest :app:testDebugUnitTest -PacousticValidationTier=full
```

The generator verifies checked-in authored text/provenance and writes only generated
binaries in prepare mode. `--write` is an intentional fixture update, followed by
regenerating each package's `reference.py` output and reviewing the changes. It does
not download anything. Public CI prepares fixtures, audits the index, runs guard
negative controls and verifies the reference scripts before the Kotlin suites.
The audit rejects tracked binary speaker fixtures and known kit-file copies even
when renamed. It audits the current index/files, **not old Git history or forks**.

## Explicit private external replay

The original source-file hashes in `external-sources.json` identify the previously
used kit examples. They are provenance metadata, not permission to redistribute.
Acquire/extract the kit separately; the preparer neither downloads nor executes it.
The output must be a **new directory outside this repository**, and every source
hash must match. A marker is written only after all preparation/reference checks pass.

```sh
python3 validation/fixture-permissions/prepare_external.py \
  --extracted-dir /path/to/extracted-kit --output /path/outside/repository/private-clf
cd android
./gradlew :shared-core:jvmTest --tests '*ClfHandednessPackageTest' \
  --tests '*ClfMagnitudePackageTest' :app:testDebugUnitTest \
  --tests '*Cf1ReferenceValidationTest' --tests '*ClfImportFixtureTest' \
  -PexternalClfValidationRoot=/path/outside/repository/private-clf
```

Selected external mode requires a valid prepared root; absence fails configuration.
Only these fixture suites switch roots; unrelated validation stays public. Reports
are isolated under `build/reports/external-clf/`, outside public CI upload paths.
Copy detailed reports into private storage if needed; do not commit or upload them.
The current local replay used hash-verified sources already extracted under `/tmp`,
with full fixtures/reports preserved in `/private/tmp/acoustic-clf-replay-20261127`.
An earlier private archive is `/private/tmp/acoustic-external-clf-20261127`.
These temporary paths are not durable backups; retain needed private evidence on
durable storage before reboot or any history purge.

`results/external-summary.json` retains only source/report hashes, scopes, counts
and maximum residuals. Its successful external format replay is **not** a public
synthetic result or a real-world/manufacturer acceptance claim. Public and private
reports must never be combined under one misleading qualification label.

## Publication and history

The four old binaries, derived text subsets and per-sample reports were removed
from the current checkout after preserving them privately. The separately licensed
JBL source and attribution are unchanged. No permission grant for CLF samples was
found or asserted. A normal commit/push does not erase older public versions or
forks; history rewrite/visibility changes need a separate decision. No such action
is performed by this batch. The STI branch remains unmerged.

## Checkpoint verification

`python3 validation/fixture-permissions/verify.py` verifies the fresh public reports,
private replay summary, test inventories and unchanged validated performance runtime.
Older per-batch receipts pin their original source/Gradle inputs; those historical
checks may reject the new fixture-task configuration. Their data was not relabelled
as a new run. This package records the fresh validation for the publication batch.
Hosted CI results are checked separately on the pushed commit; local counts do not
assert remote success. No physical-device test was repeated for test-data/build-only
changes; the prior optimized-release/native recovery evidence remains retained.
