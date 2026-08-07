# Performance Verification Report

**Baseline:** `adbfb8a8d8b3eb9e543af6372848ab870b4970d9` — *release 4.13.2.13*  
**Candidate:** working tree on branch `tweak-20260808` (five-round runtime hot-path optimization)  
**UTC stamps:** intermediate `20260807T173039Z` · final `20260807T173855Z`  
**Primary target:** ≥ **1.50×** overall geometric-mean speedup (50% throughput gain) vs baseline  
**Achieved (final protocol):** **1.0545×** overall (**+5.45%**); process-warmed trials 2–3 **1.0709×** (**+7.09%**)

---

## 1. Executive summary

This report is a **strict A/B wall-time verification** of runtime-module optimizations applied on top of the already heavily optimized `4.13.2.13` baseline (`adbfb8a8d`). Work was driven by **async-profiler CPU flame graphs** on a Groovy-style multi-file Java parse harness (serial and parallel), then iterated for five rounds with unit-test and API-boundary gates.

| Gate | Result |
|---|---|
| Functional correctness (`runtime/Java`) | **914 tests, 0 failures, 0 errors** |
| Harness syntax errors (all trials) | **0** |
| HPPC on `public` / `protected` signatures | **None** (`TestHppcApiBoundary`) |
| Flame-graph smoking gun (baseline) | `DFA.isEmpty` → `HashEdgeMap.toMap` → `TreeMap` ≈ **17% inclusive** CPU on expression-heavy parse |
| Final overall geometric mean | **1.0545×** vs baseline |
| Final trials 2–3 geometric mean | **1.0709×** |
| Peak single-scenario (final, 3-trial geo) | `lex_single_cold` **1.451×** (**+45.1%**) |
| 50% overall target (1.50×) | **Not met** on this baseline |

**Verdict.** The changes are **correct**, **API-clean**, and produce a **real, profile-aligned improvement**, especially on **cold** lex/parse and some parallel LL paths. The **50% overall** throughput goal is **not achieved** against `adbfb8a8d`: that baseline already includes HPPC hot maps, retained ATN scratch, hash caches, and the comparative harness itself. The largest remaining end-to-end cost is structural (tokenization, DFA edge lookup, ATN closure), not a single accidental allocation of the TreeMap class that dominated the first flame graph.

Intermediate stamp `20260807T173039Z` (pre–LT(1) cache polish) measured **1.0818×** overall and **1.1241×** on trials 2–3; final stamp is slightly lower on batch warm paths and is treated as the **authoritative** result under the same protocol.

---

## 2. What changed (five optimization rounds)

| Round | Focus | Main changes | Profile signal |
|---|---|---|---|
| **1** | DFA emptiness | `DFA.isEmpty` / `isContextSensitive` use `DFAState.isEdgesEmpty()` instead of `getEdgeMap().isEmpty()`; `HashEdgeMap` O(1) size; `toMap` → `LinkedHashMap`; single `isEmpty` per `adaptivePredict` | Eliminated TreeMap / `toMap` from hot stacks |
| **2** | Edge map + ATN edges | `HashEdgeMap` initial capacity 8; freeze optimized transitions to `Transition[]` after deserialize | Fewer collision resizes; array walk in closure/reach |
| **3** | Token stream setup | Eager bulk `fill` when `CharStream.size()` is known; `IntervalSet.contains` linear path for ≤4 intervals | Lower on-demand `sync` overhead for sized inputs |
| **4** | LA/LT + merge | `CommonTokenStream`/`BufferedTokenStream` LA(1)/LT(1) shortcuts; `ATNConfigSet.canMerge` drops redundant stateNumber check | Faster match/enterRule probes |
| **5** | LT(1) cache | `BufferedTokenStream.cachedLT1` refreshed on consume | Cuts repeated `tokens.get(p)` from enterRule/match |

**API / encapsulation rules preserved**

- No HPPC types on any `public` or `protected` field, method, or constructor.
- JDK interfaces remain at published boundaries (`Set`, `ConcurrentMap`, edge maps via `Map` only on cold `getEdgeMap`).
- New helpers (`isEdgesEmpty`, `freezeOptimizedTransitions`) are additive and source-compatible.

---

## 3. Methodology

### 3.1 Protocol

| Parameter | Value |
|---|---|
| Corpus | Identical synthetic Java 1.7 (`--synthetic true`), 80 files × 12 methods (515 800 chars); single-file micro 40 methods (20 640 chars) |
| Harness | `ComparativeParseHarness` identical on both trees (overlay from candidate) |
| Warmup / iters | **10** / **25** (trimmed mean inside harness) |
| Parallel workers | **4** |
| Trials | **3**, alternating **target → baseline** |
| JVM | Amazon Corretto **8.0.472**, `-Xms2g -Xmx2g -XX:+UseG1GC` (Maven `exec:java` defaults) |
| Host | Linux x86_64, 6 logical CPUs |
| Metric | Speedup = baseline_ms / target_ms (**> 1 ⇒ target faster**) |
| Primary aggregate | Geometric mean of per-scenario geometric means of three paired trial speedups |

### 3.2 Isolation

- Baseline: `git worktree` at `adbfb8a8d` under `/tmp/antlr4-ab-r5-*/base`.
- Candidate: working tree; both built with `mvn -pl runtime/Java,perf-testsuite -am package -DskipTests`.
- Same harness sources overlaid onto baseline so only runtime bytecode differs.

### 3.3 Profiling

- Tool: **async-profiler** (`asprof`) CPU collapsed stacks + flame graph HTML under `/tmp/antlr4-flame/`.
- Workload: 80-file synthetic Java, two-stage / LL mix, including clear-DFA cold edges.
- Baseline top leaf: `TreeMap.fixAfterInsertion`, `HashEdgeMap.toMap`, `HashEdgeMap.get`, `LexerATNSimulator.execATN`.
- Post-fix: TreeMap / toMap **absent**; remaining cost in lexer exec, edge `get`, token fill, LL1 `IntIntHashMap.indexOf`, epsilon closure.

### 3.4 Artifacts

| Artifact | Location |
|---|---|
| Final trial logs | `perf-testsuite/results/{base,tgt}-final-trial{1,2,3}-20260807T173855Z.log` |
| Intermediate logs | `perf-testsuite/results/{base,tgt}-r5-trial{1,2,3}-20260807T173039Z.log` |
| Flame graphs | `/tmp/antlr4-flame/cpu.html`, `cpu-r5.collapsed` |

---

## 4. Correctness and API integrity

| Check | Result |
|---|---|
| `mvn -pl runtime/Java test` | **914 run, 0 fail, 0 error** |
| New / extended tests | `TestDFA` (precedence isEmpty), `TestEdgeMaps` (HashEdgeMap size/toMap), `TestBufferedTokenStream` (eager fill), `TestATNStateFreeze` |
| Harness `syntax_errors` | **0** on every scenario × trial |
| `TestHppcApiBoundary` | Pass — no HPPC on public/protected surfaces |
| Public behavior | `getEdgeMap()` still returns a live map for diagnostics; emptiness probes no longer depend on it |

---

## 5. Final results — candidate vs `adbfb8a8d`

Each scenario value is the arithmetic mean of three trial means. **Geo speedup** is the geometric mean of the three paired trial speedups.

| Scenario | Baseline mean (ms) | Candidate mean (ms) | Geo speedup | Δ% |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.133 | 1.085 | **1.044×** | +4.4% |
| `lex_single_cold` | 5.408 | 3.421 | **1.451×** | +45.1% |
| `parse_single_two_stage_warm` | 7.067 | 6.906 | **1.026×** | +2.6% |
| `parse_single_two_stage_cold` | 15.789 | 15.148 | **1.035×** | +3.5% |
| `parse_single_ll_warm` | 17.307 | 17.293 | **1.003×** | +0.3% |
| `parse_single_ll_cold` | 15.649 | 13.085 | **1.201×** | +20.1% |
| `batch_serial_two_stage_warm` | 85.152 | 85.239 | **0.999×** | −0.1% |
| `batch_serial_two_stage_cold_per_file` | 278.479 | 281.340 | **0.989×** | −1.1% |
| `batch_serial_two_stage_cold_build` | 85.852 | 87.777 | **0.978×** | −2.2% |
| `batch_serial_ll_warm` | 139.115 | 136.427 | **1.018×** | +1.8% |
| `batch_parallel_two_stage_warm` | 75.495 | 78.789 | **0.959×** | −4.1% |
| `batch_parallel_two_stage_cold_build` | 79.371 | 80.574 | **0.986×** | −1.4% |
| `batch_parallel_ll_warm` | 93.262 | 84.414 | **1.107×** | +10.7% |

### Aggregates (final)

| Aggregate | Speedup |
|---|---:|
| **Geometric mean (all scenarios)** | **1.0545×** |
| Geometric mean (batch\*) | 1.004× |
| Geometric mean (serial batch\*) | 0.996× |
| Geometric mean (parallel\*) | 1.015× |
| Geometric mean (cold\*) | 1.095× |
| Geometric mean (warm\*) | 1.021× |
| **Geometric mean (trials 2–3 only)** | **1.0709×** |

### Intermediate stamp (for transparency)

Stamp `20260807T173039Z` (after rounds 1–4, before LT(1) cache refinement): overall **1.0818×**, trials 2–3 **1.1241×**, with a larger outlier on `parse_single_two_stage_warm` (**1.585×**). Final stamp is preferred for stability of micro scenarios.

---

## 6. Interpretation

### 6.1 Why the TreeMap fix matters but is not a 50% win

On the **baseline** flame graph, every `adaptivePredict` into a **precedence DFA** (Java `expression` and related left-recursive decisions) called:

```text
DFA.isEmpty → DFAState.getEdgeMap → HashEdgeMap.toMap → TreeMap.put*
```

That path allocated a full boxed, ordered map solely to answer “is empty?”. After round 1 it is an O(1) occupancy check. That is the highest-leverage correctness+performance fix in this series.

However, end-to-end parse of 80 files also pays:

1. Full lex of each file (`LexerATNSimulator.execATN`, token allocation).
2. Shared DFA edge walks (`HashEdgeMap.get` / `ArrayEdgeMap.get`).
3. ATN reach/closure when edges are missing (cold) or for full-context.
4. Parser rule enter/exit and error-strategy `sync`.

Those costs remain after removing TreeMap; overall geo-mean gains therefore land in the **mid-single-digit to low-teens** percent range on a warm multi-file suite, with **much larger** gains on **cold** single-file and cold lex microbenchmarks.

### 6.2 Serial vs parallel (Groovy-style front ends)

- **Serial batch** warm/cold paths are **within noise** of baseline on the final stamp (≈0.99–1.02×).
- **Parallel LL warm** improves (**1.107×**): less per-prediction overhead and no TreeMap allocation under concurrent DFA readers.
- **Parallel two-stage warm** shows a small regression (**0.96×**) on the final stamp — treated as noise/contention variance, not a functional issue (zero syntax errors). Intermediate stamp had **1.16×** on the same scenario.

### 6.3 Against the 50% goal

A **1.50×** overall gain would require roughly **half** of end-to-end time to disappear. Flame graphs after the TreeMap fix no longer show a single 15–20% accidental hotspot of that class; further 50% class wins would need broader algorithmic changes (codegen, different DFA representation, optional token pooling, grammar-specific specialization) beyond the scoped runtime micro-optimizations validated here.

---

## 7. Compatibility, robustness, maintainability

| Concern | Assessment |
|---|---|
| Binary / source compatibility | Additive APIs only (`isEdgesEmpty`, freeze helper package-private usage). No public HPPC leakage. |
| Unbuffered streams | Eager fill only when `CharStream.size()` succeeds; `UnsupportedOperationException` keeps historic on-demand setup. |
| Concurrency | DFA edge maps remain concurrent-safe; LL1 cache remains COW; token-stream cache is per-stream (single parser thread). |
| Tests | 914 runtime unit tests green; targeted tests for DFA emptiness, HashEdgeMap occupancy, eager fill, transition freeze. |
| Docs | `doc/optimized-fork.md` updated with DFA emptiness, frozen transitions, eager fill, LA(1) cache. |

---

## 8. Recommendations

1. **Land the TreeMap/`isEmpty` fix and related O(1) edge occupancy work** — high confidence, profile-proven, low risk.
2. **Treat 50% overall vs this baseline as aspirational**, not a release gate, unless the comparison baseline is rolled back to a pre-HPPC / pre-retained-scratch release (e.g. 4.13.2.7 era).
3. **Next profiling targets** if further gains are required: monomorphic lexer DFA edges for ASCII, reduce `SimulatorState` allocation, optional token object pooling behind `TokenFactory`, and codegen-side match specialization.
4. Keep **serial + parallel** harness scenarios as release regression tests (Groovy-like front ends).

---

## 9. Conclusion

Relative to `adbfb8a8d` (4.13.2.13), the five-round optimization delivers:

- **Correctness:** 914/914 unit tests; 0 harness syntax errors.  
- **API integrity:** HPPC stays off public/protected surfaces.  
- **Performance:** **+5.5%** overall geometric mean (**+7.1%** process-warmed); **+45%** cold single-file lex; **+20%** cold single-file LL parse; **+11%** parallel LL batch.  
- **50% overall target:** **not met** on this already-optimized baseline; the primary accidental hotspot (`TreeMap` via `DFA.isEmpty`) is eliminated and documented.

The work is recommended for merge as a **maintain-and-improve** performance and robustness package with honest, reproducible numbers—not as a claim of a 50% global speedup vs 4.13.2.13.
