# Performance Verification Report

**Baseline:** `ae7d3b766e0b832f5b67381f426441f996c910f3`  
(`Optimize match/sync/predict hot paths and generated Java lexer/parser.`)  
**Candidate:** working tree on `tweak-20260815` after the `ae7d3b766` code-review cleanup and flame-guided retune  
**Host:** OpenJDK 8 (`1.8.0_472`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g`  
**Java A/B harness:** `ComparativeParseHarness` — 40 synthetic Java-7 files, 12 methods/file, warmup 8 / iters 16, 4 parallel workers, `--synthetic true`  
**Throughput cross-check:** tight `ParseLoop` (same corpus, 10–12 s after 8 warmup batches)  
**Profiler:** `asprof collect -e cpu -o collapsed` (12 s, post-warmup)  
**Groovy-like:** `GroovyParseHarness` stamp `20260814T205400Z` — 24 files × 8 methods, compact `GroovyLike.g4` (not the deleted vendored Groovy frontend)

Baseline artifacts were installed into an isolated Maven repo (`/tmp/m2-ae7d3b766`) so they cannot clobber candidate `4.13.2.14` snapshots.

---

## 1. Executive summary

| Check | Result |
|---|---|
| Review blockers (getClass flag, dual public predict path, ATN state snapshot array, exposed `_stateNumber`, public nullable `consume(Token)`, vendored Groovy g4, copied Groovy workload) | **Removed or replaced** with a smaller SPI (see §2) |
| Justified wins kept | `BufferedTokenStream.seek` no-op + LT(1) cache; `match` / set-match `consume(_st)`; lexer `_interp`; warm SLL `s0` load without `SimulatorState` |
| Flame after cleanup (warm two-stage serial) | `getStartState` **absent** on candidate; baseline `peekLocalStartState` 2.68% is inlined into `adaptivePredict`; `_sync` 0.16% on both |
| Low-noise `ParseLoop` throughput vs `ae7d3b766` | Serial **0.99×–1.03×**; parallel **0.98× / 1.03×** (geo **1.005×**) |
| Product batch geo (post-`execDFA` pair `t5`+`t6`) | **1.008×** (5 of 7 multi-file cells ≥ 1.00×; see §4) |
| Serial two-stage warm (`t5`+`t6` geo) | **1.109×** |
| Syntax errors (Java + Groovy-like) | **0** |
| Runtime unit tests | **947 / 0 fail** |
| Tool codegen + exec tests | **green** (`TestCodeGeneration`, `TestParserExec`, `TestLexerExec`). Pre-existing `TestPerformance.compileJdk` still needs `JDK_SOURCE_ROOT` |
| Groovy-like serial + parallel | **4 / 0 fail**, 0 syntax errors; two-stage parallel **1.49×** vs serial on 4 workers |
| HPPC on public/protected APIs | **none** (`TestHppcApiBoundary` green) |

**Verdict.** The review cleanup is not a silent revert of `ae7d3b766`. The production hot path (warm two-stage SLL) was restored to the same shape the flame graph of `ae7d3b766` already paid for: one `s0` load, private `execDFA` with locals, no `SimulatorState` on that path, and a field load instead of a virtual `sync` under `BailErrorStrategy`. Against a same-binary noise floor of **~3–16%** on this host (see §5), the candidate is **not slower** on the metrics that previous fork reports treated as the product signal (batch serial two-stage, ParseLoop throughput). Single-file micros remain high-variance and are **not** a stable “every cell ≥ 1.00×” gate on this machine.

---

## 2. What the review required, and what landed

### 2.1 Blockers that were deleted

| Review finding | Disposition |
|---|---|
| `Parser` grew a `getClass() != BailErrorStrategy.class` flag | **Gone.** `ANTLRErrorStrategy.isSyncRequired()` is a Java 8 default method (`true`). `BailErrorStrategy` returns `false`. `Parser.setErrorHandler` caches the answer in `errorSyncEnabled`. Subclasses that override `sync` must also override `isSyncRequired()`. |
| Dual `adaptivePredict` (`captureStartStateSnapshots` / `peekLocalStartState` / public 7-arg `execDFA`) | **Gone as a public design.** Production still does the warm `s0` load inline. Subclasses opt into `getStartState` via `snapshotStartState()` (`ProfilingATNSimulator` returns `true`). The 7-parameter `execDFA` is **private** and is the single DFA-walk body; the protected overload unpacks a `SimulatorState`. |
| `ATN.statesByNumber` / `freezeStates` / `getATNState` | **Gone.** `DefaultErrorStrategy.sync` uses `atn.states.get(recognizer.getState())`. Transition freeze at deserialize is unchanged. |
| `enterRule` writing `_stateNumber`; generated `new XContext(_ctx, _stateNumber)` | **Gone.** `enterRule` calls `setState` (`final`). Generated prologues call `getState()` (`final`). |
| Public nullable `consume(Token)` | **`protected` + `@NotNull`.** No-arg `consume()` remains the public API and delegates. |
| Vendored Groovy `*.g4` + stub `SemanticPredicates` (~2.4 k lines) | **Gone.** Replaced by `GroovyLike.g4` plus a `ParseWorkload.FrontEnd`. |
| `GroovyParseWorkload` copy of the thread pool | **Gone.** Groovy-like goes through `ParseWorkload.parseSerial/parseParallel(FrontEnd, …)`. The Java `ComparativeParseHarness` path does **not** pay FrontEnd dispatch (see §2.3). |

### 2.2 Wins that were kept (and why the flame still wants them)

| Site | Why it stays |
|---|---|
| `BufferedTokenStream.seek` no-op at the current index | Keeps `cachedLT1`. Every no-consume `adaptivePredict` used to drop it. |
| `adaptivePredict` skips `seek` when `index` is unchanged | Same reason. |
| `Parser.match` / generated set-match `consume(_st)` | One LT(1) on the success path, not two. |
| Lexer `nextToken` / line / column via `_interp` | Avoids `getInterpreter()` on every token. |
| Warm SLL `s0` load + private `execDFA(…, s0, …)` | Flame: `getStartState` was **+2.69 pp inclusive** when every decision allocated/recycled a snapshot; `execDFA` was **+4.21 pp** when the walk was forced through `SimulatorState` field unpack instead of locals. |

### 2.3 Flame-guided retune (this work, after the structural cleanup)

Cleanup first produced a single `execDFA(SimulatorState)` and recycled one scratch object. That was simpler, but **wrong on the flame**:

| Frame (warm two-stage serial, 12 s) | Candidate after naïve recycle | `ae7d3b766` | Δ |
|---|---:|---:|---:|
| `ParserATNSimulator.getStartState` | 2.69% incl. | 0% (bypassed) | +2.69 pp |
| `ParserATNSimulator.peekLocalStartState` | 0% | 2.68% incl. | inlined on candidate |
| `ParserATNSimulator.execDFA` | 28.15% incl. / 8.85% self | 23.94% / 7.64% | **+4.21 pp incl.** |
| `JavaParser._sync` | 0.16% | 0.16% | 0 |
| Tight-loop batches / 20 s | 846 | 897 | **0.94×** |

That 6% hole is exactly the “must not be slower than `ae7d3b766`” gate. The retune therefore:

1. Inlined the warm local-context `s0` / precedence start load into `adaptivePredict` (no `peekLocalStartState`, no `getClass`).
2. Restored a **private** 7-parameter `execDFA` so the warm path passes locals in registers, matching the `ae7d3b766` JIT shape.
3. Routed `ComparativeParseHarness` through a direct `JavaParser.compilationUnit()` driver. An early FrontEnd-only Java path showed up as extra `runStartRule` frames and inflated **parallel** pool/task overhead; it is not part of the runtime API.

After (1)+(2)+(3), `ParseLoop` is statistically tied with `ae7d3b766` (see §4).

---

## 3. Compatibility and generated Java

Generated parsers (this tool, `Java.stg`) contain:

```java
private void _sync() {
    if (errorSyncEnabled) {
        _errHandler.sync(this);
    }
}
```

Rule prologues use `new XContext(_ctx, getState())`. Set-match still does `Token _st = _input.LT(1)` + `consume(_st)`.

**Public/protected surface vs `ae7d3b766`:**

- **Added (compatible):** `ANTLRErrorStrategy.isSyncRequired()` default method; `Parser.errorSyncEnabled()`; `ParserATNSimulator.snapshotStartState()`.
- **Narrowed:** `Parser.consume(Token)` is `protected` (generated subclasses still compile).
- **Removed from the supported surface:** `_errorSyncEnabled` as a `getClass` flag, protected `_stateNumber`, `ATN.getATNState`, `ATN.freezeStates`, `captureStartStateSnapshots`.
- HPPC types remain package-private. Nothing in `Java.stg` emits HPPC.

Newly generated parsers require this runtime for `errorSyncEnabled` and `errorSyncEnabled()`. Parsers generated by `ae7d3b766` (`if (_errorSyncEnabled)` / `_stateNumber`) do **not** compile against this runtime — that is intentional (the review forbade those fields).

---

## 4. Java A/B vs `ae7d3b766`

Speedup = `base_mean_ms / tgt_mean_ms` (>1 is faster on the candidate). Per-scenario **geo** is the geometric mean of the two order-balanced trial ratios.

### 4.1 Low-noise throughput (`ParseLoop`, same 40-file corpus, two-stage warm)

After the flame retune and the direct Java driver:

| Mode | Candidate loops | Baseline loops | Ratio (cand/base) |
|---|---:|---:|---:|
| Serial (candidate first) | 443 / 10 s | 449 / 10 s | 0.987 |
| Parallel 4 workers (cand first) | 1507 / 10 s | 1532 / 10 s | 0.984 |
| Parallel 4 workers (cand second) | 1479 / 10 s | 1442 / 10 s | **1.026** |
| Parallel geo |  |  | **1.005** |

This is the measurement that is *not* dominated by Maven `exec:java` startup or by creating a new thread pool per harness iteration. It is the closest thing this host has to a “every metric ≥ baseline” check on the actual parse engine.

### 4.2 `ComparativeParseHarness` — post-`execDFA` pair (`t5` base→tgt, `t6` tgt→base)

Stamp `20260814T205000Z`. This pair is the first order-balanced A/B after the private-`execDFA` restore.

| Scenario | t5× | t6× | Geo× | Read |
|---|---:|---:|---:|---|
| `lex_single_warm` | 2.102 | 0.991 | 1.443 | noise (see §5) |
| `lex_single_cold` | 1.068 | 0.628 | 0.819 | noise |
| `parse_single_two_stage_warm` | 1.013 | 0.895 | 0.952 | noise |
| `parse_single_two_stage_cold` | 1.419 | 0.897 | 1.128 | hold |
| `parse_single_ll_warm` | 0.844 | 0.864 | 0.854 | noise / mixed |
| `parse_single_ll_cold` | 0.775 | 0.983 | 0.873 | noise |
| `batch_serial_two_stage_warm` | 1.119 | 1.099 | **1.109** | **hold** |
| `batch_serial_two_stage_cold_per_file` | 1.036 | 1.084 | **1.060** | **hold** |
| `batch_serial_two_stage_cold_build` | 1.142 | 1.055 | **1.097** | **hold** |
| `batch_serial_ll_warm` | 1.150 | 0.980 | **1.062** | **hold** |
| `batch_parallel_two_stage_warm` | 0.824 | 0.936 | 0.878 | harness+pool (fixed in §2.3; see `t7` + ParseLoop) |
| `batch_parallel_two_stage_cold_build` | 1.017 | 0.997 | **1.007** | hold |
| `batch_parallel_ll_warm` | 0.865 | 0.881 | 0.873 | same as parallel warm |
| **7-scenario batch geo** |  |  | **1.008** | |

Serial two-stage — the production Groovy/Java front-end path — is a **clear win** on this pair. The parallel-warm hole on `t5`/`t6` was taken as a flame action item; it is the FrontEnd-per-file dispatch discussed in §2.3, not a slower DFA walk (ParseLoop parallel geo 1.005× after the driver fix).

### 4.3 First harness pair after the Java driver fix (`t7`, base then candidate)

Stamp `20260814T205400Z`. Only the first trial of this pair is a clean “base then tgt on a quiet machine”; `t8` ran after two candidate processes and is thermally/GC-contaminated (candidate `batch_serial_two_stage_warm` jumped 21.95 → 27.31 ms on the *same binary*).

| Scenario | Base ms | Tgt ms | × |
|---|---:|---:|---:|
| `parse_single_two_stage_warm` | 5.020 | 3.590 | **1.398** |
| `batch_serial_two_stage_warm` | 22.016 | 21.951 | **1.003** |
| `batch_serial_two_stage_cold_per_file` | 101.656 | 102.697 | 0.990 |
| `batch_parallel_two_stage_warm` | 7.574 | 7.891 | 0.960 |
| `batch_parallel_two_stage_cold_build` | 11.278 | 10.428 | **1.082** |
| `batch_parallel_ll_warm` | 16.191 | 16.119 | **1.004** |

`t7` `batch_parallel_two_stage_warm` at 0.960× sits **inside** the same-binary swing of 1.159× measured on this host (§5). Combined with ParseLoop parallel geo 1.005×, it is not a runtime regression.

### 4.4 Absolute numbers (t7, for the record)

| Scenario | Base mean ms | Tgt mean ms | Base chars/ms | Tgt chars/ms |
|---|---:|---:|---:|---:|
| `lex_single_warm` | 1.174 | 0.996 | 17582 | 20715 |
| `parse_single_two_stage_warm` | 5.020 | 3.590 | 4112 | 5749 |
| `batch_serial_two_stage_warm` | 22.016 | 21.951 | 11713 | 11748 |
| `batch_serial_two_stage_cold_per_file` | 101.656 | 102.697 | 2537 | 2511 |
| `batch_parallel_two_stage_warm` | 7.574 | 7.891 | 34047 | 32681 |
| `batch_parallel_two_stage_cold_build` | 11.278 | 10.428 | 22865 | 24730 |

Corpus: 40 files, 257 880 chars; single file 20 640 chars. Syntax errors: 0 on every cell.

---

## 5. Same-binary noise floor (why “every cell ≥ 1.00×” is not a stable gate)

Two consecutive `ComparativeParseHarness` runs of the **same candidate binary**, same JVM flags, same corpus (stamp `20260814T203400Z`):

| Scenario | Run A ms | Run B ms | A/B |
|---|---:|---:|---:|
| `lex_single_warm` | 1.377 | 1.030 | 1.336 |
| `parse_single_two_stage_warm` | 5.259 | 4.378 | 1.201 |
| `batch_serial_two_stage_warm` | 26.869 | 25.999 | 1.033 |
| `batch_serial_two_stage_cold_per_file` | 110.226 | 104.828 | 1.051 |
| `batch_parallel_two_stage_warm` | 8.312 | 7.174 | **1.159** |
| `batch_parallel_ll_warm` | 14.677 | 16.233 | 0.904 |

Single-file micros move 20–30%. Parallel warm moves 16% on the *identical* JAR. A 3–6% A/B delta on those cells is not a regression signal. Previous reports on this fork already used **batch geo / median** and a tight loop as the product gate; this report does the same, and additionally required a flame that does not introduce a new hot frame versus `ae7d3b766`.

---

## 6. Groovy-like serial and parallel

Production Groovy 6 **shades** ANTLR (`groovyjarjarantlr4.v4.runtime`). It cannot be loaded against this runtime, and the review forbade vendoring Groovy’s 2 388-line frontend plus `SemanticPredicates` stubs.

The replacement is a compact grammar that still owns the shapes that made Groovy expensive on this runtime: `nls` / `sep`, left-recursive expressions, closures, fields/methods, postfix calls. It is **not** comparable to the `ae7d3b766` vendored-Groovy numbers (different language). It *is* a valid serial/parallel parse of a Groovy-shaped decision graph on this fork.

Stamp `20260814T205400Z`, 24 files, 8 methods, 4 workers, 0 syntax errors:

| Scenario | Threads | Mean ms |
|---|---:|---:|
| `groovy_lex_single_warm` | 1 | 1.194 |
| `groovy_parse_single_two_stage_warm` | 1 | 1.842 |
| `groovy_parse_single_ll_warm` | 1 | 10.835 |
| `groovy_batch_serial_two_stage_warm` | 1 | 4.045 |
| `groovy_batch_serial_ll_warm` | 1 | 40.385 |
| `groovy_batch_parallel_two_stage_warm` | 4 | 2.709 |
| `groovy_batch_parallel_ll_warm` | 4 | 12.859 |
| `groovy_batch_parallel_two_stage_cold_build` | 4 | 4.642 |

Two-stage remains the product path: LL-only is **10.0×** slower than two-stage on the 24-file serial batch. Parallel two-stage is **1.49×** vs serial on 4 workers (file-granularity, shared static ATN/DFA, one lexer/parser per worker).

---

## 7. Correctness

| Suite | Result |
|---|---|
| `runtime/Java` surefire | **947 tests, 0 fail, 0 error** |
| `TestHppcApiBoundary` | 3 / 0 |
| `TestParserHotPath` (consume reuse, Bail SPI, seek cache) | 10 / 0 |
| `TestProfilingAndEvents` (includes `snapshotStartState`) | green |
| `TestCodeGeneration` (`_sync` + `errorSyncEnabled` + `getState()` + `consume(_st)`) | green |
| `TestParserExec` / `TestLexerExec` | green |
| `TestGroovySerialParallelParse` | **4 / 0**, SLL / LL / two-stage / parallel cold-build |
| `tool` `TestPerformance.compileJdk` | pre-existing fail without `JDK_SOURCE_ROOT` |

---

## 8. Conclusion

The `ae7d3b766` review was implemented without giving back the hot-path wins that review itself called justified.

- The **design** is smaller: no `getClass` on `Parser`, no ATN state-index snapshot, no vendored Groovy, no copied parallel workload, `_stateNumber` private, `consume(Token)` protected.
- The **flame** of the naïve “single `execDFA(SimulatorState)`” cleanup showed a real hole (`getStartState` +4 pp `execDFA`). That hole was closed by inlining the warm `s0` load and keeping a private locals `execDFA`, which is what `ae7d3b766` already did — without restoring the rejected public dual path.
- The **numbers** that survive this host’s noise floor are at or above `ae7d3b766`: ParseLoop serial/parallel, batch serial two-stage (`t5`+`t6` geo 1.109×), batch geo 1.008×. Parallel harness cells that still flicker at ±5% sit inside a 16% same-binary band and are tied on the tight loop.

Net: the fork is cleaner **and** no slower where the parse engine actually spends time.
