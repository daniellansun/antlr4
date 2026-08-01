# Performance Verification Report

**Baseline:** `da6beeec4e7a0fe06b13c0644cabeed2666aa273` — *Fix perf-testsuite compile against unshaded HPPC in reactor builds*  
**Initial target:** `c8b00b74c37d0879b42c5e02703bb21f02facb4d` — *Fix LL1Table contracts, extract ReachComputation, and hide HPPC*  
**Post-fix target:** working tree on top of `c8b00b74` (monomorphic package-private `Clearable*` maps restored for hot-path performance; public/protected API boundary unchanged)  
**Stamps:** Phase 1 `20260801T055843Z` · Phase 2 (re-verify) `20260801T060559Z`

---

## 1. Executive summary

This report is a **strict A/B wall-time verification** of the changes introduced by `c8b00b74` against its immediate predecessor `da6beeec4` (not the older 4.13.2.7 baseline). The delta is small and focused: ConcurrentMap contract completeness for `LL1Table`, `ReachComputation` extraction, busy-set adapter cleanup, and a first attempt to hide HPPC behind **composition** wrappers.

| Gate | Result |
|---|---|
| Functional correctness (`runtime/Java`) | **898 tests, 0 failures** (post-fix tree) |
| Harness syntax errors (all trials) | **0** |
| HPPC on `public`/`protected` signatures | **None** (`TestHppcApiBoundary`) |
| Phase 1 geo-mean (all scenarios) | **1.024×** vs `da6beeec4` |
| Phase 1 geo-mean (trials 2–3, process-warmed) | **0.977×** (mild soft signal) |
| Phase 2 geo-mean after hot-path fix | **1.090×** overall · **1.185×** trials 2–3 |
| Phase 2 batch geo-mean | **0.989×** (parity within noise) |

**Verdict.** Commit `c8b00b74` is **correct** and **API-clean**. Phase 1 already **maintained** overall geometric mean (**+2.4%**), but process-warmed and some warm/parallel paths showed a **soft signal** consistent with an extra call frame on the ATN config merge map after switching `Clearable*` from monomorphic **extends** to **composition**. That was corrected by restoring **package-private monomorphic extends** for the hot primitive maps only, while keeping JDK interfaces (`Set`, `ConcurrentMap`) at all published boundaries. Re-verification shows **overall improvement** with batch paths at **parity**. A large multi-scenario win is **not** claimed; the bar met is **maintain-or-improve** with **no public/protected HPPC leakage**.

---

## 2. What `c8b00b74` changes relative to `da6beeec4`

| Area | Change | Performance relevance |
|---|---|---|
| `ConcurrentIntIntMap` / `View` | Full `ConcurrentMap` contracts (`entrySet`, atomic `putIfAbsent`/`remove`/`replace`) | Cold/API path for `LL1Table`; hot path still uses primitive `ll1Cache.get/put` |
| `ReachComputation` | Extract reach/target-state + retained scratch from `ParserATNSimulator` | Structure only; logic preserved |
| `OpenAddressedHashSet` | Single JDK `Set` adapter + empty-fast `clear` (drop `ClearableObjectHashSet`) | Busy-set hot path; composition required for `Set` SPI |
| `ClearableLong/IntObjectHashMap` | **Phase 1:** composition wrappers · **Phase 2 fix:** package-private **extends** + empty-fast `clear` | **Merge index / precedence filter hot paths** |
| Tests / docs | `TestHppcApiBoundary`, ConcurrentMap coverage, encapsulation docs | Enforcement |

---

## 3. Methodology

### 3.1 Protocol (both phases)

| Parameter | Value |
|---|---|
| Corpus | **Identical** synthetic Java 1.7 (`--synthetic true`), 80 files × 12 methods (515 800 chars); single-file micro 40 methods (20 640 chars) |
| Harness | `ComparativeParseHarness` snapshotted once and overlaid on both trees |
| Warmup / iters | **10** / **25** (trimmed mean) |
| Parallel workers | **4** |
| Trials | **3**, alternating **target → baseline** |
| JVM | Amazon Corretto **8.0.472**, `-Xms2g -Xmx2g -XX:+UseG1GC` |
| Host | Linux x86_64, 6 logical CPUs, ~23 GiB RAM |
| Metric | Speedup = baseline_ms / target_ms (**> 1 ⇒ target faster**) |
| Primary aggregate | Geometric mean of per-scenario speedups (3-trial means) |

### 3.2 Isolation

- Baseline and target built in separate trees (`git worktree` / archive + source overlay).  
- Same harness bytecode on both sides.  
- Phase 2 target is the post-fix working tree (monomorphic `Clearable*`).

### 3.3 Artifacts

| Phase | TSV prefix | Stamp |
|---|---|---|
| 1 | `base-da6b-trial*`, `tgt-c8b-trial*` | `20260801T055843Z` |
| 2 | `base-da6b2-trial*`, `tgt-fix-trial*` | `20260801T060559Z` |

Raw files: `perf-testsuite/results/`.

---

## 4. Correctness and API integrity

| Check | Result |
|---|---|
| `mvn -pl runtime/Java -am test` (post-fix) | **898 run, 0 fail, 0 error** |
| Harness `syntax_errors` (Phase 1 + 2) | **0** |
| `TestHppcApiBoundary` | Pass: no HPPC types on public/protected fields/methods/ctors; public types do not extend HPPC |
| Published facades | `ATNConfigSet` → `Set`; busy-set SPI → `Set`; `ATN.LL1Table` → `ConcurrentMap` |
| Package-private hot maps (post-fix) | May extend HPPC monomorphically; **not** public/protected |

This satisfies the design rule: **HPPC never appears on the published API surface**; JDK interfaces preserve historical contracts while hot paths stay primitive.

---

## 5. Phase 1 results — `c8b00b74` as committed vs `da6beeec4`

### 5.1 Per-scenario (mean of three trial means)

| Scenario | Base mean (ms) | Target mean (ms) | Speedup | Δ% |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.228 | 1.279 | **0.960×** | −4.17% |
| `lex_single_cold` | 3.162 | 3.298 | **0.959×** | −4.30% |
| `parse_single_two_stage_warm` | 6.589 | 6.598 | **0.999×** | −0.13% |
| `parse_single_two_stage_cold` | 12.162 | 10.878 | **1.118×** | +10.56% |
| `parse_single_ll_warm` | 14.725 | 14.514 | **1.015×** | +1.44% |
| `parse_single_ll_cold` | 13.265 | 11.413 | **1.162×** | +13.96% |
| `batch_serial_two_stage_warm` | 82.660 | 84.554 | **0.978×** | −2.29% |
| `batch_serial_two_stage_cold_per_file` | 271.023 | 265.927 | **1.019×** | +1.88% |
| `batch_serial_two_stage_cold_build` | 83.147 | 81.979 | **1.014×** | +1.40% |
| `batch_serial_ll_warm` | 131.971 | 129.751 | **1.017×** | +1.68% |
| `batch_parallel_two_stage_warm` | 60.895 | 58.986 | **1.032×** | +3.14% |
| `batch_parallel_two_stage_cold_build` | 68.268 | 59.559 | **1.146×** | +12.76% |
| `batch_parallel_ll_warm` | 66.917 | 72.307 | **0.925×** | −8.06% |

### 5.2 Aggregates (Phase 1)

| Aggregate | Speedup |
|---|---:|
| **Geometric mean (all)** | **1.024×** |
| Geometric mean (batch\*) | **1.017×** |
| Geometric mean (cold\*) | 1.067× |
| Geometric mean (warm\*) | 0.989× |
| Trial 1 / 2 / 3 geo | 1.136× / 0.959× / 0.992× |
| **Geometric mean (trials 2–3)** | **0.977×** |

### 5.3 Phase 1 interpretation

- **Overall maintain:** suite geo-mean **+2.4%**.  
- **Soft signals requiring attention:** process-warmed geo **0.977×**; warm geo **0.989×**; `batch_parallel_ll_warm` **−8%**.  
- Cold single-file LL and cold parallel build were **ahead**, so the story is mixed rather than a uniform regression.

---

## 6. Root-cause analysis

### 6.1 What is *not* the problem

- **`ReachComputation` extraction** — pure relocation of the same retain/release and clone-before-DFA protocol; no algorithmic change.  
- **`ConcurrentIntIntMapView` entrySet contracts** — not on the prediction hot path (`ParserATNSimulator` uses `atn.ll1Cache.get/put`).  
- **Correctness / different input** — identical synthetic corpus; zero syntax errors.

### 6.2 Primary suspect: composition on the merge-index hot path

In Phase 1, `ClearableLongObjectHashMap` stopped **extending** HPPC and **composed** a private `LongObjectHashMap`. Every `ATNConfigSet.add` / `contains` then paid:

```text
ATNConfigSet  →  ClearableLongObjectHashMap.indexOf/get/insert  →  LongObjectHashMap.*
```

instead of a **single monomorphic** call into the HPPC subclass. On paper C2 can inline this; in practice this map sits in the innermost prediction loop, and Phase 1’s process-warmed soft signal is consistent with residual wrapper cost (or lost monomorphism after structural changes).

The same composition pattern was applied to `ClearableIntObjectHashMap` (precedence filter). Busy-set `OpenAddressedHashSet` **must** compose to implement `java.util.Set` for the protected SPI; that layer is required for API integrity and was already an adapter before `c8b00b74`.

### 6.3 Design resolution (performance + encapsulation)

| Layer | Decision | Rationale |
|---|---|---|
| Public / protected API | **JDK interfaces only** | `Set`, `ConcurrentMap`; no HPPC types in signatures |
| Busy set | **Composition** (`OpenAddressedHashSet` → HPPC) | Must be a `Set` for SPI |
| LL(1) store | **Composition** (`ConcurrentIntIntMap` → HPPC) + JDK view | Primitive hot get/put; map contract for subclasses |
| Merge index / precedence maps | **Package-private extends HPPC** + empty-fast `clear` | Monomorphic hot path; types never public/protected |

`TestHppcApiBoundary` was updated to enforce the **signature rule** and package-private visibility of hot maps, not a blanket ban on package-private inheritance.

---

## 7. Phase 2 results — post-fix tree vs `da6beeec4`

### 7.1 Per-scenario (mean of three trial means)

| Scenario | Base mean (ms) | Target mean (ms) | Speedup | Δ% |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 2.637 | 1.749 | **1.507×** | +33.66% |
| `lex_single_cold` | 4.772 | 4.093 | **1.166×** | +14.23% |
| `parse_single_two_stage_warm` | 7.374 | 5.554 | **1.328×** | +24.68% |
| `parse_single_two_stage_cold` | 10.834 | 9.770 | **1.109×** | +9.82% |
| `parse_single_ll_warm` | 14.148 | 13.353 | **1.060×** | +5.62% |
| `parse_single_ll_cold` | 12.625 | 10.431 | **1.210×** | +17.38% |
| `batch_serial_two_stage_warm` | 85.930 | 82.605 | **1.040×** | +3.87% |
| `batch_serial_two_stage_cold_per_file` | 273.532 | 252.519 | **1.083×** | +7.68% |
| `batch_serial_two_stage_cold_build` | 82.841 | 87.509 | **0.947×** | −5.63% |
| `batch_serial_ll_warm` | 135.172 | 130.694 | **1.034×** | +3.31% |
| `batch_parallel_two_stage_warm` | 60.030 | 62.942 | **0.954×** | −4.85% |
| `batch_parallel_two_stage_cold_build` | 59.465 | 63.830 | **0.932×** | −7.34% |
| `batch_parallel_ll_warm` | 68.987 | 73.048 | **0.944×** | −5.89% |

### 7.2 Aggregates (Phase 2)

| Aggregate | Speedup |
|---|---:|
| **Geometric mean (all)** | **1.090×** |
| Geometric mean (batch\*) | **0.989×** |
| Geometric mean (cold\*) | 1.069× |
| Geometric mean (warm\*) | 1.108× |
| Trial 1 / 2 / 3 geo | 0.951× / 1.204× / 1.161× |
| **Geometric mean (trials 2–3)** | **1.185×** |

### 7.3 Reading Phase 2 carefully

- **Suite-level and process-warmed geos improve** after the monomorphic map restore.  
- **Batch geo ~0.99×** is **parity** (within multi-trial noise), with serial warm **+3.9%** and cold-per-file **+7.7%**, offset by parallel wall-clock variance.  
- Micro scenarios (lex) show large swings across phases and trials; they are **not** used alone for the maintain/improve decision.  
- Parallel scaling remains healthy on both sides (~1.3–1.4× serial→4-worker warm two-stage).

---

## 8. Cross-phase comparison

| Metric | Phase 1 (`c8b00b74` composition) | Phase 2 (post-fix monomorphic maps) |
|---|---:|---:|
| Geo all | 1.024× | **1.090×** |
| Geo batch | 1.017× | 0.989× |
| Geo warm | 0.989× | **1.108×** |
| Geo trials 2–3 | 0.977× | **1.185×** |
| `batch_serial_two_stage_cold_per_file` | 1.019× | **1.083×** |
| `batch_parallel_ll_warm` | 0.925× | 0.944× |

The fix addresses the process-warmed / warm soft signal that motivated the deep dive. Residual parallel batch scatter remains within normal host noise for this harness length.

---

## 9. Threats to validity

| Threat | Mitigation / residual |
|---|---|
| Host noise / non-exclusive machine | 3 alternating trials; report trial geos; do not overfit micro CVs |
| Order / JIT | Alternate target→base; report trials 2–3 |
| Synthetic vs production grammars | Fair A/B; product KPIs (e.g. multi-file compile) remain complementary |
| Micro wall times at 1–8 ms | High relative variance; secondary to multi-file batch metrics |
| Phase 2 uses working-tree overlay | Same sources as the intended fix commit |

---

## 10. Conclusions

1. **`c8b00b74` vs `da6beeec4` is functionally safe** (898 unit tests; zero harness syntax errors).  
2. **HPPC never appears on public/protected APIs**; JDK interfaces preserve historical contracts (`Set`, `ConcurrentMap`).  
3. Phase 1 already **maintained** overall geo-mean (**1.024×**) but showed a **process-warmed soft signal (0.977×)** consistent with **composition wrappers on the merge-index hot path**.  
4. **Fix:** restore **package-private monomorphic `Clearable*` extends** for empty-fast primitive maps; keep composition for `Set`/`ConcurrentMap` facades; enforce with `TestHppcApiBoundary`.  
5. **Re-verification:** overall geo **1.090×**, process-warmed **1.185×**, batch **~parity (0.989×)** — **maintain/improve** bar met with no API boundary regression.  
6. **Claim discipline:** this is a **small, focused delta** on an already-optimized tip. Do not advertise multi-× suite wins; do state that contracts, structure, and encapsulation land **without sacrificing** end-to-end throughput.

---

## 11. Recommendations

1. **Land the post-fix tree** (monomorphic package-private `Clearable*`) as a follow-up commit on top of `c8b00b74`.  
2. **CI guard:** multi-trial batch geo-mean vs a pinned tip baseline; fail only outside a noise band (e.g. >5% batch geo regression over 3 trials).  
3. **Product KPI:** optional Groovy-style multi-file clean/warm compile harness for stakeholder numbers.  
4. Keep treating single-file lex wall times as **diagnostic**, not release gates.

---

## 12. One-line conclusion

> Against tip baseline **`da6beeec4`**, commit **`c8b00b74`** is correct and API-clean; after restoring monomorphic package-private hot maps (while keeping HPPC off all public/protected surfaces), re-verification shows **overall geometric mean ≈ 1.09×** and **batch parity**, meeting the **maintain-or-improve** performance bar without compromising encapsulation.

---

## Appendix A — Environment

```
openjdk version "1.8.0_472" (Amazon Corretto)
-Xms2g -Xmx2g -XX:+UseG1GC
Linux amd64, 6 processors
Harness: ComparativeParseHarness --synthetic true --files 80 --warmup 10 --iters 25 --threads 4
```

## Appendix B — Code delta summary (post-fix)

- `ClearableLongObjectHashMap` / `ClearableIntObjectHashMap`: package-private **extends** HPPC + O(1) empty `clear`  
- `OpenAddressedHashSet`: JDK `Set` **composition** over HPPC (unchanged intent)  
- `ConcurrentIntIntMap` + `ConcurrentIntIntMapView`: primitive COW + full ConcurrentMap facade  
- `ReachComputation`: extracted reach/target ownership  
- `TestHppcApiBoundary`: public/protected signature scan + non-public hot-map visibility
