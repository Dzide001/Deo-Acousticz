# CLF fixture regression package

**Current public fixtures are project-authored synthetic inputs.** They test
asymmetric orientation, alternate arc encodings and save/reopen. They do not establish external CLF conformance, manufacturer accuracy or
electrical calibration. Reference/report scope records that distinction explicitly.

The earlier official-kit samples, subsets and per-sample reports were removed from
the current checkout because redistribution permission was not established. Source
hashes remain in `../fixture-permissions/external-sources.json`; a local private
archive retains the earlier evidence. Published Git history/forks are not erased.

See [the permission-safe workflow](../fixture-permissions/README.md) for generated
public binary inputs, active CI checks, explicit private external replay and the
remaining historical-distribution issue. Synthetic assertions are always executed;
selecting external mode with unavailable inputs fails instead of silently skipping.
