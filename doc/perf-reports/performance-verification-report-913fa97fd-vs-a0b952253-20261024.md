# Performance Verification Report

**Baseline:** `a0b952253c257578fb17bb026df7bfcd966fc0a3` (release 4.13.2.17)  
**Candidate:** `913fa97fd95876dce026711d8e5a15aa143daf73` (`Reuse the loaded lookahead when execATN records an ERROR edge.`)  
**Host:** OpenJDK 8 (`1.8.0_504`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`  
**Gate:** fresh JVM per trial. Inside the JVM, 2 s warmup then a 10 s window. 24 synthetic files × 8 methods. Parallel cells use 4 workers. Trial order inside each cell: baseline, candidate, baseline, candidate. Higher loop counts are faster. The ratio is the mean candidate loops divided by the mean baseline loops.  
**Corpus:** Java from `CorpusLoader.syntheticJava7Corpus` (106 112 chars, 35 928 tokens, 2.95 chars/token). Groovy-like from `GroovyCorpus` / `GroovyLike.g4` (48 547 chars, 14 472 tokens, 3.35 chars/token). This repository does not contain the full Groovy grammar. `GroovyLike.g4` is the Groovy-style front end used by the existing serial and parallel harness: `nls` / `sep`, left-recursive expressions, closures, and postfix calls. Lex reuse rebinds one lexer to the first file of that corpus (Groovy-like 2 021 chars, Java 4 419 chars). Batch cells walk all 24 files. `setBuildParseTree(false)`. The worker pool is created before the warmup, so the window is recognizer time, not thread-startup time. Workers share the DFA on the static ATN. Each worker owns its lexer and parser. That is the same threading model the Groovy compiler uses for file-grained parallel compilation.

Every timed window reported zero syntax errors. SLL and LL parse trees, and token fingerprints (type, channel, line, column, start, stop), are identical between the two commits.

---

## 1. Executive summary

| Check | Result |
|---|---|
| Production diff vs `a0b952253` | **3 files, +22 / −2 lines.** None of them is on the warm lexer loop or the warm SLL DFA walk |
| Groovy-like lex reuse (serial) | **1.021×** (291 279 → 297 312 loops / 10 s). Pairs **1.075** and **0.973** |
| Groovy-like lex batch, 1 thread | **0.997×**. Pairs **1.045** and **0.953** |
| Groovy-like lex batch, 4 threads | **1.016×**. Pairs **0.998** and **1.035** |
| Groovy-like parse serial SLL / LL / two-stage | **0.986× / 0.991× / 1.031×** |
| Groovy-like parse parallel SLL / LL / two-stage | **0.965× / 1.023× / 0.987×** |
| Java lex reuse / serial batch / parallel batch | **1.051× / 1.052× / 1.006×** |
| Java parse serial SLL / LL / two-stage | **1.023× / 1.017× / 0.970×** |
| Java parse parallel SLL / LL / two-stage | **1.003× / 0.995× / 0.997×** |
| Pair swing on this host | **0.931–1.084** across the 18 cells. Twelve of the 18 cells have one pair above 1 and one pair below 1 |
| Syntax errors | **0** on all 72 windows |
| Trees and tokens | **SHA-256 identical** (Java and Groovy-like, SLL and LL) |

**Verdict.** `913fa97fd` is not a throughput improvement over `a0b952253`. The only edit on a parse path replaces a second `LA(1)` with the lookahead already held in a local, and only when `computeReachSet` returns null. After warmup that arm is not the steady state. The lexer is untouched. The measured ratios sit inside the pair-to-pair swing of this machine. A cell is not faster because its mean is 1.02 when the two trials disagree about the sign.

---

## 2. What actually differs

`git diff a0b952253 913fa97fd -- runtime/Java/src tool/src` is three hunks.

### 2.1 `ParserATNSimulator.execATN` (this commit)

```java
SimulatorState nextState = computeReachSet(dfa, previous, t, contextCache);
if (nextState == null) {
    // t is still LA(1): computeReachSet does not consume.
    addDFAEdge(previous.s0, t, ERROR);
    return handleNoViableAlt(input, startIndex, previous);
}
```

The previous line was `addDFAEdge(previous.s0, input.LA(1), ERROR)`. `computeReachSet` does not consume, so `t` is still `LA(1)`. The `ERROR` edge is recorded for the same symbol. `handleNoViableAlt` still sees that symbol as the failing token. No alternative changes. The warm path never enters this block: a learned decision returns from `tryLL1Prediction`, `tryPrecedenceLL1`, or `execDFA` while the edge exists.

### 2.2 `ATNDeserializer.testTailCall` (parent of this commit)

The epsilon walk now marks a state in its `BitSet` before pushing successors. Without that mark, an epsilon cycle in a rule follow set never terminates. This runs while an ATN is deserialized, once per recognizer class load, not per token and not per file in the timed window.

### 2.3 `InterpreterDataReader` (same parent)

After the rule-name section, a blank line is peeked. A lexer interp file continues with `channel names:`. A parser file is reset so the following `atn:` read still sees its header. Generated lexers and parsers in this gate do not call `InterpreterDataReader`.

No grammar was regenerated. The same `GroovyLikeLexer`, `GroovyLikeParser`, `JavaLexer`, and `JavaParser` class files sit on both classpaths. Only `runtime/Java/target/classes` changes.

---

## 3. Why a warm Groovy parse does not execute the edit

Parr and Fisher’s Adaptive LL(*) steady state, as implemented on this fork since `a0b952253`, is an SLL DFA. Left-recursive operator and stop decisions return from the `short[][]` precedence table before `execDFA`. ASCII lexer edges are a `DFAState[128]` load inside `LexerATNSimulator.execATN`. That method is unchanged between these two commits.

`ParserATNSimulator.execATN` runs when an edge is missing or a full-context retry starts. The new statement runs only when the reach is empty. On the synthetic Java and Groovy-like corpora, after the 2 s warmup, those misses are gone: two-stage stays on the SLL prediction (the LL-only cells are much slower, which is the full-context cost, and they do not go through this `ERROR` store either once the DFA exists). A second `TokenStream.LA(1)` on that cold arm cannot move a 10 s window.

---

## 4. Throughput

Loops are completions of the cell’s unit of work in a 10 s window. Lex reuse is one rebind-and-fill of the single file. A batch loop is one pass over all 24 files. Two trials, interleaved, fresh JVM each.

### 4.1 Groovy-like lexer and parser

| Cell | Baseline loops | Candidate loops | Ratio | Pair ratios |
|---|---:|---:|---:|---|
| Lex reuse, 1 thread | 291 279 | 297 312 | 1.021 | 1.075, 0.973 |
| Lex batch, 1 thread | 11 921 | 11 890 | 0.997 | 1.045, 0.953 |
| Lex batch, 4 threads | 24 532 | 24 927 | 1.016 | 0.998, 1.035 |
| Parse SLL, 1 thread | 4 664 | 4 597 | 0.986 | 0.948, 1.026 |
| Parse LL, 1 thread | 228 | 226 | 0.991 | 0.987, 0.996 |
| Parse two-stage, 1 thread | 4 593 | 4 736 | 1.031 | 0.979, 1.084 |
| Parse SLL, 4 threads | 12 115 | 11 687 | 0.965 | 0.956, 0.974 |
| Parse LL, 4 threads | 733 | 750 | 1.023 | 1.003, 1.045 |
| Parse two-stage, 4 threads | 11 752 | 11 595 | 0.987 | 0.942, 1.034 |

Serial and parallel lexer are both covered. Serial and parallel parser are covered for SLL, LL, and two-stage SLL→LL. Two-stage matches SLL on this corpus because SLL does not fail, which is why those two rows move together. LL is the expensive full-context strategy; its serial ratio is 0.991 with both trials within 1.3% of the baseline.

The parallel SLL row is the only Groovy-like cell whose candidate was slower in both trials (0.956 and 0.974). The same host, in this same gate, produced a Java LL parallel pair of 1.065 and 0.931. A 3.5% mean on one parallel cell is not separable from that swing. It is not evidence that the `ERROR`-edge edit made parallel Groovy slower. The edit does not run on a warm SLL edge.

### 4.2 Java, same runtime, same protocol

| Cell | Baseline loops | Candidate loops | Ratio | Pair ratios |
|---|---:|---:|---:|---|
| Lex reuse, 1 thread | 107 897 | 113 348 | 1.051 | 1.023, 1.079 |
| Lex batch, 1 thread | 4 331 | 4 555 | 1.052 | 1.027, 1.078 |
| Lex batch, 4 threads | 10 435 | 10 494 | 1.006 | 1.039, 0.975 |
| Parse SLL, 1 thread | 1 412 | 1 445 | 1.023 | 1.037, 1.010 |
| Parse LL, 1 thread | 522 | 531 | 1.017 | 1.025, 1.010 |
| Parse two-stage, 1 thread | 1 465 | 1 421 | 0.970 | 0.942, 1.001 |
| Parse SLL, 4 threads | 3 753 | 3 765 | 1.003 | 0.990, 1.017 |
| Parse LL, 4 threads | 1 609 | 1 600 | 0.995 | 1.065, 0.931 |
| Parse two-stage, 4 threads | 3 881 | 3 870 | 0.997 | 1.033, 0.961 |

Java lex reuse is the cell whose mean looks most like a gain (1.051). Both of its pairs are above 1. It is still not a lexer change: `LexerATNSimulator` is identical in the two commits. The next Java lex cell, the 4-thread batch, is 1.006 with pairs on opposite sides of 1. Treating 1.051 as a real lexer speedup would contradict the diff.

---

## 5. Correctness

One JVM per commit, same 24 × 8 corpus, `buildParseTree(true)` for the trees. SHA-256 over the concatenation.

| Check | Baseline | Candidate |
|---|---|---|
| Java tokens (type, channel, line, column, start, stop) | `f764557c53e8cd1665cd83866852d159bd57376716300f0a4e3150887fcba058` | same |
| Java SLL tree | `fe70432d64a1734cff510d4293adfa9d33d4e8c742bc2f2cef6c5b2c1de3f2cf` | same |
| Java LL tree | `fe70432d64a1734cff510d4293adfa9d33d4e8c742bc2f2cef6c5b2c1de3f2cf` | same |
| Groovy-like tokens | `6b9bd2fbb55b7237eff140a7a21cdaeaa7fc5b1c5ac85766bdf55cb3043dd3da` | same |
| Groovy-like SLL tree | `fd239ea072be18ca17c2aaec085373b5df9a485123b7eb1362b66ee501974f55` | same |
| Groovy-like LL tree | `fd239ea072be18ca17c2aaec085373b5df9a485123b7eb1362b66ee501974f55` | same |
| Syntax errors in every timed window | 0 | 0 |

SLL and LL trees match each other inside each commit as well as across commits. On this corpus the two-stage strategy therefore stays on its SLL attempt, which is what the throughput rows show.

---

## 6. Conclusion

`913fa97fd` is a one-line cleanup of a cold arm of `ParserATNSimulator.execATN`, plus two non-hot fixes that arrived in its parent (deserializer termination, interp-file section peek). Against `a0b952253`, Groovy-like lexer serial, Groovy-like lexer parallel, and Groovy-like parser serial and parallel (SLL, LL, two-stage) do not move by more than the pair-to-pair noise of this host. Java shows the same picture. The commit should not be described as a performance improvement.
