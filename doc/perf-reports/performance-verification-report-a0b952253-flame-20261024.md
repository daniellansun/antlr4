# Performance Verification Report

**Baseline:** `a0b952253c257578fb17bb026df7bfcd966fc0a3` (release 4.13.2.17)  
**Candidate:** working tree experiments on that commit. None were kept in the runtime or the tool.  
**Host:** OpenJDK 8 (`1.8.0_504`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`  
**Gate:** fresh JVM per trial, 2 s warmup then a 10 s window, 24 synthetic files × 8 methods, 4 parallel workers when the cell is parallel. Trial order inside a cell: base, candidate, repeated. Higher loop counts are faster.  
**Corpus:** Java from `CorpusLoader.syntheticJava7Corpus` (106 112 chars, 35 928 tokens, 2.95 chars/token). Groovy-like from `GroovyCorpus` (48 547 chars, 14 472 tokens, 3.35 chars/token). Lex cells rebind one file through `ParseWorkload.lexReuse`. Parse cells are two-stage SLL unless named otherwise. `setBuildParseTree(false)`, matching the previous throughput gate.

SLL and LL trees and token fingerprints (type, channel, line, column, start, stop) of the experimental builds matched the baseline. Every timed run reported zero syntax errors. The losses below are overhead, not a wrong alternative.

---

## 1. Executive summary

| Check | Result |
|---|---|
| Runtime and tool sources vs `a0b952253` | **unchanged** |
| Deciding 4×10 s gate (package in §4.3) | Java serial two-stage **0.990×**, Groovy-like serial two-stage **0.992×**, Java lexer reuse **0.980×**, Groovy-like lexer reuse **1.018×** |
| Pair swing on that gate | Java serial pairs **0.963–1.055**. A 2% effect is not separable from the host |
| Self-loop inside `execATN` | Groovy-like lex **0.881×**, Java lex **0.954×**. Removed |
| One slot per code point | Serial two-stage fell to about **0.87×** on the first clean pair. Removed |
| `getChars` instead of `String.charAt` for the Latin-1 copy | **0.78×–0.99×** the existing loop. Not integrated |
| Three stacked rounds, every cell strictly faster | **not achieved** |

**Verdict.** On this already-optimized fork the warm cost is the lexer DFA loop (`LexerATNSimulator.execATN`, 25% self) and `adaptivePredict` (17% self). The loop is past the point where an extra branch or a wider token array pays for itself. Every change that touched it, or that reserved more pointer slots than the token list needs, lost at least one cell. Parser-only cleanups (a multiply instead of a divide on the precedence table, an LT(-1) cache, `IntegerStack.pop` without a tail copy) were measured only as part of a package whose point estimate was below 1.0 on three of the four deciding cells, so they were not landed either. The tree keeps `a0b952253`.

---

## 2. Flame graph

Arthas 4.3.5 (`~/as.sh`, `JAVA_HOME` pinned to Corretto 8) attached to a warm Java serial two-stage `ParseLoop` (24 files × 8 methods) and sampled CPU for 12 s. The graph is `doc/perf-reports/flame-serial-two-stage-20261024.html`. 1 228 samples.

Self time, samples that are not in a callee:

| Frame | Self |
|---|---|
| `LexerATNSimulator.execATN` | 25.4% |
| `ParserATNSimulator.adaptivePredict` | 17.1% |
| `ParserATNSimulator.execDFA` | 6.1% |
| `ArrayList.grow` | 5.9% |
| `BufferedTokenStream.fetch` | 5.5% |
| `JavaParser._match` | 4.1% |
| `Lexer.nextToken` | 3.2% |
| `Parser.exitRule` | 3.0% |
| `CommonTokenFactory.create` | 2.8% |
| `HashEdgeMap.get` | 2.4% |
| `CommonTokenStream.LB` | 2.0% |
| `CodePointBuffer.Builder.appendStringByte` | 1.2% |

Every `ArrayList.grow` sample sits under `BufferedTokenStream.fetch` → `ArrayList.add`. The stream already calls `ensureCapacity(chars >> 2)` from `setup`. At 2.95 chars/token that hint is short of the token count, so the list still copies. `appendStringByte` is the `CharStreams.fromString` Latin-1 loop. It is about 1% of a full parse. On the 4 419-character file, §4.3 lexer reuse is 109 434 loops / 10 s, about 91 µs per lex. The copy itself is ~2.5 µs, about 3% of that loop.

`execATN` does not show `getLexerTarget` or `captureSimState` as their own frames. They are already inlined into the character loop. That is why a “small” extra call inside the loop shows up as a loss on the whole lex cell rather than as a new frame.

---

## 3. What the steady state already is

Parr and Fisher’s Adaptive LL(*) result, as implemented on this fork, is an SLL DFA after warmup. Left-recursive operator and stop decisions additionally hit the `short[][]` precedence table in `ATN.cachedPrecedenceAlt` and return before `execDFA`. ASCII lexer edges are a volatile `DFAState[128]` load. Generated Java parsers already call `_match`, `_adaptivePredict`, `_sync`, and `_prec`. `GroovyParser.expression` is 6 915 bytes and `JavaParser.expression` is 2 991 bytes. Both are under HotSpot’s 8 000-byte huge-method cutoff, so they are compiled, but `adaptivePredict` itself is 475 bytes and stays an out-of-line call (`FreqInlineSize` is 325).

Groovy’s real lexer (`GroovyLexer.g4`) records the previous default-channel token in an overridden `emit` and decides slashy strings from that. That work is grammar code. The runtime change that would help it is a cheaper `nextToken` / `execATN`, which is the same loop the synthetic lexer uses. Whitespace and comments in the Java grammar are already runs (`[ \t\r\n\u000C]+`, `/* … */`), so a per-token prologue saving does not multiply by the number of spaces.

---

## 4. Experiments

### 4.1 One slot per code point

`tokenCapacityHint = size + 1` removes every `ArrayList.grow` on these corpora (a token per code point plus EOF is an upper bound when there are no zero-length tokens). A 4 419-character Java file has 1 497 tokens, so the list zeroed about three times as many slots as it stored. The first uncontaminated serial pair was about **0.87×**. Parallel swung harder while the machine was also compiling. The direction was a loss, from young-generation traffic, and the idea was dropped before the 4-trial gate.

### 4.2 Self-loop scan inside `execATN`

Identifier and whitespace DFA states point at themselves. After the character that entered such a state, a helper consumed the rest of the run and captured the accept position once. The helper was small enough to inline. Inlining it into `execATN` made the short-token path worse. Operators and the first character of each token never take the run, and they paid for the fatter loop.

| Cell | Baseline mean (loops / 8 s) | Candidate mean | Ratio |
|---|---|---|---|
| Java lexer reuse | 84 045 | 80 169 | **0.954×** |
| Groovy-like lexer reuse | 221 847 | 195 394 | **0.881×** |

Both Groovy-like candidate trials sat under both baseline trials. The screen was stopped after those two cells. Same outcome as the earlier array walk inside the lexer loop: the general loop is already the tight one.

### 4.3 Package that stayed off `execATN`

Landed together in one build, then measured 4 trials × 10 s:

- Token-list hint `size / 2 + 1` (above the measured density, one allocation, no per-token copy).
- Precedence LL(1) lookup by multiply when `table.length == 201 * ll1Stride`, with the old division kept for a table built under a different stride.
- `CommonTokenStream` cache of the previous on-channel token for `LT(-1)`, invalidated on `seek` to another index and on `setTokenSource`. A cold `LT(-1)` still calls `lazyInit` before it can miss, so `p` cannot stay −1.
- `fetch` fast path: `getClass() == CommonToken.class`, then `setTokenIndex` and a field read of `type` for EOF.
- `IntegerStack.pop` via a package-private `removeLast` that does not copy the prefix.

| Cell | Baseline mean | Candidate mean | Ratio | Paired ratios |
|---|---|---|---|---|
| Java serial two-stage | 1 580.5 | 1 564.5 | **0.990×** | 0.963, 0.979, 1.055, 0.967 |
| Groovy-like serial two-stage | 5 311.8 | 5 269.0 | **0.992×** | 1.001, 1.014, 0.945, 1.011 |
| Java lexer reuse | 109 434 | 107 297 | **0.980×** | 0.975, 0.923, 1.042, 0.982 |
| Groovy-like lexer reuse | 279 018 | 284 097 | **1.018×** | 1.049, 1.041, 1.031, 0.953 |

Three of the four means are under 1. The Groovy-like lex mean is over 1 and its worst pair is 0.953. An 8 s screen of the same build had looked like a Java serial win (1 287 vs 1 231 loops) and then did not repeat at 10 s × 4. That is the noise band, not a second effect. The package was removed.

### 4.4 Latin-1 copy via `String.getChars`

A standalone loop over a 4 419-character ASCII string, 200 000 iterations after warmup, three runs. `charAt` into a `byte[]` versus `getChars` into a scratch `char[]` then a byte compress: **0.781×, 0.992×, 0.892×** in favor of `charAt`. The existing monomorphic `appendStringByte` loop stays.

### 4.5 Earlier negative results that were not retried

Recorded against this same hot loop on previous rounds, and consistent with §4.2:

| Experiment | Result |
|---|---|
| Fuse `consume` with the next `LA(1)` | Java lexer reuse about **0.975×** after the fused loop was split out |
| Skip `mark` / `release` via `instanceof` | Both methods already inline to nothing. The test made lex about **0.90×** |
| `lastConsumed` instead of `LT(-1)` on recursion push/pop | Lost to the precedence/ASCII round on all 14 cells |
| Walk the code-point array from the lexer | About **0.87×** Java lex |
| Skip the post-match EOF `LA(1)` | About **0.90×** Java lex |
| Dense `DFAState[]` for parser edges | No win over `HashEdgeMap.get`, and parallel edge publication was racy |

---

## 5. Why three monotonic rounds were not delivered

Each round had to beat the previous round on every cell, including pure lexer reuse and both languages, serial and parallel. Lexer reuse does not call `adaptivePredict`, `LT(-1)`, or `IntegerStack.pop`. A parser-only change cannot move it. The lexer time that *is* shared with parsing is `execATN`. That method is already a tight inline of the ASCII edge load, `LA(1)`, and `consume`. Widening it lost 5–12% on the lex cells. The token-list copy that the flame attributes to `ArrayList.grow` (6% self) costs less than zeroing a larger backing array on the Java corpus, which is reallocated on every `lexReuse`.

The 10 s paired band on Java serial two-stage was **0.963–1.055** with no source change between the two JVMs of a pair beyond the candidate jar. A real improvement has to clear that band on every cell at once. Nothing measured here did.

---

## 6. What stayed in the tree

Runtime and tool sources match `a0b952253`. Three test classes gained cases that lock behavior the rejected fast paths have to preserve. They pass on the unchanged runtime (`mvn -pl runtime/Java -Dtest=TestCommonTokenStream,TestIntegerStack,TestLexerATNSimulatorHotPaths,TestLexerInterpreter,TestBufferedTokenStream test`).

- `TestCommonTokenStream.lookbackBeyondStartIsNull` — a cold `LT(-1)` still initializes the stream. `get(0)` and `seek(index())` do not throw, which is the failure mode if a cache stores the miss before `lazyInit`.
- `TestCommonTokenStream.ltMinusOneHitIsStableUntilSeek` — lookback skips hidden tokens, repeats the same token instance, and is empty again after `seek(0)`.
- `TestIntegerStack.popRemovesOnlyTheTail` — 32 pushes, pop back to empty, prefix intact.
- `TestLexerATNSimulatorHotPaths.selfLoopRunKeepsIndexLineAndColumn` — warm `[a-z]+` and `[ \n]+` leave index, line, and column on the character the slow loop would. A newline inside the whitespace run moves the line and clears the column.

No HPPC type was added to a public or protected signature. Templates were not changed. Existing generated Groovy and Java recognizers are bit-for-bit the ones at `a0b952253`.

---

## 7. Limits

- The deciding gate is four cells, not the full 14-cell matrix. The 8 s screen of parallel two-stage and Java LL moved inside the same band and was not repeated once the 10 s means were under 1.
- Groovy-like is the compact grammar in `perf-testsuite`, not `GroovyParser.g4`. The real Groovy lexer’s `emit` override is extra per-token work on top of the same `execATN`. A change that loses on Groovy-like lex loses on that override as well.
- Single-file and cold-start numbers move more than these windows. The gate is warm throughput.
- `mvn -pl runtime/Java test` on JDK 8 exited 0 against the reverted tree, including the cases in §6. Tool sources were not edited. The known `TestPerformance` failure that requires `JDK_SOURCE_ROOT` was not re-run.
