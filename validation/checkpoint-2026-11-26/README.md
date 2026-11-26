# Validated source-control checkpoint — 2026-11-26

Full local run: 615 shared JVM tests and 471 Android tests passed; debug assembly passed.
Six independent reference packages and six comparison CSV packaging tests passed.
The first full run exposed one outdated test assuming broadband always uses per-band reference
power. It now explicitly selects legacy drive and checks the fixed-total difference; the complete
suite was rerun successfully. Runtime calculation behavior was unchanged in this checkpoint step.

Includes accumulated shared-core/Android work and retained validation evidence, including failed
physical-accuracy screens. This is a source-control checkpoint, not a professional release or
manufacturer-equivalence claim. Prior OnePlus acceptance and iOS compilation evidence are retained
in their respective validation packages.

STI remains unmerged at branch head 5b58c7e97819332518f227df4d894a107b9d1c90. Runtime STI/RT60
publication remains disabled. Existing reflection-reference files are included from the working tree.
Private device backups, build outputs, restricted corpus and Python caches are excluded. Curated
compiler logs remain verbatim; their tool-generated trailing spaces are exempt from whitespace lint.

Reproduce from android/ using JDK 21:

```sh
./gradlew :shared-core:jvmTest :app:testDebugUnitTest :app:assembleDebug \
  -Pkotlin.compiler.execution.strategy=in-process --offline
```

CI also runs the reference.py --check scripts in numerical/, engine-capture/, relative-response/,
broadband-capture/, relative-broadband/ and source-spectrum/, plus comparison-import/test_package_csv.py.
