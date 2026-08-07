# Optimized Fork

The optimized fork of ANTLR 4 is maintained by Sam Harwell at Tunnel Vision Laboratories, LLC. This "bleeding edge" implementation of ANTLR 4 contains numerous features and performance optimizations which are not included in the reference release. In general, these features are not included in the reference release for one or more of the following reasons.

* **Implementation complexity:** Many features of the optimized fork were developed to meet specific concerns found during the use of ANTLR 4 for large-scale commercial applications. In some cases, the implementation of the feature is *extremely* complex, while the target audience likely to benefit from the change is small. The reference release of ANTLR is widely used in both educational scenarios and small- to medium-sized parsing applications where these optimizations are not needed.
* **Incomplete specification and/or implementation:** Some features of the optimized fork were developed to address a specific use case without specific concern to other use cases. These features may or may not work in other use cases.
* **Configuration required:** Some optimizations present in the optimized fork, including some of the most powerful, have the ability to dramatically improve performance for some grammars while reducing performance for others. Optimal use of these features requires manual configuration with respect to a specific grammar, and in some cases knowledge about characteristics of the machine where the application is being executed. While these features are essential for certain applications, including them all in a reference release of ANTLR would be quite overwhelming for new users.

## Features

### Base Context

**Reason for exclusion:** Incomplete implementation (not known to work in all grammars)

This release has the ability to instruct multiple rules in a grammar to use the same context object. This feature was originally intended for use in cases like the following, which is extracted from a [Java 8 grammar](https://github.com/sharwell/antlr4/blob/java8-grammar/tool/test/org/antlr/v4/test/Java.g4) derived from the language specification (many uses of the feature are seen in this grammar).

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

In the above example, the rule `unannPrimitiveType` does not allow annotations, but the parse tree will still contain `PrimitiveTypeContext`

### Automated left factoring

**Reason for exclusion:** Incomplete implementation (not known to work in all grammars)

This release has the ability to automatically left-factor a named rule reference from alternatives of a rule. This feature was created a few releases into GoWorks in an effort to reduce memory consumption by reducing the lookahead requirements for a specific rule in the grammar. Many features of GoWorks were already written with a specific parse tree shape in mind, and changing the shape of the parse tree in order to manually left factor the offending rule would be both time-consuming and risky for introducing bugs. To address this, I created a parse-tree-preserving grammar transformation which left-factors a rule without changing the shape of the final parse tree. GoWorks [uses the feature](https://github.com/tunnelvisionlabs/goworks/blob/5543a633dfc0d9b0e2ab407445a82bc70b24f100/goworks.editor/src/org/tvl/goworks/editor/go/parser/GoParser.g4#L435-L444) to left factor its *expression* rule out of the *simpleStmt* rule.

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

> :warning: While the parse tree shape will not be changed for *correct* input, it is possible that use of the automatic left factoring functionality could change the behavior in error handling scenarios. Specifically, it may be possible for erroneous input to result in parse trees where nodes are placed in locations that appear impossible according to the grammar.

### Indirect left recursion elimination

**Reason for exclusion:** Incomplete implementation (not known to work in all grammars)

This release has the ability to eliminate indirect left recursion (reported by the reference release as "mutual" left recursion). This feature is an early expansion of the ability to automatically left factor a grammar.

### Backwards compatibility

**Reason for exclusion:** Implementation complexity (quite constraining to the benefit of only a few users)

The optimized fork releases have a stronger emphasis on preserving compatibility. Unlike the reference release, grammars typically do not need to be regenerated when upgrading the runtime. However, we still recommend that grammars be generated using the new version as it may improve performance or the available features at runtime.

### `@Nullable` and `@NotNull` annotations

**Reason for exclusion:** Implementation complexity

### Rule versioning

**Reason for exclusion:** Implementation complexity

The optimized fork includes the [rule versioning](https://github.com/sharwell/antlr4/wiki/Rule-Versioning) feature.

## Optimizations

### Improved SLL termination condition

**Reason for exclusion:** Implementation complexity

This release of ANTLR 4 uses a stronger termination condition for SLL prediction. In some cases it is possible for this implementation to detect an SLL conflict with fewer symbols of lookahead than the reference release. In general, this change would not be observable. However, aside from the performance benefit it is possible for the shorter lookahead to allow for better error handling in some edge cases.

### Full context DFA

**Reason for exclusion:** Implementation complexity, unproven theoretical impact on algorithmic complexity of ALL(*)

The reference release of ANTLR 4 only uses a DFA for local-context prediction (SLL). The optimized fork expands on that by allowing the use of a DFA for full-context prediction (LL) as well.

> :bulb: This optimization is disabled by default. It can be enabled by setting `ParserATNSimulator.enable_global_context_dfa` to true:
>
> ```java
> Parser myParser = new MyParser(input);
> myParser.getInterpreter().enable_global_context_dfa = true;
> ```
>
> :warning: This feature can substantially increase the memory consumed by the DFA. For grammars and applications that rarely need to use full context prediction, especially in combination with two-stage parsing, the overhead of this feature may not provide gains that justify its use. I recommend leaving this feature disabled initially, and only experiment with it if you find that other options do not provide an acceptable level of performance. With that said, there are multiple known grammars which are practically unusable without this feature.

### Tail call elimination

**Reason for exclusion:** Incomplete specification (work in progress)

The optimized fork of ANTLR compacts `PredictionContext` instances associated with states in the DFA by eliminating certain unnecessary return states. As an added bonus, this feature reduced overall DFA storage requirements by allowing `DFAState` instances to be shared in scenarios where the reference release of ANTLR believes the states to be distinct.

> :bulb: This feature includes a configuration option `ParserATNSimulator.tail_call_preserves_sll`, which has a default value of `true`. Before enabling this feature, be aware of the following advantages and disadvantages.
>
> * Advantages of `tail_call_preserves_sll=true` (the default)
>   * Preserves maximum accuracy of `PredictionMode.SLL`. When the setting is false, it is possible (varies by grammar and input) for `PredictionMode.SLL` to report a parse error even though the input successfully parses when the setting is true.
>   * Minimizes lookahead of SLL decisions. When the setting is false, it is possible (varies by grammar and input) for SLL prediction - which is enabled even for `PredictionMode.LL` - to increase the lookahead. In one case it was observed that overall performance was degraded due to the impact of this.
> * Advantages of `tail_call_preserves_sll=false`
>   * Reduces DFA size. For cases where two-stage parsing is used (so reduced accuracy of SLL mode is acceptable) and lookahead doesn't hit a pathological case, explicitly setting `tail_call_preserves_sll` to false can substantially reduce the size of the DFA.

### DFA edge optimization

**Reason for exclusion:** Implementation complexity (the reference release uses a subset of this optimization)

The optimized fork uses several different map implementations based on the number of outgoing edges held in a DFA state. This feature minimizes the size of DFA states, especially in infrequently used areas of the DFA.

### ATN configuration optimization

**Reason for exclusion:** Implementation complexity (most small- and medium-sized applications don't run into DFA-related memory problems)

The optimized fork uses several `ATNConfig` classes to reduce the size of the DFA. For configurations returning default values from most properties, a small `ATNConfig` instance is used. The larger types are only used for configurations that need to represent non-default values from one or more methods. As a simple example, configurations appearing in the lexer DFA need to store a few more fields than configurations appearing in the parser DFA. By removing these fields from `ATNConfig` instances used in the parser DFA, some applications observe marked reductions in the memory overhead for the parser DFA (we've seen 20MiB or more for large applications).

### Primitive collections on prediction hot paths (HPPC)

**Reason for exclusion:** Additional dependency (mitigated by shading)

Hot prediction paths store primitive keys in HPPC maps/sets instead of boxed
JDK collections. **HPPC types are never part of any `public` or `protected`
API surface** — they appear only as non-exported implementation detail
(private fields/locals and package-private diagnostics). Callers and
subclasses see JDK collection interfaces (for example `Set<ATNConfig>`).

| Structure | Storage (package-private; never public/protected) | Exposed API | Role |
| --- | --- | --- | --- |
| `ATNConfigSet` merge index | `ClearableLongObjectHashMap` extends HPPC `LongObjectHashMap` (empty-fast clear; monomorphic hot path) | `Set<ATNConfig>` | packed `(state, alt)` → config; no `Long` boxing; empty clear O(1) |
| Precedence filter | `ClearableIntObjectHashMap` extends HPPC `IntObjectHashMap` (private field on simulator) | not in any method signature | state number → alt-1 context; retained; empty clear O(1) |
| Epsilon-closure busy set | package-private `OpenAddressedHashSet` **composes** HPPC `ObjectHashSet` + empty-fast `clear` | `Set<ATNConfig>` | right-recursion / EOF* guards; no `HashMap.Node`; empty clear O(1) |
| LL(1) prediction cache | `ConcurrentIntIntMap` **composes** HPPC `IntIntHashMap` (COW) | `protected ConcurrentMap<Integer,Integer> LL1Table` via `ConcurrentIntIntMapView` | primitive get/put on hot path; JDK map for subclasses/tests |

**Rule:** HPPC types never appear in any `public` or `protected` field, method, or constructor signature. JDK interfaces (`Set`, `ConcurrentMap`) are used at API boundaries. Package-private types may extend HPPC on monomorphic hot paths for performance; `TestHppcApiBoundary` enforces the signature rule by reflection.

`ATNConfigSet.add` / `contains` use `indexOf` / `indexGet` / `indexInsert` so
each long key is hashed once per operation (not a separate `get` then `put`).
Busy sets still key by `ATNConfig.hashCode()` / `equals` (see hash cache below).
Cold paths (for example ATN deserialization) keep JDK maps.

Epsilon-closure orchestration (busy set, predicate vs BFS control, double-
buffered intermediate layers) lives in package-private `EpsilonClosure` so
`ParserATNSimulator` stays orchestration-focused. The retained busy set is an
`OpenAddressedHashSet` (JDK `Set` over HPPC `ObjectHashSet` with empty-fast
`clear`) so the protected recursive `closure` SPI stays interface-
oriented and source-compatible with the pre-HPPC `Set` signature. Edge
reach / target-state computation lives in package-private
`ReachComputation` (retained config sets + `ReachConfigSource`) so
`ParserATNSimulator` stays orchestration-focused.

HPPC is shaded into `org.antlr.v4.runtime.shaded.com.carrotsearch.hppc` at
package time (Java 8–compatible HPPC release). The published runtime is
self-contained and does not clash with a consumer's own HPPC.

### ATN hot-path package (parser + lexer simulation)

**Reason for exclusion:** Implementation complexity

When a DFA edge is missing, the simulator runs ATN *reach* + *epsilon closure*
to build the next DFA state. That path used to allocate many short-lived
config sets, recompute MurmurHash repeatedly, and pay virtual dispatch on
edges that cannot contribute to closure. The optimized fork packages several
related fixes so prediction and lexing do less work per symbol.

All retained buffers assume **one thread owns one simulator** for the duration
of `adaptivePredict` / `match` (the existing ANTLR threading model). Do not
share a single simulator across concurrent predictions.

#### Retained scratch buffers (`RetainedConfigSet`)

| Owner | What is retained |
| --- | --- |
| `ParserATNSimulator` | reach + intermediate config sets for `computeTargetState` |
| `LexerATNSimulator` | ordered reach set for lexer `computeTargetState` |
| `EpsilonClosure` | busy set + BFS intermediate buffers |

Contract: `obtain(n)` always returns an **empty** writable set; `release()` in
`finally` drops config-graph references but keeps list/map capacity. DFA
states always store a readonly `clone`, never the reusable buffer. Capacity
hints go through `ATNConfigSet.scratchCapacity`.

`ATNConfigSet.clear()` also resets `outermostConfigSet`, so a retained set
cannot leave a sticky outermost flag that would break later edges.

#### Retained-pool clear cost (no wholesale `Arrays.fill`)

HPPC's stock `LongObjectHashMap.clear` / `ObjectHashSet.clear` always
`Arrays.fill` the **entire** open-addressed table (O(capacity)), even when
the container is already empty. Retained scratch called clear on both
`obtain` and `release`, so every missing DFA edge paid two full-table zero
passes — profiled as ~13% of lexer worker CPU on Groovy compile
(`Arrays.fill(Object[])` under lexer `computeTargetState` pool).

Mitigations (package-private only; HPPC types stay off `public`/`protected`
API surfaces):

| Mechanism | Effect |
| --- | --- |
| `ClearableLongObjectHashMap` / `OpenAddressedHashSet` | empty `clear()` is O(1) (no bulk fill) |
| `ATNConfigSet.clear` sparse path | when `size * 4 < tableLength`, remove known merge keys (O(n)) instead of filling capacity |
| `ArrayList.clear` | already nulls only the used prefix `[0, size)` |
| `RetainedConfigSet` obtain+release | still both clear (defensive); second clear is free when empty |

Net: one non-empty clear per edge (on `release` after work), never a double
full-capacity zero of the merge map or busy set.

#### `ATNConfig.hashCode` cache

`hashCode()` is computed once (MurmurHash) and cached. Cache is invalidated
when hash-participating fields change (`context`, or the
reaches-into-outer-context flag). Zero is reserved as "uncomputed"; a raw
hash of zero is stored as `1`.

Why it matters:

* lexer `OrderedATNConfigSet` keys configs by `hashCode()`
* closure busy sets hash configs on right-recursion / EOF* guards
* DFA state lookup hashes entire config sets

#### Lazy full-context config view (`ReachConfigSource`)

`computeTargetState` walks source configs via a retained `ReachConfigSource`:

1. **SLL / common case:** zero-copy view over `s.configs` (`size` / `get` only).
2. **Full-context step-out:** first `appendContext` materializes a mutable
   list once; later appends reuse that list; capacity is kept across edges
   via `reset` / `release`.

SLL never allocates the rewrite list. Unique-closure and normal reach both
finish through `finishTargetEdge` (no nullable edge-result sentinel).

#### Early non-epsilon skip in closure

When EOF is not treated as epsilon, both parser (`EpsilonClosure`) and lexer
(`LexerATNSimulator.closure`) skip non-epsilon optimized transitions before
`getEpsilonTarget`. Pure consume edges cannot contribute to epsilon closure.

#### Index-based config walks

Hot loops walk `ATNConfigSet` by index (`get(i)`) instead of enhanced-for
iterators, avoiding `Iterator` allocation on reach, closure BFS layers, and
related paths.

#### DFA emptiness without `TreeMap` (precedence DFAs)

`ParserATNSimulator.adaptivePredict` calls `DFA.isEmpty()` (historically twice
per prediction). For **precedence DFAs** (left-recursive expression rules —
the common case in Java, Groovy, etc.) the synthetic `s0` / `s0full` states
always exist, so emptiness was defined as “no outgoing symbol edges yet”.

The previous implementation answered that via
`s0.get().getEdgeMap().isEmpty()`, and `HashEdgeMap.toMap()` built a
**`TreeMap<Integer, DFAState>`** (boxed keys + red-black inserts) on every
call. Flame graphs of synthetic Java multi-file parse showed
`DFA.isEmpty` → `HashEdgeMap.toMap` → `TreeMap` at **~17% inclusive CPU**.

Mitigation:

| API | Behavior |
| --- | --- |
| `DFAState.isEdgesEmpty()` / `isContextEdgesEmpty()` | O(1) occupancy; no `Map` allocation |
| `DFA.isEmpty()` / `isContextSensitive()` | use the above; never `getEdgeMap().isEmpty()` |
| `HashEdgeMap.size` / `isEmpty` | O(1) via retained occupancy counter |
| `HashEdgeMap.toMap()` | cold path only (still sorted `TreeMap` for stable dumps) |
| `adaptivePredict` | answers emptiness **once** per call |

`getEdgeMap()` remains for diagnostics / `DFASerializer` only.

#### Frozen optimized ATN transitions

After ATN deserialization and optimization, each `ATNState` freezes its
optimized transition list into a `Transition[]`. Epsilon closure and reach
then walk the array (`getOptimizedTransition` / `getNumberOfOptimizedTransitions`)
instead of `ArrayList.get` / `size` on every edge. Mutations of optimized
transitions (test-only after freeze) invalidate the snapshot automatically.

#### Eager token buffer fill + LA(1) fast path

When `TokenSource.getInputStream()` reports a finite `CharStream.size()`
(file / string streams used by compilers), `BufferedTokenStream.setup`
bulk-fills the token list once (with capacity pre-size) instead of
on-demand `sync` during the first parse pass. Unbuffered streams that throw
from `size()` keep historic on-demand setup.

`CommonTokenStream.LT(1)` / `LA(1)` and `BufferedTokenStream.LA(1)` short-circuit
to `tokens.get(p)` after the cursor is initialized (p already on-channel).
`BufferedTokenStream` retains a private `cachedLT1` reference, refreshed on
`consume` and cleared on `seek` / `setTokenSource`, so `Parser.enterRule` /
`match` do not re-index the token list on every call.

#### API note (subclasses)

The recursive protected
`ParserATNSimulator.closure(ATNConfig, ..., Set<ATNConfig>, ...)` entry point
takes a JDK `Set` for the busy set. Production code supplies the retained
`OpenAddressedHashSet` from `EpsilonClosure` (HPPC open addressing underneath);
subclasses may pass any `Set` implementation. No HPPC type appears in this or
any other `public`/`protected` signature of the optimized runtime.

### Prediction context optimization

**Reason for exclusion:** Implementation complexity

The optimized release uses an exact implementation for merging `PredictionContext` instances. In some cases, the reference release produces prediction context graphs which are not fully reduced (maximum sharing of nodes in the graph). The algorithm used by the optimized fork implements an exact merge for these contexts, so the `PredictionContext` instances appearing in the DFA cache are fully reduced.
