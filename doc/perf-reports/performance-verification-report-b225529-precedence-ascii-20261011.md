# Performance Verification Report

**Baseline:** `b22552915b3e05fc5c67df5c85ec038add2727af`  
(`Add unit tests for lexer/parser hot-path and generated-code changes.`)  
**Candidate:** working tree on that commit. One round landed. Two later experiments were measured and removed (§4).  
**Host:** OpenJDK 8 (`1.8.0_504`, Corretto), 6 processors, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`  
**Gate:** `GateBench`, 24 synthetic files × 8 methods, 4 parallel workers, 4-second windows, two trials averaged. Trial order: base → round 1 → rejected build → rejected build → round 1 → base. The table uses the base and round-1 legs.  
**Corpus:** Java from `CorpusLoader.syntheticJava7Corpus`. Groovy-like from `GroovyCorpus` (`nls` / `sep`, left-recursive expressions, closures, postfix calls). Parallelism is file-grained. Workers share the DFA on the static ATN, the same threading model Groovy uses to compile files.  
**Earlier throughput check:** `ParseLoop`, same Java corpus shape (24 × 8), 8 seconds after warmup.

Parse trees for Java and Groovy-like inputs match the baseline byte for byte under SLL and LL. Token type, channel, line, column, and start/stop indexes match. Every timed run reported zero syntax errors.

---

## 1. Executive summary

| Check | Result |
|---|---|
| Protocol | Clean worktree at `b225529` vs the landed round; identical synthetic corpora; runtime classes on the front of the classpath |
| Java serial two-stage | **1.283×** (479.5 → 615.0 loops / 4 s) |
| Java parallel two-stage | **1.149×** (1240.5 → 1425.5) |
| Java serial SLL | **1.250×** (525.0 → 656.0) |
| Java parallel SLL | **1.157×** (1289.5 → 1492.5) |
| Java serial LL | **1.142×** (186.5 → 213.0) |
| Java parallel LL | **1.131×** (582.0 → 658.0) |
| Groovy-like serial two-stage | **1.292×** (1578.5 → 2039.0) |
| Groovy-like parallel two-stage | **1.117×** (2722.5 → 3040.0) |
| Groovy-like serial SLL | **1.270×** (1612.0 → 2047.5) |
| Groovy-like parallel SLL | **1.110×** (2735.0 → 3037.0) |
| Groovy-like serial LL | **1.113×** (80.0 → 89.0) |
| Groovy-like parallel LL | **1.028×** (286.0 → 294.0) |
| Java lexer reuse | **1.438×** (30383.5 → 43684.5) |
| Groovy-like lex | **1.457×** (73841.0 → 107579.5) |
| ParseLoop serial / parallel / SLL | **1.245× / 1.093× / 1.213×** (8 s, Java two-stage and SLL) |
| Syntax errors | **0** |
| Runtime tests | **1112 / 0 failures** (`mvn -pl runtime/Java test`) |
| Tool tests | **1592 run, 1 pre-existing failure** (`TestPerformance` requires `JDK_SOURCE_ROOT`), 13 skipped |
| HPPC on public/protected APIs | **none** (`TestHppcApiBoundary`) |

**Verdict.** On an already-optimized fork, the warm path is DFA edge walks plus token traffic. This round shortens two of those walks. Left-recursive operator/stop decisions return from a `short[][]` once they are known, and ASCII lexer edges are a plain array load. All 14 throughput cells are faster than `b225529`. A follow-on build that fused `consume` with `LA(1)`, skipped no-op `mark`/`release`, and replaced `LT(-1)` on left-recursive context push/pop was still ahead of the baseline and behind this round on every cell, so it was removed.

---

## 2. What landed

### 2.1 Precedence LL(1)

After warmup, ALL(*) does not resimulate the ATN. The steady state in Parr and Fisher’s Adaptive LL(*) paper is an SLL DFA walk, with a full-context retry only when SLL conflicts. Java and Groovy expressions are directly left-recursive. The runtime rewrites them as a precedence DFA: each operator loop calls `adaptivePredict`, picks a start state from the current precedence, and looks at one token to continue or stop. That decision consumes no input.

The previous code paid `getPrecedenceStartState` plus one DFA edge on every such call. The new table records `(decision, precedence, LA(1)) → alternative` in a `short[][]` on the ATN. Zero is a miss. A hit returns before the precedence-start walk and before `execDFA`.

An entry is stored only when all of the following hold:

- `optimize_ll1` is on
- prediction consumed no input
- the outer context was not rewritten
- the config set has no semantic context (precedence predicates were already filtered at the precedence start state)
- there is a single alternative, with no conflicting alts
- the simulation is local context, not full context

`force_global_context` and `always_try_local_context == false` are applied before the probe. A call that must run in full context does not read the SLL entry. The defaults (`force_global_context == false`, `always_try_local_context == true`) stay on the probe. A full-context retry still calls `adaptivePredict(..., useContext=true)` and misses the table. `clearDFA()` drops it.

Stores are plain `short`s. The first alternative written for a slot sticks. A racer may still observe zero and take the DFA. A torn alternative cannot appear.

### 2.2 Lexer ASCII edges

Identifier, whitespace, and operator edges are code points. `HashEdgeMap.get` hashes and then reads the key from an `AtomicIntegerArray` on a hit. Java and Groovy source is almost entirely U+0000..U+007F. Letters sit at 97..122. Whitespace is 9, 10, 13, and 32. All of those fit in 128 slots.

The first ASCII edge on a lexer state publishes a `DFAState[128]`. The hot lookup is one volatile read and one array load. Code points at or above 128 stay on the existing hash map. When that map has grown so every key lies inside the table, `setTarget` also publishes an identity-indexed array for the wide path. `HashEdgeMap.get` itself has no layout branch, so parser edge walks are unchanged.

Parser maps include EOF (`-1`). Precedence-start maps cover `0..200`. Both have `maxIndex` far below the lexer’s `U+10FFFF`, so they never allocate the ASCII table. `DFAState.getTarget` is unchanged. On a parser state, `getLexerTarget` returns the same state as `getTarget`.

---

## 3. Compatibility

- `ATN.LL1Table` remains `protected ConcurrentMap<Integer,Integer>`. `precedenceLL1` is package-private and is not part of the API.
- `DFAState.getLexerTarget` is a new public method used by the lexer simulator. Parser states keep the previous edge-map lookup.
- No HPPC type appears on a `public` or `protected` signature. The new tables are `short[]` and `DFAState[]`.
- Java target templates are unchanged. Existing generated Groovy and Java recognizers pick up the runtime change without regeneration.
- Tree shape, token channels, line/column, and error recovery match the baseline (§5). `nls : NL*` stays an `adaptivePredict` loop. `FOLLOW(nls)` includes `NL` through `sep`, so a greedy `while (LA == NL)` would starve statement separators.

---

## 4. Experiments that were removed

Each later round had to beat the previous round on every cell. The three changes below parsed identically and then lost the throughput gate against round 1.

| Experiment | Intent | Measurement against round 1 | Disposition |
|---|---|---|---|
| `CodePointCharStream.consumeAndLA1` | One call per character instead of `consume` plus `LA(1)` | After the loop was split out so it could inline: Java lexer reuse **0.975×**, Groovy-like lex **0.946×**. The fatter loop did not stay as tight as the ASCII array. | Removed |
| Skip `mark` / `release` on `CodePointCharStream` | Drop two interface calls per token | Both methods are already empty and inline to nothing through a monomorphic receiver. The added `instanceof` made all 14 cells slower (lex about **0.90×–0.94×**). | Removed |
| `lastConsumed` in `pushNewRecursionContext` / `unrollRecursionContexts` | Avoid the backward on-channel scan in `LT(-1)` per operator | Measured together with the row above. Parse cells fell with the lexer regression. Trees still matched, so the loss was overhead, not a wrong alternative. | Removed |

Round 1 beat the baseline on all 14 cells. Stacking the rejected experiments stayed ahead of the baseline and behind round 1 on every cell. The tree keeps round 1.

---

## 5. Correctness

| Check | Result |
|---|---|
| Java SLL / LL trees, 6 files × 4 methods | Byte-identical to the baseline |
| Groovy-like SLL / LL trees | Byte-identical to the baseline |
| Token stream (type, channel, line, column, start, stop) | Identical between the landed classes and the rejected build; landed trees match the baseline |
| Syntax errors on the throughput gate | **0** |
| `mvn -pl runtime/Java test` | **1112** tests, 0 failures |
| `mvn -pl runtime/Java,tool -am test` | Tool: **1592** tests, 13 skipped, 1 failure. `TestPerformance` asserts that `JDK_SOURCE_ROOT` is set. That assertion is present on the baseline and is independent of this change |
| Stop token of `1+2*3` | The operand `3`, not EOF (`TestPrecedenceLL1Cache`) |
| Cache on vs off, including a second parse on a warm table | Trees match (`TestPrecedenceLL1`, `TestPrecedenceLL1Cache`) |
| `force_global_context` and `always_try_local_context == false` after the SLL table is warm | Trees match a simulator that never fills the table (`TestPrecedenceLL1.fullContextFlagsDoNotReuseSllPrecedenceEntry`) |
| Full-context and closure suites | `TestDeepCoveragePush4`, `TestDeepCoveragePush5`, `TestGlobalContextPredictionAndInterpreterRecover`, `TestParserATNSimulatorCoverage`: **green** after the flag-order fix |

A cache hit is the same fact the DFA already records: one SLL edge, no input consumed, one alternative. When SLL reports a unique alternative, LL reports that same alternative. That inclusion is part of ALL(*), not a new approximation.

---

## 6. Tests and the API boundary

- `TestPrecedenceLL1` — table growth, out-of-range keys, stride changes, `clearDFA`, first-writer-wins under four threads, tree parity with the cache off, and the full-context flag cases above.
- `TestPrecedenceLL1Cache` (tool) — `e : e '*' e | e '+' e | INT` under SLL and LL, cache on and off, cold and warm, plus the expression stop token.
- `TestDFA` — ASCII bounds 0 and 127, code point 128 falling through to the hash map, edge replacement, parser states (including EOF) staying off the ASCII table.
- `TestEdgeMaps` — lexer keys becoming identity-indexed, parser negative keys never identity-indexed, key 0 against an empty slot whose key word is already 0, demotion when a key falls past the table, identity preserved across remove.

Early returns in `cachedPrecedenceAlt` / `cachePrecedenceAlt` (negative decision, negative precedence, stride overflow, non-positive alternative, `clearDFA`) each have a direct assertion.

---

## 7. Limits

- Single-file and cold-start cells move more than these 14 four-second windows. The gate is the paired throughput runs, where all 14 cells moved the same way.
- Groovy-like is a compact grammar, not the full ATN from `GroovyParser.g4`. Production Groovy has the same `nls` / `sep` shape and the same left-recursive expression decision. The hot runtime sites are the precedence probe and the lexer edge. Templates did not change, so a regeneration is not required to pick this up.
- Each precedence decision holds at most `(201 × stride)` shorts. A stride above 4096 does not allocate the table and stays on the DFA. Java and Groovy vocabularies sit well under that cap.
- Each lexer state that has seen an ASCII edge holds a 1 KB array. Identifier and whitespace states dominate the character stream. States that only have high code points do not allocate it. Parallel two-stage stayed at **1.149×** (Java) and **1.117×** (Groovy-like). This is the opposite of the earlier experiment that grew every `Transition` and lost parallel DFA walks.

---

## 8. Flame graph

Arthas 4.3.5 (`~/as.sh`) attached to a 25-second Java serial two-stage `ParseLoop` and sampled CPU for 12 seconds (`profiler start --event cpu --duration 12`). The graph is `doc/perf-reports/flame-serial-two-stage-20261011.html`.

After warmup the visible names are `nextToken`, `Lexer.emit`, `CommonToken.getType`, `BufferedTokenStream.LA`, `consume`, `enterRecursionRule`, `pushNewRecursionContext`, `unrollRecursionContexts`, `exitRule`, and `setState`. `getLexerTarget` does not appear as its own frame, which matches it being inlined into the lexer DFA loop. Left-recursive context traffic and the lexer/token path are the frames that remain. The precedence table removes the no-consume edge inside `adaptivePredict`. The ASCII table removes the lexer edge probe. ATN closure (`computeTargetState` / `addDFAEdge`) shows up only on the cold edge.

---

## 9. Artifacts

| Kind | Path |
|---|---|
| Three-way throughput (base / round 1 / rejected build) | `/tmp/perf-final/*.tsv` |
| Tree diff | `/tmp/perf-r1/trees-base.txt` and `trees-final.txt` (identical) |
| Token diff | `/tmp/perf-r2/tokens-r1.txt` and `tokens-final.txt` (identical) |
| Runtime test log | `/tmp/mvn-runtime-full.log` |
| Tool test log | `/tmp/mvn-tool-full.log` |
| Flame graph | `doc/perf-reports/flame-serial-two-stage-20261011.html` |
| Review notes | `/tmp/grok-review-antlr-precedence.md` |
