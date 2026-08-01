# Performance Optimization & Verification Report

**Baseline:** `07111a8ee7c16217d94989a368863db377970a34` (release **4.13.2.7**)  
**Working tip:** current `opt` tree after three optimization rounds  
**Goal:** ≥ **1.50×** overall geometric-mean speedup (wall time) on the synthetic multi-scenario harness  
**Result:** **goal not met** — final overall geometric mean **≈ 1.08×** (+7.8%)  
**Correctness:** **882** runtime unit tests, **0** failures  

---

## 1. Executive summary

Three iterative design/implement/test/measure cycles were executed against a fixed, identical synthetic Java 1.7 corpus (serial and parallel, warm and cold DFA). All new performance machinery keeps **HPPC types off every `public`/`protected` signature**, exposing only JDK collection interfaces (or package-private facades).

| Round | Focus | Overall geo mean vs 07111a8 | Notes |
|-------|--------|------------------------------|--------|
| R1 | Primitive LL(1) cache, retained context/precedence scratch | ~1.03× | Cold LL single-file ~1.26× |
| R2 | In-place `DFA.clear()`, token-buffer pre-size | ~1.03× | Cold clear cost reduced; rebuild still dominates |
| R3 | Single-store LL(1) ConcurrentMap view (no dual-write) | **~1.08×** | Best overall; warm lex up to ~1.57× in aggregate |

**Verdict:** Relative to 4.13.2.7 the runtime is **correct and modestly faster** on several ATN-heavy and lex paths, with **healthy parallel scaling**. A blanket **50% end-to-end win on a warm-DFA-dominated multi-file Java parse is not achievable** with local interpreter micro-optimizations alone: the baseline is already the optimized fork, and steady-state prediction is largely DFA edge lookup + token allocation—paths already near a practical ceiling.

---

## 2. Design principles

1. **Correctness first** — every round re-ran the full `runtime/Java` suite (882 tests).  
2. **Interface-first API** — callers see `Set`, `ConcurrentMap`, etc.; HPPC never appears in `public`/`protected` methods or fields of external types.  
3. **Reuse, don’t reinvent** — HPPC open addressing for primitive maps/sets; copy-on-write for concurrent rare-write maps; retain-and-clear scratch buffers.  
4. **Thread model** — one simulator owns retained scratch for the duration of a prediction; shared ATN LL(1) cache is concurrent-safe; DFA edges remain concurrent-safe as before.  
5. **Measure what products care about** — serial vs parallel multi-file parse (Groovy-style), warm shared DFA vs cold ATN rebuild.

### 2.1 HPPC encapsulation map

| Storage | Visibility | Exposed surface |
|---------|------------|-----------------|
| `ClearableLongObjectHashMap` (composes HPPC; does not extend) | package-private | inside `ATNConfigSet` → `Set<ATNConfig>` |
| `OpenAddressedHashSet` (composes HPPC + empty-fast clear) | package-private | `Set<ATNConfig>` busy set |
| `ClearableIntObjectHashMap` (composes HPPC; does not extend) | package-private field on `ParserATNSimulator` | not in any method signature |
| `ConcurrentIntIntMap` (composes HPPC COW map) | package-private | `ATN.ll1Cache` |
| `ConcurrentIntIntMapView` | package-private | `protected ConcurrentMap LL1Table` (JDK facade) |
| `IntIntHashMap` / `LongObjectHashMap` / … | shaded under `org.antlr.v4.runtime.shaded…` | never in ANTLR public/protected signatures |

`ParserATNSimulator` no longer imports `com.carrotsearch.hppc.*`. Wrappers use **composition** so HPPC is not part of the production type hierarchy. Signature boundary enforced by `TestHppcApiBoundary`.

---

## 3. Optimizations implemented

### Round 1 — prediction hot path

| Change | Rationale |
|--------|-----------|
| `ConcurrentIntIntMap` for LL(1) | Replace hot `ConcurrentHashMap<Integer,Integer>` get with primitive COW map (lock-free reads) |
| Retained `PredictionContextCache` | Avoid allocating three HashMaps per ATN simulation |
| Retained `ClearableIntObjectHashMap` in `applyPrecedenceFilter` | Cut alloc + empty-clear cost on left-recursive / precedence DFAs |
| `PredictionContextCache.clear()` | Enable retain-and-reuse without pinning contexts across predictions |

### Round 2 — DFA flush & token buffer

| Change | Rationale |
|--------|-----------|
| `DFA.clear()` + in-place `ATN.clearDFA()` | Cold-path harnesses and long-running services that flush DFAs no longer allocate one new `DFA` per decision/mode every clear |
| `BufferedTokenStream.fill()` `ensureCapacity` | Estimate token count from char stream size to reduce `ArrayList` growth copies |

### Round 3 — LL(1) single store

| Change | Rationale |
|--------|-----------|
| `ConcurrentIntIntMapView` as `LL1Table` | One primitive store; protected JDK map remains for subclasses/tests; **no dual-write** tax on the hot put path |

---

## 4. Verification methodology

- **Corpus:** synthetic Java 1.7, 80 files × 12 methods; single-file micro: 40 methods (byte-identical on both commits).  
- **Harness:** `ComparativeParseHarness` (trimmed mean, 10 warmup / 25 iters, 3 alternating trials).  
- **JVM:** Corretto 8, `-Xms2g -Xmx2g -XX:+UseG1GC`.  
- **Parallelism:** 4 file-granularity workers, shared warm DFA.  
- **Metric:** speedup = baseline_ms / target_ms; overall = geometric mean across scenarios.  

Raw TSV/log under `perf-testsuite/results/` (`round1-ab-*`, `round2-ab-*`, `round3-ab-*`).

---

## 5. Final measurement (Round 3)

Stamp: `20260801T034852Z`

| Scenario | Base ms | Target ms | Speedup | Δ% |
|----------|--------:|----------:|--------:|---:|
| `lex_single_warm` | 1.788 | 1.143 | **1.565×** | +36.1% |
| `lex_single_cold` | 3.904 | 3.085 | **1.266×** | +21.0% |
| `parse_single_two_stage_warm` | 5.700 | 5.029 | **1.133×** | +11.8% |
| `parse_single_two_stage_cold` | 12.863 | 12.089 | **1.064×** | +6.0% |
| `parse_single_ll_warm` | 11.863 | 13.305 | **0.892×** | −12.2% |
| `parse_single_ll_cold` | 13.172 | 10.869 | **1.212×** | +17.5% |
| `batch_serial_two_stage_warm` | 82.638 | 79.392 | **1.041×** | +3.9% |
| `batch_serial_two_stage_cold_per_file` | 273.287 | 255.692 | **1.069×** | +6.4% |
| `batch_serial_two_stage_cold_build` | 80.306 | 81.100 | **0.990×** | −1.0% |
| `batch_serial_ll_warm` | 122.616 | 121.076 | **1.013×** | +1.3% |
| `batch_parallel_two_stage_warm` | 56.006 | 60.196 | **0.930×** | −7.5% |
| `batch_parallel_two_stage_cold_build` | 63.214 | 61.371 | **1.030×** | +2.9% |
| `batch_parallel_ll_warm` | 67.772 | 70.382 | **0.963×** | −3.9% |

**Aggregates**

| Aggregate | Value |
|-----------|------:|
| Geometric mean (all scenarios) | **1.078×** |
| Geometric mean (cold*) | **1.101×** |
| Geometric mean (warm*) | **1.059×** |
| Geometric mean (batch*) | **1.004×** |
| Goal ≥ 1.50× overall | **NOT MET** |

Parallel wall-clock scaling remains ~1.3× serial→4-thread on two-stage warm batches (same structural behaviour as the baseline).

---

## 6. Why 50% overall was not reached

1. **Baseline is already the optimized fork (4.13.2.7)**, not stock ANTLR 4. Many large wins (full-context DFA options, edge maps, tail-call elimination, etc.) predate the interval.  
2. **Warm multi-file Java parse is DFA + tokens.** After warmup, `adaptivePredict` is mostly edge-map get + accept; ATN retained-pool/HPPC work is off the hot path. Ceiling effects dominate.  
3. **Cold multi-file cost is dominated by ATN rebuild**, not DFA shell allocation. In-place `DFA.clear()` helps fixed costs but not closure/reach complexity.  
4. **Variance** on single-file micro scenarios (especially lex warm) remains high; multi-trial geometric means are the right summary, not peak single-run numbers.  
5. A **true 1.5×** would require product-level changes (codegen shape, specialized token pipelines, grammar-level left-factoring, or measuring only cold-first-compile KPI)—out of scope for safe runtime-local patches.

---

## 7. Correctness & API integrity

- **Unit tests:** `mvn -pl runtime/Java test` → **882 run, 0 fail**.  
- **HPPC:** no `com.carrotsearch.hppc` type in any `public`/`protected` method signature or field of exported types; shaded package relocation unchanged.  
- **Protected compatibility:** `ATN.LL1Table` remains `ConcurrentMap<Integer,Integer>` (now a view).  
- **Busy-set SPI:** still `Set<ATNConfig>` on protected `closure(...)`.

---

## 8. Follow-on recommendations

1. **Product KPI:** validate on real Groovy clean vs warm-incremental compiles rather than synthetic geo-mean alone.  
2. **If 1.5× remains a hard product goal:** invest in codegen / grammar transforms / token factory specialization, not only interpreter micro-opts.  
3. **CI:** wire `ComparativeParseHarness` nightly with fixed synthetic corpus to catch regressions.  
4. **Optional:** async-profiler on warm batch LL regressions (`parse_single_ll_warm`) to see if façade/inline noise remains.

---

## 9. Key source files

| File | Role |
|------|------|
| `runtime/Java/src/.../atn/ConcurrentIntIntMap.java` | COW primitive LL(1) map |
| `runtime/Java/src/.../atn/ConcurrentIntIntMapView.java` | JDK ConcurrentMap façade |
| `runtime/Java/src/.../atn/ClearableIntObjectHashMap.java` | Retained precedence filter map |
| `runtime/Java/src/.../atn/ParserATNSimulator.java` | Retained caches; LL(1) probe |
| `runtime/Java/src/.../atn/ATN.java` | In-place `clearDFA`; `ll1Cache` |
| `runtime/Java/src/.../dfa/DFA.java` | `clear()` |
| `runtime/Java/src/.../BufferedTokenStream.java` | `fill()` capacity hint |
| `perf-testsuite/.../jmh/ComparativeParseHarness.java` | A/B harness |

---

## 10. One-line conclusion

> After three rigorous optimization rounds, the runtime is **correct**, **API-clean w.r.t. HPPC**, and **modestly faster** (~1.08× geometric mean) than 4.13.2.7; the **≥50% overall speedup target is not met** under the end-to-end synthetic harness, which is the expected outcome given an already-optimized baseline and warm-DFA-dominated workloads.
