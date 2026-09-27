# Java 25 bytecode-native CRAP experiment

Generated at 2026-09-27T10:39:07.798404705Z from `1b95b264fc2718b1e9c465d9f71cc7c8b7ae8c1f`.

## Environment and fixed evidence

- Runtime: Java 25.0.4.1 ( Eclipse Adoptium ).
- Maven: Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5).
- OS: Linux 6.17.0-1022-azure / amd64.
- Warmups per arm: 2; measured runs per arm: 7.
- Fixture source SHA-256: `4dbbe03a1c331fb8105a8c44f415d2593d92bae649fa44fb23a11b24d85ffeda`.
- Compiled class-file SHA-256: `f3f9593ae8518abed39612eb78df817ee17833491ffe62c292f9db3fb1831cc0`.
- JaCoCo execution-data SHA-256: `571b6982140900ac6788e31e4135a4c653c250425d2a0c19deeeccd999d8fb85`.

Compilation, fixture execution, and coverage generation ran once before either measured arm. Each timed source pass rereads and parses the fixture source with the unchanged `JavaMethodParser`. Each timed bytecode pass rereads the same class files and JaCoCo execution data, then extracts method metadata with `java.lang.classfile` and JaCoCo analysis.

## Method-set and complexity parity

| Measure | Count |
|---|---:|
| Source methods | 19 |
| Bytecode methods | 25 |
| Unique source-to-bytecode mappings | 19 |
| Source-only methods | 0 |
| Ambiguous source mappings | 0 |
| Bytecode-only methods | 6 |
| Ambiguous bytecode mappings | 0 |
| Exact CC matches among unique mapped methods | 14 |
| CC mismatches among unique mapped methods | 5 |
| Mapped methods without JaCoCo CC | 0 |

Bytecode-only methods by observable category: bridge method=1, constructor=4, synthetic/compiler-generated=1.

### Analysis-only timings

| Arm | Median | Range | Runs (ms) |
|---|---:|---:|---|
| Source oracle | 9.407 ms | 8.008–11.766 ms | 8.008, 8.089, 9.386, 9.407, 9.601, 9.738, 11.766 |
| Bytecode + JaCoCo | 5.589 ms | 4.506–6.099 ms | 4.506, 4.628, 4.851, 5.589, 5.750, 5.821, 6.099 |

Build, test, fixture compilation, and coverage-generation time are excluded.

### CC mismatches by construct

| Construct | Mismatches |
|---|---:|
| multi-catch | 1 |
| nested catches | 1 |
| plain try/catch | 1 |
| switch | 1 |
| try-with-resources | 1 |

### Individual mismatches

| Construct | Source identity | Bytecode identity | Source CC | JaCoCo CC | Exception table types |
|---|---|---|---:|---:|---|
| switch | `FixtureMatrix.java#switchCases@35-42` | `experiment.fixtures.FixtureMatrix#switchCases(I)I` | 5 | 4 | none |
| plain try/catch | `FixtureMatrix.java#plainTryCatch@44-53` | `experiment.fixtures.FixtureMatrix#plainTryCatch(Z)I` | 3 | 2 | java.lang.IllegalStateException |
| multi-catch | `FixtureMatrix.java#multiCatch@55-67` | `experiment.fixtures.FixtureMatrix#multiCatch(I)I` | 4 | 3 | java.lang.IllegalArgumentException, java.lang.IllegalStateException |
| nested catches | `FixtureMatrix.java#nestedCatch@69-82` | `experiment.fixtures.FixtureMatrix#nestedCatch(Z)I` | 4 | 2 | java.lang.IllegalArgumentException, java.lang.RuntimeException |
| try-with-resources | `FixtureMatrix.java#tryWithResources@97-103` | `experiment.fixtures.FixtureMatrix#tryWithResources()I` | 2 | 1 | java.io.IOException, java.io.IOException, java.lang.Throwable, java.lang.Throwable |

## Exception-handler observations

The JDK Class-File API reports exception-table entries, including catch types or `catch-all` entries. These entries are not treated as source catch clauses: javac also emits entries for finally and try-with-resources cleanup. No JaCoCo complexity adjustment is applied.

| Source construct | Bytecode methods found | Exception-table entries | Handler types | Synchronized flag |
|---|---|---:|---|---|
| multi-catch | 1 | 2 | java.lang.IllegalArgumentException, java.lang.IllegalStateException | no |
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

This fixture run measures parity and cost only for the listed Java constructs on this JDK and compiler output. It does not establish production replacement suitability. The exception-table evidence is not sufficient on its own to identify source catch clauses, so the candidate complexity remains unadjusted.

### Next experiment

Repeat the same fixed-evidence comparison on one larger real-world Java repository, if a local checkout is available, while retaining the same source oracle and mismatch-preservation rules.
