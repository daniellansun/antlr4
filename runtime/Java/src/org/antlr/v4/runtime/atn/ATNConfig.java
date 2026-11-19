/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.misc.ObjectEqualityComparator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Represents a location with context in an ATN. The location is identified by
 * the following values:
 *
 * <ul>
 * <li>The current ATN state</li>
 * <li>The predicted alternative</li>
 * <li>The semantic context which must be true for this configuration to be
 * enabled</li>
 * <li>The syntactic context, which is represented as a graph-structured stack
 * whose path(s) lead to the root of the rule invocations leading to this
 * state.</li>
 * </ul>
 *
 * <p>In addition to these values, {@link ATNConfig} stores several properties
 * about paths taken to get to the location which were added over time to help
 * with performance, correctness, and/or debugging.</p>
 *
 * <ul>
 * <li>{@link #getReachesIntoOuterContext()}: Used to ensure semantic predicates
 * are not evaluated in the wrong context.</li>
 * <li>{@link #hasPassedThroughNonGreedyDecision()}: Used for enabling
 * first-match-wins instead of longest-match-wins after crossing a non-greedy
 * decision.</li>
 * <li>{@link #getLexerActionExecutor()}: Used for tracking the lexer action(s)
 * to execute should this instance be selected during lexing.</li>
 * <li>{@link #isPrecedenceFilterSuppressed()}: A state variable for one of the
 * dynamic disambiguation strategies employed by
 * {@link ParserATNSimulator#applyPrecedenceFilter}.</li>
 * </ul>
 *
 * <p>Due to the use of a graph-structured stack, a single {@link ATNConfig} is
 * capable of representing many individual ATN configurations which reached the
 * same location in an ATN by following different paths.</p>
 *
 * <p>PERF: To conserve memory, {@link ATNConfig} is split into several
 * different concrete types. {@link ATNConfig} itself stores the minimum amount
 * of information typically used to define an {@link ATNConfig} instance.
 * Various derived types provide additional storage space for cases where a
 * non-default value is used for some of the object properties. The
 * {@link ATNConfig#create} and {@link ATNConfig#transform} methods
 * automatically select the smallest concrete type capable of representing the
 * unique information for any given {@link ATNConfig}.</p>
 */
public class ATNConfig {
	/**
	 * This field stores the bit mask for implementing the
	 * {@link #isPrecedenceFilterSuppressed} property as a bit within the
	 * existing {@link #altAndOuterContextDepth} field.
	 */
	private static final int SUPPRESS_PRECEDENCE_FILTER = 0x80000000;

	/** The ATN state associated with this configuration */
	@NotNull
	private final ATNState state;

	/**
	 * This is a bit-field currently containing the following values.
	 *
	 * <ul>
	 * <li>0x00FFFFFF: Alternative</li>
	 * <li>0x7F000000: Outer context depth</li>
	 * <li>0x80000000: Suppress precedence filter</li>
	 * </ul>
	 */
	private int altAndOuterContextDepth;

	/** The stack of invoking states leading to the rule/states associated
	 *  with this config.  We track only those contexts pushed during
	 *  execution of the ATN simulator.
	 */
	@NotNull
	private PredictionContext context;

	/**
	 * Lazily cached {@link #hashCode} result. Zero means "not yet computed"
	 * (or invalidated after a mutable field change). The hash algorithm is
	 * biased away from zero when the raw MurmurHash finishes at zero so a
	 * legitimate hash of zero cannot thrash the cache sentinel.
	 *
	 * <p>PERF: Lexer {@link OrderedATNConfigSet} keys configs by
	 * {@code hashCode()}, parser epsilon-closure busy sets hash configs on
	 * every right-recursion / EOF* guard, and DFA state lookup hashes entire
	 * config sets. Caching avoids repeating MurmurHash over the same
	 * immutable-after-first-use field set on these hot paths.</p>
	 */
	private int cachedHashCode;

	/**
	 * Whether this configuration has passed through a non-greedy decision
	 * (lexer first-match-wins). Stored in the base class alignment padding
	 * (keeping 64-bit CompressedOops object size at 32 bytes) so parser
	 * configurations never need to allocate {@link ActionATNConfig} and
	 * {@link #hasPassedThroughNonGreedyDecision()} stays monomorphic.
	 */
	private final boolean passedThroughNonGreedyDecision;

	protected ATNConfig(@NotNull ATNState state,
						int alt,
						@NotNull PredictionContext context)
	{
		this(state, alt, context, false);
	}

	ATNConfig(@NotNull ATNState state,
			  int alt,
			  @NotNull PredictionContext context,
			  boolean passedThroughNonGreedyDecision)
	{
		assert (alt & 0xFFFFFF) == alt;
		this.state = state;
		this.altAndOuterContextDepth = alt;
		this.context = context;
		this.passedThroughNonGreedyDecision = passedThroughNonGreedyDecision;
	}

	protected ATNConfig(@NotNull ATNConfig c, @NotNull ATNState state, @NotNull PredictionContext context)
    {
		this(c, state, context, false);
	}

	ATNConfig(@NotNull ATNConfig c,
			  @NotNull ATNState state,
			  @NotNull PredictionContext context,
			  boolean passedThroughNonGreedyDecision)
	{
		this.state = state;
		this.altAndOuterContextDepth = c.altAndOuterContextDepth;
		this.context = context;
		this.passedThroughNonGreedyDecision = passedThroughNonGreedyDecision;
		// state/context usually differ from {@code c}; do not copy its hash.
	}

	public static ATNConfig create(@NotNull ATNState state, int alt, @Nullable PredictionContext context) {
		return create(state, alt, context, SemanticContext.NONE, null);
	}

	public static ATNConfig create(@NotNull ATNState state, int alt, @Nullable PredictionContext context, @NotNull SemanticContext semanticContext) {
		return create(state, alt, context, semanticContext, null);
	}

	public static ATNConfig create(@NotNull ATNState state, int alt, @Nullable PredictionContext context, @NotNull SemanticContext semanticContext, LexerActionExecutor lexerActionExecutor) {
		if (semanticContext != SemanticContext.NONE) {
			if (lexerActionExecutor != null) {
				return new ActionSemanticContextATNConfig(lexerActionExecutor, semanticContext, state, alt, context, false);
			}
			else {
				return new SemanticContextATNConfig(semanticContext, state, alt, context);
			}
		}
		else if (lexerActionExecutor != null) {
			return new ActionATNConfig(lexerActionExecutor, state, alt, context, false);
		}
		else {
			return new ATNConfig(state, alt, context);
		}
	}

	/** Gets the ATN state associated with this configuration */
	@NotNull
	public final ATNState getState() {
		return state;
	}

	/** What alt (or lexer rule) is predicted by this configuration */
	public final int getAlt() {
		return altAndOuterContextDepth & 0x00FFFFFF;
	}

	@NotNull
	public final PredictionContext getContext() {
		return context;
	}

	public void setContext(@NotNull PredictionContext context) {
		if (this.context != context) {
			this.context = context;
			// Context is part of {@link #hashCode}; drop the cache.
			cachedHashCode = 0;
		}
	}

	public final boolean getReachesIntoOuterContext() {
		return getOuterContextDepth() != 0;
	}

	/**
	 * We cannot execute predicates dependent upon local context unless
	 * we know for sure we are in the correct context. Because there is
	 * no way to do this efficiently, we simply cannot evaluate
	 * dependent predicates unless we are in the rule that initially
	 * invokes the ATN simulator.
	 *
	 * <p>
	 * closure() tracks the depth of how far we dip into the outer context:
	 * depth &gt; 0.  Note that it may not be totally accurate depth since I
	 * don't ever decrement. TODO: make it a boolean then</p>
	 */
	public final int getOuterContextDepth() {
		return (altAndOuterContextDepth >>> 24) & 0x7F;
	}

	public void setOuterContextDepth(int outerContextDepth) {
		assert outerContextDepth >= 0;
		// saturate at 0x7F - everything but zero/positive is only used for debug information anyway
		outerContextDepth = Math.min(outerContextDepth, 0x7F);
		// hashCode only samples reaches-into-outer (depth != 0), not the full depth.
		boolean wasReaching = getReachesIntoOuterContext();
		this.altAndOuterContextDepth = (outerContextDepth << 24) | (altAndOuterContextDepth & ~0x7F000000);
		if (wasReaching != getReachesIntoOuterContext()) {
			cachedHashCode = 0;
		}
	}

	@Nullable
	public LexerActionExecutor getLexerActionExecutor() {
		return null;
	}

	@NotNull
	public SemanticContext getSemanticContext() {
		return SemanticContext.NONE;
	}

	public boolean hasPassedThroughNonGreedyDecision() {
		return passedThroughNonGreedyDecision;
	}

	@Override
	public final ATNConfig clone() {
		return transform(this.getState(), false);
	}

	public final ATNConfig transform(@NotNull ATNState state, boolean checkNonGreedy) {
		return transform(state, this.context, this.getSemanticContext(), checkNonGreedy, this.getLexerActionExecutor());
	}

	public final ATNConfig transform(@NotNull ATNState state, @NotNull SemanticContext semanticContext, boolean checkNonGreedy) {
		return transform(state, this.context, semanticContext, checkNonGreedy, this.getLexerActionExecutor());
	}

	public final ATNConfig transform(@NotNull ATNState state, @Nullable PredictionContext context, boolean checkNonGreedy) {
		return transform(state, context, this.getSemanticContext(), checkNonGreedy, this.getLexerActionExecutor());
	}

	public final ATNConfig transform(@NotNull ATNState state, LexerActionExecutor lexerActionExecutor, boolean checkNonGreedy) {
		return transform(state, context, this.getSemanticContext(), checkNonGreedy, lexerActionExecutor);
	}

	/**
	 * Package-private parser-only config transitions used by
	 * {@link EpsilonClosure} and {@link ReachComputation}. Parser configs never
	 * carry a {@link LexerActionExecutor} or pass through non-greedy lexer
	 * decisions; keeping these helpers small and lexer-free ensures HotSpot C2
	 * inlines them cleanly into {@code closeOneDirect} without dragging in
	 * {@link ActionATNConfig}.
	 */
	final ATNConfig transformParser(@NotNull ATNState state) {
		SemanticContext sem = getSemanticContext();
		return sem == SemanticContext.NONE
			? new ATNConfig(this, state, context, false)
			: new SemanticContextATNConfig(sem, this, state, context, false);
	}

	final ATNConfig transformParser(@NotNull ATNState state, @Nullable PredictionContext context) {
		SemanticContext sem = getSemanticContext();
		return sem == SemanticContext.NONE
			? new ATNConfig(this, state, context, false)
			: new SemanticContextATNConfig(sem, this, state, context, false);
	}

	final ATNConfig transformParser(@NotNull ATNState state, @NotNull SemanticContext semanticContext) {
		return semanticContext == SemanticContext.NONE
			? new ATNConfig(this, state, context, false)
			: new SemanticContextATNConfig(semanticContext, this, state, context, false);
	}

	private ATNConfig transform(@NotNull ATNState state, @Nullable PredictionContext context, @NotNull SemanticContext semanticContext, boolean checkNonGreedy, LexerActionExecutor lexerActionExecutor) {
		boolean passedThroughNonGreedy = checkNonGreedy && checkNonGreedyDecision(this, state);
		if (semanticContext != SemanticContext.NONE) {
			if (lexerActionExecutor != null) {
				return new ActionSemanticContextATNConfig(lexerActionExecutor, semanticContext, this, state, context, passedThroughNonGreedy);
			}
			else {
				return new SemanticContextATNConfig(semanticContext, this, state, context, passedThroughNonGreedy);
			}
		}
		else if (lexerActionExecutor != null) {
			return new ActionATNConfig(lexerActionExecutor, this, state, context, passedThroughNonGreedy);
		}
		else {
			return new ATNConfig(this, state, context, passedThroughNonGreedy);
		}
	}

	private static boolean checkNonGreedyDecision(ATNConfig source, ATNState target) {
		return source.hasPassedThroughNonGreedyDecision()
			|| target instanceof DecisionState && ((DecisionState)target).nonGreedy;
	}

	public ATNConfig appendContext(int context, PredictionContextCache contextCache) {
		PredictionContext appendedContext = getContext().appendContext(context, contextCache);
		ATNConfig result = transform(getState(), appendedContext, false);
		return result;
	}

	public ATNConfig appendContext(PredictionContext context, PredictionContextCache contextCache) {
		PredictionContext appendedContext = getContext().appendContext(context, contextCache);
		ATNConfig result = transform(getState(), appendedContext, false);
		return result;
	}

	/**
	 * Determines if this {@link ATNConfig} fully contains another
	 * {@link ATNConfig}.
	 *
	 * <p>An ATN configuration represents a position (including context) in an
	 * ATN during parsing. Since {@link ATNConfig} stores the context as a
	 * graph, a single {@link ATNConfig} instance is capable of representing
	 * many ATN configurations which are all in the same "location" but have
	 * different contexts. These {@link ATNConfig} instances are again merged
	 * when they are added to an {@link ATNConfigSet}. This method supports
	 * {@link ATNConfigSet#contains} by evaluating whether a particular
	 * {@link ATNConfig} contains all of the ATN configurations represented by
	 * another {@link ATNConfig}.</p>
	 *
	 * <p>An {@link ATNConfig} <em>a</em> contains another {@link ATNConfig}
	 * <em>b</em> if all of the following conditions are met:</p>
	 *
	 * <ul>
	 * <li>The configurations are in the same state ({@link #getState()})</li>
	 * <li>The configurations predict the same alternative
	 * ({@link #getAlt()})</li>
	 * <li>The semantic context of <em>a</em> implies the semantic context of
	 * <em>b</em> (this method performs a weaker equality check)</li>
	 * <li>Joining the prediction contexts of <em>a</em> and <em>b</em> results
	 * in the prediction context of <em>a</em></li>
	 * </ul>
	 *
	 * <p>This method implements a conservative approximation of containment. As
	 * a result, when this method returns {code true} it is known that parsing
	 * from {@code subconfig} can only recognize a subset of the inputs which
	 * can be recognized starting at the current {@link ATNConfig}. However, due
	 * to the imprecise evaluation of implication for the semantic contexts, no
	 * assumptions can be made about the relationship between the configurations
	 * when this method returns {@code false}.</p>
	 *
	 * @param subconfig The sub configuration.
	 * @return {@code true} if this configuration contains {@code subconfig};
	 * otherwise, {@code false}.
	 */
	public boolean contains(ATNConfig subconfig) {
		if (this.getState().stateNumber != subconfig.getState().stateNumber
			|| this.getAlt() != subconfig.getAlt()
			|| !this.getSemanticContext().equals(subconfig.getSemanticContext())) {
			return false;
		}

		Deque<PredictionContext> leftWorkList = new ArrayDeque<>();
		Deque<PredictionContext> rightWorkList = new ArrayDeque<>();
		leftWorkList.add(getContext());
		rightWorkList.add(subconfig.getContext());
		while (!leftWorkList.isEmpty()) {
			PredictionContext left = leftWorkList.pop();
			PredictionContext right = rightWorkList.pop();

			if (left == right) {
				return true;
			}

			if (left.size() < right.size()) {
				return false;
			}

			if (right.isEmpty()) {
				return left.hasEmpty();
			} else {
				for (int i = 0; i < right.size(); i++) {
					int index = left.findReturnState(right.getReturnState(i));
					if (index < 0) {
						// assumes invokingStates has no duplicate entries
						return false;
					}

					leftWorkList.push(left.getParent(index));
					rightWorkList.push(right.getParent(i));
				}
			}
		}

		return false;
	}

	public final boolean isPrecedenceFilterSuppressed() {
		return (altAndOuterContextDepth & SUPPRESS_PRECEDENCE_FILTER) != 0;
	}

	public final void setPrecedenceFilterSuppressed(boolean value) {
		if (value) {
			this.altAndOuterContextDepth |= SUPPRESS_PRECEDENCE_FILTER;
		}
		else {
			this.altAndOuterContextDepth &= ~SUPPRESS_PRECEDENCE_FILTER;
		}
	}

	/** An ATN configuration is equal to another if both have
     *  the same state, they predict the same alternative, and
     *  syntactic/semantic contexts are the same.
     */
    @Override
    public boolean equals(Object o) {
		if (!(o instanceof ATNConfig)) {
			return false;
		}

		return this.equals((ATNConfig)o);
	}

	public boolean equals(ATNConfig other) {
		if (this == other) {
			return true;
		}
		else if (other == null) {
			return false;
		}

		if (this.getState().stateNumber != other.getState().stateNumber
			|| this.getAlt() != other.getAlt()
			|| this.getReachesIntoOuterContext() != other.getReachesIntoOuterContext()
			|| this.isPrecedenceFilterSuppressed() != other.isPrecedenceFilterSuppressed()
			|| this.hasPassedThroughNonGreedyDecision() != other.hasPassedThroughNonGreedyDecision()) {
			return false;
		}
		int h1 = this.cachedHashCode;
		int h2 = other.cachedHashCode;
		if (h1 != 0 && h2 != 0 && h1 != h2) {
			return false;
		}
		PredictionContext c1 = this.context;
		PredictionContext c2 = other.context;
		if (c1 != c2 && !c1.equals(c2)) {
			return false;
		}
		SemanticContext s1 = this.getSemanticContext();
		SemanticContext s2 = other.getSemanticContext();
		if (s1 != s2 && !s1.equals(s2)) {
			return false;
		}
		return ObjectEqualityComparator.INSTANCE.equals(this.getLexerActionExecutor(), other.getLexerActionExecutor());
	}

	@Override
	public int hashCode() {
		int h = cachedHashCode;
		if (h != 0) {
			return h;
		}
		return computeHashCode();
	}

	private static final int MURMUR_C1 = 0xCC9E2D51;
	private static final int MURMUR_C2 = 0x1B873593;
	private static final int MURMUR_N = 0xE6546B64;
	private static final int MURMUR_K_ONE = Integer.rotateLeft(MURMUR_C1, 15) * MURMUR_C2;
	private static final int MURMUR_K_SEMANTIC_NONE =
		Integer.rotateLeft(SemanticContext.NONE.hashCode() * MURMUR_C1, 15) * MURMUR_C2;

	private static int murmurStep(int h, int value) {
		int k = Integer.rotateLeft(value * MURMUR_C1, 15) * MURMUR_C2;
		return Integer.rotateLeft(h ^ k, 13) * 5 + MURMUR_N;
	}

	private int computeHashCode() {
		// Bit-exact MurmurHash3 matching the 7-word sequence:
		// (stateNumber, alt, reachesIntoOuterContext?1:0, context, semanticContext,
		//  passedThroughNonGreedyDecision?1:0, lexerActionExecutor) with seed 7.
		int h = murmurStep(7, state.stateNumber);
		int packed = altAndOuterContextDepth;
		h = murmurStep(h, packed & 0x00FFFFFF);
		h = (packed & 0x7F000000) != 0
			? Integer.rotateLeft(h ^ MURMUR_K_ONE, 13) * 5 + MURMUR_N
			: Integer.rotateLeft(h, 13) * 5 + MURMUR_N;
		PredictionContext ctx = this.context;
		h = ctx != null ? murmurStep(h, ctx.hashCode()) : Integer.rotateLeft(h, 13) * 5 + MURMUR_N;

		if (getClass() == ATNConfig.class && !passedThroughNonGreedyDecision) {
			// Dominant parser config path: SemanticContext.NONE, nonGreedy=false, exec=null.
			h = Integer.rotateLeft(h ^ MURMUR_K_SEMANTIC_NONE, 13) * 5 + MURMUR_N;
			h = Integer.rotateLeft(h, 13) * 5 + MURMUR_N;
			h = Integer.rotateLeft(h, 13) * 5 + MURMUR_N;
		}
		else {
			SemanticContext sem = getSemanticContext();
			h = sem == SemanticContext.NONE
				? Integer.rotateLeft(h ^ MURMUR_K_SEMANTIC_NONE, 13) * 5 + MURMUR_N
				: (sem != null ? murmurStep(h, sem.hashCode()) : Integer.rotateLeft(h, 13) * 5 + MURMUR_N);
			h = hasPassedThroughNonGreedyDecision()
				? Integer.rotateLeft(h ^ MURMUR_K_ONE, 13) * 5 + MURMUR_N
				: Integer.rotateLeft(h, 13) * 5 + MURMUR_N;
			LexerActionExecutor exec = getLexerActionExecutor();
			h = exec != null ? murmurStep(h, exec.hashCode()) : Integer.rotateLeft(h, 13) * 5 + MURMUR_N;
		}

		h ^= 28; // 7 * 4
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		h *= 0xC2B2AE35;
		h ^= h >>> 16;
		// Preserve zero as the "uncomputed" sentinel: map a raw zero hash to 1.
		if (h == 0) {
			h = 1;
		}
		cachedHashCode = h;
		return h;
	}

	/**
	 * Returns a graphical representation of the current {@link ATNConfig} in
	 * Graphviz format. The graph can be stored to a <strong>.dot</strong> file
	 * and then rendered to an image using Graphviz.
	 *
	 * @return A Graphviz graph representing the current {@link ATNConfig}.
	 *
	 * @see http://www.graphviz.org/
	 */
	public String toDotString() {
		StringBuilder builder = new StringBuilder();
		builder.append("digraph G {\n");
		builder.append("rankdir=LR;\n");

		Map<PredictionContext, PredictionContext> visited = new IdentityHashMap<>();
		Deque<PredictionContext> workList = new ArrayDeque<>();
		workList.add(getContext());
		visited.put(getContext(), getContext());
		while (!workList.isEmpty()) {
			PredictionContext current = workList.pop();
			for (int i = 0; i < current.size(); i++) {
				builder.append("  s").append(System.identityHashCode(current));
				builder.append("->");
				builder.append("s").append(System.identityHashCode(current.getParent(i)));
				builder.append("[label=\"").append(current.getReturnState(i)).append("\"];\n");
				if (visited.put(current.getParent(i), current.getParent(i)) == null) {
					workList.push(current.getParent(i));
				}
			}
		}

		builder.append("}\n");
		return builder.toString();
	}

	@Override
	public String toString() {
		return toString(null, true, false);
	}

	public String toString(@Nullable Recognizer<?, ?> recog, boolean showAlt) {
		return toString(recog, showAlt, true);
	}

	public String toString(@Nullable Recognizer<?, ?> recog, boolean showAlt, boolean showContext) {
		StringBuilder buf = new StringBuilder();
//		if ( state.ruleIndex>=0 ) {
//			if ( recog!=null ) buf.append(recog.getRuleNames()[state.ruleIndex]+":");
//			else buf.append(state.ruleIndex+":");
//		}
		String[] contexts;
		if (showContext) {
			contexts = getContext().toStrings(recog, this.getState().stateNumber);
		}
		else {
			contexts = new String[] { "?" };
		}
		boolean first = true;
		for (String contextDesc : contexts) {
			if ( first ) {
				first = false;
			}
			else {
				buf.append(", ");
			}

			buf.append('(');
			buf.append(getState());
			if ( showAlt ) {
				buf.append(",");
				buf.append(getAlt());
			}
			if ( getContext()!=null ) {
				buf.append(",");
				buf.append(contextDesc);
			}
			if ( getSemanticContext()!=null && getSemanticContext() != SemanticContext.NONE ) {
				buf.append(",");
				buf.append(getSemanticContext());
			}
			if ( getReachesIntoOuterContext() ) {
				buf.append(",up=").append(getOuterContextDepth());
			}
			buf.append(')');
		}
		return buf.toString();
    }

	/**
	 * This class was derived from {@link ATNConfig} purely as a memory
	 * optimization. It allows for the creation of an {@link ATNConfig} with a
	 * non-default semantic context.
	 *
	 * <p>See the {@link ATNConfig} documentation for more information about
	 * conserving memory through the use of several concrete types.</p>
	 */
	private static class SemanticContextATNConfig extends ATNConfig {

		@NotNull
		private final SemanticContext semanticContext;

		public SemanticContextATNConfig(SemanticContext semanticContext, @NotNull ATNState state, int alt, @Nullable PredictionContext context) {
			this(semanticContext, state, alt, context, false);
		}

		public SemanticContextATNConfig(SemanticContext semanticContext, @NotNull ATNState state, int alt, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(state, alt, context, passedThroughNonGreedyDecision);
			this.semanticContext = semanticContext;
		}

		public SemanticContextATNConfig(SemanticContext semanticContext, @NotNull ATNConfig c, @NotNull ATNState state, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(c, state, context, passedThroughNonGreedyDecision);
			this.semanticContext = semanticContext;
		}

		@Override
		public SemanticContext getSemanticContext() {
			return semanticContext;
		}

	}

	/**
	 * This class was derived from {@link ATNConfig} purely as a memory
	 * optimization. It allows for the creation of an {@link ATNConfig} with a
	 * lexer action.
	 *
	 * <p>See the {@link ATNConfig} documentation for more information about
	 * conserving memory through the use of several concrete types.</p>
	 */
	private static class ActionATNConfig extends ATNConfig {

		private final LexerActionExecutor lexerActionExecutor;

		public ActionATNConfig(LexerActionExecutor lexerActionExecutor, @NotNull ATNState state, int alt, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(state, alt, context, passedThroughNonGreedyDecision);
			this.lexerActionExecutor = lexerActionExecutor;
		}

		protected ActionATNConfig(LexerActionExecutor lexerActionExecutor, @NotNull ATNConfig c, @NotNull ATNState state, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(c, state, context, passedThroughNonGreedyDecision);
			if (c.getSemanticContext() != SemanticContext.NONE) {
				throw new UnsupportedOperationException();
			}

			this.lexerActionExecutor = lexerActionExecutor;
		}

		@Override
		public LexerActionExecutor getLexerActionExecutor() {
			return lexerActionExecutor;
		}
	}

	/**
	 * This class was derived from {@link SemanticContextATNConfig} purely as a memory
	 * optimization. It allows for the creation of an {@link ATNConfig} with
	 * both a lexer action and a non-default semantic context.
	 *
	 * <p>See the {@link ATNConfig} documentation for more information about
	 * conserving memory through the use of several concrete types.</p>
	 */
	private static class ActionSemanticContextATNConfig extends SemanticContextATNConfig {

		private final LexerActionExecutor lexerActionExecutor;

		public ActionSemanticContextATNConfig(LexerActionExecutor lexerActionExecutor, @NotNull SemanticContext semanticContext, @NotNull ATNState state, int alt, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(semanticContext, state, alt, context, passedThroughNonGreedyDecision);
			this.lexerActionExecutor = lexerActionExecutor;
		}

		public ActionSemanticContextATNConfig(LexerActionExecutor lexerActionExecutor, @NotNull SemanticContext semanticContext, @NotNull ATNConfig c, @NotNull ATNState state, @Nullable PredictionContext context, boolean passedThroughNonGreedyDecision) {
			super(semanticContext, c, state, context, passedThroughNonGreedyDecision);
			this.lexerActionExecutor = lexerActionExecutor;
		}

		@Override
		public LexerActionExecutor getLexerActionExecutor() {
			return lexerActionExecutor;
		}
	}

}
