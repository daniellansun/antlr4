# Optimized Fork

## Overview

The optimized fork of ANTLR 4 originated with Sam Harwell at Tunnel Vision Laboratories, LLC, and is continued and extended in this repository (`daniellansun/antlr4`, published under Maven group `me.sunlan` targeting Java 8). It builds on the reference ANTLR 4 implementation with grammar features, runtime architectural improvements, and Java code-generation optimizations aimed at large, demanding, production-scale recognizers such as Apache Groovy's Parrot parser.

In general, features and optimizations in this fork are not part of the reference ANTLR 4 release for one or more of the following reasons:

* **Implementation complexity:** Many mechanisms were introduced to solve throughput, allocation, or memory-footprint bottlenecks observed in large-scale commercial and language-tooling workloads. Their implementations can be intricate and tightly tuned to JVM behavior, whereas the reference release prioritizes simplicity and maintainability for educational use and small- to medium-sized grammars.
* **Specialized scope:** Several grammar transformations were designed for specific large grammars and may not handle every corner case of arbitrary user grammars.
* **Configuration and tuning trade-offs:** Certain high-impact optimizations can substantially accelerate some grammars while increasing memory consumption or regressing throughput on others. Using them effectively requires grammar-specific measurement rather than one-size-fits-all defaults.
* **Internal dependencies:** Hot-path primitive collections rely on HPPC (`com.carrotsearch:hppc`), which is kept strictly internal and shaded into the published runtime artifact to avoid transitive dependency conflicts.

---

## Grammar and Runtime Features

### Base Context

**Reason for exclusion:** Specialized scope (not guaranteed to work across all grammars)

Multiple parser rules can be instructed to share a single parse-tree context type via the `baseContext` rule option. This is especially useful for specification-derived grammars (such as a [Java 8 grammar](https://github.com/sharwell/antlr4/blob/java8-grammar/tool/test/org/antlr/v4/test/Java.g4)) where syntactic variants must be separated in the grammar without duplicating downstream parse-tree visitor or listener logic:

```antlr
primitiveType
    :   annotation* numericType
    |   annotation* 'boolean'
    ;

unannPrimitiveType
options { baseContext=primitiveType; }
    :   numericType
    |   'boolean'
    ;
```

In the example above, `unannPrimitiveType` disallows leading annotations syntactically, yet produces a `PrimitiveTypeContext` node in the resulting parse tree.

### Automated Left Factoring

**Reason for exclusion:** Specialized scope (not guaranteed to work across all grammars)

A rule can automatically left-factor a named rule reference out of its alternatives via `@leftfactor{...}` without altering the parse tree shape produced for valid input. This transformation was originally created for GoWorks to cut lookahead depth and DFA memory consumption without rewriting downstream code that depended on the original tree structure (for example, factoring `expression` out of `simpleStmt` in [GoParser.g4](https://github.com/tunnelvisionlabs/goworks/blob/5543a633dfc0d9b0e2ab407445a82bc70b24f100/goworks.editor/src/org/tvl/goworks/editor/go/parser/GoParser.g4#L435-L444)):

```antlr
simpleStmt
@version{1}
@leftfactor{expression}
    :   shortVarDecl
    |   sendStmt
    |   incDecStmt
    |   assignment
    |   expressionStmt
    |   emptyStmt
    ;
```

> :warning: Although the parse tree shape is preserved for *valid* input, automated left factoring can alter error-recovery behavior. On syntactically invalid input, recovery may produce parse trees whose node arrangement would not be reachable in the unfactored grammar.

### Indirect Left Recursion Elimination

**Reason for exclusion:** Specialized scope (not guaranteed to work across all grammars)

This fork extends ANTLR 4's direct left-recursion transformation to eliminate indirect ("mutual") left recursion, building on the same grammar-rewriting infrastructure as automated left factoring.

### Rule Versioning

**Reason for exclusion:** Implementation complexity

Rules can be annotated with `@version{N}`, emitting `@RuleVersion(N)` on the generated rule methods. Together with `@RuleDependency` / `@RuleDependencies` and the compile-time `RuleDependencyProcessor` / runtime `RuleDependencyChecker`, this lets downstream listeners, visitors, and IDE integrations declare and verify compatibility with specific rule revisions. See the [Rule Versioning](https://github.com/sharwell/antlr4/wiki/Rule-Versioning) design notes for details.

### `@NotNull` and `@Nullable` Annotations

**Reason for exclusion:** Implementation complexity

The companion `me.sunlan:antlr4-annotations` artifact provides `@NotNull` and `@Nullable` annotations (`org.antlr.v4.runtime.misc`) together with `NullUsageProcessor`, a `javac` compile-time annotation processor. During compilation of the runtime (and optionally consumer code), `NullUsageProcessor` validates nullability contracts—reporting errors for conflicting `@NotNull`/`@Nullable` pairs, annotations on `void` methods, `@Nullable` on primitive types, and incompatible override variance (such as widening a `@NotNull` return type to `@Nullable` or narrowing a `@Nullable` parameter to `@NotNull`).

### Bare Carriage Return (`\r`) Line Endings

**Reason for exclusion:** Behavioral extension introduced for real-world legacy source files (since `4.13.2.19`)

Both `LexerATNSimulator.consume` (including the `consume(CharStream, int)` overload used on the DFA walk) and `CodePointLexerRunner` treat a standalone carriage return (`\r` not immediately followed by `\n`, including `\r` at EOF and consecutive `\r\r`) as a line break, incrementing the line number and resetting `charPositionInLine` to `0`. Standard `\r\n` pairs remain a single line break whose line increment occurs on `\n`, leaving `\r\n` and `\n` line/column coordinates unchanged. `Lexer.recover(RecognitionException)` advances through the same path, keeping token positions consistent during recovery.

### Backwards Compatibility

**Reason for exclusion:** Implementation complexity

The optimized fork places strong emphasis on preserving source and binary compatibility across runtime upgrades. In most cases, existing generated recognizers continue to run without regeneration when upgrading `me.sunlan:antlr4-runtime` (for example, legacy generated parsers that call `match`, `getInterpreter().adaptivePredict`, or `_errHandler.sync(this)` remain fully supported). Regenerating grammars with the matching tool version is nonetheless recommended to unlock the Java code-generation optimizations described below.

---

## Runtime Optimizations

### Prediction Strategy and DFA Architecture

#### Improved SLL Termination Condition

**Reason for exclusion:** Implementation complexity

The parser ATN simulator enforces a stronger termination condition during SLL prediction, allowing SLL conflicts to be detected with fewer lookahead symbols than in the reference release. Under normal parsing this is transparent, while the shorter lookahead improves throughput and can yield cleaner error recovery in edge cases.

#### Full-Context DFA

**Reason for exclusion:** Implementation complexity; unproven worst-case impact on ALL(\*) complexity

Whereas reference ANTLR 4 maintains a DFA only for local-context (SLL) prediction, this fork optionally caches full-context (LL) transitions in the DFA as well.

> :bulb: Full-context DFA caching is **disabled by default** (`ParserATNSimulator.enable_global_context_dfa = false`). It can be enabled per parser instance:
>
> ```java
> Parser myParser = new MyParser(input);
> myParser.getInterpreter().enable_global_context_dfa = true;
> ```
>
> :warning: Enabling `enable_global_context_dfa` can substantially increase DFA memory consumption. For grammars that rarely enter full-context prediction—especially under two-stage parsing (`SLL` then `LL`)—the memory overhead may outweigh the speed benefit. Leave this option disabled initially and enable it only after profiling demonstrates that full-context recomputation is a bottleneck. For certain heavily context-sensitive grammars, however, this option is essential for acceptable performance.

#### Tail Call Elimination

**Reason for exclusion:** Incomplete formal specification

During ATN closure, the simulator compacts `PredictionContext` chains by eliding redundant return states on tail calls (`ParserATNSimulator.optimize_tail_calls`, default `true`). Eliminating unnecessary stack frames reduces context-graph depth and allows equivalent `DFAState` instances to be shared where the reference runtime treats them as distinct.

> :bulb: Tail-call compaction is governed by `ParserATNSimulator.tail_call_preserves_sll` (default `true`):
>
> * **`tail_call_preserves_sll = true` (default):**
>   * Preserves maximum `PredictionMode.SLL` accuracy. Setting this to `false` can cause pure SLL parsing to report a syntax error on inputs that succeed when `true`.
>   * Minimizes SLL lookahead length. Setting this to `false` can lengthen SLL lookahead (even under `PredictionMode.LL`), which has been observed to degrade overall throughput on some grammars.
> * **`tail_call_preserves_sll = false`:**
>   * Further reduces DFA state count and memory footprint. In two-stage parsing setups where occasional SLL fallback is acceptable and lookahead does not hit pathological cases, disabling preservation can noticeably shrink the DFA.

#### Adaptive DFA Edge Maps and Lock-Free Lookups

**Reason for exclusion:** Implementation complexity (the reference release implements only a subset)

Each `DFAState` dynamically selects an `AbstractEdgeMap` implementation according to its outgoing edge count (`EmptyEdgeMap` → `SingletonEdgeMap` → `HashEdgeMap` → `ArrayEdgeMap`), keeping sparse DFA regions compact while giving dense states O(1) array lookup:

* **`HashEdgeMap`:** Uses an open-addressed power-of-two table starting at capacity 8 (`DEFAULT_MAX_SIZE = 8`), promoting to `ArrayEdgeMap` when density approaches half of the allowed symbol range. Structural writes update `keys[bucket]` before release-publishing the target through `AtomicReferenceArray.lazySet`; lock-free readers acquire-load from `values` first to establish full JMM happens-before ordering. An `AtomicInteger` occupancy counter makes `size()` and `isEmpty()` O(1), while `toMap()` stays a cold diagnostic path (sorted `TreeMap` for deterministic `DFASerializer` output). Lexer maps whose keys all lie in `[0, tableLength)` may also use an identity-indexed layout (`directArray()`).
* **Exact-class edge dispatch (`DFAState.getTarget`):** Dispatches directly on the final map classes (`HashEdgeMap`, `ArrayEdgeMap`, `SingletonEdgeMap`) so HotSpot C2 eliminates megamorphic virtual stubs and inlines edge lookups into warm prediction loops.
* **Lock-free ASCII and wide lexer tables (`DFAState.getLexerTarget`):** For lexer states (`minIndex >= 0 && maxIndex > 0xFFFF`), `DFAState` maintains a copy-on-write length-128 `volatile DFAState[] asciiEdges` table for ASCII code points (`0..127`) and a `volatile AtomicReferenceArray<DFAState> directValues` reference for identity-indexed wider ranges. Copy-on-write volatile publication guarantees full JMM happens-before safety across x86, ARM64, and POWER while keeping warm ASCII lookups to a single volatile read plus array load (within HotSpot C2's 35-byte `MaxInlineSize`).
* **O(1) DFA emptiness and context-sensitivity checks:** `DFA.isEmpty()` and `DFA.isContextSensitive()` delegate to `DFAState.isEdgesEmpty()` and `DFAState.isContextEdgesEmpty()` rather than calling `getEdgeMap().isEmpty()`, avoiding cold `TreeMap` materialization (historically profiled at ~17% CPU on precedence-DFA parses). In addition, warm LL(1), precedence LL(1), and warm SLL calls in `ParserATNSimulator.adaptivePredict` bypass `DFA.isEmpty()` entirely; only `adaptivePredictSlow` consults it for precedence DFAs.
* **Compact state metadata:** `DFAState` caches immutable `minEdgeIndex`, publishes `volatile BitSet contextSymbols` copy-on-write for lock-free concurrent LL reads, and caches `unconflictedPrediction` (`getUnconflictedPrediction()`) before safe DFA publication so warm SLL lookups collapse `acceptStateInfo` and `configs.getConflictInfo()` checks into a single field read.

---

### ATN Data Structures and Prediction Context Caching

#### Compact `ATNConfig` Hierarchy and Hash Caching

**Reason for exclusion:** Implementation complexity

To minimize DFA memory overhead (often saving 20 MiB or more on large grammars), `ATNConfig` uses specialized concrete subclasses selected automatically by `ATNConfig.create` / `transform` (`ATNConfig`, `SemanticContextATNConfig`, `ActionATNConfig`, `ActionSemanticContextATNConfig`) so default parser configurations store only the fields they need:

* **32-byte base layout (64-bit CompressedOops):** Alternative number, outer-context depth (saturated at `0x7F`), and the precedence-filter suppression flag are bit-packed into `altAndOuterContextDepth`. The `passedThroughNonGreedyDecision` flag sits in base-class alignment padding, keeping object size at 32 bytes while keeping `hasPassedThroughNonGreedyDecision()` monomorphic without promoting parser configs to `ActionATNConfig`.
* **Cached MurmurHash3 (`cachedHashCode`):** `ATNConfig.hashCode()` computes an inline bit-exact MurmurHash3 once and caches it (`0` is reserved as the uncomputed sentinel; a raw hash of `0` is biased to `1`). Mutating `context` or toggling `reachesIntoOuterContext` invalidates the cache. `ATNConfig.equals` rejects unequal cached hashes before performing structural comparison. This accelerates lexer `OrderedATNConfigSet` lookups, epsilon-closure busy-set probes, and DFA state hashing.
* **Zero-allocation merge-on-hit (`ATNConfigSet.addTransformed`):** On base `ATNConfigSet`, `EpsilonClosure` and `ReachComputation` probe the merge index by packed `(targetState, alt)` *before* allocating a transformed `ATNConfig`. On a merge hit, the existing entry's context is merged in place (with hash invalidation whenever `mergeConfigContext` flips `reachesIntoOuterContext`); on a miss, a fresh transformed instance is allocated so templates are never aliased.

#### Exact `PredictionContext` Reduction and Monomorphic `PredictionContextCache`

**Reason for exclusion:** Implementation complexity

The fork implements an exact merge algorithm for `PredictionContext` graphs, ensuring contexts stored in the DFA are fully reduced (maximally shared) rather than leaving redundant unreduced subgraphs.

* **Fast canonicalization (`PredictionContext.getCachedContextInternal`):** Walks pure `SingletonPredictionContext` chains iteratively without allocating a visited map, falling back to an internal HPPC `ObjectObjectIdentityHashMap` only when an `ArrayPredictionContext` node is encountered.
* **Monomorphic `PredictionContextCache`:** Backed by three specialized open-addressed tables at 0.5 load factor—`ContextInternSet`, stride-2 `ChildContextMap`, and stride-3 `JoinContextMap`—sharing capacity, sparse-clear tracking (`SPARSE_TRACK_LIMIT = 4096`), and shrink-on-clear (`MAX_RETAINED_CAPACITY = 16384`) logic through `private` `OpenTableBase`. This eliminates `HashMap.Node` and wrapper-key allocations during cold DFA construction and full-context LL(\*) prediction, performs O(size) sparse clears on reuse, and shrinks tables that temporarily grew during an outlier decision.

#### Frozen ATN Snapshots and O(1) `IntervalSet` Bitsets

**Reason for exclusion:** Implementation complexity

* **Single-buffer deserialization (`ATNDeserializer`):** `deserialize(String)` converts the serialized literal with a single `toCharArray()` call and decodes the `+2` shift in place (avoiding a second defensive clone). `deserialize(char[])` preserves its defensive `clone()` for full backwards compatibility when callers may reuse the input array.
* **Frozen ATN state and transition arrays:** Once deserialization and ATN optimization complete, `ATNDeserializer` calls `ATNState.freezeOptimizedTransitions()` on every state and `ATN.freezeStatesSnapshot()` on the ATN:
  * Each `ATNState` caches its constant `stateType`, freezes `optimizedTransitions` into a `Transition[]` (`optimizedTransitionsArray`), and precomputes `singleNonEpsilonTransition` and `noNonEpsilonOptimizedTransitions` so `ReachComputation` skips array loading and loop iteration on single-edge and epsilon-only states. Any subsequent mutation to transitions automatically invalidates the snapshot.
  * `ATN.statesSnapshot` backs closure return-state lookups with a plain array load rather than `ArrayList.get`.
* **O(1) `IntervalSet` lookup (`fastBits`):** During read-only sealing (`setReadonly(true)`—invoked automatically for every `SetTransition` set at the end of `ATNDeserializer`), immutable sets with more than 2 disjoint intervals whose elements all fall within `[-2..511]` (`Token.EPSILON` through `511`) materialize a compact `long[]` bitmask (`fastBits`) so `contains(int)` executes in O(1) without binary search or unsynchronized lazy initialization.

---

### Parser ATN Simulation and Hot-Path Engines

All per-simulator retained caches and scratch buffers assume ANTLR's standard threading model: **one thread owns a given simulator instance for the duration of a parse or lex call**, while `ATN`, `DFA` tables, and `PredictionContext` graphs are safely shared across threads.

#### Dense LL(1) and Precedence LL(1) Prediction Tables

Before touching any volatile DFA start-state reference or allocating simulator state, `ParserATNSimulator.adaptivePredict` (when `optimize_ll1` is enabled) probes lock-free primitive arrays on `ATN`:

* **Standard decisions (`ATN.ll1Dense`):** A flat `short[]` table indexed by `decision * ll1Stride + la` (`0` = miss) answers cached single-token LL(1) decisions without hashing or boxing. `ATN.ll1Cache` (`ConcurrentIntIntMap`) and `protected final ConcurrentMap<Integer, Integer> LL1Table` (`ConcurrentIntIntMapView`) remain synchronized with `ll1Dense` to preserve full subclass and test compatibility.
* **Left-recursive precedence decisions (`ATN.precedenceLL1`):** Per-decision `PrecedenceTable` objects store flat `short[]` arrays indexed by `precedence * stride + la` for precedences in `0..200` (when vocabulary stride `<= 4096`), capturing the steady state of left-recursive expression loops (operator-or-stop decisions that accept without consuming input or evaluating semantic predicates).
* **In-place `ATN.clearDFA()`:** Reuses existing `DFA` objects via `DFA.clear()` whenever decision/mode counts are unchanged, avoiding per-decision reallocation on cache flushes.

#### Cursorless Warm SLL Fast Path

For base `ParserATNSimulator` instances on warm local-context decisions (`!useContext && isBaseSimulator && !debug && !dfa_debug`), `adaptivePredict` (367 B, `exc=0`) dispatches directly to `execDFASllWarm` (509 B, `exc=0`, zero `try/finally`):

* Handles **both single-token and multi-token** warm SLL accepts without allocating `SimulatorState`, mutating `this.dfa` (avoiding G1GC write barriers), or calling `input.mark()` / `consume()` / `seek()` / `release()`.
* Multi-token lookahead on base `CommonTokenStream` reads ahead via cursorless `CommonTokenStream.nextOnChannel(int)`, keeping the stream cursor (`p`) and `cachedLTMinus1` intact.
* Intentionally kept above HotSpot C2's 325-byte `FreqInlineSize` so it compiles as a dedicated C2 root with full inlining budget for `DFAState.getTarget` and `CommonTokenStream.nextOnChannel`, while cold paths stay strictly out-of-line in `execDFASllWarmSlow` (342 B), `adaptivePredictSlow` (281 B), and `completeAccept` (720 B).

#### Unified `EpsilonClosure`, `ReachComputation`, and Zero-Sort `ConflictDetector`

To keep `ParserATNSimulator` orchestration-focused without diverging fast and subclass-overridable semantics, ATN simulation is factored into three lazily allocated, package-private per-simulator engines:

* **`EpsilonClosure`:** Unifies both the base direct walker (`walk`, which carries `(state, template)` lazily along pure-epsilon chains, tail-loops on single/final edges, and merges via `ATNConfigSet.addTransformed`) and overriding-subclass virtual dispatch (`walkVirtual`) around a single closure core and shared rule-stop / precedence-suppression logic. When EOF is not treated as epsilon, non-epsilon transitions are skipped before `getEpsilonTarget`.
* **`ReachComputation`:** Encapsulates `computeTargetState` using `ATNState.singleNonEpsilonTransition` / `noNonEpsilonOptimizedTransitions` fast checks, index-based config walks (no `Iterator` allocations), and a retained `ReachConfigSource` view that stays zero-copy on SLL paths and lazily materializes a capacity-preserving rewrite list only when full-context step-out calls `appendContext`. Both unique-closure and general reach paths converge through `finishTargetEdge`.
* **`ConflictDetector`:** Implements Sam Harwell's exact join-semilattice conflict definition in 3-pass O(n) time using lazily allocated, sparsely cleared open-addressed primitive arrays—completely eliminating `DualPivotQuicksort.sort` during DFA state construction (with a `ClearableLongObjectHashMap` fallback for read-only or duplicate-entry sets).
* **O(D) bottom-up `computeStartState`:** Uses a lazily retained `int[] startReturnStates` stack on `ParserATNSimulator` to construct full-context start states in linear time relative to call-stack depth `D`.

#### Retained Scratch Buffers and Occupancy-Aware Clearing

Hot ATN reach and closure paths reuse lazily allocated scratch sets rather than allocating fresh `ATNConfigSet` instances on every missing DFA edge:

| Owner (all per-simulator, single-threaded) | Retained Scratch Structures |
| --- | --- |
| `ReachComputation` | `retainedReach` (`RetainedConfigSet`), `retainedIntermediate` (`RetainedConfigSet`), `retainedReachConfigs` (`ReachConfigSource`) |
| `ParserATNSimulator` | `retainedStartConfigs` / `retainedStartIntermediate` (`RetainedConfigSet`), `retainedPrecedenceAlt1` (`ClearableIntObjectHashMap`), `startReturnStates` (`int[]`), `retainedContextCache` (`PredictionContextCache`), `conflictDetector` (`ConflictDetector`) |
| `EpsilonClosure` | `closureBusy` (`OpenAddressedHashSet` typed as `Set<ATNConfig>`), double-buffered `bufferA` / `bufferB` (`ATNConfigSet`) |
| `LexerATNSimulator` | `retainedReach` (ordered `RetainedConfigSet`), lazy `closureBusy` (allocated only when `closureDepth > 64`), lazy `predTrace` (`LexerPredEdgeTable.PredTrace`) |

**Ownership contract:** `RetainedConfigSet.obtain(sourceSize)` always returns an empty writable set (sized via `ATNConfigSet.scratchCapacity`); `release()` runs in `finally` to drop config-graph references while preserving backing capacity. DFA states always receive a read-only `clone`, never a retained buffer. `ATNConfigSet.clear()` also resets `outermostConfigSet` so flags cannot leak across edges.

**Eliminating wholesale `Arrays.fill` clear overhead:** Stock HPPC `clear()` fills the entire open-addressed table (O(capacity)), even when already empty. Calling `clear()` defensively on both `obtain()` and `release()` previously caused double full-table zeroing on every DFA miss (~13% of lexer CPU on Groovy compilation). This fork eliminates that cost via:

| Mechanism | Effect |
| --- | --- |
| `ClearableLongObjectHashMap` / `ClearableIntObjectHashMap` / `OpenAddressedHashSet` | `clear()` is O(1) when already empty (no backing-array fill) |
| `ATNConfigSet.clear()` sparse path | When `size * 4 < tableLength` (`SPARSE_CLEAR_CAPACITY_FACTOR = 4`), removes only occupied merge keys in O(n) instead of zeroing the full table (with a safety fallback if any entry remains) |
| `ArrayList.clear()` | Nulls only the used prefix `[0, size)`, never full capacity |
| `RetainedConfigSet` obtain + release | Both remain defensive; `release()` clears once after work and the next `obtain()` is a free O(1) empty clear |

---

### Lexer Simulation, Character Streams, and Token Streams

#### Fast `CodePointCharStream` and Direct `CodePointLexerRunner`

* **`CodePointCharStream` & `CharStreams`:** `CharStreams.fromString` constructs Latin-1 (`byte[]`), BMP (`char[]`), or supplementary (`int[]`) backing buffers directly without intermediate `ByteBuffer` wrappers, widening lazily only when non-Latin-1 or surrogate code points appear. `LA(1)` is a compact position/size compare plus direct array load (`laSlow` outlined). `getText(int start, int stop)` (since `4.13.2.19`) avoids `Interval` allocations, uses 64-bit overflow-safe bounds math (`computeTextLength`), returns cached single-character `ASCII_STRINGS[0..127]` and `"\r\n"`, and performs direct Latin-1 `char[]` widening on JDK 8. The concrete subclasses `CodePoint{8,16,32}BitCharStream` and their backing arrays are strictly package-private in `org.antlr.v4.runtime`—never exposed on any `public` or `protected` API.
* **`CodePointLexerRunner`:** Package-private in `org.antlr.v4.runtime`, invoked from `Lexer.nextToken()` (27 B, outlining `nextTokenSlow` at 20 B) via `nextTokenCodePoint`. Executes register-resident `exec8Bit`, `exec16Bit`, and `exec32Bit` DFA scan loops directly over the stream's package-private backing array, sharing a single `finishToken` tail that fuses built-in single-action `-> skip` rules (`LexerActionExecutor.isSingleSkip()`) in place without exiting the scan loop or calling `recog.skip()`.
* **Safe subclass gating:** Fast DFA execution is governed by `LexerATNSimulator.fastDfaEligibleClass()` together with a metadata-driven `ClassValue<Boolean> NO_HOOK_OVERRIDES` check (backed by a single-entry `volatile Class<?>` cache `lastFastEligibleClass` to avoid per-file `ClassValue.get` lookups). Helper-only subclasses that override no `LexerATNSimulator` or `ATNSimulator` hooks remain eligible automatically; subclasses that override hooks fall back safely to standard `LexerATNSimulator.match`. Likewise, `Lexer` tracks a single-entry `volatile Class<?>` cache (`lastDefaultSkipClass`) so in-place `-> skip` fusion runs only when `Lexer.skip()` is not overridden. `Lexer.emit()` (75 B) outlines `emitWithFactory()` (51 B) and `pushMode(int)` stays at 17 B to respect HotSpot C2 inlining thresholds.

#### Predicate-Guarded ASCII Lexer Edge Cache (`LexerPredEdgeTable`)

Standard ANTLR suppresses static DFA edges whenever lexer ATN reach encounters a `PredicateTransition`, forcing full ATN reach and closure on every subsequent occurrence of `(state, char)`—a major hotspot for context-sensitive tokens in languages like Groovy (such as `/` and `'`).

Package-private `LexerPredEdgeTable` caches the deterministic binary decision tree of `(ruleIndex, predIndex)` boolean outcomes (up to `MAX_PRED_DEPTH = 8`) per `(stateNumber, asciiChar)` in lock-free copy-on-write `AtomicReferenceArray<PredNode>[]` rows, bound to the owning mode's `dfaS0` identity so `DFA.clear()` automatically invalidates stale entries. Warm single-predicate hits execute with zero `PredTrace` allocation. `LexerPredEdgeTable.resolveFast` is deliberately kept as a single 460-byte static method (`> 325 B` `FreqInlineSize`) so HotSpot C2 does not inline its cold ATN-capture path into `CodePointLexerRunner`'s scan loops and spill registers.

#### Compiled `LexerActionExecutor` Dispatch

`LexerActionExecutor` precomputes dispatch metadata at construction time:

* `hasUnfixedPositionDependent` makes `fixOffsetBeforeMatch` O(1) when no unindexed position-dependent actions are present.
* `isSingleSkip()` (`public final`) identifies single built-in `LexerSkipAction` executors in O(1) for safe in-place skip fusion.
* `fastMode` classifies common non-indexed action sequences into `MODE_SINGLE`, `MODE_MULTI`, `MODE_CUSTOM_OPT_BUILTIN` (a leading `LexerCustomAction` with an optional trailing built-in command), or `MODE_SLOW`. Maintaining a single `lexer.action(...)` call site avoids HotSpot C2 `NodeCountInliningCutoff` regressions on parallel lexing.

#### Chunked `TokenList`, On-Demand Streaming, and Lookahead Caching

* **Chunked `TokenList`:** `BufferedTokenStream.tokens` defaults to package-private `TokenList`, which stores tokens in fixed **256-slot** `Token[]` chunks (1 KiB per chunk under 64-bit CompressedOops, initial chunk directory of 4). Appending never copies prior token references, eliminating `ArrayList` resize stalls and G1GC copy barriers on large files; `clear()` releases excess chunks when the directory has grown past its initial size. If a subclass replaces `tokens` with an `ArrayList`, `setup()` / `fill()` still pre-size it (~4 characters per token) when `CharStream.size()` is finite.
* **On-demand fetch semantics preserved:** Token fetching remains lazy via 64-bit overflow-safe `BufferedTokenStream.sync(int)`. Eagerly lexing to EOF in `setup()` is intentionally avoided because it would reorder lexer-vs-parser error notifications; callers requiring a full buffer should call `fill()` explicitly.
* **Lookahead caches (`cachedLT1` / `cachedLTMinus1`):** `BufferedTokenStream` maintains package-private `cachedLT1` and `cachedLTMinus1` fields—refreshed on `consume()` and on in-buffer `seek()` (keeping `cachedLT1` intact on no-op `seek(p)`), and cleared on `setTokenSource()` or when `consume()` cannot advance. `cachedLTMinus1` eliminates repeated backward channel scans from `Parser.pushNewRecursionContext` and `unrollRecursionContexts`.
* **Fused `CommonTokenStream` operations:** When `getClass() == CommonTokenStream.class && tokens == fastTokens`, `consume()` (178 B), `LT(k > 1)` (`ltForwardFast`), and cursor-safe `nextOnChannel(int)` (111 B) operate directly on `TokenList` and pull unbuffered frontier tokens via `fetchNextOnChannel` without intermediate `lazyInit` / `sync` frames. Internal `tokens == fastTokens` reads are encapsulated behind compact `tokenCount()` (29 B) and `tokenAt(int)` (32 B) helpers.
* **MDO profile isolation and inline-shape preservation:** `BufferedTokenStream.LA(int)` (32 B), `BufferedTokenStream.LT(int)` (27 B), and `CommonTokenStream.LT(int)` (27 B) all stay below HotSpot C2's 35-byte `MaxInlineSize` on the `k == 1` hit path (`cachedLT1`), splitting rare `k == 1` cache misses (`laOneSlow` 50 B, `ltOneSlow` 184 B) from `k != 1` lookahead (`laSlow`, `ltSlow`, `ltCommonSlow` 136 B) so C2 dead-code-eliminates `k != 1` IR at constant `LA(1)`/`LT(1)` call sites and never pollutes generated parser methods with cold slow-path inlines. *(Design note: extracting a helper method on the warm hit path, e.g. `return lt1().getType()`, previously introduced a call boundary that C2 did not consistently fold and regressed multi-scenario benchmarks; the warm hit path therefore remains directly inlined.)*
* **`CommonTokenFactory`:** Uses the 7-argument `CommonToken` constructor (passing start line and column directly) rather than querying `TokenSource.getLine()` / `getCharPositionInLine()` after matching.

---

### Parser Runtime, Parse Trees, and Error Handling

* **`Parser` and `ParserDiagnostics`:** `Parser` (< 1,000 lines) hosts the `protected final` generated-parser hooks and outlined cold helpers (`_syncSlow`, `_reportMatchSlow`, `_recoverInline`, `_consumeMatched`, `_consumeEOF`), fuses `consume(Token)` and `addChild` using `TerminalNodeImpl(Token, RuleNode)`, caches `errorSyncEnabled`, `reportMatchRequired`, `errorRecoveryMode`, and `lastConsumed` (so `exitRule()` avoids backward `LT(-1)` scans), and offers `setTrimParseTree(boolean)` (`TrimToSizeListener`). Cold diagnostic, expected-token, and tracing utilities live in package-private `ParserDiagnostics`.
* **`ANTLRErrorStrategy` and `DefaultErrorStrategy`:** `ANTLRErrorStrategy` adds two Java 8 default methods—`isSyncRequired()` (default `true`; `BailErrorStrategy` returns `false`) and `isReportMatchRequired()` (default `true`; `DefaultErrorStrategy` returns `false`)—allowing generated parsers to skip virtual `sync` and `reportMatch` calls on the common success path via `Parser`'s cached flags. `DefaultErrorStrategy.reportMatch` is a no-op outside recovery, and `sync` exits early while recovering and skips clearing `nextTokensContext` when already `null`.
* **Lock-free `Recognizer` error listeners:** Replaces `synchronized (this)` listener mutation with a copy-on-write `ANTLRErrorListener<?>[]` updated via `AtomicReferenceFieldUpdater`, starting on a shared default array so constructing a recognizer or calling `removeErrorListeners()` allocates nothing and acquires no locks.
* **Parse-tree helpers:** `ParserRuleContext` initializes `children` lists at capacity 4 (`CHILDREN_INITIAL_CAPACITY = 4`) and provides package-private `replaceLastChild` to swap labeled-alternative contexts in place without `remove` + `add` shifting; `RuleContext.getText()` fast-paths single-child nodes without allocating a `StringBuilder`; `AbstractParseTreeVisitor.visitChildren` iterates `ParserRuleContext.children` directly.

---

### Internal Primitive Collections (HPPC) and API Encapsulation

**Reason for exclusion:** Additional dependency (mitigated by shading and strict API encapsulation)

Hot ATN and prediction data structures use HPPC (`com.carrotsearch:hppc:0.9.1`, the last HPPC release targeting Java 8 bytecode) to avoid boxed keys and `HashMap.Node` allocations. **HPPC types never appear in any `public` or `protected` class, field, method, constructor, generic parameter, or annotation signature.**

| Subsystem | Internal Storage (never `public`/`protected`) | Exposed API Boundary | Role |
| --- | --- | --- | --- |
| `ATNConfigSet` merge index | `ClearableLongObjectHashMap` extends HPPC `LongObjectHashMap` | `Set<ATNConfig>` | Packed `(state, alt)` → `ATNConfig`; single-hash `indexOf` / `indexGet` / `indexInsert`; O(1) empty `clear()` |
| `ParserATNSimulator` precedence filter | `ClearableIntObjectHashMap` extends HPPC `IntObjectHashMap` (private field) | Not in any method signature | State number → alt-1 `PredictionContext`; lazily retained; O(1) empty `clear()` |
| `ConflictDetector` duplicate fallback | `ClearableLongObjectHashMap` (local on read-only / duplicate-entry path) | Not in any method signature | Packed `(slot, alt)` → joined `PredictionContext` |
| `EpsilonClosure` busy set | Package-private `OpenAddressedHashSet` **composes** HPPC `ObjectHashSet` | `Set<ATNConfig>` | Right-recursion / `EOF*` guards; no `HashMap.Node`; O(1) empty `clear()` |
| `ATN` LL(1) fallback cache | Package-private `ConcurrentIntIntMap` **composes** HPPC `IntIntHashMap` (COW) | `protected final ConcurrentMap<Integer, Integer> LL1Table` via `ConcurrentIntIntMapView` | Lock-free primitive reads behind the JDK `ConcurrentMap` façade (hot path hits `ATN.ll1Dense` first) |
| `PredictionContext` graph interning | Local HPPC `ObjectObjectIdentityHashMap` in `getCachedContextInternal` | `PredictionContext` | Visited identity map allocated only when an `ArrayPredictionContext` is reached |

Cold paths (such as ATN deserialization) retain standard JDK collections. At package time, `maven-shade-plugin` minimizes and relocates HPPC exclusively into `org.antlr.v4.runtime.shaded.com.carrotsearch.hppc` and publishes a dependency-reduced POM with zero transitive HPPC dependency, ensuring the runtime is self-contained and cannot clash with a consumer's own HPPC version. Both `TestHppcApiBoundary` (reflective scan of all `public`/`protected` signatures in `runtime/Java`) and Maven's `verify-shaded-hppc` build gate enforce zero HPPC leakage.

---

## Tool Static Analysis and Java Code Generation

**Reason for exclusion:** Implementation complexity (target-specific)

The ANTLR 4 tool (`tool/src/...` and `tool/resources/org/antlr/v4/tool/templates/codegen/Java/Java.stg`) includes static-analysis and Java target code-generation optimizations that shrink generated recognizer bytecode and align hot rule methods with HotSpot C2 inlining thresholds without altering parse semantics.

| Pattern / Subsystem | Reference / Prior Codegen | Optimized Fork Codegen | Engineering Benefit |
| --- | --- | --- | --- |
| LL(\*) adaptive decisions & sync | `setState(s); _errHandler.sync(this);` + `getInterpreter().adaptivePredict(_input, d, _ctx)` | Inherited `protected final` `_sync()` (12 B), `_sync(int)` (17 B), `_adaptivePredict(int)` (20 B), and fused `_syncPredict(int, int)` (22 B) | Monomorphic hooks strictly `<= 35 B` (`MaxInlineSize`); skips virtual `sync` via cached `errorSyncEnabled`; eliminates per-parser boilerplate |
| Token & EOF matching | `match(T)` / `match(EOF)` | Inherited `protected final _match(T)` (34 B) + `_consumeMatched` (24 B), and dedicated `_matchEOF()` (34 B) + `_consumeEOF` (11 B) | Monomorphic; skips `reportMatch` when not recovering; avoids `matchedEOF` branch on normal tokens; outlines `_reportMatchSlow` / `_recoverInline` / `_matchRecover` |
| Set & inverted-set matching | Inline `_input.LA(1)` + `consume()` + recovery | Inherited `protected final _matchSet(...)` (17 B) / `_matchSetEOF(...)` (30 B) | Single `LT(1)` lookup on success; tracks `matchedEOF` only when EOF is in the set; shrinks rule bytecode |
| Single-token `*` loops | Inline `setState` / `_sync` / `while` / `_match` per site | Inherited `protected final _matchStar(...)` (53 B) / `_matchStarPredict(...)` (41 B), opt-in via `Target.supportsCompactSingleTokenStarLoop()` | Removes repetitive loop boilerplate and brings hot rules back under HotSpot C2's 325-byte `FreqInlineSize` |
| Star/plus loop exit test | `while (_alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER)` | `while (_alt==continueAlt)` | Single comparison per iteration; immune to `ATN` token-name shadowing |
| Compact LL(1) multi-alt tests | Sparse `switch (_input.LA(1))` or verbose `==` chains | Compact `if` / 64-bit bitmask test chains for 1–3 alternatives (`LL1AltBlock`, opt-in via `Target.supportsCompactLL1AltTests()`) | Replaces bloated sparse `lookupswitch` tables when 1–2 tiny alts (`<= 3` cases) or 1–3 sparse alts (`>= 6` cases) fit cleanly in `<= 4` words/arm (`<= 6` total) |
| Left-recursive precedence check | `precpred(_ctx, n)` | Inherited `protected final _prec(n)` (17 B) | Monomorphic; drops unused context argument |
| Rule error recovery | Inline `catch (RecognitionException re)` body | Inherited `protected final _recover(_localctx, re)` (28 B) | Shrinks exception-handler bytecode in every rule method |
| Rule context construction | `new XContext(_ctx, getState())` | Unchanged (`Recognizer.getState()` is `final`) | Inlines cleanly while keeping `Recognizer._stateNumber` private |
| Labeled recursive alt start | `new FooContext(new BarContext(parent, state))` | `new FooContext(parent, state)` | Eliminates throwaway intermediate context allocation |
| Single-alt & LL(1) labeled-alt rules (Tier 2a / 2b) | Allocate base `RuleContext` then `copyFrom()` into `AltContext` | Direct `AltContext` allocation (Tier 2a) or deferred entry via inherited `protected final _enterDeferred(...)` (18 B, Tier 2b) when no rule `catch`/`finally` and sound `baseContext` / `LL1AltBlock` guards hold (and `_parseListeners == null` at runtime for Tier 2b) | Eliminates 1–2 wasted context allocations and `copyFrom()` per invocation while preserving exact listener, `baseContext`, and recovery semantics |
| Flat `action()` / `sempred()` dispatch | Two-level switch (`ruleIndex` → per-rule helper → `actionIndex`/`predIndex`) | Flat O(1) `tableswitch` directly on `actionIndex` / `predIndex` (opt-in via `Target.supportsFlatActionAndSempredDispatch()`), using exhaustive 31-subclass `ActionChunk` analysis in `OutputModelController` and zero-alloc JLS §3.3 `JavaIdentifierScanner` (`\uXXXX`, literals, text blocks, comments); `_localctx`-dependent parser predicates outline to typed helpers | Eliminates the second dispatch hop and helper overhead on lexer/parser actions and predicates without affecting non-Java targets |
| EBNF exit LL(1) recovery (`AnalysisPipeline`) | Greedy `X*` / `X+` / `X?` before a left-recursive or context-predicated sub-rule fell back to adaptive prediction | Delegates to `LL1Analyzer.LOOK(..., PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS)` (exact 3-regime `callDepth`: `== 0`, `> 0`, `< 0`) to see through only callee precedence and context-dependent predicates dropped by ALL(\*) `EpsilonClosure` at depth `> 0` | Eliminates a large share of adaptive prediction sites (for example, cutting Apache Groovy's benchmark grammar from 326 to 184 fused `_syncPredict` sites, −43.6%) while keeping context-independent hoisted predicates 100% ALL(\*)-sound |
| ATN initialization | `deserialize(_serializedATN.toCharArray())` | `deserialize(_serializedATN)` | Single buffer allocation without `toCharArray()` + `clone()` double copy |
| Generated imports | `java.util.Iterator` imported unconditionally | Omitted when unused | Cleaner generated Java sources |

---

## API Compatibility and Subclass Contracts

* **Public source and binary compatibility:** Generated recognizers preserve standard ANTLR 4 public APIs. Existing compiled parsers continue to function on newer `me.sunlan:antlr4-runtime` releases, though regeneration is recommended to take advantage of the Java code-generation improvements.
* **Reserved `Parser` hooks:** The 13 `protected final` hooks on `Parser`—11 single-step hooks (`_adaptivePredict`, `_sync()`, `_sync(int)`, `_syncPredict`, `_match`, `_matchEOF`, `_matchSet`, `_matchSetEOF`, `_prec`, `_recover`, `_enterDeferred`, all `<= 35 B`) and 2 compact single-token loop helpers (`_matchStar` 53 B, `_matchStarPredict` 41 B)—are reserved for generated Java parsers and cannot be overridden by third-party subclasses. `Parser.consume(Token)` is `protected` for generated code and subclasses.
* **Error-strategy opt-in defaults:** `ANTLRErrorStrategy.isSyncRequired()` and `ANTLRErrorStrategy.isReportMatchRequired()` are Java 8 `default` methods returning `true`, so custom implementations of `ANTLRErrorStrategy` retain the historical always-invoke contract without code changes. Subclasses of `DefaultErrorStrategy` that override `reportMatch` or `inErrorRecoveryMode` to run logic on every successful match should override `isReportMatchRequired()` to return `true`; subclasses of `BailErrorStrategy` that override `sync` should override `isSyncRequired()` to return `true`.
* **`ParserATNSimulator` subclass SPI:** The protected recursive `closure(ATNConfig, ..., Set<ATNConfig>, ...)` entry point accepts any JDK `Set<ATNConfig>` (production code passes `EpsilonClosure`'s retained `OpenAddressedHashSet`). Overriding subclasses bypass `isBaseSimulator` fast paths automatically so every protected hook remains observable.
* **`LexerATNSimulator` subclass SPI:** `LexerATNSimulator.fastDfaEligibleClass()` defaults to `LexerATNSimulator.class`. Helper-only subclasses that override no `LexerATNSimulator` or `ATNSimulator` methods remain automatically eligible for `CodePointLexerRunner`; subclasses may also return their own class literal to opt in explicitly (without reflective inspection) or `null` to opt out.
* **Multi-target tool compatibility:** `Target.supportsCompactSingleTokenStarLoop()`, `Target.supportsCompactLL1AltTests()`, and `Target.supportsFlatActionAndSempredDispatch()` default to `false` and are enabled only in `JavaTarget`, preserving full compatibility with pluggable non-Java targets.
* **Encapsulation guarantees:** `Recognizer._stateNumber` stays `private`; `CodePoint{8,16,32}BitCharStream` and their backing arrays stay package-private (zero mutable internal array exposure); and HPPC remains strictly confined to package-private runtime internals, never emitted into generated code, and enforced by `TestHppcApiBoundary` and Maven's `verify-shaded-hppc`.
