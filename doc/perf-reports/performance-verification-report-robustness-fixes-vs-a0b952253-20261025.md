# Performance Verification Report

**Baseline:** `a0b952253c257578fb17bb026df7bfcd966fc0a3` (release 4.13.2.17)
**Candidate:** working tree on `opt`, on top of `be0f5169509551a2164fcc724d78dea4392d2f40`
**Host:** the same machine and JVM as `performance-verification-report-review-fixes-vs-a0b952253-20261024.md` (Corretto 1.8.0_504, 6 processors, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`)
**What was measured:** whether this candidate can move the runtime throughput cells

---

## 1. Executive summary

This candidate does not change the timed loops. The production diff against `be0f51695` is `InterpreterDataReader` (a `.interp` file reader), `Tool`, `Grammar`, and `JavaTarget`. `LexerATNSimulator`, `ParserATNSimulator`, `Parser`, and `CodePointCharStream` are untouched. The parse harness loads pre-generated Java and Groovy-like recognizers. Those class files call the same runtime methods they called when the 2026-10-24 gate was recorded.

A fresh 8-second gate would time that same runtime again and could only resample the host noise already reported (pair ratios about 0.93 to 1.08). That noise is larger than any change in this diff, so a new window would not be evidence of a gain or a loss.

| Check | Result |
|---|---|
| Warm lexer `execATN` and warm parser `tryLL1Prediction` / `tryPrecedenceLL1` / private `execDFA` | **unchanged** |
| Harness recognizers regenerated | **no** |
| Java and Groovy-like lex, serial and parallel parse, versus `a0b952253` | **the 2026-10-24 review-fixes gate stands** |
| Stable loss versus `a0b952253` | **none recorded on that gate** |
| Runtime unit tests | **1198 run, 0 failures, 0 skipped** |
| Tool unit tests | **1619 run, 0 failures, 14 skipped** |

**Verdict.** There is no runtime throughput change to claim, and none to repair. The edits close three tool and interpreter-data defects. They do not change the loops the existing harness times.

The numbers versus `a0b952253` are in the 2026-10-24 report. After the closure-depth and `seek` corrections, no cell was slow on both trials. This tree keeps that runtime.

---

## 2. Why these edits are outside the timed loop

The timed path is `LexerATNSimulator.execATN` for a warm lexer and `tryLL1Prediction` / `tryPrecedenceLL1` / private `execDFA` for a warm parser. None of those methods were edited. `git diff be0f51695 --` those files is empty.

What did change:

- A missing string option (`-o`, `-lib`, and the other string options) reports the command-line error and still applies the `"."` directory defaults. `-depend` can then resolve `tokenVocab` without a null `libDirectory`. This runs once, in `Tool.handleArgs`, before any parse.
- Lexer channel names for `.interp` and `LexerInterpreter` come from one list. A hole is written as a line of two quote characters, which the reader stores as an empty name. A blank line still ends the section. The word `null` stays text, so an older file keeps that channel name. `InterpreterDataReader.parseFile` is file I/O. Generated lexers do not call it. The new `equals` is one compare per channel line.
- An undecodable grammar escape is kept in the generated Java literal. Only that escape is consumed. Each of its characters is Java-escaped, so a later quote, newline, or unicode escape in the same literal stays data and the file still compiles. A real scalar still uses the short escapes for newline, CR, quote, and backslash. This runs in the tool, when a grammar is compiled, not in `execATN`.

The harness does not recompile `GroovyLike.g4` or the Java corpus grammars. Those grammars are not the charset, channel-hole, or escape cases fixed here. Rebuilding them would time a different recognizer than the one the baseline report used.

`TestPerformance.compileJdk` is skipped unless `JDK_SOURCE_ROOT` is set. It is not a throughput cell of the gate.

---

## 3. The standing gate

Same protocol as the 2026-10-24 report: fresh JVM, 2 s warmup, 8 s window, 24 files, trial order baseline, candidate, baseline, candidate. Java from `CorpusLoader.syntheticJava7Corpus`. Groovy-like from `GroovyCorpus` / `GroovyLike.g4`. This repository has no full Groovy grammar. `GroovyLike.g4` is the Groovy-style front end. Lex reuse, serial lex batch, and SLL / LL / two-stage parse, serial and 4-worker, are the cells. Workers share the DFA. `setBuildParseTree(false)`.

| Cell | Ratio | Pairs, where the report gives them |
|---|---|---|
| Java lex reuse, 1 thread | 0.991× | 0.911, 1.083 |
| Java lex batch, 1 thread | 0.966× | 1.002, 0.929 |
| Java parse serial SLL / LL / two-stage | 1.004× / 1.002× / 0.950× | |
| Java parse parallel SLL / LL / two-stage | 0.932× / 1.026× / 0.939× | |
| Groovy-like lex reuse, 1 thread | 1.008× | 1.033, 0.984 |
| Groovy-like lex batch, 1 thread | 0.949× | 0.902, 0.996 |
| Groovy-like parse serial SLL / LL / two-stage | 1.021× / 1.014× / 0.989× | |
| Groovy-like parse parallel SLL / LL / two-stage | matched pair 1.053× / 1.038× / 0.968× | |

Every timed window in that report had zero syntax errors. A mean inside about 0.93 to 1.08 is the pair swing of this host. It is not a loss and it is not a speedup. Parallel Groovy-like SLL has one baseline window at about half speed; the matched pair is 1.053×. Lex batch with 4 threads is not a separate parallel lexer in that driver. Parallel coverage is the parse cells.

`913fa97fd` is not a throughput improvement over `a0b952253`. This candidate does not add another one.

---

## 4. What the tool change does to generated code

These are correctness fixes. They are not a new fast path.

- A missing option value no longer skips the directory defaults, so `-depend` does not throw on a null library directory.
- User channel indexes in a new `.interp` file match `LexerInterpreter`. A hole does not end the channel section.
- A code point that is not a Unicode scalar stays visible in the generated Java literal. The bad escape does not swallow the next quote or the next real escape. Newline, CR, quote, and backslash still use the short escapes.
- `skip` rules still receive a token type. `[a-zA-Z0-9-_]` is unchanged by this diff. The report-match gate on the success path is unchanged.

HPPC does not appear on any new public or protected signature. `getInterpreterChannelNames` returns `List<String>`. `interpreterChannelLine` returns `String`.

---

## 5. Tests

JDK 8, offline Maven, `mvn -o -pl tool -am test`.

- Runtime: 1198 tests, 0 failures, 0 errors, 0 skipped. The two new tests are the channel-hole `.interp` reader and `matchWildcard` skipping `reportMatch` when not recovering.
- Tool: 1619 tests, 0 failures, 0 errors, 14 skipped. The extra skip is `TestPerformance.compileJdk` when `JDK_SOURCE_ROOT` is unset.
- `TestToolReviewFixes.unicodeEscapesUseTheCodePoint` locks a scalar past Unicode, a three-digit body, a short body followed by a quote, and a short body followed by a real newline escape.
