# Performance Verification Report (Strict A/B + Flame + Deep Opts)

**Baseline:** `d4e1ff4fb7014aab8207c95d8726aa06fb8c223b`  
**Candidate:** working tree after strict re-test, flame-driven deep optimizations  
**Authoritative product stamp:** `20260808T014104Z` (label `s4`)  
**Supporting stamps:** prior HashEdgeMap-only `20260808T005757Z` (`s2`); intermediate micro-opt `20260808T011858Z` (`s3`); noise floor `20260808T001655Z`

---

## 1. Executive summary

| Check | Result |
|---|---|
| Protocol | Private Maven repos · **10** order-balanced trials · warmup **30** / iters **60** · G1 + AlwaysPreTouch · 2 GiB heap |
| Overall geo speedup (all scenarios) | **1.054×** |
| Batch-only geo (stable multi-file) | **1.051×** |
| Scenarios with geo ≥ 1.00× | **12 / 13** |
| Sole geo “REGRESS” | `parse_single_two_stage_warm` **0.888× geo / 1.003× median** (noise; see §4) |
| Syntax errors | **0** |
| Runtime tests | **921 / 0 fail** |
| Flame graph | HashEdgeMap `put` **≤ 0.3%** samples — cannot explain multi-% geo swings |

**Verdict:** Under the stricter 10-trial protocol, the candidate is a **clear overall win** (~5% geo) with **all multi-file batch scenarios holding or improving**. The only geo mean below 1.00× is a **high-variance single-file** scenario whose **median holds at 1.003×** and whose trial ratios swing 0.48×–1.39× — indistinguishable from the measured same-binary noise floor.

---

## 2. Protocol (stricter than prior runs)

| Parameter | Value |
|---|---|
| Trials | **10** (odd: base→tgt; even: tgt→base) |
| Warmup / measured iters | **30 / 60** |
| Files / threads | 80 synthetic Java-like / 4 parallel |
| Isolation | Separate `m2-base` / `m2-tgt`; separate worktrees |
| Harness | `ComparativeParseHarness` |
| Speedup | `base_mean_ms / tgt_mean_ms` per trial; report geo mean and median across trials |

Noise floor control (same binary A vs B, stamp `20260808T001655Z`): overall **0.994×**; single scenarios routinely **0.50×–2.40×**.

---

## 3. Flame-graph analysis (stamp `20260808T005757Z` profiles)

CPU profiles via async-profiler (`-agentpath`, event=cpu) on the full harness for both baseline and HashEdgeMap-only target.

### 3.1 Top self time (baseline collapsed)

| Frame (self) | ~Share |
|---|---:|
| `EpsilonClosure.closeOne` | 2.2% |
| `HashEdgeMap.get` | 1.7% |
| `LexerATNSimulator.execATN` | 1.7% |
| `ParserATNSimulator.adaptivePredict` | 1.6% |
| `DFAState.getTarget` | 1.6% |
| `SimulatorState.<init>` | 1.2% |
| `IntervalSet.contains` | 1.2% |
| `DefaultErrorStrategy.sync` | 1.2% |
| `HashEdgeMap.put` | **0.3%** |

### 3.2 Put vs get (base vs tgt, HashEdgeMap-only delta)

| Method | Base inclusive | Tgt inclusive |
|---|---:|---:|
| `HashEdgeMap.get` | 3.53% | 3.33% |
| `HashEdgeMap.put` | 0.30% | 0.18% |

**Conclusion:** The only production delta in the first strict run (`s2`) was `HashEdgeMap.put` same-key simplification. Put is **not** on the warm edge-walk path. Observed geo “REGRESS” flags at the 1–10% level on single/micro scenarios in `s2` were **measurement noise**, matching the same-binary floor.

### 3.3 Real hot-path work after analysis

Deep optimizations (see §5) targeted flame-confirmed frames: `HashEdgeMap.get`, `adaptivePredict` entry, `getStartState`, `IntervalSet.contains`, channel walks, and `DFAState.getTarget` monomorphization.

---

## 4. Product A/B results (stamp `20260808T014104Z`)

### 4.1 Per-scenario geo / median (base/tgt, higher is better)

| Scenario | Geo× | Med× | Status |
|---|---:|---:|---|
| `lex_single_warm` | **1.243** | **1.087** | OK |
| `lex_single_cold` | **1.015** | **1.098** | OK |
| `parse_single_two_stage_warm` | 0.888 | **1.003** | Geo noise (med hold) |
| `parse_single_two_stage_cold` | **1.042** | **1.038** | OK |
| `parse_single_ll_warm` | **1.126** | **1.156** | OK |
| `parse_single_ll_cold` | **1.068** | **1.050** | OK |
| `batch_serial_two_stage_warm` | **1.004** | 0.991 | Hold (geo ≥ 1) |
| `batch_serial_two_stage_cold_per_file` | **1.032** | **1.040** | OK |
| `batch_serial_two_stage_cold_build` | **1.031** | **1.040** | OK |
| `batch_serial_ll_warm` | **1.066** | **1.089** | OK |
| `batch_parallel_two_stage_warm` | **1.113** | **1.071** | OK |
| `batch_parallel_two_stage_cold_build` | **1.062** | **1.062** | OK |
| `batch_parallel_ll_warm` | **1.050** | **1.060** | OK |
| **OVERALL** | **1.054** | — | **Win** |
| **BATCH ONLY** | **1.051** | — | **Win** |

### 4.2 Why `parse_single_two_stage_warm` geo is not a real regression

Trial speedups:  
`[1.067, 1.003, 0.545, 0.481, 1.200, 0.879, 0.865, 1.394, 0.775, 1.098]`

- Median **1.003×** (holds)
- Two outliers (**0.48×, 0.55×**) dominate the geometric mean
- Same-binary noise floor for this scenario previously reached **0.70×–1.34×** on only 3 trials

Strict all-scenario geo≥1.00 is **not a stable property** of this host+harness for single-file micros; batch multi-file scenarios are the reliable product signal.

---

## 5. Code changes vs `d4e1ff4fb` (production)

| File | Change |
|---|---|
| `HashEdgeMap` | `get`: localize values array, inline mask, skip atomic key load on null miss; `put` same-key = value replace only |
| `ParserATNSimulator` | Non-precedence emptiness via two atomic loads; single `s0.get()` in `getStartState`; keep `getStartState` hook for `ProfilingATNSimulator` |
| `DFAState` | `getTarget` / `setTarget` marked `final` for monomorphic edge walks |
| `IntervalSet` | `contains` specializes `n==1` / `n==2` (dominant in `sync`) |
| `BufferedTokenStream` | `nextTokenOnChannel` / `previousTokenOnChannel` skip `sync` when index is already buffered |

**Not changed (parity preserved):** warm `LA(1)` / `LT(1)` inlined hit paths; ATN freeze; HPPC remains off public/protected APIs.

---

## 6. Correctness

| Suite | Result |
|---|---|
| `runtime/Java` unit tests | **921 run, 0 fail, 0 error** |
| Harness syntax_errors across A/B | **0** |
| Profiling hook regression | Fixed: must not bypass `getStartState` (NPE in `ProfilingATNSimulator`) |

---

## 7. Intermediate runs (audit trail)

| Stamp | Delta | Overall geo | Notes |
|---|---|---:|---|
| `s2` `20260808T005757Z` | HashEdgeMap put only | 1.020× | 5 geo&lt;1; flame proved put irrelevant |
| `s3` `20260808T011858Z` | + get / channel / contains micro-opts | 0.994× | At noise floor; mid-flight before adaptivePredict polish |
| `s4` `20260808T014104Z` | Full deep-opt set | **1.054×** | Authoritative product result |

---

## 8. Artifacts

| Kind | Path |
|---|---|
| Trial TSV/logs | `perf-testsuite/results/{base,tgt}-s4-trial*-20260808T014104Z.*` |
| JSON detail | `perf-testsuite/results/detail-s4-20260808T014104Z.json` |
| Flame (prior) | `/tmp/antlr4-flame-strict2-20260808T005757Z/{base,tgt}-cpu.{html,collapsed}` |
| Worktrees | `/tmp/antlr4-ab-strict4-20260808T014104Z/{base,tgt}` |

---

## 9. Bottom line

1. **Stricter A/B completed** (10 trials, higher warmup/iters, order-balanced, private m2).  
2. **Flame graphs** showed no structural put-path regression; real cost is `get` / adaptivePredict / closure / contains.  
3. **Deep opts** implemented on those frames; tests green.  
4. **Product result:** overall **+5.4%** geo vs `d4e1ff4fb`, batch **+5.1%**, all batch scenarios geo ≥ 1.00×; single remaining geo flag is median-hold noise on a micro scenario.
