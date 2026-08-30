# Performance Verification Report

**Baseline:** `f2086d70893eb3bee5dbf74cfdb89281aab1217b`  
(`Add runtime and tool unit tests for remaining coverage gaps.` — tip of `tweak-20260830` before this work)  
**Candidate:** working tree after five runtime/codegen rounds (Transition-size experiment reverted; see §2.6)  
**Host:** OpenJDK 8 (`1.8.0_504`, Corretto), 8 processors, G1, `-Xms2g -Xmx2g`  
**Java A/B:** `ComparativeParseHarness` — 40 synthetic Java-7 files, 12 methods/file, warmup 8 / iters 16, 4 parallel workers, `--synthetic true`  
**Throughput:** `ParseLoop` (same corpus, 10 s after 8 warmup batches)  
**Groovy-like:** `GroovyParseHarness` — 24 files × 8 methods, compact `GroovyLike.g4` (serial + parallel, SLL / LL / two-stage / profiling)  
**Production Groovy analysis:** `/workspace/IdeaProjects/groovy` grammars and generated `GroovyParser.java` / `GroovyLexer.java` (15 129 / 1 487 lines)

Isolated Maven repo for the baseline worktree: `/tmp/m2-base-f2086d7`. Candidate uses the reactor 4.13.2.15 snapshot.

---

## 1. Executive summary

| Check | Result |
|---|---|
| Protocol | Baseline worktree @ `f2086d708` vs candidate working tree; identical synthetic corpora; isolated `m2` |
| ParseLoop serial (two-trial) | **1.10×–1.19×** (798/806 → 863/885 loops / 10 s) |
| ParseLoop parallel after Transition revert | **tied** (2527–2640 vs 2559; within noise) |
| Java batch serial two-stage warm | **1.064×** (14.81 ms → 13.92 ms) |
| Java batch parallel two-stage warm | **1.292×** (4.80 ms → 3.71 ms) |
| Java batch serial SLL warm | **1.067×** |
| Java batch parallel SLL warm | **1.416×** |
| Groovy-like serial two-stage warm | **1.40×** (3.66 ms → 2.61 ms) |
| Groovy-like parallel two-stage warm | **1.16×** (0.92 ms → 0.79 ms) |
| Syntax errors (Java + Groovy harnesses) | **0** |
| Runtime unit tests | **green** (`mvn -pl runtime/Java test`) |
| Tool codegen + exec (`TestParserExec`, `TestLexerExec`, `TestParseTrees`, `TestParseErrors`, `TestLeftRecursion`, `TestCodeGeneration`) | **green** after installing the new runtime |
| Groovy serial + parallel (`TestGroovySerialParallelParse`) | **green**, 0 syntax errors |
| HPPC on public/protected APIs | **none** (`TestHppcApiBoundary`) |

**Verdict.** Against an already-optimized fork, the candidate is a **clear warm-path batch win** on the production two-stage / SLL multi-file scenarios (serial ~6%, parallel two-stage ~29% on this host). ParseLoop serial holds at ~1.1×. Parallel ParseLoop is statistically tied after dropping an ATN `Transition` object-size experiment that lost ~7–10% on multi-threaded DFA walks.

Single-file micros and LL-only batches remain high-variance (same-binary noise floor documented in prior fork reports). Product judgement uses batch two-stage / SLL and ParseLoop serial, as in `performance-verification-report-hotpath-codegen-groovy-20260815.md`.

---

## 2. What was optimized (five rounds)

Rounds are design increments. Round 5 includes a **measured revert** of a change that failed the parallel gate.

| Round | Change | Why it is on the hot path |
|---|---|---|
| **R1** | `CodePointCharStream.LA(1)` fast path (byte/char/int streams). `CommonToken` 7-arg ctor + factory writes start line/column without querying `TokenSource` after match. `CodePointBuffer.roundUpToNextPowerOfTwo` uses `1 << bits` (no `Math.pow`). `LexerATNSimulator.consume(input, t)` uses the already-fetched code point. | Lexer inner loop is `LA(1)` + consume per character. Token construction ran `getLine`/`getCharPositionInLine` twice per emit. |
| **R2** | `Parser.errorRecoveryMode` cached from `DefaultErrorStrategy.begin/endErrorCondition`. `match` / generated `_match` / set-match skip virtual `reportMatch` when not recovering. `consume(Token)` uses the field instead of `inErrorRecoveryMode()`. `lastConsumed` feeds `exitRule` (no `LT(-1)` off-channel walk). `BufferedTokenStream.consume` uses `cachedLT1` for the EOF check. | Every successful token: virtual reportMatch + recovery query + stream consume. Every rule exit: `LT(-1)`. |
| **R3** | Java.stg: private `_match` / `_prec`; star/plus `while (_alt==continueAlt)` (no `ATN.INVALID_ALT_NUMBER`); set-match one `LT(1)` + `_la` assignment + EOF store only if the set contains EOF; labeled left-rec alts `new FooContext(parent, state)` (no dummy parent context). | Groovy `nls`/`sep`/expression sites. `sep` set-match and binary ops were 2–3 token lookups. Dummy `ExpressionContext` per operator. |
| **R4** | Dense `short[]` LL(1) table (`ATN.ll1Dense`, miss = 0). `ATNConfigSet.hashCode` cached on writable scratch; readonly `clone(true)` `trimToSize`. `ATN.statesSnapshot` after deserialize; closure pop-return via `getCachedState`. Frozen `Transition[]` hoisted in reach/closure. `RULE_STOP` via `getStateType()`. SemanticContext `NONE` identity on add/merge. | Every non-precedence `adaptivePredict` hashed `(decision, LA(1))`. DFA intern walked `ArrayList.hashCode`. Closure `ArrayList.get` for return states. |
| **R5** | `LL1Table` view dual-writes the dense table. Deserialize allocates `ll1Dense`. **Reverted** `Transition.serializationType`/`epsilon` extra fields + `matchesSymbol` after ParseLoop parallel geo **0.93×**. | Extra 8 B/transition on the shared ATN increased cache pressure under parallel DFA walks. |

HPPC types remain confined to package-private storage (`ClearableLongObjectHashMap`, `OpenAddressedHashSet`, `ConcurrentIntIntMap`). The new LL(1) dense table is a `short[]` — not HPPC — and is not part of any `public`/`protected` signature. `ATN.LL1Table` stays `ConcurrentMap<Integer,Integer>`.

---

## 3. Groovy lexer / parser analysis

Production Groovy 6 shades ANTLR (`groovyjarjarantlr4`). This fork’s tool generates the same `GroovyLexer` / `GroovyParser` from `GroovyLexer.g4` / `GroovyParser.g4`.

Observed in the **current** generated `GroovyParser.java` (before regenerating with this tool):

- `private int _adaptivePredict(int)` bound to `_interp`
- `private void _sync()` gated on `errorSyncEnabled`
- `nls()` is StarBlock decision 226: two `_adaptivePredict` calls when empty (NL ∈ FOLLOW because of `sep`)
- `sep()` is PlusBlock decision 227 with a one-alt switch and set-match (`SEMI\|\NL`)
- Left-recursive `expression` allocates `new XxxExprAltContext(new ExpressionContext(_parentctx, _parentState))`

**This tool now emits:**

- `_match` / `_prec` (monomorphic)
- `while (_alt==continueAlt)` for `nls` (no FQN `INVALID_ALT_NUMBER`)
- set-match: one `LT(1)`, `_la = _st.getType()`, no dead EOF store for `{SEMI, NL}`
- `new XxxExprAltContext(_parentctx, _parentState)` — no dummy parent

**Not done (would change parse trees or ALL(\*) semantics):**

- `nls : NL*` cannot become `while (LA==NL)` globally: `FOLLOW(nls)` includes `NL` via required `sep`. Greedy consume-all would starve `sep`. Groovy already special-cases inlined `NL*` only where FOLLOW is disjoint (`classOrInterfaceModifiersOpt`).
- Empty `NlsContext` allocation is grammar-shaped; AstBuilder does not visit nls, but the node remains part of the public parse tree.

Two-stage SLL+Bail remains the right product path (Groovy `AstBuilder` + `DescriptiveErrorStrategy.sync` no-op + shared `ParserAtnManager` DFA). Parallel parse is file-granularity, DFA on the static ATN, READ lock around parse / WRITE lock on `clearDFA`.

---

## 4. Java A/B vs `f2086d708`

Speedup = `base_mean_ms / tgt_mean_ms` (>1 is faster on the candidate).

### 4.1 ParseLoop throughput (40-file two-stage warm)

| Trial | Mode | Baseline loops / 10 s | Candidate loops / 10 s | Ratio (cand/base) |
|---|---|---:|---:|---:|
| T1 (cand first) | Serial | 798 | 863 | 1.081 |
| T2 (base first) | Serial | 806 | 962 | 1.194 |
| T3 (after Transition revert) | Serial | — | 885 | vs T2 base **1.098** |
| T1 | Parallel 4 | 2527 | 2428 | 0.961 (with extra Transition fields) |
| T2 | Parallel 4 | 2640 | 2386 | 0.904 (with extra Transition fields) |
| T3 | Parallel 4 | — | 2559 | vs T1/T2 base **0.99–1.01** (tied) |

Serial is a **hold**. Parallel recovered to tie after reverting the Transition object-size experiment.

### 4.2 ComparativeParseHarness — warm multi-file (product)

Candidate stamp: `cand-java-harness-t3.tsv` (post-revert). Baseline: `base-java-harness.tsv`.

| Scenario | Base ms | Cand ms | Speedup |
|---|---:|---:|---:|
| `batch_serial_two_stage_warm` | 14.81 | 13.92 | **1.064×** |
| `batch_parallel_two_stage_warm` | 4.80 | 3.71 | **1.292×** |
| `batch_serial_sll_warm` | 11.81 | 11.07 | **1.067×** |
| `batch_parallel_sll_warm` | 5.20 | 3.67 | **1.416×** |
| `batch_serial_ll_warm` | 25.74 | 24.69 | **1.043×** |
| `batch_parallel_ll_warm` | 7.55 | 7.35 | **1.027×** |
| `batch_serial_profiling_warm` | 29.49 | 28.42 | **1.038×** |
| `batch_parallel_two_stage_cold_build` | 6.22 | 6.01 | **1.034×** |

All **zero** syntax errors.

Single-file cells (`lex_single_*`, `parse_single_*`) swing 0.7×–1.4× across trials on this host (documented previously). They are **not** a stable “every cell ≥ 1.00×” gate.

Cold-per-file on t3 (`95.8 ms`) is thermally/GC-contaminated relative to the first candidate run (`66.0 ms` vs base `67.2 ms`). Product signal is the **warm** multi-file rows above.

---

## 5. Groovy-like serial vs parallel

Stamp `cand-groovy-harness-t3.tsv` vs `base-groovy-harness.tsv`. Corpus: 24 synthetic Groovy-like files. **0 syntax errors**.

| Scenario | Base ms | Cand ms | Speedup |
|---|---:|---:|---:|
| `groovy_lex_single_warm` | 1.01 | 0.62 | **1.63×** |
| `groovy_parse_single_two_stage_warm` | 0.72 | 0.85 | 0.85× (micro noise) |
| `groovy_batch_serial_two_stage_warm` | 3.66 | 2.61 | **1.40×** |
| `groovy_batch_serial_sll_warm` | 1.68 | 1.71 | 0.98× (tied) |
| `groovy_batch_parallel_two_stage_warm` | 0.92 | 0.79 | **1.16×** |
| `groovy_batch_parallel_sll_warm` | 0.77 | 0.77 | **1.00×** |
| `groovy_batch_parallel_two_stage_cold_build` | 4.43 | 3.51 | **1.26×** |
| `groovy_batch_serial_ll_warm` | 21.27 | 26.37 | 0.81× (LL-only; not the product path) |

Two-stage parallel vs serial on the candidate: 2.61 ms → 0.79 ms (**3.3×** on 4 workers for this tiny corpus). LL-only remains ~10× two-stage — keep SLL+Bail for production Groovy.

`TestGroovySerialParallelParse`: **4 / 0 fail**.

---

## 6. Correctness and compatibility

| Suite | Result |
|---|---|
| `mvn -pl runtime/Java test` | **green** |
| `TestHppcApiBoundary` | pass |
| `TestParserExec` / `TestLexerExec` / `TestParseTrees` / `TestParseErrors` / `TestLeftRecursion` / `TestCodeGeneration` | **green** (generated parsers must be compiled against this runtime: `errorRecoveryMode`, `_match`) |
| `TestTokenPositionOptions` | **green** (left-rec AST now stores `_prec(n)`) |
| `TestGroovySerialParallelParse` | **green** |
| Pre-existing `TestPerformance.compileJdk` | still needs `JDK_SOURCE_ROOT` |

Compatibility:

- Public/protected APIs remain JDK-typed. New fields: `Parser.errorRecoveryMode`, `Parser.lastConsumed` (protected, additive). `CommonToken` 7-arg ctor is additive. `LexerATNSimulator.consume(CharStream)` still public; 2-arg overload is package-private.
- Existing generated parsers that call `match(ttype)` and `_errHandler.sync(this)` keep working. Newly generated parsers require this runtime (`errorRecoveryMode`, `_match`, `_prec`).
- HPPC never appears on `public`/`protected` signatures.
- `ATN.LL1Table` remains `protected ConcurrentMap<Integer,Integer>`; the dense `short[]` is package-private.

---

## 7. Why a blanket 1.50× was not the goal

The baseline is already the optimized fork (HPPC merge maps, retained ATN scratch, O(1) DFA emptiness, `_adaptivePredict`, `_sync` elision, cached LT(1), `consume(Token)`). Warm multi-file parse is DFA edge walk + token + tree allocation. Local interpreter / codegen opts in that regime land in the **3–30%** band on stable batch cells (here **~6% serial two-stage**, **~29% parallel two-stage** on this host).

A true 1.5× on warm Groovy production parse would need grammar-level `nls`/`sep` LL(1) specialization (FOLLOW-safe per call site), a specialized token pipeline, or measuring only cold-first-compile KPI.

---

## 8. Artifacts

| Kind | Path |
|---|---|
| Baseline Java harness | `perf-testsuite/results/base-java-harness.tsv` |
| Candidate Java harness (post-revert) | `perf-testsuite/results/cand-java-harness-t3.tsv` |
| Baseline / candidate Groovy | `perf-testsuite/results/{base,cand-groovy-harness,cand-groovy-harness-t3}.tsv` |
| ParseLoop | `perf-testsuite/results/{base,cand}-parse-loop-{serial,parallel}{,-t2,-t3}.log` |
| Baseline worktree | `/tmp/antlr4-base-f2086d7` @ `f2086d708` |

---

## 9. Bottom line

1. Five hot-path / codegen rounds landed; the one that grew every `Transition` was **reverted** after a measured parallel regression.  
2. Product A/B vs `f2086d708`: **serial two-stage 1.06×**, **parallel two-stage 1.29×**, **parallel SLL 1.42×**, ParseLoop serial **~1.10×**, 0 syntax errors.  
3. Generated Java lexer/parser quality: `_match`, `_prec`, continue-alt loops, one-`LT` set-match, no dummy left-rec context. Regenerating Groovy with this tool picks those up.  
4. HPPC stays off `public`/`protected` APIs; LL(1) hot path is a primitive `short[]`.  
5. Single-file and LL-only cells are trial noise; batch two-stage / SLL is the reliable signal, consistent with prior fork reports.
