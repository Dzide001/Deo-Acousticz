# Third-party material and permissions

The repository's original code is proprietary; see [LICENSE](LICENSE). That notice
does not relicense third-party code, data, or documents.

## Public measurement reference

The JBL PRX635 measurement source retains its CC BY 4.0 attribution and source
provenance in [the original notice](validation/public-measurements/source/NOTICE.md).
Keep that notice with the retained source material and adaptations.

## CLF authoring-kit material — removed from the current checkout

The earlier official-kit binaries (`Active.CF1`, `Passive1.CF1`, `Powered.CF1`,
`Passive2.CF2`), their derived TAB subsets and detailed per-sample reports were
removed from the current tree. Redistribution permission was not established.
They are **not** relicensed by the repository's proprietary notice.

Public tests now use deterministic project-authored synthetic TAB fixtures and
locally generated binary inputs. Their scope is regression testing, not independent
external format conformance or calibration. No CLF kit download is needed for CI.
The JBL CC BY 4.0 source above is unchanged.

Source provenance hashes remain in
`validation/fixture-permissions/external-sources.json`. Explicit optional external
replay requires separately acquired, hash-matching files outside the repository;
raw external inputs and detailed reports are not published. See the
[fixture workflow](validation/fixture-permissions/README.md).

**Historical copies remain:** ordinary deletion does not remove these files from
older commits, clones or forks. No history rewrite, takedown or visibility change
has been performed. Before any historical purge, preserve needed evidence privately
and decide that operation separately. Do not redistribute the external samples
unless suitable permission is documented.

Official source: https://www.clfgroup.org/clfdocuments.htm

## Application packaging

Validation fixtures are repository test inputs, not Android app assets. This does
not certify the licensing of all transitive dependencies; retain their respective
license notices and complete a release dependency/license inventory before shipping.
