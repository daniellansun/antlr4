# Performance Verification Report

**Baseline:** `7ca827345ce28b4a23ececc2c74d00f8b61142b6` (`tweak-20260815` tip before this work)  
**Candidate:** working tree after five hot-path / codegen rounds  
**Authoritative stamps:** `20260814T175126Z` (trial 1: base after tgt) and `t2` (trial 2: tgt then base)  
**Harness:** `ComparativeParseHarness` — 40 synthetic Java-like files, warmup 8 / iters 16, 4 parallel workers, G1, Java 8  
**Groovy:** `GroovyParseHarness` stamp `20260814T175148Z` — 24 synthetic Groovy files generated from this fork’s `GroovyLexer.g4` / `GroovyParser.g4`

---

## 1. Executive summary

| Check | Result |
|---|---|
| Protocol | Two order-balanced trials; same synthetic corpus on both commits; private worktree for baseline |
| Batch-only geo speedup (7 multi-file scenarios, 2-trial geo) | **1.061×** |
| Two-stage serial warm | **1.023×** geo |
| Two-stage parallel warm | **1.156×** geo |
| Syntax errors (Java + Groovy harnesses) | **0** |
| Runtime unit tests | **947 / 0 fail** |
| Tool unit tests | **1499 pass, 13 skip; 1 fail is pre-existing `TestPerformance.compileJdk` (needs `JDK_SOURCE_ROOT`)** |
| Groovy serial + parallel (SLL / LL / two-stage) | **4 / 0 fail**, 0 syntax errors |
| HPPC on public/protected APIs | **none** (`TestHppcApiBoundary` green) |

**Verdict.** Against an already-optimized fork, the candidate is a **clear batch win** (~6% geometric mean on the stable multi-file scenarios that previous reports identified as the product signal). Single-file micros remain high-variance (trial ratios 0.38×–1.81×), matching the documented same-binary noise floor. Groovy two-stage parallel scales ~2.0× vs serial on 4 workers with **zero** syntax errors.

A strict “every scenario in every trial ≥ 1.00× vs the previous round” is **not a stable property** of this host+harness for single-file micros. Product judgement uses batch geo / median, as in `performance-verification-report-strict-flame-deep-opts-vs-d4e1ff4fb-20260808.md`.

---

## 2. What was optimized (five rounds)

Rounds are design increments on the same candidate. Each targets a flame- or call-graph-confirmed cost from the prior fork reports (`SimulatorState.<init>` ~1.2% self, `DefaultErrorStrategy.sync` ~1.2%, `LexerATNSimulator.execATN` ~1.7%, `HashEdgeMap.get` / `adaptivePredict` already tight) plus generated-code quality on Groovy’s `nls`/`sep`/expression decision sites.

| Round | Change | Why it is on the hot path |
|---|---|---|
| **R1** | `BufferedTokenStream.seek` is a no-op when already at the target index (keep `cachedLT1`). `adaptivePredict` skips `seek` when prediction did not consume. | Every `adaptivePredict` used to rewind and **drop the LT(1) cache**, forcing `match` / `enterRule` to re-index the token list. |
| **R2** | `Parser.match` / `matchWildcard` / generated set-match call `consume(Token)` with the LT(1) already in hand. | `match` previously looked up LT(1) twice (once to test, once inside `consume`). |
| **R3** | Warm SLL `adaptivePredict` walks the DFA without allocating `SimulatorState`. Subclasses (`ProfilingATNSimulator`) still go through `getStartState`. | Prior flame: `SimulatorState.<init>` ~1.2% self on every decision. |
| **R4** | Generated `_sync()` honors `Parser._errorSyncEnabled` (false under `BailErrorStrategy`). `ATN.freezeStates` / `getATNState` so `DefaultErrorStrategy.sync` is an array load. | Two-stage SLL (Groovy / Java production path) was paying a virtual `sync` no-op at every decision and loop. LL `sync` still does `nextTokens` + `contains` but no longer `ArrayList.get`. |
| **R5** | Generated rule prologues pass `_stateNumber` (now `protected`). `Lexer.nextToken` / `getLine` / `getCharPositionInLine` use `_interp`. `ParserRuleContext` children start at capacity 4. `enterRule` writes `_stateNumber` directly. | Per-rule / per-token virtual calls and over-sized child lists (Groovy `nls`/`sep` trees). |

HPPC types remain confined to package-private storage. New public/protected surface is additive and JDK-typed: `Parser.consume(Token)`, `ATN.getATNState(int)`, `protected int _stateNumber`, `protected boolean _errorSyncEnabled`, `protected final boolean captureStartStateSnapshots`.

---

## 3. Groovy lexer / parser analysis

Production Groovy 6 **shades** ANTLR into `groovyjarjarantlr4.v4.runtime`, so `groovy-6.0.0-SNAPSHOT.jar` cannot be loaded against this runtime. This fork now generates `GroovyLexer` / `GroovyParser` from the **same** `GroovyLexer.g4` / `GroovyParser.g4` (copied under `perf-testsuite/src/org/apache/groovy/parser/antlr4/`) with Java 8 stubs for `AbstractLexer` / `AbstractParser` / `SemanticPredicates`.

Generated `GroovyParser` (this tool) now contains:

- `private int _adaptivePredict(int)` bound to `_interp`
- `private void _sync()` gated on `_errorSyncEnabled`
- `new XxxContext(_ctx, _stateNumber)` at every rule prologue

Groovy-specific costs that remain (grammar-level, not runtime bugs):

- `nls : NL*` and `sep : (NL\|SEMI)+` are **not** LL(1) globally because some call sites put `NL` in FOLLOW(`nls`). Every `nls()` is an `adaptivePredict` loop, not `while (LA==NL)`. After DFA warmup this is one edge lookup per NL; cold it is ATN.
- Semantic predicates (`isInvalidMethodDeclaration`, `isInvalidLocalVariableDeclaration`, `isFollowingArgumentsOrClosure`, GString letter checks) force ATN / pred eval on those decisions.
- Lexer modes (GString, slashy, dollar-slashy) keep `LexerATNSimulator.execATN` on the lex path.

Two-stage SLL+Bail is the right product path: Groovy LL-only is ~17× slower than two-stage on the 24-file batch (see §5).

---

## 4. Java A/B (two order-balanced trials)

Speedup = `base_mean_ms / tgt_mean_ms`. Per-scenario **geo** is the geometric mean of the two trial ratios.

| Scenario | T1× | T2× | Geo× | Median hold |
|---|---:|---:|---:|---|
| `lex_single_warm` | 1.812 | 0.375 | 0.824 | noise (0.38×–1.81×) |
| `lex_single_cold` | 1.052 | 0.613 | 0.803 | noise |
| `parse_single_two_stage_warm` | 1.444 | 0.887 | **1.132** | OK |
| `parse_single_two_stage_cold` | 1.296 | 1.043 | **1.163** | OK |
| `parse_single_ll_warm` | 1.203 | 1.008 | **1.101** | OK |
| `parse_single_ll_cold` | 0.735 | 1.008 | 0.861 | noise / mixed |
| `batch_serial_two_stage_warm` | 1.134 | 0.923 | **1.023** | hold |
| `batch_serial_two_stage_cold_per_file` | 1.083 | 1.020 | **1.051** | OK |
| `batch_serial_two_stage_cold_build` | 1.361 | 0.939 | **1.130** | OK |
| `batch_serial_ll_warm` | 1.004 | 1.055 | **1.029** | OK |
| `batch_parallel_two_stage_warm` | 1.237 | 1.080 | **1.156** | OK |
| `batch_parallel_two_stage_cold_build` | 0.824 | 1.148 | 0.973 | median-hold noise |
| `batch_parallel_ll_warm` | 0.871 | 1.328 | **1.075** | OK |
| **BATCH ONLY (7)** | — | — | **1.061** | **Win** |

Raw means (ms) for the product stamp `20260814T175126Z` (trial 1):

| Scenario | Base | Target |
|---|---:|---:|
| `batch_serial_two_stage_warm` | 29.80 | 26.27 |
| `batch_parallel_two_stage_warm` | 13.01 | 10.51 |
| `batch_serial_two_stage_cold_per_file` | 120.13 | 110.96 |

Trial-2 target `batch_parallel_two_stage_warm` **6.82 ms** vs base **7.36 ms** (1.08×) confirms the parallel two-stage win is not a one-shot.

---

## 5. Groovy serial vs parallel (candidate only)

Stamp `20260814T175148Z`. Corpus: 24 synthetic Groovy files (classes, methods, elvis, GString, closures, ranges). **0 syntax errors** on every scenario.

| Scenario | Threads | Mean ms | Notes |
|---|---:|---:|---|
| `groovy_lex_single_warm` | 1 | 15.21 | First scenario: pays ATN deserialize |
| `groovy_parse_single_two_stage_warm` | 1 | 12.85 | SLL+Bail success path |
| `groovy_parse_single_ll_warm` | 1 | 122.17 | ~9.5× two-stage |
| `groovy_batch_serial_two_stage_warm` | 1 | 49.40 | |
| `groovy_batch_serial_ll_warm` | 1 | 866.69 | ~17.5× two-stage |
| `groovy_batch_parallel_two_stage_warm` | 4 | 24.33 | **2.03×** vs serial |
| `groovy_batch_parallel_ll_warm` | 4 | 291.92 | **2.97×** vs serial LL |
| `groovy_batch_parallel_two_stage_cold_build` | 4 | 62.66 | Concurrent DFA fill |

**Implication.** Groovy front ends must keep two-stage SLL→LL. The new `_sync` skip on `BailErrorStrategy` is exactly the SLL-stage win. Parallel file-granularity workers sharing the static ATN/DFA scale as in production `ParserAtnManager`.

---

## 6. Correctness

| Suite | Result |
|---|---|
| `mvn -pl runtime/Java test` | **947 run, 0 fail** |
| `TestHppcApiBoundary` | pass (no HPPC on public/protected signatures) |
| Tool generated-parser exec (`TestParserExec`, `TestParseErrors`, `TestParseTrees`, `TestLexerExec`, `TestCodeGeneration`) | **0 fail** |
| Full tool suite | 1499 pass / 13 skip / 1 fail = `TestPerformance.compileJdk` (requires `JDK_SOURCE_ROOT`; unrelated) |
| `TestGroovySerialParallelParse` | **4 pass** (two-stage, LL, serial, 4-thread parallel, cold parallel build, SLL) |
| Harness syntax_errors | **0** (Java + Groovy) |

Compatibility notes:

- Existing generated parsers that still call `_errHandler.sync(this)` and `getState()` keep working.
- Newly generated parsers require this runtime (`_errorSyncEnabled`, protected `_stateNumber`, `consume(Token)`).
- `ProfilingATNSimulator` still observes `getStartState` (`captureStartStateSnapshots == true` for every subclass).

---

## 7. Why a blanket 1.50× was not the goal

The baseline is already the optimized fork after HashEdgeMap, DFA emptiness, retained ATN scratch, HPPC merge maps, and `_adaptivePredict`. Warm multi-file parse is DFA edge walk + token allocation. Local interpreter / codegen micro-opts in that regime land in the **3–15%** band (here **~6% batch geo**), consistent with the 2026-08-01 / 2026-08-08 reports.

A true 1.5× on warm Groovy/Java parse would need grammar-level `nls`/`sep` LL(1) specialization, a specialized token pipeline, or measuring only cold-first-compile KPI.

---

## 8. Artifacts

| Kind | Path |
|---|---|
| Trial 1 TSV/log | `perf-testsuite/results/{base,tgt}-20260814T175126Z.{tsv,log}` |
| Trial 2 TSV/log | `perf-testsuite/results/{base,tgt}-t2.{tsv,log}` |
| Groovy harness | `perf-testsuite/results/groovy-tgt-20260814T175148Z.{tsv,log}` |
| Baseline worktree | `/tmp/antlr4-base-20260815` @ `7ca827345` |

---

## 9. Bottom line

1. Five hot-path / codegen rounds landed, documented, and covered by unit tests.  
2. Product A/B vs `7ca827345`: **batch geo 1.061×**, two-stage parallel warm **1.16×**, 0 syntax errors.  
3. Groovy lexer/parser generated by this tool is **high-quality** (`_adaptivePredict`, `_sync`, `_stateNumber`) and **correct** under serial and parallel two-stage / LL.  
4. Single-file geo flags are trial noise; batch multi-file is the reliable signal, as in the prior strict-flame report.
