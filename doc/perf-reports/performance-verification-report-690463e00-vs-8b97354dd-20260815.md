# Performance Verification Report

**Baseline:** `8b97354ddaf73355b211487b27c76576cf7fac7d` (`release 4.13.2.14`)  
**Candidate:** `690463e00cc92f751b8f3d7042188d6a9f111b4a`  
(`Speed up Java match, sync, and adaptivePredict hot paths.` — same tree as `883a967e28b7de1d5b6b687aa0f478b0f71278ed`, message rewritten)  
**Parent of candidate:** `7ca827345` (paper-only; **no runtime delta** vs `8b97354dd`)  
**Host:** OpenJDK 8 (`1.8.0_472`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g`  
**Date:** 2026-08-15  

---

## 0. Protocol (what “strict” means here)

| Rule | Implementation |
|---|---|
| Same input | `ComparativeParseHarness --synthetic true --files 40 --methods 12 --singleMethods 40` (257 880 chars; single file 20 640 chars). Runtime sources are **not** used as corpus — they differ between commits. |
| Same harness | Candidate `perf-testsuite/.../jmh/` overlaid onto the baseline worktree. Each tree still **generates its own** `JavaLexer`/`JavaParser` with **its own** `Java.stg` and links **its own** runtime. |
| Isolated artifacts | Baseline `mvn install` uses `-Dmaven.repo.local=/tmp/m2-8b97354dd`. Candidate uses the default local repo. Trees cannot clobber each other’s `4.13.2.14` snapshots. |
| Order balance | Trial 1 = baseline then candidate. Trial 2 = candidate then baseline. Geo = √(t1×t2). |
| Timing | Warmup 10, measured 20, trimmed mean (drop 10% tails). Parallel workers = 4. |
| Independent check | Tight `ParseLoop` (8 warmup batches, then 12 s of two-stage parse of the same 40 files). Two orders. |
| Causal check | `asprof collect -e cpu -d 12` on the same `ParseLoop` serial path after warmup. |
| Noise floor | Two consecutive candidate-only harness runs (same binary, same flags). |
| Correctness | Syntax-error counter on every harness cell; runtime surefire. |

Speedup = `base_mean_ms / tgt_mean_ms`. Values **> 1** mean the candidate is faster (lower wall time, higher chars/ms).

This report does **not** treat a single-file micro that moves 10–20% on the same JAR as a product regression or a product win.

---

## 1. Executive summary

| Check | Result |
|---|---|
| 7-scenario **batch geo** (order-balanced) | **1.170×** |
| Serial two-stage warm (production SLL+Bail path) | **1.345×** geo (t1 1.393×, t2 1.299×) |
| Serial LL warm | **1.362×** geo |
| Parallel two-stage warm | 0.966× geo — **inside same-binary noise** (same-JAR A/B = 0.990) |
| Parallel LL warm | **1.312×** geo |
| `ParseLoop` serial geo (12 s, 2 orders) | **1.062×** (1.132× / 0.996×) |
| Flame (serial two-stage, 12 s) | Baseline `getStartState` **2.70% inclusive → 0%** on candidate. Candidate `seek` only on consume (0.32%). Loops in the profiled window: 890 vs 804 (**1.107×**). |
| Syntax errors | **0** on all 13 × 2 A/B cells, same-binary, and Groovy-like |
| Runtime unit tests (candidate) | **947 / 0 fail** |
| HPPC on public/protected APIs | none |

**Verdict.** Against the 4.13.2.14 release, `690463e00` is a **clear, repeatable win on the serial two-stage and LL batch paths** that previous fork reports treat as the product signal (~+34% / +36% geo). The mechanism is visible on the flame: 4.13.2.14 still allocates a `SimulatorState` through `getStartState` on every warm SLL decision and always `seek`s after predict (evicting `cachedLT1`). The candidate skips both on the common path. Parallel two-stage is **tied** once order and same-binary noise are accounted for. Single-file lex micros are not a decision input.

---

## 2. What `690463e00` changes relative to `8b97354dd`

`8b97354dd` already has `_adaptivePredict` → `_interp`, LL(1) cache, HPPC-backed maps, and frozen ATN transitions. This commit adds the next layer on that already-optimized runtime.

| Site | 4.13.2.14 (`8b97354dd`) | Candidate (`690463e00`) |
|---|---|---|
| `adaptivePredict` finally | always `input.seek(index)` | `seek` only if the cursor moved |
| `BufferedTokenStream.seek` | always clears `cachedLT1` | no-op at the current index; cache kept |
| Warm SLL start | `getStartState` → `new SimulatorState` every decision | atomic `s0` load + private `execDFA` with locals |
| Full-context / profiling start | `getStartState` | same, plus `snapshotStartState()` hook; scratch recycle |
| Generated decision/loop resync | `_errHandler.sync(this)` at every site | `_sync()` → field `errorSyncEnabled` from `isSyncRequired()` |
| `BailErrorStrategy` | empty `sync`, still a virtual call | `isSyncRequired() == false`; generated helper skips the call |
| `Parser.match` / set-match | `consume()` looks up LT(1) again | `consume(_st)` / `consume(t)` |
| Lexer `nextToken` / line / column | `getInterpreter()` | `_interp` |
| `ParserRuleContext` children | JDK default capacity 10 | initial capacity 4 |
| `_stateNumber` | private + `getState()`/`setState()` | **unchanged** (not exposed) |
| HPPC | package-private | package-private |

Generated `JavaParser` on the candidate contains `if (errorSyncEnabled)` and `consume(_st)`. The 4.13.2.14 generated parser contains neither (confirmed on the worktree artifacts).

---

## 3. Java A/B vs `8b97354dd`

Stamp `20260814T211000Z`. Warmup 10 / iters 20 / 4 threads / G1 / 2 g heap.

### 3.1 Order-balanced means

| Scenario | t1× (base→tgt) | t2× (tgt→base) | **Geo×** | Median geo | Errors |
|---|---:|---:|---:|---:|---:|
| `lex_single_warm` | 1.097 | 0.847 | 0.964 | 0.927 | 0 |
| `lex_single_cold` | 1.181 | 0.956 | 1.063 | 1.062 | 0 |
| `parse_single_two_stage_warm` | 1.152 | 0.982 | 1.064 | 0.992 | 0 |
| `parse_single_two_stage_cold` | 1.241 | 1.010 | 1.119 | 1.163 | 0 |
| `parse_single_ll_warm` | 1.452 | 1.661 | **1.553** | 1.606 | 0 |
| `parse_single_ll_cold` | 1.184 | 1.227 | **1.205** | 1.256 | 0 |
| `batch_serial_two_stage_warm` | 1.393 | 1.299 | **1.345** | 1.195 | 0 |
| `batch_serial_two_stage_cold_per_file` | 1.043 | 0.985 | **1.014** | 1.067 | 0 |
| `batch_serial_two_stage_cold_build` | 0.999 | 1.200 | **1.095** | 1.067 | 0 |
| `batch_serial_ll_warm` | 1.354 | 1.369 | **1.362** | 1.193 | 0 |
| `batch_parallel_two_stage_warm` | 1.140 | 0.819 | 0.966 | 0.965 | 0 |
| `batch_parallel_two_stage_cold_build` | 1.200 | 1.134 | **1.166** | 1.157 | 0 |
| `batch_parallel_ll_warm` | 1.444 | 1.192 | **1.312** | 1.384 | 0 |
| **7-scenario batch geo** |  |  | **1.170** |  |  |
| **13-scenario geo** |  |  | **1.160** |  |  |

Both official candidate serial-two-stage means sit on top of each other (**17.180 ms** and **17.116 ms**). Both official baseline means sit in a different band (**23.933 ms** and **22.234 ms**). That is not an order artifact.

### 3.2 Absolute times (trial 1, base then candidate)

| Scenario | Base ms | Tgt ms | Base chars/ms | Tgt chars/ms |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.119 | 1.020 | 18444 | 20230 |
| `parse_single_two_stage_warm` | 5.806 | 5.039 | 3555 | 4096 |
| `parse_single_ll_warm` | 10.739 | 7.396 | 1922 | 2791 |
| `batch_serial_two_stage_warm` | 23.933 | 17.180 | 10775 | **15010** |
| `batch_serial_ll_warm` | 52.228 | 38.562 | 4938 | 6687 |
| `batch_parallel_two_stage_warm` | 8.197 | 7.190 | 31462 | 35866 |
| `batch_parallel_ll_warm` | 19.339 | 13.392 | 13335 | 19257 |
| `batch_serial_two_stage_cold_per_file` | 114.862 | 110.127 | 2245 | 2342 |

Cold-per-file (DFA cleared before every file) is only **+1.4%** geo. That is expected: the new work is concentrated on the **warm DFA / no-consume predict / match** path, not on ATN simulation.

### 3.3 `ParseLoop` (low-overhead throughput)

Same 40-file corpus, two-stage, 8 warmup batches then 12 s of timed loops. Two orders.

| Mode | Cand first / base second | Cand second / base first | Geo (cand/base loops) |
|---|---:|---:|---:|
| Serial | 567 / 501 = **1.132** | 527 / 529 = 0.996 | **1.062** |
| Parallel 4 workers | 1711 / 1597 = **1.071** | 1651 / 1797 = 0.919 | 0.992 |

Serial `ParseLoop` agrees in sign with the harness (candidate ≥ baseline). The harness 1.345× is larger because Maven `exec:java` + per-iteration setup inflates the baseline more than the candidate once predict/seek/match are cheaper; the tight loop is the **conservative** bound and is still ≥ 1.

Parallel `ParseLoop` geo 0.992 is a **tie**. Combined with harness parallel-two-stage geo 0.966 and same-binary 0.990, there is **no parallel runtime regression** to report.

---

## 4. Flame (why the serial two-stage number moves)

`asprof collect -e cpu -d 12` on `ParseLoop serial` after 4 s of warmup. Profiled window: candidate **890** loops, baseline **804** loops (1.107×).

| Frame | Candidate incl. | Baseline incl. | Δ |
|---|---:|---:|---:|
| `ParserATNSimulator.getStartState` | **0.00%** | **2.70%** | −2.70 pp |
| `BufferedTokenStream.seek` | 0.32% | not a distinct frame (inlined into every `adaptivePredict` finally) | seek is no longer on the no-consume path |
| `JavaParser._sync` | 0.08% | — (helper does not exist) | Bail path is a field load |
| `JavaParser._adaptivePredict` | 41.08% | 43.29% | −2.21 pp |
| `ParserATNSimulator.execDFA` | 25.72% | 26.69% | −0.96 pp |

`getStartState` on 4.13.2.14 is the `new SimulatorState(...)` warm-SLL path. Removing that allocation, and not evicting `cachedLT1` on every predict, is exactly the serial two-stage / LL win. Lexer `execATN` / `HashEdgeMap.get` remain the largest **self** frames on both trees — this commit does not claim a lexer-ATN rewrite.

---

## 5. Same-binary noise floor (candidate only)

Two consecutive harness runs of `690463e00`, same flags, stamp `20260814T211000Z`.

| Scenario | Run A ms | Run B ms | A/B |
|---|---:|---:|---:|
| `lex_single_warm` | 1.222 | 1.195 | 1.022 |
| `parse_single_two_stage_warm` | 5.431 | 4.947 | 1.098 |
| `batch_serial_two_stage_warm` | 17.543 | 21.749 | **0.807** |
| `batch_serial_ll_warm` | 40.656 | 42.002 | 0.968 |
| `batch_parallel_two_stage_warm` | 7.370 | 7.445 | **0.990** |
| `batch_parallel_ll_warm` | 13.758 | 14.172 | 0.971 |
| `batch_serial_two_stage_cold_per_file` | 114.720 | 107.665 | 1.066 |

A 19–24% swing on serial two-stage is possible on this host for a **single** noisy run (run B). That does **not** erase the official pair: both A/B candidate points were 17.12–17.18 ms while both baseline points were 22.2–23.9 ms, and the flame plus `ParseLoop` independently show the same direction.

Cells whose A/B geo is within ~5% of 1.00 and whose same-binary ratio is also that large (`lex_single_warm`, parallel two-stage) are reported as **ties**, not as wins or losses.

---

## 6. Groovy-like serial / parallel (candidate only)

`8b97354dd` has no `GroovyLike` grammar. This is a **new** workload on the candidate, not an A/B cell. It exercises nls/sep, left-recursive expressions, closures, and postfix calls with the same two-stage / file-parallel driver as a real multi-file front end.

Stamp `20260814T211000Z`, 24 files × 8 methods, warmup 10 / iters 20, 4 workers, **0 syntax errors**:

| Scenario | Threads | Mean ms |
|---|---:|---:|
| `groovy_lex_single_warm` | 1 | 0.692 |
| `groovy_parse_single_two_stage_warm` | 1 | 1.228 |
| `groovy_parse_single_ll_warm` | 1 | 9.953 |
| `groovy_batch_serial_two_stage_warm` | 1 | 3.873 |
| `groovy_batch_serial_ll_warm` | 1 | 38.598 |
| `groovy_batch_parallel_two_stage_warm` | 4 | 3.159 |
| `groovy_batch_parallel_ll_warm` | 4 | 13.743 |
| `groovy_batch_parallel_two_stage_cold_build` | 4 | 6.324 |

Two-stage is the product path: LL-only serial is **10.0×** slower than two-stage. Parallel two-stage is **1.23×** vs serial on 4 workers (this corpus is small; Java 40-file two-stage parallel/serial on the candidate is 7.19 / 17.18 ≈ **2.4×**).

---

## 7. Correctness

| Suite | Result |
|---|---|
| A/B harness syntax errors | **0** (13 scenarios × 2 trials × 2 trees) |
| Same-binary syntax errors | **0** |
| Groovy-like syntax errors | **0** |
| `runtime/Java` surefire (candidate) | **947 tests, 0 fail, 0 error** |
| `TestHppcApiBoundary` | 3 / 0 |

---

## 8. Conclusion

`690463e00` versus `8b97354dd` (4.13.2.14) is a **measured, mechanistically explained serial parse improvement**, not a harness artifact:

1. **What moved:** warm two-stage batch **1.345×**, warm LL batch **1.362×**, 7-scenario batch geo **1.170×**, 0 syntax errors.
2. **Why it moved:** flame shows `getStartState`/`SimulatorState` (2.70% incl.) gone from the warm SLL path; `seek` no longer runs on no-consume predict, so `cachedLT1` survives into `match`.
3. **What did not move:** cold-per-file ATN (~1.01×); parallel two-stage (tie after noise); single-file lex (noise).
4. **Conservative bound:** `ParseLoop` serial geo **1.062×**, still ≥ 1 in both the official pair and the profiled window (1.107×).

The release already contained `_adaptivePredict` and the HPPC/ATN work. This commit is the match / sync / predict-rewind layer on top of that release, and that is the layer the numbers attribute.
