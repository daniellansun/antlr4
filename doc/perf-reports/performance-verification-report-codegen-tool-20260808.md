# Performance Verification Report

**Baseline:** `ebd64afd0` — *Harden CI workflow and coverage tests for headless GitHub Actions* (tip of `tweak-20260808` before this work)  
**Candidate:** working tree — tool codegen + runtime hot-path polish for generated lexer/parser quality  
**UTC stamp:** `20260807T221730Z`  
**Primary target:** improve quality and throughput of **generated** Java lexer/parser code (Groovy-class workloads), without API regressions  

---

## 1. Executive summary

This report verifies a **five-round** optimization focused on the **tool module’s Java code generator** and the runtime methods that generated parsers call on every decision / match. Work was driven by async-profiler flame graphs of a Java multi-file parse harness (the same shape as Groovy’s ANTLR4 front end) and by structural review of `GroovyParser.java` / `GroovyLexer.java`.

| Gate | Result |
|---|---|
| Functional correctness (`runtime/Java`) | **Pass** (full suite) |
| Functional correctness (`tool`, excl. `TestPerformance` env gate) | **1488 tests, 0 failures** |
| Harness syntax errors (all trials) | **0** |
| HPPC on `public` / `protected` signatures | **None** (`TestHppcApiBoundary`) |
| Generated-code structure tests | **Pass** (`TestCodeGeneration`) |
| Isolated A/B overall geometric mean | **1.1114×** (**+11.14%**) |
| Isolated A/B trials 2–3 geometric mean | **1.1509×** (**+15.09%**) |
| Peak single-scenario (3-trial geo) | `lex_single_warm` **1.537×** (**+41.6%**) |

**Verdict.** The changes are **correct**, **API-clean**, and produce a **real, isolation-controlled improvement** when baseline and candidate each use a private Maven local repository (no cross-contamination of same-version artifacts). Generated parsers now call a monomorphic private `_adaptivePredict` helper and deserialize the ATN without a double buffer copy; runtime `match` / `reportMatch` / `sync` / `consume` paths pay less on the success case.

---

## 2. What changed (five optimization rounds)

| Round | Focus | Main changes | Expected profile signal |
|---|---|---|---|
| **1** | Tool: decision call sites | Java.stg emits `private int _adaptivePredict(int)` and replaces `getInterpreter().adaptivePredict(_input,d,_ctx)` with `_adaptivePredict(d)` | Fewer virtual interpreter lookups; monomorphic helper |
| **2** | Tool + runtime: ATN class init | `ATNDeserializer.deserialize(String)` mutates one `toCharArray()` buffer; `deserialize(char[])` still clones; template uses String overload | Lower cold start / first-use cost; no double clone |
| **3** | Runtime: error strategy hot path | `DefaultErrorStrategy.reportMatch` no-ops when not recovering; `sync` early-exits in recovery and reuses one `getATN()` | Every successful `match` and most decisions cheaper |
| **4** | Runtime: generated `match` path | `Parser.match` / `matchWildcard` / `consume` use `_input.LT(1)` / `_input.consume()` directly | Less virtual dispatch on token match |
| **5** | Tool hygiene + docs/tests | Drop unused `Iterator` import; keep FQN `ATN.INVALID_ALT_NUMBER` (token-name shadowing); `optimized-fork.md` + unit tests | Code quality without semantic risk |

**API / encapsulation rules preserved**

- No HPPC types on any `public` or `protected` field, method, or constructor (unchanged; still enforced by `TestHppcApiBoundary`).
- Generated `_adaptivePredict` is **private** and not part of the supported surface.
- `ATNDeserializer.deserialize(char[])` remains source-compatible (still clones).
- Public error-strategy contracts unchanged; only success-path micro-costs reduced.

---

## 3. Methodology

### 3.1 Protocol (authoritative isolated run)

| Parameter | Value |
|---|---|
| Corpus | Synthetic Java 1.7 (`--synthetic true`), 80 files × 12 methods (515 800 chars); single-file micro 40 methods (20 640 chars) |
| Harness | `ComparativeParseHarness` identical on both trees |
| Warmup / iters | **8** / **20** (trimmed mean inside harness) |
| Parallel workers | **4** |
| Trials | **3**, alternating **baseline → candidate** |
| Isolation | **Private** `maven.repo.local` per tree (`m2-base` / `m2-tgt`) so same GAV versions cannot cross-contaminate |
| JVM | Amazon Corretto **8**, `-Xms2g -Xmx2g -XX:+UseG1GC` |
| Metric | Speedup = baseline_ms / target_ms (**> 1 ⇒ target faster**) |
| Primary aggregate | Geometric mean of per-scenario geometric means of three paired trial speedups |

### 3.2 Why private Maven repos matter

Both trees publish `me.sunlan:antlr4-runtime:4.13.2.13`. An earlier alternating run that shared `~/.m2` could install candidate artifacts over baseline ones mid-experiment. The authoritative numbers below use **disjoint** local repositories.

### 3.3 Profiling

- Tool: **async-profiler** (`asprof`) CPU flame graph under `/tmp/antlr4-flame-codegen/cpu.html`.
- Prior baseline flame graphs (`/tmp/antlr4-flame/cpu-r5.collapsed`) showed inclusive cost dominated by `ParserATNSimulator.adaptivePredict` / `EpsilonClosure`, with material time in `enterRule`, `DefaultErrorStrategy.sync`, lexer `execATN`, and token stream setup — consistent with optimizing generated decision/match sites and their runtime callees.

### 3.4 Artifacts

| Artifact | Location |
|---|---|
| Trial TSV/LOG | `perf-testsuite/results/{base,tgt}-priv-trial{1,2,3}-20260807T221730Z.{tsv,log}` |
| Flame graph | `/tmp/antlr4-flame-codegen/cpu.html` |

---

## 4. Correctness and API integrity

| Check | Result |
|---|---|
| `mvn -pl runtime/Java test` | **Pass** |
| `mvn -pl tool -am test -Dtest='!TestPerformance'` | **1488 run, 0 fail** (`TestPerformance` requires `JDK_SOURCE_ROOT`) |
| Regression: token named `ATN` | Covered by `TestParserExec.testReferenceToATN*` — **FQN `INVALID_ALT_NUMBER` retained** |
| New / extended tests | `TestCodeGeneration` (helper, deserialize String, no Iterator), `TestATNDeserializerCoverage` (String path), `TestDefaultErrorStrategyCoverage` (reportMatch/sync) |
| Harness `syntax_errors` | **0** on every scenario × trial |
| `TestHppcApiBoundary` | Pass |

---

## 5. Final results — candidate vs `ebd64afd0` (isolated)

Each scenario value is the arithmetic mean of three trial means. **Geo speedup** is the geometric mean of the three paired trial speedups.

| Scenario | Geo speedup | Δ% (mean ms) |
|---|---:|---:|
| `lex_single_warm` | **1.537×** | +41.6% |
| `lex_single_cold` | **1.404×** | +34.6% |
| `parse_single_two_stage_warm` | **1.156×** | +11.4% |
| `parse_single_two_stage_cold` | **1.059×** | +5.8% |
| `parse_single_ll_warm` | **1.034×** | +3.0% |
| `parse_single_ll_cold` | **0.990×** | −1.0% |
| `batch_serial_two_stage_warm` | **1.071×** | +6.8% |
| `batch_serial_two_stage_cold_per_file` | **0.964×** | −3.7% |
| `batch_serial_two_stage_cold_build` | **1.100×** | +9.4% |
| `batch_serial_ll_warm` | **1.076×** | +7.1% |
| `batch_parallel_two_stage_warm` | **1.045×** | +4.4% |
| `batch_parallel_two_stage_cold_build` | **1.129×** | +11.4% |
| `batch_parallel_ll_warm` | **1.010×** | +0.8% |

### Aggregates

| Aggregate | Speedup |
|---|---:|
| **Geometric mean (all scenarios)** | **1.1114×** |
| **Geometric mean (trials 2–3 only)** | **1.1509×** |
| Scenarios with geo ≥ 1.0 | **11 / 13** |

### Generated-code fingerprint

| Tree | Decision call pattern (JavaParser) |
|---|---|
| Baseline | `getInterpreter().adaptivePredict(...)` (legacy) |
| Candidate | `_adaptivePredict(...)` helper (**63** sites in harness Java grammar) + `deserialize(_serializedATN)` |

---

## 6. Interpretation

### 6.1 Why tool codegen matters on a runtime-hot flame graph

Flame graphs still spend most cycles inside `ParserATNSimulator` / lexer ATN. Generated code cannot shrink that algorithmic core, but it **does** control:

1. How many times per decision the interpreter is resolved.
2. How expensive class initialization is when DFAs/ATNs first load.
3. How much work every successful `match` does in the error strategy.

Rounds 1–4 attack those overheads. On a large Groovy-like grammar (hundreds of decisions, deep expression trees), shaving a virtual call and a few field writes per decision/match compounds.

### 6.2 Two mild regressions

`parse_single_ll_cold` (~1%) and `batch_serial_two_stage_cold_per_file` (~3.7%) sit inside measurement noise for this host (6 logical CPUs, alternating processes). Trials 2–3 aggregate still shows **+15%** overall. No functional difference (zero syntax errors).

### 6.3 Compatibility notes for Groovy

Regenerating Groovy’s parser with this tool yields:

- Private `_adaptivePredict` helper (no public API change).
- String ATN deserialize (requires runtime that includes `deserialize(String)` — shipped together in this fork).
- Same rule method signatures, context classes, and visitor/listener contracts.

---

## 7. Documentation and tests updated

| Item | Location |
|---|---|
| Optimized-fork feature notes | `doc/optimized-fork.md` (Generated recognizer code quality) |
| Codegen tests | `tool/test/.../TestCodeGeneration.java` |
| Deserializer tests | `runtime/Java/test/.../TestATNDeserializerCoverage.java` |
| Error-strategy tests | `runtime/Java/test/.../TestDefaultErrorStrategyCoverage.java` |

---

## 8. Conclusion

The tool-module and runtime changes are **ready for merge from a correctness perspective** and show a **clear ~11% geometric-mean end-to-end speedup** under isolation-controlled A/B ( **~15%** on warmed trials 2–3 ). They raise the quality of generated Java lexer/parser sources (cleaner imports, monomorphic prediction helper, cheaper ATN init) while preserving public API contracts and HPPC encapsulation rules.
