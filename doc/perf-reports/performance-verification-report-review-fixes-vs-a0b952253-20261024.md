# Performance Verification Report

**Baseline:** `a0b952253c257578fb17bb026df7bfcd966fc0a3` (release 4.13.2.17)  
**Candidate:** working tree on `opt` after the runtime review fixes (not a separate commit)  
**Host:** OpenJDK 8 (`1.8.0_504`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`  
**Gate:** fresh JVM per trial. Inside the JVM, 2 s warmup then an 8 s window. 24 synthetic files. Trial order inside each cell: baseline, candidate, baseline, candidate. Higher loop counts are faster. The ratio is the mean candidate loops divided by the mean baseline loops.  
**Corpus:** Java from `CorpusLoader.syntheticJava7Corpus` (24 files, 8 methods). Groovy-like from `GroovyCorpus` / `GroovyLike.g4` (24 files, 8 methods). This repository does not contain the full Groovy grammar. `GroovyLike.g4` is the Groovy-style front end. Lex reuse rebinds one lexer to the first file. Lex batch and parse walk all 24 files. `setBuildParseTree(false)`. Workers share the DFA on the static ATN. Each worker owns its lexer and parser.

Every timed window reported zero syntax errors (`ERRORS 0`).

---

## 1. Executive summary

This change is a correctness fix for the runtime review, not a throughput change. The warm lexer loop and the warm SLL DFA walk were not rewritten. A first gate hashed every lexer-closure frame and slowed lex batch. That hash now starts only after the walk is longer than the ATN, which is the cycle case. `CodePointCharStream.seek` keeps the negative-index check in a separate method so `accept` can still inline the leaf. After those two corrections, the cells the first gate had marked as slow came back inside the pair swing of this host.

| Check | Result |
|---|---|
| Java lex reuse, 1 thread | **0.991×**. Pairs **0.911**, **1.083** |
| Java lex batch, 1 thread (remeasured) | **0.966×**. Pairs **1.002**, **0.929** |
| Java parse serial SLL / LL / two-stage | **1.004× / 1.002× / 0.950×** |
| Java parse parallel SLL / LL / two-stage | **0.932× / 1.026× / 0.939×** |
| Groovy-like lex reuse, 1 thread | **1.008×**. Pairs **1.033**, **0.984** |
| Groovy-like lex batch, 1 thread (remeasured) | **0.949×**. Pairs **0.902**, **0.996** |
| Groovy-like parse serial SLL / LL / two-stage | **1.021× / 1.014× / 0.989×** |
| Groovy-like parse parallel SLL / LL / two-stage | **1.053×** on the matched pair (see §4) / **1.038× / 0.968×** |
| Syntax errors | **0** on every window |

**Verdict.** No cell shows a stable loss on both trials after the closure and `seek` corrections. No cell shows a stable gain either. Where a mean sits a few percent under 1, the mate trial is on the other side of 1 or within a few percent, which is the same swing this host showed on the `913fa97fd` gate (pair ratios from 0.931 to 1.084). Parallel Groovy-like SLL has one baseline trial at about half speed; the other pair is 1.053×. That outlier is not a candidate speedup.

Lex batch with 4 threads was not a separate parallel lexer in this driver: the batch lex loop is serial. Parallel coverage is the parse cells (4 workers, shared DFA).

---

## 2. What the candidate changes on the hot path

The candidate is the `a0b952253` runtime plus every commit after it on `opt`, plus the uncommitted review fixes. The commits between the baseline and this tree already included the precedence LL(1) table and the lexer ASCII edge array. Those stay.

Review fixes that can run on a warm parse or lex:

- Generated Java `_match` and `Parser.match` / `matchWildcard` load one extra boolean, `reportMatchRequired`. It is false for `DefaultErrorStrategy`, so the success path is still a field check. `reportMatch` runs on every success only when a strategy returns true from `isReportMatchRequired()`.
- `CodePointCharStream.seek` rejects a negative index and clamps past EOF. The throw is not in the leaf method.
- Lexer closure records a config only after 64 frames. A normal DFA-miss closure does not probe the map. A same-config cycle stops there instead of pushing one frame per ATN state.
- The LL DFA loop reads the edge map once, then the plain context bit. SLL does not read the bit. An EOF step that does not consume records the DFA state by identity together with the remaining outer context, and only on that EOF arm.

`InterpreterDataReader`, `ArrayEdgeMap`, `RuleDependencyProcessor`, `RuleDependencyChecker`, `UnbufferedTokenStream.getText`, and the `TokenStreamRewriter` delete-overlap print are off the timed path.

---

## 3. Why the first lex-batch dip was not left in

The first 8 s windows hashed every lexer-closure frame. Java lex batch was 0.940× and 0.896× with both pairs under 1. Groovy-like lex batch was 0.883× and 0.850×, same shape. Lex reuse of a single file, whose DFA is hot and does not re-enter closure, was already 0.991× (Java) and 1.008× (Groovy-like). That split says the cost was closure, not the per-character DFA edge.

The map probe now waits until `closureDepth` exceeds 64, not the size of the ATN. Closures shallower than that, which is the lex-batch miss path that was measured, still do not probe the map. A same-config cycle stops within a few frames of that threshold, including when the ATN is large. The remeasured serial lex batch is the table in §4. Java's two pairs are 1.002 and 0.929. Groovy-like's two pairs are 0.902 and 0.996. One trial in each language matches the baseline. The other is a single-window dip. They do not agree on a stable loss. The later review fixes (uncached EOF states, the profiler hook, the dependency checker) are not on that shallow closure or on the warm SLL edge walk. Production full-context DFA walks still use the edge already loaded; only a subclass that overrides `getExistingTargetState` pays a second load.

Java parallel LL was 0.925× on the build that still had the closure map on the miss path. After the leaf `seek` and the single edge load, the same cell is 1.026× (pairs 0.996 and 1.055).

---

## 4. Throughput

Loops are completions of the cell in an 8 s window. A lex-reuse loop is one rebind-and-fill. A batch loop is one pass over 24 files.

### 4.1 Java

| Cell | Baseline loops | Candidate loops | Ratio | Pair ratios |
|---|---:|---:|---:|---|
| Lex reuse, 1 thread | 86 737 | 85 951 | 0.991 | 0.911, 1.083 |
| Lex batch, 1 thread | 3 676 | 3 551 | 0.966 | 1.002, 0.929 |
| Parse serial SLL | 1 215 | 1 219 | 1.004 | 1.034, 0.973 |
| Parse serial LL | 413 | 414 | 1.002 | 1.000, 1.005 |
| Parse serial two-stage | 1 198 | 1 139 | 0.950 | 0.896, 1.015 |
| Parse parallel SLL, 4 threads | 2 476 | 2 307 | 0.932 | 0.975, 0.884 |
| Parse parallel LL, 4 threads | 1 144 | 1 174 | 1.026 | 0.996, 1.055 |
| Parse parallel two-stage, 4 threads | 2 670 | 2 508 | 0.939 | 0.847, 1.038 |

Lex batch and both LL rows are the remeasure. The other rows are the first gate. Serial LL baseline trials were 414 and 412; candidate trials were 414 and 414.

### 4.2 Groovy-like

| Cell | Baseline loops | Candidate loops | Ratio | Pair ratios |
|---|---:|---:|---:|---|
| Lex reuse, 1 thread | 228 677 | 230 458 | 1.008 | 1.033, 0.984 |
| Lex batch, 1 thread | 9 434 | 8 957 | 0.949 | 0.902, 0.996 |
| Parse serial SLL | 3 609 | 3 685 | 1.021 | 1.096, 0.954 |
| Parse serial LL | 178 | 180 | 1.014 | 1.029, 1.000 |
| Parse serial two-stage | 3 593 | 3 553 | 0.989 | 0.931, 1.049 |
| Parse parallel SLL, 4 threads | 3 635 | 4 987 | 1.372 | 2.016, 1.053 |
| Parse parallel LL, 4 threads | 516 | 536 | 1.038 | 1.027, 1.049 |
| Parse parallel two-stage, 4 threads | 5 293 | 5 122 | 0.968 | 0.925, 1.016 |

Parallel SLL's first baseline window was 2 406 loops and the second was 4 863. The candidate windows were 4 851 and 5 122. The 2.016 pair is that first baseline window, not a faster candidate. The matched pair is 1.053×.

---

## 5. Tests

JDK 8, offline Maven.

- `runtime/Java`: 1 196 tests, 0 failures, 0 skipped. JDK 8, offline Maven.
- `tool`: 1 594 tests, 0 failures, 13 skipped, with `TestPerformance` excluded from that run. `TestPerformance.compileJdk` asserts that `JDK_SOURCE_ROOT` is set and parses the JDK sources; that assertion predates this change and was not run. `TestPerformance.testExponentialInclude` is covered separately. The other tool tests passed, including `TestParserProfiler`, `TestUnbufferedCharStream` (the historical `U+FFFF` sentinel stays in the buffer; `LA` treats only the trailing slot as EOF), and `TestCodeGeneration` (generated `_match` gates `reportMatch` on `reportMatchRequired || errorRecoveryMode`).

A read-only review of the diff rejected the first EOF-cycle guard: it ignored `stateNumber < 0`, so a full-context state left at `-1` could still spin, and a repeated state number after a context pop was treated as a cycle. The guard keys on DFA-state identity plus the remaining outer context, and only when `LA(1)` is EOF. States that `addDFAState` does not intern (outermost sets while the global-context DFA is off) are a new object on every reach; those also compare equal by configuration set. `TestClosureAndEofCycle` covers an unnumbered EOF self-edge, a fresh outermost state on every reach, a full-context revisit with a new outer context, and a lexer cycle inside an ATN of more than 200 states. Each spin test has a 2 s timeout.
