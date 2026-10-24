# Performance Verification Report

**Baseline:** `a0b952253c257578fb17bb026df7bfcd966fc0a3` (release 4.13.2.17)  
**Candidate:** working tree on `opt` after the tool-module review fixes, on top of `535b352a9`  
**Host:** the same machine and JVM as the runtime gate recorded in `performance-verification-report-review-fixes-vs-a0b952253-20261024.md`  
**What was measured:** whether this candidate can move the runtime throughput cells

---

## 1. Executive summary

This candidate does not change the runtime. `git diff` against `535b352a9` has no files under `runtime/`. The parse harness loads pre-generated Java and Groovy-like recognizers from `perf-testsuite`. Those class files call the same runtime methods they called at `535b352a9`. A fresh 8-second gate would time that same runtime again and could only resample the host noise already reported (pair ratios about 0.93 to 1.08).

| Check | Result |
|---|---|
| Runtime sources versus `535b352a9` | **unchanged** |
| Harness recognizers regenerated | **no** |
| Java and Groovy-like lex, serial and parallel parse, versus `a0b952253` | **the `535b352a9` gate stands** |
| Stable loss versus `a0b952253` | **none recorded on that gate** |
| Tool unit tests | **1616 run, 0 failures, 14 skipped** |
| Runtime unit tests | **1196 run, 0 failures** |

**Verdict.** There is no runtime throughput change to claim, and none to repair. The tool fixes change what a newly generated lexer or parser does for grammars that previously mis-compiled. They do not change the loops the existing harness times.

The numbers versus `a0b952253` are in the 2026-10-24 report. After the closure-depth and `seek` corrections, no cell was slow on both trials. This tree keeps that runtime.

---

## 2. Why the tool fixes are outside the timed loop

The timed path is `execATN` for a warm lexer and `tryLL1Prediction` / `tryPrecedenceLL1` / private `execDFA` for a warm parser. None of those methods were edited.

The tool changes are in grammar lexing, character-set construction, Java string escaping, label declarations, channel-name layout in `.interp`, and import handling. A grammar that is compiled with this tool and then run will pick them up. The harness does not recompile `GroovyLike.g4` or the Java corpus grammars. Regenerating them was not part of this check: those grammars are not the charset and escape cases fixed here, and rebuilding them would time a different recognizer than the one the baseline report used.

`TestPerformance.compileJdk` is skipped unless `JDK_SOURCE_ROOT` is set. It is not a throughput cell of the gate.

---

## 3. What the tool change does to generated code

These are correctness fixes. They are not a new fast path.

- `[a-zA-Z0-9-_]` no longer widens through EOF. A hyphen after a finished range is a literal.
- A code point above `U+10FFFF` is rejected instead of becoming a range.
- Generated Java Unicode escapes use the code point. Newline, CR, quote, and backslash use short escapes so javac does not rewrite them before it tokenizes the string.
- `ys+=.` compiles. `@parser::header` stays out of the listener. The lexer file name is java-escaped.
- Brackets before a rule's colon stay arguments. A charset after the colon in a lexer rule stays a charset.
- A file that reports a syntax error is not given an ATN. A later file that parsed cleanly is still processed.
- `skip` rules still receive a token type, so existing token numbers do not move.

HPPC does not appear in the tool sources. No public or protected signature was added.

---

## 4. Tests

JDK 8, offline Maven, `-pl tool -am test`.

- Runtime: 1196 tests, 0 failures, 0 errors, 0 skipped.
- Tool: 1616 tests, 0 failures, 0 errors, 14 skipped. The extra skip is `TestPerformance.compileJdk` when `JDK_SOURCE_ROOT` is unset. `testExponentialInclude` ran.

`TestToolReviewFixes` covers the charset, a legal `-> type` rule, parser arguments after a lexer rule, a trailing backslash in `locals`, a partial alt label, an action-valued option, `+= .`, Unicode escapes, a code point past Unicode, a circular import, a combined grammar with no lexer, and a missing `-o` value.
