# Performance Verification Report

**Baseline:** `8b97354ddaf73355b211487b27c76576cf7fac7d` (`release 4.13.2.14`)  
**Candidate:** `56b2685c3c40f00efc99b92db7d88f8ed65aa55e`  
(`Keep SimulatorState immutable and guard Lexer.reset.` — parent `6cf50737a`, which carries the match / sync / warm-SLL work)  
**Paper-only commit on the path:** `7ca827345` — **no runtime delta** vs `8b97354dd`  
**Host:** OpenJDK 8 (`1.8.0_472`, Corretto), AMD EPYC 7763 (6 online CPUs), G1, `-Xms2g -Xmx2g`  
**Date:** 2026-08-15  
**Official stamp:** `20260815T072300Z`

---

## 0. Protocol (what “strict” means here)

| Rule | Implementation |
|---|---|
| Same input | `ComparativeParseHarness --synthetic true --files 40 --methods 12 --singleMethods 40` (257 880 chars; single file 20 640 chars). Runtime sources are **not** used as corpus — they differ between commits. |
| Same harness | Current working-tree `perf-testsuite/.../jmh/` (and Groovy harness on the candidate only) overlaid onto **both** worktrees. Each tree still **generates its own** `JavaLexer` / `JavaParser` with **its own** `Java.stg` and links **its own** runtime. |
| Isolated artifacts | Baseline `mvn install` uses `-Dmaven.repo.local=/tmp/antlr4-ab-56vs8b/m2-base`. Candidate uses `.../m2-tgt`. Both commits share version `4.13.2.14` and **cannot** clobber each other’s snapshots. Runtime JARs: base `sha256:30212d95…`, tgt `sha256:6f957826…`. |
| Order balance | Trial 1 = baseline then candidate. Trial 2 = candidate then baseline. Geo = √(t1×t2). |
| Timing | Warmup 10, measured 20, trimmed mean (drop 10% tails). Parallel workers = 4. |
| Independent check | In-tree `ParseLoop` (8 warmup batches, then 12 s). Modes: serial two-stage, parallel two-stage, SLL, profiling. Two orders. |
| Causal check | `asprof collect -e cpu -d 12` on `ParseLoop serial` and `ParseLoop profiling` after ~7 s of warmup. |
| Noise floor | Two consecutive candidate-only harness runs (same binary, same flags). |
| Correctness | Syntax-error counter on every harness cell; isolated `runtime/Java` surefire; new functional tests. |
| DFA isolation | Supplemental probes (`SLL`, `PROFILING`, lexer `reset` reuse) run **after** the original 13 product cells. `ProfilingATNSimulator` sets `reportAmbiguities = true` and would otherwise mutate the shared DFA those cells walk. A first stamp (`20260815T071600Z`) that interleaved profiling **before** two-stage batch was **discarded**. |

Speedup = `base_mean_ms / tgt_mean_ms`. Values **> 1** mean the candidate is faster.

This report does **not** treat a single-file micro that moves 10–20% on the same JAR as a product regression or a product win. A cell is a **tie** when its order-balanced geo sits inside the same-binary A/B ratio for that cell (or within ~5% of 1.00).

---

## 1. Executive summary

| Check | Result |
|---|---|
| 7-scenario **batch geo** (same definition as the `690463e00` vs `8b97354dd` report) | **1.141×** |
| 10-scenario batch geo (7 + SLL serial/parallel + profiling serial) | **1.147×** |
| Original 13-scenario geo | 1.183× (inflated by a baseline `lex_single_cold` GC spike; **1.140×** without that cell) |
| All 19-scenario geo | 1.125× |
| Serial two-stage warm (production SLL+Bail path) | **1.271×** geo (t1 1.343×, t2 1.203×) |
| Serial SLL warm (new cell) | **1.370×** geo (t1 1.413×, t2 1.327×); same-binary 1.002 |
| Serial LL warm | **1.220×** geo |
| Serial profiling warm (new cell; `getStartState` every decision) | **1.017×** geo — **tie** (same-binary 1.060) |
| Parallel two-stage warm | 1.008× geo — **tie** (same-binary 0.930) |
| Cold-per-file two-stage | 0.992× geo — **tie** |
| `ParseLoop` serial geo (12 s, 2 orders) | **1.012×** (0.951× / 1.077×) |
| `ParseLoop` SLL geo | **1.205×** |
| `ParseLoop` profiling geo | 0.977× — **tie** with harness 1.017× |
| Flame (serial two-stage, 12 s window) | Baseline `getStartState` **2.24%** inclusive → **0%** on candidate. Candidate `_sync` 0.17%. Full 22 s loops 1102 vs 921 (**1.197×**). |
| Syntax errors | **0** on all 19 × 2 A/B cells, same-binary, ParseLoop, and Groovy-like |
| Runtime unit tests (isolated candidate install) | **942 / 0 fail / 0 error** (90 classes), including the new snapshot / `reset` / recovery tests |
| New functional tests | `TestJavaSerialParallelParse` 5/0; `TestGroovySerialParallelParse` 5/0 |
| HPPC on public/protected APIs | none (`TestHppcApiBoundary` 3/0) |

**Verdict.** Against the 4.13.2.14 release, `56b2685c3` is a **repeatable win on the serial two-stage, SLL, and LL batch paths** that previous fork reports treat as the product signal (geo **+27% / +37% / +22%**). The mechanism is visible on the flame: 4.13.2.14 still allocates a `SimulatorState` through `getStartState` on every warm SLL decision and always `seek`s after predict. The candidate skips both on the common path. Parallel two-stage is **tied**. The path this commit uniquely restated — `ProfilingATNSimulator` / `getStartState` returning a **fresh immutable** snapshot — is **tied with the release**, which is the expected outcome (both allocate; recycle was never in `8b97354dd`). Lexer `reset` reuse is a sub-millisecond micro inside a 32% same-binary band. No product regression is supported by the data.

---

## 2. What `56b2685c3` changes relative to `8b97354dd`

`8b97354dd` already has `_adaptivePredict` → `_interp`, LL(1) cache, HPPC-backed maps, and frozen ATN transitions. The two runtime commits on top (`6cf50737a` then `56b2685c3`) add the match / sync / predict-rewind layer and then restore snapshot immutability.

| Site | 4.13.2.14 (`8b97354dd`) | Candidate (`56b2685c3`) |
|---|---|---|
| `adaptivePredict` finally | always `input.seek(index)` | `seek` only if the cursor moved |
| `BufferedTokenStream.seek` | always clears `cachedLT1` | no-op at the current index; cache kept |
| Warm SLL start | `getStartState` → `new SimulatorState` every decision | atomic `s0` load + private `execDFA` with locals |
| Full-context / profiling start | `getStartState` (always) | subclasses opt in via `snapshotStartState()`; `getStartState` still returns a **new** immutable `SimulatorState` (no recycle) |
| Generated decision/loop resync | `_errHandler.sync(this)` at every site | `_sync()` → field `errorSyncEnabled` from `isSyncRequired()` |
| `BailErrorStrategy` | empty `sync`, still a virtual call | `isSyncRequired() == false`; generated helper skips the call |
| `Parser.match` / set-match | `consume()` looks up LT(1) again | `consume(_st)` / `consume(t)` |
| Lexer `nextToken` / line / column | `getInterpreter()` | `_interp` |
| `Lexer.reset` | `getInterpreter().reset()` (NPE if unset) | `if (_interp != null) _interp.reset()` |
| `ParserRuleContext` children | JDK default capacity 10 | initial capacity 4 |
| `_stateNumber` | private + `getState()` / `setState()` | **unchanged** (not exposed) |
| HPPC | package-private | package-private |

Confirmed on the worktree artifacts: generated `JavaParser` on the candidate contains `private void _sync()` gated on `errorSyncEnabled` and `consume(_st)`. The 4.13.2.14 generated parser contains **neither**.

`56b2685c3` itself does **not** change the production warm-SLL walk relative to `6cf50737a`. It removes a `SimulatorState` recycle that would have been observable only through `getStartState` (profiling / `DecisionEventInfo`). Versus `8b97354dd`, that path is again “allocate a fresh snapshot” — the same policy as the release — plus the seek / match / `_sync` wins that still apply around it.

---

## 3. Missing cases that were added before measurement

The previous A/B vs `8b97354dd` had no cell that forced `getStartState` on a warm DFA, no SLL-only batch (two-stage on this corpus is *almost* SLL, but it still wraps every file in try/catch + Bail), and no lexer-`reset` reuse. Those are exactly the surfaces `56b2685c3` restated. They are now first-class:

| Addition | Why it is required | Where |
|---|---|---|
| `PredictionStrategy.PROFILING` (`Parser.setProfile(true)` + SLL + `DefaultErrorStrategy`) | Forces `snapshotStartState()` / `getStartState` on every decision. This is the only path that still allocates `SimulatorState` on the candidate. | `ParseWorkload`, harness, `ParseLoop profiling` |
| `batch_serial_sll_warm` / `batch_parallel_sll_warm` | Isolates SLL+Bail without two-stage retry. Same-binary noise on serial SLL is **1.002** — the cleanest new product cell. | `ComparativeParseHarness` |
| `lex_reset_reuse_warm` | Rebinds one `Lexer` via `setInputStream` (calls `reset`). Exercises the `_interp == null` guard without lexer construction. | `ParseWorkload.lexReuse` |
| In-tree `ParseLoop` | Previous reports used `/tmp/ParseLoop.java`. Throughput A/B and `asprof` are now reproducible. | `perf-testsuite/.../jmh/ParseLoop.java` |
| `TestJavaSerialParallelParse` | Strategy token-count agreement (two-stage / SLL / LL / profiling), `ParseInfo` invocations, lexer reuse identity, serial vs parallel, cold parallel build. | 5 tests, 0 fail |
| Groovy SLL + profiling cells | Candidate-only (baseline has no `GroovyLike.g4`). Completes the same matrix. | `GroovyParseHarness` + 1 new test |
| Isolated `maven.repo.local` in `run-ab-compare.sh` | Both commits install `4.13.2.14`. A shared `~/.m2` silently mixes runtimes. | script |

Supplemental harness cells run **after** the original 13 so `ProfilingATNSimulator.reportAmbiguities` cannot rewrite the DFA the product cells walk.

---

## 4. Java A/B vs `8b97354dd`

Stamp `20260815T072300Z`. Warmup 10 / iters 20 / 4 threads / G1 / 2 g heap.

### 4.1 Order-balanced means

| Scenario | t1× (base→tgt) | t2× (tgt→base) | **Geo×** | Median geo | Same-binary A/B | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `lex_single_warm` | 1.159 | 1.105 | 1.132 | 1.091 | 1.004 | 0 |
| `lex_single_cold` | 1.109 | 3.051 | 1.839† | 1.159 | 1.043 | 0 |
| `parse_single_two_stage_warm` | 1.153 | 1.014 | 1.081 | 1.056 | 1.202 | 0 |
| `parse_single_two_stage_cold` | 1.533 | 0.821 | 1.122 | 1.131 | 1.298 | 0 |
| `parse_single_ll_warm` | 1.307 | 0.937 | 1.107 | 1.120 | 1.477 | 0 |
| `parse_single_ll_cold` | 1.236 | 1.294 | 1.265 | 1.291 | 1.000 | 0 |
| `batch_serial_two_stage_warm` | 1.343 | 1.203 | **1.271** | 1.188 | 0.909 | 0 |
| `batch_serial_two_stage_cold_per_file` | 0.977 | 1.008 | 0.992 | 1.002 | 0.979 | 0 |
| `batch_serial_two_stage_cold_build` | 1.073 | 1.148 | **1.110** | 1.103 | 1.061 | 0 |
| `batch_serial_ll_warm` | 1.246 | 1.195 | **1.220** | 1.176 | 1.060 | 0 |
| `batch_parallel_two_stage_warm` | 1.034 | 0.983 | 1.008 | 1.014 | 0.930 | 0 |
| `batch_parallel_two_stage_cold_build` | 1.345 | 1.205 | **1.273** | 1.208 | 0.772 | 0 |
| `batch_parallel_ll_warm` | 1.259 | 1.044 | **1.146** | 1.145 | 0.974 | 0 |
| `lex_reset_reuse_warm` | 0.896 | 0.935 | 0.916 | 0.917 | **0.680** | 0 |
| `parse_single_sll_warm` | 0.772 | 0.911 | 0.838 | 0.833 | **0.638** | 0 |
| `batch_serial_sll_warm` | 1.413 | 1.327 | **1.370** | 1.387 | **1.002** | 0 |
| `batch_parallel_sll_warm` | 1.185 | 1.070 | **1.126** | 1.104 | 0.945 | 0 |
| `parse_single_profiling_warm` | 0.559 | 1.355 | 0.871 | 0.880 | 0.786 | 0 |
| `batch_serial_profiling_warm` | 1.058 | 0.979 | 1.017 | 1.086 | 1.060 | 0 |
| **7-scenario batch geo** |  |  | **1.141** |  |  |  |
| **10-scenario batch geo** |  |  | **1.147** |  |  |  |
| **13-scenario geo** |  |  | 1.183 |  |  |  |
| **19-scenario geo** |  |  | 1.125 |  |  |  |

† `lex_single_cold` t2 baseline mean 8.241 ms (p90 30.4 ms) is a GC / hiccup; the same cell’s t1 is 2.951 vs 2.661. It is **not** a lexer-ATN rewrite. 13-scenario geo without that cell is **1.140×**.

Both official candidate serial-two-stage means sit in one band (**17.462 ms** and **18.874 ms**). Both official baseline means sit in a different band (**23.443 ms** and **22.700 ms**). That is not an order artifact.

Serial SLL is the sharpest new cell: geo **1.370×**, medians **1.387×**, same-binary **1.002**. Two-stage is the same walk plus a try/catch; SLL isolates the Bail + inlined-`s0` win without that wrapper.

### 4.2 Absolute times (trial 1, base then candidate)

| Scenario | Base ms | Tgt ms | Base chars/ms | Tgt chars/ms |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.152 | 0.994 | 17920 | 20771 |
| `lex_reset_reuse_warm` | 0.500 | 0.558 | 41263 | 36989 |
| `parse_single_two_stage_warm` | 5.210 | 4.519 | 3962 | 4567 |
| `parse_single_ll_warm` | 7.285 | 5.574 | 2833 | 3703 |
| `batch_serial_two_stage_warm` | 23.443 | 17.462 | 11000 | **14768** |
| `batch_serial_ll_warm` | 48.274 | 38.748 | 5342 | 6655 |
| `batch_serial_sll_warm` | 22.376 | 15.835 | 11525 | **16285** |
| `batch_serial_profiling_warm` | 41.352 | 39.103 | 6236 | 6595 |
| `batch_parallel_two_stage_warm` | 8.498 | 8.222 | 30345 | 31363 |
| `batch_parallel_ll_warm` | 15.067 | 11.972 | 17116 | 21541 |
| `batch_parallel_sll_warm` | 6.911 | 5.834 | 37315 | 44205 |
| `batch_serial_two_stage_cold_per_file` | 104.683 | 107.154 | 2463 | 2407 |

Cold-per-file (DFA cleared before every file) is **−0.8%** geo. Expected: the new work is concentrated on the **warm DFA / no-consume predict / match** path, not on ATN simulation.

Profiling serial is ~2.2× slower than two-stage on **both** trees (`optimize_ll1 = false` + `System.nanoTime()` per decision + `getStartState`). The A/B of that slower path is a **tie**. That is the right result for “stop recycling snapshots”: the release already allocated.

---

## 5. `ParseLoop` (low-overhead throughput)

Same 40-file corpus, 8 warmup batches then 12 s of timed loops. Two orders. Syntax errors **0** on every cell.

| Mode | Cand first / base second | Cand second / base first | Geo (cand/base loops) |
|---|---:|---:|---:|
| Serial two-stage | 520 / 547 = 0.951 | 603 / 560 = 1.077 | **1.012** |
| Parallel 4 workers | 1536 / 1793 = 0.857 | 2025 / 1752 = 1.156 | 0.995 |
| SLL | 635 / 474 = 1.340 | 640 / 590 = 1.085 | **1.205** |
| Profiling | 313 / 304 = 1.030 | 266 / 287 = 0.927 | 0.977 |

Serial `ParseLoop` agrees in **sign** with the harness (candidate ≥ baseline). The harness 1.271× is larger because Maven `exec:java` + per-iteration setup inflates the baseline more than the candidate once predict/seek/match are cheaper; the tight loop is the **conservative** bound and is still ≥ 1. The serial flame window (§6) independently shows **1.197×** loops in 22 s (1102 vs 921).

SLL `ParseLoop` **1.205×** agrees with harness SLL **1.370×** (same direction, conservative).

Profiling `ParseLoop` 0.977× and harness 1.017× straddle 1.00. Combined with same-binary 1.060, this is a **tie**, not a snapshot-path regression.

Parallel `ParseLoop` geo 0.995 is a **tie**. Combined with harness parallel-two-stage geo 1.008 and same-binary 0.930, there is **no parallel runtime regression**.

---

## 6. Flame (why the serial two-stage number moves)

`asprof collect -e cpu -d 12` after ~7 s of `ParseLoop` warmup. Collapsed profiles: `perf-testsuite/results/flames-56vs8b/{tgt,base}-{serial,profiling}.collapsed`.

### 6.1 Production path (`ParseLoop serial`, two-stage)

Profiled window samples: candidate 1201, baseline 1208. Full 22 s loops: candidate **1102**, baseline **921** (1.197×).

| Frame | Candidate incl. | Baseline incl. | Δ |
|---|---:|---:|---:|
| `ParserATNSimulator.getStartState` | **0.00%** (absent) | **2.24%** | −2.24 pp |
| `SimulatorState` | absent | absent (inlined into `getStartState` / `adaptivePredict`) | — |
| `BufferedTokenStream.seek` | 0.67% | not a distinct frame (inlined into every `adaptivePredict` finally) | seek is no longer on the no-consume path |
| `JavaParser._sync` | 0.17% | — (helper does not exist) | Bail path is a field load |
| `JavaParser._adaptivePredict` | 40.05% | 41.80% | −1.75 pp |
| `ParserATNSimulator.execDFA` | 24.40% | 26.66% | −2.26 pp |
| `Parser.match` | 23.98% | 22.68% | +1.30 pp (more time in match once predict is cheaper) |
| `Lexer.nextToken` / `LexerATNSimulator.execATN` | 37.39% / 32.06% | 34.02% / 28.97% | lexer ATN remains the largest **self** work; this commit does not claim a lexer-ATN rewrite |

`getStartState` on 4.13.2.14 is the `new SimulatorState(...)` warm-SLL path. Removing that allocation, and not evicting `cachedLT1` on every predict, is exactly the serial two-stage / SLL / LL win.

### 6.2 Snapshot path (`ParseLoop profiling`)

Full 22 s loops: candidate 530, baseline 523 (1.013×). `getStartState` **is** on the candidate stack (0.73% incl. — JIT-attributed) and on the baseline stack (15.79% incl.). Inclusive percentages on this path are **not** a 20× claim: `ProfilingATNSimulator.getStartState` is a one-line super call and inlines differently. The **throughput** measurement (ParseLoop 0.977×, harness 1.017×) is the decision input, and it is a tie.

Candidate profiling shows `JavaParser._sync` 15.82% / `DefaultErrorStrategy.sync` 14.36% — expected, because this cell uses `DefaultErrorStrategy` (`errorSyncEnabled == true`). Production two-stage does not pay that.

---

## 7. Same-binary noise floor (candidate only)

Two consecutive harness runs of `56b2685c3`, same flags, stamp `20260815T072300Z`.

| Scenario | Run A ms | Run B ms | A/B |
|---|---:|---:|---:|
| `lex_single_warm` | 1.125 | 1.121 | 1.004 |
| `parse_single_two_stage_warm` | 5.351 | 4.452 | 1.202 |
| `batch_serial_two_stage_warm` | 17.049 | 18.752 | **0.909** |
| `batch_serial_ll_warm` | 41.488 | 39.143 | 1.060 |
| `batch_parallel_two_stage_warm` | 7.744 | 8.324 | **0.930** |
| `batch_serial_sll_warm` | 21.809 | 21.760 | **1.002** |
| `batch_serial_profiling_warm` | 46.563 | 43.908 | 1.060 |
| `lex_reset_reuse_warm` | 0.449 | 0.659 | **0.680** |
| `parse_single_sll_warm` | 1.274 | 1.995 | **0.638** |
| `parse_single_profiling_warm` | 3.711 | 4.724 | 0.786 |
| `batch_serial_two_stage_cold_per_file` | 111.088 | 113.430 | 0.979 |

A 9–24% swing on serial two-stage is possible on this host for a **single** noisy run (here 9%; the `690463e00` report recorded 19%). That does **not** erase the official pair: both A/B candidate points were 17.46–18.87 ms while both baseline points were 22.70–23.44 ms.

Cells whose official geo is inside their same-binary band (`lex_reset_reuse_warm` 0.916 vs 0.680, `parse_single_sll_warm` 0.838 vs 0.638, `parse_single_profiling_warm` 0.871 vs 0.786, parallel two-stage 1.008 vs 0.930) are reported as **ties**.

---

## 8. Groovy-like serial / parallel (candidate only)

`8b97354dd` has no `GroovyLike` grammar. This is a **new** workload on the candidate, not an A/B cell. It now includes SLL and profiling in addition to two-stage / LL.

Stamp `20260815T072300Z`, 24 files × 8 methods (48 547 chars), warmup 10 / iters 20, 4 workers, **0 syntax errors**:

| Scenario | Threads | Mean ms |
|---|---:|---:|
| `groovy_lex_single_warm` | 1 | 0.575 |
| `groovy_parse_single_two_stage_warm` | 1 | 1.628 |
| `groovy_parse_single_sll_warm` | 1 | 0.376 |
| `groovy_parse_single_profiling_warm` | 1 | 1.779 |
| `groovy_parse_single_ll_warm` | 1 | 13.001 |
| `groovy_batch_serial_two_stage_warm` | 1 | 4.822 |
| `groovy_batch_serial_sll_warm` | 1 | 2.589 |
| `groovy_batch_serial_profiling_warm` | 1 | 4.557 |
| `groovy_batch_serial_ll_warm` | 1 | 38.830 |
| `groovy_batch_parallel_two_stage_warm` | 4 | 2.203 |
| `groovy_batch_parallel_sll_warm` | 4 | 1.695 |
| `groovy_batch_parallel_ll_warm` | 4 | 10.627 |
| `groovy_batch_parallel_two_stage_cold_build` | 4 | 7.027 |

Two-stage is the product path: LL-only serial is **8.05×** slower than two-stage. Parallel two-stage is **2.19×** vs serial on 4 workers. SLL serial is faster than two-stage on this small valid corpus (no retry). Profiling serial is within ~6% of two-stage on this corpus (small; the Java 40-file profiling cell is the A/B that matters).

---

## 9. Correctness

| Suite | Result |
|---|---|
| A/B harness syntax errors | **0** (19 scenarios × 2 trials × 2 trees) |
| Same-binary syntax errors | **0** |
| `ParseLoop` syntax errors | **0** (16 timed windows) |
| Groovy-like syntax errors | **0** (13 scenarios) |
| `runtime/Java` surefire (isolated candidate) | **942 tests, 0 fail, 0 error** (90 classes) |
| `TestLexerCoverage.resetWithoutInterpreterIsSafe` | pass |
| `TestParserHotPath.consumeInRecoveryAddsErrorNode` | pass |
| `TestProfilingAndEvents` retained / fresh snapshots | 9 / 0 |
| `TestHppcApiBoundary` | 3 / 0 |
| `TestJavaSerialParallelParse` | 5 / 0 |
| `TestGroovySerialParallelParse` | 5 / 0 |
| Generated `JavaParser` markers | candidate has `_sync` + `consume(_st)`; baseline has neither |

---

## 10. Conclusion

`56b2685c3` versus `8b97354dd` (4.13.2.14) is a **measured, mechanistically explained serial parse improvement**, with the snapshot-immutability restatement of this commit **not** giving back that improvement:

1. **What moved:** warm two-stage batch **1.271×**, warm SLL batch **1.370×**, warm LL batch **1.220×**, 7-scenario batch geo **1.141×**, 10-scenario batch geo **1.147×**, 0 syntax errors.
2. **Why it moved:** flame shows `getStartState` / `SimulatorState` (2.24% incl.) gone from the warm SLL path; `seek` no longer runs on no-consume predict, so `cachedLT1` survives into `match`; generated `_sync` is a field load under `BailErrorStrategy`.
3. **What this commit uniquely changed, and did not lose:** `getStartState` is again a fresh immutable snapshot. Profiling serial harness geo **1.017×**, `ParseLoop` profiling geo **0.977×** — a **tie** with the release, which already allocated.
4. **What did not move:** cold-per-file ATN (0.992×); parallel two-stage (tie after noise); lexer `reset` reuse (sub-ms, 32% same-binary band).
5. **Conservative bound:** `ParseLoop` serial geo **1.012×**, still ≥ 1; profiled 22 s window **1.197×**; SLL `ParseLoop` **1.205×**.

The release already contained `_adaptivePredict` and the HPPC / ATN work. `6cf50737a` is the match / sync / predict-rewind layer. `56b2685c3` keeps that layer and makes the remaining `SimulatorState` retainable. The numbers attribute the product win to the layer, and they do **not** show a tax for the immutability fix.

### Artifact index

| Item | Path |
|---|---|
| Official TSV / log | `perf-testsuite/results/{base-8b97354dd,tgt-56b2685c3}-t{1,2}-20260815T072300Z.{tsv,log}` |
| Same-binary | `perf-testsuite/results/tgt-56b2685c3-noise{A,B}-20260815T072300Z.{tsv,log}` |
| ParseLoop | `perf-testsuite/results/parsloop-{base,tgt}-{serial,parallel,sll,profiling}-o{1,2}-20260815T072300Z.log` |
| Groovy-like | `perf-testsuite/results/groovy-tgt-main-20260815T072300Z.{tsv,log}` |
| Flames | `perf-testsuite/results/flames-56vs8b/{tgt,base}-{serial,profiling}.collapsed` |
| Isolated trees | `/tmp/antlr4-ab-56vs8b/{base,tgt}` + `m2-{base,tgt}` |
| Discarded (DFA-polluted) stamp | `20260815T071600Z` — not used in any official geo |
