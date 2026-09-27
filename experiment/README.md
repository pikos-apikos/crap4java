# Java 25 bytecode parity experiment

This experiment compares the existing `JavaMethodParser` source oracle with a
bytecode arm built from JDK 25's `java.lang.classfile` API and JaCoCo analysis.
It does not alter the production CRAP path or its complexity definition.

## Run

Requirements: JDK 25, Maven 3.9.9 or newer, and network access to resolve the
repository's pinned Maven dependencies on the first run.

From the repository root:

```bash
bash experiment/run.sh
```

The script runs the existing Maven test suite, compiles the deterministic
fixtures once with debug line information, executes the fixture driver once
under the pinned JaCoCo agent, and then runs both analysis arms repeatedly on
that fixed source/class/coverage evidence. Compilation, fixture execution, and
coverage generation are excluded from the per-arm timings.

The default is two warmups and seven measured runs per arm. Override with
`CRAP_EXPERIMENT_WARMUPS` and `CRAP_EXPERIMENT_ITERATIONS` when needed; preserve
those values in any report that compares runs.

## Output

The run writes durable outputs to `experiment/results/`:

- `comparison.json` — environment, per-run timings, parity counts, mismatch
  groups, and exception-table evidence;
- `methods.csv` — source methods and class-file/JaCoCo methods with their
  descriptors, line evidence, complexity, flags, and match status;
- `report.md` — concise human-readable results, exact commands, and an
  evidence-limited next-experiment recommendation.

Exception-table entries are evidence only. Catch and finally/resource-cleanup
constructs can all produce entries, so the report does not infer source `catch`
clauses or adjust JaCoCo complexity from handler counts. Ambiguous source to
bytecode mappings remain explicit in the artifacts.
