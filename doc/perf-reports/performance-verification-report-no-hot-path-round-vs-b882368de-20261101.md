# Performance Verification Report

**Baseline:** `b882368de901d62590d7196c1d5e2b4ff50266a7` (release 4.13.2.18)
**Candidate:** `opt` at `8cf54ca51`, with no further hot-path edit
**Host:** the same class of machine as `performance-verification-report-a0b952253-flame-20261024.md` (Corretto 1.8.0_504, G1, `-Xms2g -Xmx2g`)
**What was measured:** whether another runtime or Java-template change can raise every timed cell, for three stacked rounds, above both the previous round and this baseline

---

## 1. Executive summary

No change was landed. A third round of universal speedups is not available on this fork, and neither is the first.

| Check | Result |
|---|---|
| Warm lexer `execATN` and warm parser `adaptivePredict` / `execDFA` | **left as they are** |
| Production diff from `b882368de` to `8cf54ca51` on the timed loop | the bare-CR test in `LexerATNSimulator.consume`, taken only when the current code point is `'\r'` |
| Groovy generated lexer | no method at or over the 8 000-byte huge-method limit. The character loop is the runtime |
| Groovy generated `expression` | **6 915 bytes**, under the limit, same size the 2026-10-24 flame report recorded |
| Three rounds, every serial and parallel lex and parse cell strictly faster | **not achieved**. The October gate already showed why |
| Syntax and API | unchanged by this investigation |

**Verdict.** Parr, Harwell, and Fisher's ALL(*) result, as implemented here, is an SLL DFA after warmup. The steady state is `LexerATNSimulator.execATN` for characters and `ParserATNSimulator.adaptivePredict` for decisions. Both are already specialized (ASCII lexer edges, dense LL(1), precedence `short[][]`). Changes that add a branch to `execATN`, or that reserve more token slots than the corpus stores, lost lexer cells or sat inside host noise of about 0.93× to 1.08×. A parser-only edit cannot move lexer reuse. A lexer-only edit cannot move a parse cell that is dominated by `adaptivePredict`. The requirement that every cell improve on every round therefore has no implementation that this tree can honestly ship.

The flame graph for that steady state remains `doc/perf-reports/flame-serial-two-stage-20261024.html`.

---

## 2. What the baseline already does

`b882368de` is release 4.13.2.18. Relative to the 4.13.2.17 gate (`a0b952253`), the later commits before this report do not rewrite `execATN` or `adaptivePredict`. The one timed-loop difference at `8cf54ca51` is:

```java
if ( curChar=='\n' || (curChar=='\r' && input.LA(2)!='\n') )
```

`LA(2)` runs only for CR. LF, identifiers, operators, and spaces do not take it. CR+LF still counts as one break; the CR only advances the column. That is a correctness fix for GROOVY-12435, not a throughput round.

HPPC stays off every `public` and `protected` signature. `TestHppcApiBoundary` is the check. This investigation does not add a signature.

---

## 3. Groovy lexer and parser

Sources: `GroovyLexer.g4`, `GroovyParser.g4`, and the generated files under `groovy/build/generated/sources/antlr4/org/apache/groovy/parser/antlr4/`, compiled as `groovy/build/classes/java/main/...`. The generator string in `GroovyLexer.java` is ANTLR 4.13.2.17, which is this fork. The generated parser already calls `_adaptivePredict`, `_match`, `_sync`, and `_prec`.

Bytecode `code_length` from the class files:

| Method | Bytes | Note |
|---|---:|---|
| `GroovyParser.expression(int)` | 6 915 | under HotSpot's 8 000-byte huge-method cutoff, so C2 may compile it |
| `GroovyParser.pathElement()` | 2 347 | |
| Next largest rule methods | under 1 300 | |
| Parser methods at or over 8 000 bytes | 0 | |
| Parser methods at or over 325 bytes | 41 | above `FreqInlineSize`; they stay out of line |
| `GroovyLexer` methods at or over 8 000 bytes | 0 | |
| `GroovyLexer.sempred` | 284 | predicate edges only, not every character |
| `GroovyLexer.action` | 282 | lexer actions, not the DFA walk |

`GroovyLexer` does not override `nextToken`. Slashy-string decisions sit in `emit` and semantic predicates. That is grammar code. A cheaper `execATN` would be the runtime help, and that method is the loop the October experiments made slower by widening it.

`expression` calls the one-line `_adaptivePredict`, which calls `ParserATNSimulator.adaptivePredict`. The simulator method is about 475 bytes, above `FreqInlineSize` (325). Keeping that call out of line is what leaves `expression` at 6 915 bytes instead of pushing it over 8 000, where C2 would refuse to compile it. Shrinking `adaptivePredict` so it inlines into `expression` is the change most likely to *lose* Groovy parse throughput.

The harness in this repository times `GroovyLike.g4`, not the full Groovy grammar. `GroovyLike` shares the same runtime loops. The full grammar adds predicate and action work that a runtime micro-edit does not remove.

---

## 4. Flame graph, and why three rounds fail

Arthas 4.3.5 (`~/as.sh`, `JAVA_HOME` pinned to Corretto 8) on a warm Java serial two-stage `ParseLoop` (24 files × 8 methods), 12 s CPU sample, 1 228 samples. Self time:

| Frame | Self |
|---|---:|
| `LexerATNSimulator.execATN` | 25.4% |
| `ParserATNSimulator.adaptivePredict` | 17.1% |
| `ParserATNSimulator.execDFA` | 6.1% |
| `ArrayList.grow` under `BufferedTokenStream.fetch` | 5.9% |
| `BufferedTokenStream.fetch` | 5.5% |
| `JavaParser._match` | 4.1% |
| `Lexer.nextToken` | 3.2% |
| `Parser.exitRule` | 3.0% |
| `CommonTokenFactory.create` | 2.8% |
| `HashEdgeMap.get` | 2.4% |
| `CommonTokenStream.LB` | 2.0% |

`getLexerTarget` does not appear. It is inlined into `execATN`. An extra call or branch in that loop shows up as a slower lex cell, not as a new frame.

The deciding 4×10 s gate in that report, same corpus shape this baseline still uses:

| Cell | Ratio vs `a0b952253` | Paired ratios |
|---|---:|---|
| Java serial two-stage | 0.990× | 0.963, 0.979, 1.055, 0.967 |
| Groovy-like serial two-stage | 0.992× | 1.001, 1.014, 0.945, 1.011 |
| Java lexer reuse | 0.980× | 0.975, 0.923, 1.042, 0.982 |
| Groovy-like lexer reuse | 1.018× | 1.049, 1.041, 1.031, 0.953 |

A self-loop inside `execATN` was **0.954×** Java lex and **0.881×** Groovy-like lex, and was removed. Reserving one token slot per code point fell to about **0.87×** on the first clean serial pair, and was removed. `String.getChars` for the Latin-1 copy was **0.78×–0.99×** and was not integrated.

Lexer reuse does not call `adaptivePredict`. A parser-only change cannot satisfy "every cell improved." `execATN` is the shared lexer cost, and it is already the tight loop. There is no third idea, after those two failures, that moves lex and parse together by more than the pair swing.

Parallel Groovy-like and Java parse cells in the same family of gates sat in that same band. They are not a separate fast path. Workers share the DFA and still call `execATN` and `adaptivePredict`.

---

## 5. What was not changed

- No edit to `execATN`, `adaptivePredict`, `Java.stg`, or the token-list hint.
- No HPPC type on a `public` or `protected` API.
- Generated Groovy sources were not regenerated. Rebuilding them without a template change would time the same calls.
- Unit tests were not re-run for a tree that this report does not modify. The suite on `8cf54ca51` is the one already recorded: runtime 1 203 tests, 0 failures; tool 1 619 tests, 0 failures, 14 skipped (`TestPerformance.compileJdk` when `JDK_SOURCE_ROOT` is unset).

---

## 6. Conclusion

The baseline `b882368de` is the right runtime to keep for throughput. The bare-CR consume rule on top of it is a line-number fix and stays off the common character. Further rounds that have to beat every serial and parallel lex and parse cell would have to delete work in `execATN` without adding a test, and delete work in `adaptivePredict` without inlining it into `expression`. This tree does not contain that deletion. Shipping a round whose mean is 0.99× and calling it a win would not match the gate.
