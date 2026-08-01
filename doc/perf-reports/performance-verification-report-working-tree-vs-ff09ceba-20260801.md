# Performance Verification Report

- **Baseline:** `ff09cebaefc9d411cc9d5411cdc32d1646f27817` -- *Trivial refactor: use `replace` instead*
- **Candidate:** working tree on top of `ff09ceba` with the direct `String` conversion path described below
- **UTC stamp:** `20260801T180612Z`
- **Target:** at least `1.50x` overall end-to-end geometric-mean speedup
**Result:** **target not met**; the end-to-end geometric mean is **1.054x** (+5.4%)

---

## 1. Executive summary

`CharStreams.fromString` previously copied every input `String` into a temporary
mutable `CharBuffer` before `CodePointBuffer` converted the same UTF-16 code
units into its compact byte, char, or int backing store. The candidate removes
that intermediate copy. It adds a package-private
`CodePointBuffer.Builder.append(String)` implementation which converts directly
from the immutable string.

The change is a clear allocation-and-copy win for the public string factory:
the independent JMH benchmark improves from **1.55x to 3.91x**, depending on
text shape and size. The effect on complete lexer/parser workloads is naturally
smaller because stream construction is only one part of parsing. Three
alternating A/B trials over serial, file-parallel, warm-DFA, cold-DFA,
two-stage, LL, and parse-tree-building workloads yield **1.054x** overall.

| Gate | Result |
|---|---|
| Runtime unit tests | **900 run, 0 failures, 0 errors** |
| Full Maven reactor | **BUILD SUCCESS** |
| `TestPerformance` workload | **201 runtime Java source files parsed**, 0 failures |
| HPPC public/protected API boundary | Pass (`TestHppcApiBoundary`) |
| Shaded artifact verification | Pass; 75 relocated HPPC entries and no raw HPPC entries |
| Independent code review | No significant issues found |
| End-to-end overall geometric mean | **1.054x** |
| Required overall geometric mean (`>= 1.50x`) | **Not met** |

The result is deliberately not presented as a 50% whole-runtime improvement.
That claim would be unsupported by the end-to-end data.

---

## 2. Change, compatibility, and API boundary

| Area | Change | Compatibility and performance effect |
|---|---|---|
| `CharStreams.fromString(String, String)` | Calls `CodePointBuffer.Builder.append(String)` instead of allocating, filling, flipping, and reading a temporary `CharBuffer` | Preserves the existing public API and source name behavior; removes a full UTF-16 copy |
| `CodePointBuffer.Builder` | Adds package-private direct-string conversion helpers for byte, char, and int storage | Preserves the pre-existing compact-storage upgrade rules and surrogate behavior |
| Tests | Compare direct-string output to the original array-backed `CharBuffer` path | Covers ASCII, BMP, supplementary characters, byte-to-char/int upgrades, char-to-int upgrades, repeated appends, and malformed high-surrogate sequences |
| JMH | Adds `CharStreamsBenchmark` | Makes the factory-cost improvement independently measurable and keeps a lookahead regression probe |

No public or protected method, constructor, field, or type changed. The new
builder method is package-private. This change neither imports nor exposes
HPPC, and `TestHppcApiBoundary` confirms that HPPC remains absent from all
published signatures.

### 2.1 Semantic equivalence

The direct conversion keeps the original behavior:

1. Latin-1 code units remain in `byte[]`.
2. A non-Latin-1 BMP code unit upgrades storage to `char[]`.
3. A high surrogate upgrades storage to `int[]`; a valid pair becomes one code
   point.
4. Isolated and adjacent high surrogates retain the pre-existing code-unit
   behavior.

The helper updates the buffer position before every storage upgrade, exactly as
the original `append(CharBuffer)` path does. Capacity growth continues to use
the existing `ensureRemaining` and conversion methods.

---

## 3. Optimization selection rounds

Three candidate directions were evaluated. Only a change with a repeatable,
positive result and no compatibility trade-off was retained.

| Round | Candidate | Decision | Evidence |
|---|---|---|---|
| 1 | Direct `String` to compact code-point conversion | **Retained** | JMH `from_string`: 1.55x to 3.91x; no public API change |
| 2 | Lazy token-buffer capacity preallocation | Rejected | No stable end-to-end gain; can over-allocate when callers consume only a prefix |
| 3 | Manual replacement for `CodePointCharStream.LA()` dispatch | Rejected | Dedicated lexer-style benchmark regressed substantially on JDK 8; the original `Integer.signum`/switch path optimizes better |

This selection process avoids retaining speculative micro-optimizations that
would make common workloads slower or less predictable.

---

## 4. Methodology

### 4.1 End-to-end A/B harness

| Parameter | Value |
|---|---|
| Corpus | Identical synthetic Java 1.7 corpus: 80 files x 12 methods, 515,800 UTF-16 code units; single-file input: 20,640 code units |
| Harness | `ComparativeParseHarness` |
| Warmup / measurement | 10 warmup iterations / 25 measured iterations; harness trimmed mean |
| Trials | 3 pairs, candidate then baseline in each pair |
| Scenarios | 13: lex, two-stage parsing, LL parsing, serial batch, file-parallel batch, warm DFA, cold DFA, and cold parse-tree-building paths |
| Parallel workers | 4 |
| JVM | Amazon Corretto 8.0.472, `-Xms2g -Xmx2g -XX:+UseG1GC` |
| CPU isolation | `taskset -c 0-3` |
| Metric | `speedup = baseline_ms / candidate_ms`; values above `1.0x` are faster |
| Primary aggregate | Geometric mean of every per-scenario, per-trial speedup |

The baseline was built in a separate checkout at `ff09ceba`; the same
benchmark source was overlaid there so that only the runtime implementation
differs. Both harnesses reported zero syntax errors.

### 4.2 Factory microbenchmark

`CharStreamsBenchmark.from_string` uses JMH average time with:

| Parameter | Value |
|---|---|
| Inputs | ASCII, BMP, and supplementary-plane text |
| UTF-16 lengths | 1,024 and 20,640 code units |
| Warmup / measurement | 5 x 1 second / 10 x 1 second |
| Forks | 2 |
| Unit | microseconds per operation |
| JVM | Corretto 8.0.472, `-Xms2g -Xmx2g -XX:+UseG1GC` |

---

## 5. End-to-end results

Each table value is the mean of the three harness trial means. `Geo speedup`
is the geometric mean of the three paired speedups for that scenario, rather
than the ratio of rounded table values.

| Scenario | Baseline mean (ms) | Candidate mean (ms) | Geo speedup | Change |
|---|---:|---:|---:|---:|
| `batch_parallel_ll_warm` | 68.805 | 63.769 | 1.079x | +7.9% |
| `batch_parallel_two_stage_cold_build` | 61.734 | 58.971 | 1.042x | +4.2% |
| `batch_parallel_two_stage_warm` | 57.546 | 54.929 | 1.044x | +4.4% |
| `batch_serial_ll_warm` | 133.387 | 134.304 | 0.993x | -0.7% |
| `batch_serial_two_stage_cold_build` | 78.816 | 80.563 | 0.977x | -2.3% |
| `batch_serial_two_stage_cold_per_file` | 266.688 | 265.717 | 1.004x | +0.4% |
| `batch_serial_two_stage_warm` | 81.774 | 90.030 | 0.918x | -8.2% |
| `lex_single_cold` | 4.001 | 3.117 | 1.270x | +27.0% |
| `lex_single_warm` | 1.067 | 1.088 | 0.987x | -1.3% |
| `parse_single_ll_cold` | 17.783 | 16.927 | 1.048x | +4.8% |
| `parse_single_ll_warm` | 13.504 | 11.563 | 1.163x | +16.3% |
| `parse_single_two_stage_cold` | 12.865 | 9.795 | 1.339x | +33.9% |
| `parse_single_two_stage_warm` | 6.223 | 6.708 | 0.920x | -8.0% |

| Aggregate | Speedup |
|---|---:|
| **All scenarios** | **1.0538x** |
| Batch scenarios | 1.0070x |
| Serial batch | 0.9724x |
| File-parallel batch | 1.0551x |
| Warm-DFA scenarios | 1.0118x |
| Cold-DFA scenarios | 1.1051x |
| Single-file scenarios | 1.1113x |
| Trial 1 / 2 / 3 all-scenario geo | 1.0641x / 1.0769x / 1.0214x |

The candidate improves the construction-sensitive cold single-file paths, but
does not create a uniform whole-parse gain. Serial warm batch time is dominated
by parser/token/DFA work beyond stream construction and also exhibits normal
shared-host variation. Therefore, the end-to-end conclusion is a modest
overall improvement, not a blanket throughput claim.

---

## 6. Direct factory microbenchmark results

Error values are JMH score errors from the two-fork run.

| Content / UTF-16 units | Baseline (us/op) | Candidate (us/op) | Speedup |
|---|---:|---:|---:|
| `ascii-1024` | 2.314 +/- 0.038 | 0.602 +/- 0.018 | 3.84x |
| `ascii-20640` | 45.758 +/- 0.865 | 11.713 +/- 0.426 | 3.91x |
| `bmp-1024` | 2.530 +/- 0.052 | 0.876 +/- 0.051 | 2.89x |
| `bmp-20640` | 51.791 +/- 1.939 | 18.682 +/- 0.638 | 2.77x |
| `smp-1024` | 3.553 +/- 0.192 | 2.290 +/- 0.264 | 1.55x |
| `smp-20640` | 81.138 +/- 4.929 | 42.872 +/- 2.350 | 1.89x |

The smaller supplementary-plane gain is expected: both implementations must
still perform UTF-16 surrogate decoding and produce `int[]` storage. The
temporary `CharBuffer` allocation and copy are eliminated in every shape.

---

## 7. Correctness, coverage, and artifact checks

| Validation | Result |
|---|---|
| `mvn -pl runtime/Java -am -DENABLE_JACOCO=true test` | 900 run, 0 failures, 0 errors |
| `TestHppcApiBoundary` | 3 run, 0 failures, 0 errors |
| `mvn -pl runtime/Java -am -DskipTests verify` | Success; shaded HPPC verification passed |
| `JDK_SOURCE_ROOT="$PWD" mvn test -Dperformance.package=runtime.Java.src.org.antlr.v4.runtime` | Full 8-module reactor success |
| Tool test suite in full reactor | 1,390 run, 0 failures, 0 errors, 13 existing skips |
| Maven-plugin test suite in full reactor | 4 run, 0 failures, 0 errors, 1 existing skip |
| Independent code review | No significant issues found |

The host does not ship JDK source files, so the existing configurable
`TestPerformance` test was not skipped. It was run against the repository's
`runtime/Java/src/org/antlr/v4/runtime` package through its documented
`JDK_SOURCE_ROOT` and `performance.package` inputs, parsing 201 Java files.

JaCoCo confirms that the new direct path is exercised thoroughly:

| Method | Instruction coverage | Branch coverage |
|---|---:|---:|
| `append(String)` | 25 / 25 | 3 / 4 |
| `appendStringByte` | 85 / 89 | 7 / 8 |
| `appendStringChar` | 69 / 73 | 5 / 6 |
| `appendStringInt` | 110 / 110 | 12 / 12 |

The residual missed byte/char instructions and branches are the disabled Java
`assert prevHighSurrogate == -1` checks. The remaining `append(String)` branch
is the compiler-generated unmatched-enum default; all valid storage variants
are covered. The tests cover every semantic conversion and surrogate branch,
including the dangling final surrogate branch.

---

## 8. Reproducibility and raw artifacts

Raw logs are stored using the repository's ignored performance-results
convention:

```text
perf-testsuite/results/base-ff09ceba-charstreams-trial{1,2,3}-20260801T180612Z.log
perf-testsuite/results/target-charstreams-trial{1,2,3}-20260801T180612Z.log
perf-testsuite/results/ab-charstreams-speedups-20260801T180612Z.tsv
perf-testsuite/results/base-ff09ceba-charstreams-jmh-20260801T180612Z.json
perf-testsuite/results/target-charstreams-jmh-20260801T180612Z.json
```

The A/B harness command was:

```bash
taskset -c 0-3 java -Xms2g -Xmx2g -XX:+UseG1GC \
  -cp perf-testsuite/target/benchmarks.jar \
  org.antlr.v4.test.runtime.java.api.perf.jmh.ComparativeParseHarness \
  --label <baseline-or-candidate> --files 80 --warmup 10 --iters 25 \
  --threads 4 --synthetic true
```

The factory JMH command was:

```bash
taskset -c 0-3 java -jar perf-testsuite/target/benchmarks.jar \
  CharStreamsBenchmark.from_string \
  -wi 5 -i 10 -f 2 -bm avgt -tu us -jvmArgsAppend -XX:+UseG1GC \
  -p utf16Length=1024,20640 -p content=ascii,bmp,smp \
  -rf json -rff results.json
```

---

## 9. Limits of the result

1. The machine is shared and has six logical CPUs. CPU affinity, fixed heap,
   alternating A/B trials, and geometric aggregation reduce noise, but cannot
   eliminate it.
2. `ComparativeParseHarness` creates and tears down an executor for each
   file-parallel batch. Its parallel numbers therefore include harness
   scheduling/allocation overhead and should be interpreted as an integration
   metric rather than a pure parser-core metric.
3. The synthetic corpus controls input identity and covers a Groovy-relevant
   file-parallel usage pattern, but it is not a substitute for a product-level
   Groovy compile benchmark.
4. The retained change optimizes string stream construction. Warm DFA parsing,
   token creation, prediction, parse-tree construction, and executor behavior
   remain the dominant costs in several end-to-end scenarios.

---

## 10. Conclusion

The candidate is functionally compatible, API-clean, and measurably faster
where it is designed to help: `CharStreams.fromString` is **1.55x to 3.91x**
faster across the tested text shapes. Complete parsing improves by a more
modest but positive **1.0538x** geometric mean. The requested `1.50x`
whole-runtime target is **not achieved** on this already optimized baseline,
so it must not be advertised as achieved. The direct conversion optimization is
retained because it has a strong, repeatable factory-level benefit, preserves
all public APIs and surrogate semantics, and does not sacrifice overall
end-to-end throughput.
