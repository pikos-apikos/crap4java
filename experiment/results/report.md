# Java 25 bytecode-native CRAP experiment

Generated at 2026-09-27T12:00:53.581850846Z from `6f9e646e0c317d0c98544742cfe168b1e55fcdc4`.

## Environment and fixed evidence

- Runtime: Java 25.0.4.1 ( Eclipse Adoptium ).
- Maven: Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5).
- OS: Linux 6.17.0-1022-azure / amd64.
- Warmups per arm: 2; measured runs per arm: 7.
- Fixture source SHA-256: `d7c23c41fd0f3bd9c19bc4f1aa86b1b2317d5fd8129e5da0a4e25ae95d5f9ecd`.
- Compiled class-file SHA-256: `5487f7912032e5bb888213199ab3bab1c05c53d67152cfb9605b418e194d28eb`.
- JaCoCo execution-data SHA-256: `4ef54206c91ab6e9c5e8d8b03cf9f828f1fa755fc701be86b2f5d1459ecf7344`.

Compilation, fixture execution, and coverage generation ran once before either measured arm. Each timed source pass rereads and parses the fixture source with the unchanged `JavaMethodParser`. Each timed bytecode pass rereads the same class files and JaCoCo execution data, then extracts method metadata with `java.lang.classfile` and JaCoCo analysis.

## Method-set and complexity parity

| Measure | Count |
|---|---:|
| Source methods | 21 |
| Bytecode methods | 27 |
| Unique source-to-bytecode mappings | 21 |
| Source-only methods | 0 |
| Ambiguous source mappings | 0 |
| Bytecode-only methods | 6 |
| Ambiguous bytecode mappings | 0 |
| Exact CC matches among unique mapped methods | 15 |
| CC mismatches among unique mapped methods | 6 |
| Mapped methods without JaCoCo CC | 0 |
| Exact matches after source-alignment adjustment | 21 |
| Residual mismatches after source-alignment adjustment | 0 |
| Methods without sufficient class-file evidence for adjustment | 0 |

Bytecode-only methods by observable category: bridge method=1, constructor=4, synthetic/compiler-generated=1.

### Analysis-only timings

| Arm | Median | Range | Runs (ms) |
|---|---:|---:|---|
| Source oracle | 15.815 ms | 13.147–17.232 ms | 13.147, 14.016, 14.135, 15.815, 16.192, 16.877, 17.232 |
| Bytecode + JaCoCo | 9.028 ms | 7.853–9.307 ms | 7.853, 8.522, 8.816, 9.028, 9.139, 9.257, 9.307 |

Build, test, fixture compilation, and coverage-generation time are excluded.

### CC mismatches by construct

| Construct | Mismatches |
|---|---:|
| multi-catch | 1 |
| multiple catch clauses | 1 |
| nested catches | 1 |
| plain try/catch | 1 |
| switch | 1 |
| try-with-resources | 1 |

### Individual mismatches

| Construct | Source identity | Bytecode identity | Source CC | JaCoCo CC | Exception table types |
|---|---|---|---:|---:|---|
| switch | `FixtureMatrix.java#switchCases@35-42` | `experiment.fixtures.FixtureMatrix#switchCases(I)I` | 5 | 4 | none |
| plain try/catch | `FixtureMatrix.java#plainTryCatch@54-63` | `experiment.fixtures.FixtureMatrix#plainTryCatch(Z)I` | 3 | 2 | java.lang.IllegalStateException |
| multi-catch | `FixtureMatrix.java#multiCatch@65-77` | `experiment.fixtures.FixtureMatrix#multiCatch(I)I` | 4 | 3 | java.lang.IllegalArgumentException, java.lang.IllegalStateException |
| multiple catch clauses | `FixtureMatrix.java#multipleCatchClauses@79-93` | `experiment.fixtures.FixtureMatrix#multipleCatchClauses(I)I` | 5 | 3 | java.lang.IllegalArgumentException, java.lang.IndexOutOfBoundsException |
| nested catches | `FixtureMatrix.java#nestedCatch@95-108` | `experiment.fixtures.FixtureMatrix#nestedCatch(Z)I` | 4 | 2 | java.lang.IllegalArgumentException, java.lang.RuntimeException |
| try-with-resources | `FixtureMatrix.java#tryWithResources@123-129` | `experiment.fixtures.FixtureMatrix#tryWithResources()I` | 2 | 1 | java.io.IOException, java.io.IOException, java.lang.Throwable, java.lang.Throwable |

### Source-aligned bytecode complexity

The normalized value is JaCoCo CC plus explicit source `default` labels and source `catch` clauses whose line ranges match the corresponding bytecode switch default target or typed handler target. A multi-catch contributes one source clause even when its handler table has multiple type entries. Catch-all cleanup handlers are not added. This aligns the experiment's bytecode value to the existing source parser; it is a calibration result, not an independent bytecode-only metric.

| Method | Source CC | JaCoCo CC | `default` adjustment | `catch` adjustment | Aligned bytecode CC |
|---|---:|---:|---:|---:|---:|
| `switchCases` | 5 | 4 | 1 | 0 | 5 |
| `plainTryCatch` | 3 | 2 | 0 | 1 | 3 |
| `multiCatch` | 4 | 3 | 0 | 1 | 4 |
| `multipleCatchClauses` | 5 | 3 | 0 | 2 | 5 |
| `nestedCatch` | 4 | 2 | 0 | 2 | 4 |
| `tryWithResources` | 2 | 1 | 0 | 1 | 2 |

Every uniquely mapped method with sufficient class-file evidence matches the source parser after alignment.

## Exception-handler observations

The JDK Class-File API reports exception-table entries and groups typed entries by handler target, so multi-catch entries sharing a handler are not mistaken for separate source clauses. Catch-all entries remain separate because they may represent `finally`, synchronized cleanup, or try-with-resources scaffolding.

| Source construct | Bytecode methods found | Exception-table entries | Handler types | Synchronized flag |
|---|---|---:|---|---|
| multi-catch | 1 | 2 | java.lang.IllegalArgumentException, java.lang.IllegalStateException | no |
| multiple catch clauses | 1 | 2 | java.lang.IllegalArgumentException, java.lang.IndexOutOfBoundsException | no |
| nested catches | 1 | 2 | java.lang.IllegalArgumentException, java.lang.RuntimeException | no |
| plain try/catch | 1 | 1 | java.lang.IllegalStateException | no |
| synchronized block | 1 | 2 | catch-all | no |
| synchronized method | 1 | 0 | none | yes |
| try-with-resources | 1 | 4 | java.io.IOException, java.lang.Throwable | no |
| try/finally | 1 | 1 | catch-all | no |

## Reproduction commands

```bash
bash experiment/run.sh
# The script runs: mvn -B clean test
# Then: javac --release 25 -g -d target/experiment/classes <sorted fixture sources>
# Then runs FixtureMatrix once with the JaCoCo 0.8.14 runtime agent
# Finally runs crap4java.ExperimentRunner against those fixed files
```

## Evidence-limited conclusion

This fixture run measures parity and cost only for the listed Java constructs on this JDK. Source labels are needed to distinguish explicit `default`/`catch` constructs from implicit switch targets and compiler-generated handlers; class-file evidence confirms the corresponding branch or handler exists. The normalized result therefore does not establish a source-independent replacement for the production parser.

### Next experiment

Repeat the same fixed-evidence comparison on one larger real-world Java repository, if a local checkout is available, while retaining the same source oracle and mismatch-preservation rules.
