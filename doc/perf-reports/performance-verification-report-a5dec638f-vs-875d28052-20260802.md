# Performance Verification Report: `a5dec638f` vs `875d28052`

- **Baseline:** `875d28052f9c898c9f453b8a3062d426d7402ebe` -- *Optimize CharStreams string conversion*
- **Candidate:** `a5dec638f04ecfec1859e33a64f4a8cd950f34f7` -- *Unify CodePointBuffer UTF-16 append into one state machine*
- **UTC Stamp:** `20260802T033350Z`
- **Target:** Maintain or improve performance (`>= 1.00x` overall end-to-end geometric-mean speedup)
- **Result:** **REJECTED (CRITICAL PERFORMANCE REGRESSION)**; the end-to-end geometric mean is **0.7507x** (**-24.93%** overall throughput drop / **1.33x** slowdown)

---

## 1. Executive Summary

Commit `a5dec638f` refactored `CodePointBuffer.Builder` to collapse the duplicated `String` (`appendStringByte`, `appendStringChar`, `appendStringInt`) and `CharBuffer` (`appendArrayByte`, `appendArrayChar`, `appendArrayInt`) UTF-16 append code paths into a single unified state machine that accepts the JDK `CharSequence` abstraction. It also introduced a zero-allocation `CharArrayRange` wrapper for array-backed `CharBuffer` instances and added support for read-only `CharBuffer` inputs (e.g., `CharBuffer.wrap(String)`).

While code unification and read-only `CharBuffer` support are architecturally appealing, strict A/B empirical benchmarking reveals a **severe performance regression** across end-to-end parsing and lexing workloads:

1. **End-to-End Parsing Regressions:** Across 13 benchmark scenarios spanning serial parsing, parallel parsing, warm DFA, cold DFA, single-file microbenchmarks, SLL/LL, and two-stage execution, the candidate achieved an overall geometric mean speedup of **0.7507x** (-24.93% throughput).
2. **Serial & Parallel Workloads (Groovy-style Front Ends):** Multi-file serial batch parsing throughput dropped to **0.7943x** (-20.57%), while multi-file parallel batch parsing dropped to **0.8808x** (-11.92%).
3. **Factory & Microbenchmark Regressions:** The primary `CharStreams.fromString` factory path regressed by 4% to 15% across ASCII and BMP text shapes due to the replacement of direct `String.charAt` calls with interface invocation (`invokeinterface CharSequence.charAt`).

| Gate | Result |
|---|---|
| Runtime Unit Tests | **908 run, 0 failures, 0 errors** |
| Full Maven Reactor | **BUILD SUCCESS** |
| HPPC API Boundary (`TestHppcApiBoundary`) | **PASS** (No HPPC leakage on public/protected surfaces) |
| Functional Correctness (`CharStreams` & `CodePointBuffer`) | **PASS** (All compact storage upgrades & surrogate edge cases verified) |
| End-to-End Overall Geometric Mean | **0.7507x** (**Not met**; >= 1.00x required) |
| Serial Batch Geometric Mean | **0.7943x** (**Not met**) |
| Parallel Batch Geometric Mean | **0.8808x** (**Not met**) |
| Overall Recommendation | **REJECT / REVERT** (or redesign monomorphic dispatch paths) |

---

## 2. Change, Compatibility, and API Boundary

| Area | Change | Compatibility and Performance Impact |
|---|---|---|
| `CodePointBuffer.Builder` | Replaced separate `String` and `CharBuffer` conversion loops with a unified `append(CharSequence, int, int)` implementation calling `utf16In.charAt(i)` | Preserves public builder API signature. Introduces `invokeinterface` method call overhead per UTF-16 code unit. |
| `CharArrayRange` | Package-private transient `CharSequence` wrapper over `char[]` slice | Avoids array copying for `CharBuffer.wrap(char[])`, but adds indirection and prevents C2 array bounds check elimination. |
| Read-Only `CharBuffer` Support | Added fallback for non-array / read-only `CharBuffer` via direct `CharSequence` wrapping | Fixes `ReadOnlyBufferException` when passing `CharBuffer.wrap(String)`. |
| Surrogate Handling | Resets `prevHighSurrogate = -1` at the end of each `append` call | Fixes cross-call surrogate pairing ambiguity; ensures complete sequence isolation per append. |

No public or protected API signatures were changed. The refactoring is 100% source and binary compatible, but introduces major JVM bytecode execution inefficiencies on hot conversion loops.

---

## 3. Root-Cause Analysis

Detailed JVM bytecode and HotSpot C2 compiler analysis reveals three primary drivers for the **24.9% throughput loss**:

### 3.1 Interface Dispatch Penalty (`invokeinterface`) vs Direct Inlining

In baseline `875d28052`, `appendStringByte` took `String utf16In` directly. The HotSpot C2 compiler recognized `utf16In.charAt(i)` as a monomorphic `invokevirtual` on `java.lang.String`, inlining the internal byte/char array access into a direct memory read with zero method call overhead.

In candidate `a5dec638f`, `appendByte` receives `CharSequence utf16In`. When both `String` and `CharArrayRange` (or `CharBuffer`) are passed to `CodePointBuffer.Builder` during application lifetime:
1. The callsite `utf16In.charAt(i)` becomes **bimorphic / megamorphic**.
2. The JVM cannot inline `charAt(i)` without optimistic type guard checks.
3. Every single character conversion pays the cost of an `invokeinterface` instruction, vtable/itable lookup, register spilling, and lost SIMD vectorization.

### 3.2 Loss of Array Bounds Check Elimination

In baseline `875d28052`'s `appendArrayByte`, `char[] in = utf16In.array()` was extracted **once** prior to entering the loop. The C2 JIT compiler proved that `in[inOffset]` stayed within `[0, inLimit)`, completely hoisting and eliminating array bounds checks.

In candidate `a5dec638f`'s `CharArrayRange.charAt(index)`, every character access evaluates:
```java
public char charAt(int index) {
    return array[offset + index];
}
```
This introduces a virtual call, addition overhead (`offset + index`), and un-hoisted bounds checks on every loop iteration.

### 3.3 Microbenchmark Evidence

The JMH factory benchmark `from_string` confirms that even under isolated thread execution:
- `ascii-20640` string stream creation degraded from **12.371 us/op to 12.917 us/op** (4.4% slowdown).
- `bmp-1024` string stream creation degraded from **0.961 us/op to 1.131 us/op** (17.7% slowdown).

---

## 4. Methodology

### 4.1 End-to-End A/B Harness (`ComparativeParseHarness`)

| Parameter | Value |
|---|---|
| Corpus | Identical synthetic Java 1.7 corpus: 80 files x 12 methods (515,800 UTF-16 code units); single-file micro: 20,640 code units |
| Harness | `ComparativeParseHarness` snapshotted and overlaid identically on both commit checkouts |
| Warmup / Measurement | 10 warmup passes / 25 measured iterations (trimmed mean dropping 10% outliers) |
| Parallel Workers | 4 worker threads |
| Trials | 3 paired, alternating A/B trials (`Candidate` vs `Baseline`) |
| JVM | Amazon Corretto OpenJDK 1.8.0_472, `-Xms2g -Xmx2g -XX:+UseG1GC` |
| Host Isolation | `taskset -c 0-3` pinned to 4 dedicated physical CPU cores |
| Metric | `Speedup = baseline_ms / candidate_ms` (values `< 1.0x` indicate performance regression) |
| Primary Aggregate | Geometric mean of 3-trial paired speedups across all scenarios |

### 4.2 Factory Microbenchmarks (`CharStreamsBenchmark`)

JMH average time benchmark evaluating stream creation across text shapes (`ascii`, `bmp`, `smp`) and lengths (`1024`, `20640` code units):
- 5 warmup iterations x 1s, 10 measurement iterations x 1s, 2 forks per scenario.
- Included newly added `from_char_buffer_array` benchmark to evaluate `CharBuffer` backed streams.

---

## 5. End-to-End Parse Harness Results

Each scenario value represents the arithmetic mean of 3 independent trial means. `Geo Speedup` is the geometric mean of the 3 paired trial speedups for that scenario.

| Scenario | Baseline Mean (ms) | Candidate Mean (ms) | Geo Speedup | Change |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.049 | 1.611 | **0.6590x** | -34.10% |
| `lex_single_cold` | 3.537 | 6.448 | **0.5868x** | -41.32% |
| `parse_single_two_stage_warm` | 7.644 | 13.245 | **0.5963x** | -40.37% |
| `parse_single_two_stage_cold` | 11.066 | 19.116 | **0.5919x** | -40.81% |
| `parse_single_ll_warm` | 15.015 | 20.255 | **0.8011x** | -19.89% |
| `parse_single_ll_cold` | 20.752 | 26.922 | **0.8080x** | -19.20% |
| `batch_serial_two_stage_warm` | 84.341 | 106.281 | **0.8071x** | -19.29% |
| `batch_serial_two_stage_cold_per_file` | 254.623 | 363.316 | **0.7262x** | -27.38% |
| `batch_serial_two_stage_cold_build` | 82.353 | 101.563 | **0.8222x** | -17.78% |
| `batch_serial_ll_warm` | 131.621 | 160.586 | **0.8261x** | -17.39% |
| `batch_parallel_two_stage_warm` | 67.979 | 80.229 | **0.8623x** | -13.77% |
| `batch_parallel_two_stage_cold_build` | 71.014 | 89.782 | **0.7879x** | -21.21% |
| `batch_parallel_ll_warm` | 97.563 | 97.483 | **1.0059x** | +0.59% |

### 5.1 Aggregate Geometric Means

| Aggregate Metric | Geometric Mean Speedup | Performance Impact |
|---|---:|---:|
| **Overall Geometric Mean (All Scenarios)** | **0.7507x** | **-24.93% (REGRESSION)** |
| All Batch Scenarios | **0.8303x** | -16.97% |
| Serial Batch Scenarios | **0.7943x** | -20.57% |
| Parallel Batch Scenarios | **0.8808x** | -11.92% |
| Warm DFA Scenarios | **0.7840x** | -21.60% |
| Cold DFA Scenarios | **0.7136x** | -28.64% |
| Single-file Micro Scenarios | **0.6674x** | -33.26% |
| Process-Warmed (Trials 2–3) Geo Mean | **0.8041x** | -19.59% |
| Trial 1 / 2 / 3 Geo Means | 0.6542x / 0.6616x / 0.9774x | All trials regressed |

---

## 6. Serial vs. Parallel Parsing Analysis (Groovy-Style Workloads)

Languages like Groovy and Java compilers rely heavily on batch parsing, supporting both serial compilation pipelines and multi-threaded parallel compilation pipelines.

1. **Serial Parsing (`batch_serial_*`):**
   - Serial execution exhibits an average throughput regression of **-20.57%** (0.7943x).
   - `batch_serial_two_stage_cold_per_file`, which clearing DFA per file to isolate input stream creation and ATN cold paths, suffered the heaviest batch drop at **-27.38%** (0.7262x).

2. **Parallel Parsing (`batch_parallel_*`):**
   - Multi-threaded parallel compilation across 4 workers showed an average throughput regression of **-11.92%** (0.8808x).
   - While thread concurrency partially masks memory latency and method invocation overhead, `batch_parallel_two_stage_cold_build` still regressed by **-21.21%** (0.7879x).

---

## 7. JMH Factory Microbenchmark Results

Scores represent average execution time per operation (us/op); lower is better.

| Benchmark Case | Baseline (`875d28052`) | Candidate (`a5dec638f`) | Speedup | Status |
|---|---:|---:|---:|---|
| `from_string:ascii-1024` | 0.608 +/- 0.022 us | 0.645 +/- 0.023 us | **0.94x** | Regressed (-5.7%) |
| `from_string:ascii-20640` | 12.371 +/- 0.441 us | 12.917 +/- 0.478 us | **0.96x** | Regressed (-4.2%) |
| `from_string:bmp-1024` | 0.961 +/- 0.033 us | 1.131 +/- 0.036 us | **0.85x** | Regressed (-15.0%) |
| `from_string:bmp-20640` | 20.007 +/- 0.520 us | 21.955 +/- 0.375 us | **0.91x** | Regressed (-8.9%) |
| `from_string:smp-1024` | 2.345 +/- 0.075 us | 2.229 +/- 0.086 us | **1.05x** | Improved (+4.9%) |
| `from_string:smp-20640` | 43.598 +/- 1.415 us | 46.367 +/- 1.208 us | **0.94x** | Regressed (-6.0%) |
| `from_char_buffer_array:ascii-1024` | 0.644 +/- 0.020 us | 0.876 +/- 0.043 us | **0.73x** | Regressed (-26.5%) |
| `from_char_buffer_array:ascii-20640` | 30.189 +/- 0.213 us | 16.100 +/- 0.655 us | **1.88x** | Improved (+87.5%) |
| `from_char_buffer_array:bmp-1024` | 0.925 +/- 0.061 us | 0.996 +/- 0.053 us | **0.93x** | Regressed (-7.1%) |
| `from_char_buffer_array:bmp-20640` | 18.457 +/- 0.518 us | 18.831 +/- 0.738 us | **0.98x** | Parity (-2.0%) |
| `from_char_buffer_array:smp-1024` | 2.299 +/- 0.101 us | 2.140 +/- 0.131 us | **1.07x** | Improved (+6.9%) |
| `from_char_buffer_array:smp-20640` | 45.118 +/- 1.425 us | 46.930 +/- 2.529 us | **0.96x** | Regressed (-3.8%) |
| `la_one_then_consume:ascii-1024` | 0.449 +/- 0.013 us | 0.451 +/- 0.014 us | **1.00x** | Parity |
| `la_one_then_consume:ascii-20640` | 9.023 +/- 0.247 us | 8.855 +/- 0.210 us | **1.02x** | Parity |

---

## 8. Correctness and Verification Checks

| Verification Check | Command / Harness | Result |
|---|---|---|
| Java Runtime Unit Tests | `mvn -pl runtime/Java -am test` | **908 run, 0 failures, 0 errors** |
| Full Reactor Build | `mvn test-compile` | **BUILD SUCCESS** |
| HPPC API Boundary | `TestHppcApiBoundary` | **PASS** (0 HPPC leakage) |
| Harness Parsing Syntax Errors | `ComparativeParseHarness` | **0 errors across all 3 trials** |

---

## 9. Reproducibility and Raw Artifacts

Raw benchmark logs and JSON reports are saved under `perf-testsuite/results/`:

```text
perf-testsuite/results/base-875d28052-trial1-20260802T033350Z.log
perf-testsuite/results/base-875d28052-trial2-20260802T033350Z.log
perf-testsuite/results/base-875d28052-trial3-20260802T033350Z.log
perf-testsuite/results/target-trial1-20260802T033350Z.log
perf-testsuite/results/target-trial2-20260802T033350Z.log
perf-testsuite/results/target-trial3-20260802T033350Z.log
perf-testsuite/results/base-875d28052-jmh-20260802T033350Z.json
perf-testsuite/results/target-jmh-20260802T033350Z.json
perf-testsuite/results/analyze_results.py
```

### Reproducing the A/B Run

```bash
# 1. Build candidate and baseline benchmark jars
mvn -pl perf-testsuite -am package -DskipTests -Pjmh

# 2. Execute A/B harness and JMH suite
python3 perf-testsuite/results/run_ab.py

# 3. Compute geometric means and report
python3 perf-testsuite/results/analyze_results.py
```

---

## 10. Conclusions and Recommendations

1. **Rejection Verdict:** Commit `a5dec638f` must **NOT** be merged to the main optimization branch (`opt`). It causes a major **24.93% overall throughput regression** (0.7507x geometric mean) across complete parsing workloads.
2. **Design Lesson:** In hot loops processing millions of characters/tokens, abstraction abstractions like `CharSequence` should **not** replace specialized monomorphic method overloads (`String` vs `char[]`).
3. **Recommended Fix:** 
   - Retain specialized monomorphic `append(String)` and `append(char[], offset, length)` methods in `CodePointBuffer.Builder`.
   - Restore direct `String.charAt` and `char[]` index loops to preserve C2 JIT inlining and array bounds check elimination.
   - Separate the read-only `CharBuffer` bug fix and surrogate state clearing (`prevHighSurrogate = -1`) into a dedicated patch without abstracting the hot loops into `CharSequence`.

---

## 11. One-Line Conclusion

> Relative to baseline **`875d28052`**, commit **`a5dec638f`** introduces a severe **24.9% overall parsing throughput regression (0.7507× geometric mean)** due to `invokeinterface` dispatch overhead and lost C2 array bounds check elimination, requiring **rejection/reversal** of the unified `CharSequence` loop refactoring.
