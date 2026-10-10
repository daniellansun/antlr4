/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.dfa;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.LexerActionExecutor;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.misc.MurmurHash;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A DFA state represents a set of possible ATN configurations.
 *  As Aho, Sethi, Ullman p. 117 says "The DFA uses its state
 *  to keep track of all possible states the ATN can be in after
 *  reading each input symbol.  That is to say, after reading
 *  input a1a2..an, the DFA is in a state that represents the
 *  subset T of the states of the ATN that are reachable from the
 *  ATN's start state along some path labeled a1a2..an."
 *  In conventional NFA&rarr;DFA conversion, therefore, the subset T
 *  would be a bitset representing the set of states the
 *  ATN could be in.  We need to track the alt predicted by each
 *  state as well, however.  More importantly, we need to maintain
 *  a stack of states, tracking the closure operations as they
 *  jump from rule to rule, emulating rule invocations (method calls).
 *  I have to add a stack to simulate the proper lookahead sequences for
 *  the underlying LL grammar from which the ATN was derived.
 *
 *  <p>I use a set of ATNConfig objects not simple states.  An ATNConfig
 *  is both a state (ala normal conversion) and a RuleContext describing
 *  the chain of rules (if any) followed to arrive at that state.</p>
 *
 *  <p>A DFA state may have multiple references to a particular state,
 *  but with different ATN contexts (with same or different alts)
 *  meaning that state was reached via a different set of rule invocations.</p>
 */
public class DFAState {
	public int stateNumber = -1;

	@NotNull
	public final ATNConfigSet configs;

	/** {@code edges.get(symbol)} points to target of symbol.
	 */
	@NotNull
	private volatile AbstractEdgeMap<DFAState> edges;

	/**
	 * ASCII lexer edges ({@code 0..127}), or {@code null} until the first such
	 * edge is installed. Lexer source is overwhelmingly in this range
	 * (identifiers, whitespace, operators). A hit is a volatile read plus one
	 * array load — no hash probe and no second volatile key check.
	 *
	 * <p>Only lexer maps ({@code maxIndex > 0xFFFF}) publish this. Parser maps
	 * and precedence-start maps stay {@code null}, so {@link #getTarget} is
	 * unchanged. Slot writes happen-before the volatile store of this
	 * reference; {@link #getLexerTarget} re-reads it on every character.</p>
	 */
	@Nullable
	private volatile DFAState[] asciiEdges;

	/**
	 * Identity-indexed lexer edges for code points outside the ASCII table, or
	 * {@code null} when {@link #edges} is not that layout. Parser states leave
	 * it {@code null} and {@link #getTarget} never reads it.
	 */
	@Nullable
	private volatile Object[] directValues;

	private AcceptStateInfo acceptStateInfo;

	/** These keys for these edges are the top level element of the global context. */
	@NotNull
	private volatile AbstractEdgeMap<DFAState> contextEdges;

	/** Symbols in this set require a global context transition before matching an input symbol. */
	@Nullable
	private BitSet contextSymbols;

	/**
	 * This list is computed by {@link ParserATNSimulator#predicateDFAState}.
	 */
	@Nullable
	public PredPrediction[] predicates;

	/** Map a predicate to a predicted alternative. */
	public static class PredPrediction {
		@NotNull
		public SemanticContext pred; // never null; at least SemanticContext.NONE
		public int alt;
		public PredPrediction(@NotNull SemanticContext pred, int alt) {
			this.alt = alt;
			this.pred = pred;
		}
		@Override
		public String toString() {
			return "("+pred+", "+alt+ ")";
		}
	}

	/**
	 * Constructs a new {@link DFAState} for a DFA.
	 *
	 * <p>This constructor initializes the DFA state using empty edge maps
	 * provided by the specified DFA.</p>
	 *
	 * @param dfa The DFA.
	 * @param configs The set of ATN configurations defining this state.
	 */
	public DFAState(@NotNull DFA dfa, @NotNull ATNConfigSet configs) {
		this(dfa.getEmptyEdgeMap(), dfa.getEmptyContextEdgeMap(), configs);
	}

	/**
	 * Constructs a new {@link DFAState} with explicit initial values for the
	 * outgoing edge and context edge maps.
	 *
	 * <p>The empty maps provided to this constructor contain information about
	 * the range of edge values which are allowed to be stored in the map. Since
	 * edges outside the allowed range are simply dropped from the DFA, this
	 * offers several potential benefits:</p>
	 *
	 * <ul>
	 * <li>In the general case, empty edge maps are initialized to support the
	 * range of values expected to be seen during prediction, which is in turn
	 * used to minimize the memory overhead associated with a
	 * dynamically-constructed DFA.</li>
	 * <li>As a special case, empty edge maps can be intentionally constructed
	 * so the DFA will not store edges with specific values. In the limit, a
	 * range which does not contain any allowed edges can be used to prevent the
	 * DFA from storing <em>any</em> edges, forcing the prediction algorithm to
	 * recompute all transitions as they are needed.</li>
	 * </ul>
	 *
	 * @param emptyEdges The empty edge map.
	 * @param emptyContextEdges The empty context edge map.
	 * @param configs The set of ATN configurations defining this state.
	 */
	public DFAState(@NotNull EmptyEdgeMap<DFAState> emptyEdges, @NotNull EmptyEdgeMap<DFAState> emptyContextEdges, @NotNull ATNConfigSet configs) {
		this.configs = configs;
		this.edges = emptyEdges;
		this.contextEdges = emptyContextEdges;
	}

	public final boolean isContextSensitive() {
		return contextSymbols != null;
	}

	public final boolean isContextSymbol(int symbol) {
		if (!isContextSensitive() || symbol < edges.minIndex) {
			return false;
		}

		return contextSymbols.get(symbol - edges.minIndex);
	}

	public final void setContextSymbol(int symbol) {
		assert isContextSensitive();
		if (symbol < edges.minIndex) {
			return;
		}

		contextSymbols.set(symbol - edges.minIndex);
	}

	public void setContextSensitive(ATN atn) {
		assert !configs.isOutermostConfigSet();
		if (isContextSensitive()) {
			return;
		}

		synchronized (this) {
			if (contextSymbols == null) {
				contextSymbols = new BitSet();
			}
		}
	}

	public final AcceptStateInfo getAcceptStateInfo() {
		return acceptStateInfo;
	}

	public final void setAcceptState(AcceptStateInfo acceptStateInfo) {
		this.acceptStateInfo = acceptStateInfo;
	}

	public final boolean isAcceptState() {
		return acceptStateInfo != null;
	}

	public final int getPrediction() {
		if (acceptStateInfo == null) {
			return ATN.INVALID_ALT_NUMBER;
		}

		return acceptStateInfo.getPrediction();
	}

	public final LexerActionExecutor getLexerActionExecutor() {
		if (acceptStateInfo == null) {
			return null;
		}

		return acceptStateInfo.getLexerActionExecutor();
	}

	/**
	 * PERF: {@code final} so warm {@code execDFA} edge walks can monomorphically
	 * inline through to {@link AbstractEdgeMap#get} (Hash/Array) without a
	 * virtual call at this layer.
	 */
	public final DFAState getTarget(int symbol) {
		return edges.get(symbol);
	}

	/**
	 * Lexer edge lookup. ASCII symbols hit {@link #asciiEdges} (one volatile
	 * read and an array load). Wider code points use an identity table when
	 * one has been published, otherwise {@link AbstractEdgeMap#get}.
	 * {@link HashEdgeMap#get} stays branch-free so parser edge walks do not
	 * pay a layout test.
	 */
	public final DFAState getLexerTarget(int symbol) {
		DFAState[] ascii = asciiEdges;
		// Unsigned compare: symbol is a code point, so values above 127 and
		// negatives (EOF) both fail this test.
		if (ascii != null && (symbol & ~0x7F) == 0) {
			return ascii[symbol];
		}
		Object[] direct = directValues;
		if (direct != null) {
			if (symbol >= 0 && symbol < direct.length) {
				return (DFAState) direct[symbol];
			}
			return null;
		}
		return edges.get(symbol);
	}

	public final void setTarget(int symbol, DFAState target) {
		AbstractEdgeMap<DFAState> next = edges.put(symbol, target);
		// Lexer vocabulary reaches U+10FFFF. Parser token maps and the
		// precedence-start map (0..200) must not allocate an ASCII table.
		if (next.minIndex >= 0 && next.maxIndex > 0xFFFF) {
			synchronized (this) {
				if ((symbol & ~0x7F) == 0) {
					DFAState[] ascii = asciiEdges;
					if (ascii == null) {
						ascii = new DFAState[128];
					}
					ascii[symbol] = target;
					// Volatile store releases the slot write. Same reference on
					// later edges; the store is still a release barrier.
					asciiEdges = ascii;
				}
				directValues = directValuesOf(next);
			}
		}
		else {
			directValues = null;
		}
		edges = next;
	}

	private static Object[] directValuesOf(AbstractEdgeMap<DFAState> edgeMap) {
		if (edgeMap.getClass() == HashEdgeMap.class) {
			@SuppressWarnings("unchecked")
			HashEdgeMap<DFAState> hash = (HashEdgeMap<DFAState>) edgeMap;
			return hash.directArray();
		}
		return null;
	}

	/**
	 * Returns {@code true} if this state has no outgoing symbol edges.
	 *
	 * <p>PERF: Prefer this over {@link #getEdgeMap()}{@code .isEmpty()} on hot
	 * paths. {@link #getEdgeMap()} materializes a boxed {@link Map} (historically
	 * a {@link java.util.TreeMap} for {@link HashEdgeMap}) solely to answer a
	 * boolean question — profiled at ~17% of end-to-end Java parse CPU when
	 * {@link DFA#isEmpty()} used that pattern for every precedence-DFA
	 * prediction.</p>
	 *
	 * @return {@code true} if there are no symbol edges from this state
	 */
	public final boolean isEdgesEmpty() {
		return edges.isEmpty();
	}

	/**
	 * Returns {@code true} if this state has no context edges.
	 *
	 * <p>Same allocation-avoidance rationale as {@link #isEdgesEmpty()}.</p>
	 *
	 * @return {@code true} if there are no context edges from this state
	 */
	public final boolean isContextEdgesEmpty() {
		return contextEdges.isEmpty();
	}

	public Map<Integer, DFAState> getEdgeMap() {
		return edges.toMap();
	}

	public synchronized DFAState getContextTarget(int invokingState) {
		if (invokingState == PredictionContext.EMPTY_FULL_STATE_KEY) {
			invokingState = -1;
		}

		return contextEdges.get(invokingState);
	}

	public synchronized void setContextTarget(int invokingState, DFAState target) {
		if (!isContextSensitive()) {
			throw new IllegalStateException("The state is not context sensitive.");
		}

		if (invokingState == PredictionContext.EMPTY_FULL_STATE_KEY) {
			invokingState = -1;
		}

		contextEdges = contextEdges.put(invokingState, target);
	}

	public Map<Integer, DFAState> getContextEdgeMap() {
		Map<Integer, DFAState> map = contextEdges.toMap();
		if (map.containsKey(-1)) {
			if (map.size() == 1) {
				return Collections.singletonMap(PredictionContext.EMPTY_FULL_STATE_KEY, map.get(-1));
			}
			else {
				try {
					map.put(PredictionContext.EMPTY_FULL_STATE_KEY, map.remove(-1));
				} catch (UnsupportedOperationException ex) {
					// handles read only, non-singleton maps
					map = new LinkedHashMap<>(map);
					map.put(PredictionContext.EMPTY_FULL_STATE_KEY, map.remove(-1));
				}
			}
		}

		return map;
	}

	@Override
	public int hashCode() {
		int hash = MurmurHash.initialize(7);
		hash = MurmurHash.update(hash, configs.hashCode());
		hash = MurmurHash.finish(hash, 1);
		return hash;
	}

	/**
	 * Two {@link DFAState} instances are equal if their ATN configuration sets
	 * are the same. This method is used to see if a state already exists.
	 *
	 * <p>Because the number of alternatives and number of ATN configurations are
	 * finite, there is a finite number of DFA states that can be processed.
	 * This is necessary to show that the algorithm terminates.</p>
	 *
	 * <p>Cannot test the DFA state numbers here because in
	 * {@link ParserATNSimulator#addDFAState} we need to know if any other state
	 * exists that has this exact set of ATN configurations. The
	 * {@link #stateNumber} is irrelevant.</p>
	 */
	@Override
	public boolean equals(Object o) {
		// compare set of ATN configurations in this set with other
		if ( this==o ) return true;

		if (!(o instanceof DFAState)) {
			return false;
		}

		DFAState other = (DFAState)o;
		boolean sameSet = this.configs.equals(other.configs);
//		System.out.println("DFAState.equals: "+configs+(sameSet?"==":"!=")+other.configs);
		return sameSet;
	}

	@Override
	public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(stateNumber).append(":").append(configs);
        if ( isAcceptState() ) {
            buf.append("=>");
            if ( predicates!=null ) {
                buf.append(Arrays.toString(predicates));
            }
            else {
                buf.append(getPrediction());
            }
        }
		return buf.toString();
	}
}
