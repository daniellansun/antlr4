/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class LL1Analyzer {
	/** Special value added to the lookahead sets to indicate that we hit
	 *  a predicate during analysis if {@code seeThruPreds==false}.
	 */
	public static final int HIT_PRED = Token.INVALID_TYPE;

	/**
	 * Controls how {@link LL1Analyzer} treats semantic and precedence predicate
	 * transitions during ATN lookahead walks.
	 *
	 * @since 4.13.2.20
	 */
	public enum PredicatePolicy {
		/** Treat every predicate transition as opaque and record {@link #HIT_PRED}. */
		OPAQUE {
			@Override
			boolean shouldSeeThrough(@NotNull AbstractPredicateTransition t, boolean inDroppedDepth) {
				return false;
			}
		},
		/** See through all predicate transitions as implicitly {@code true}. */
		SEE_THROUGH_ALL {
			@Override
			boolean shouldSeeThrough(@NotNull AbstractPredicateTransition t, boolean inDroppedDepth) {
				return true;
			}
		},
		/**
		 * Mirrors ALL(*) {@link EpsilonClosure} predicate dropping: outside the
		 * entry rule context ({@code callDepth != 0}), see through
		 * {@link PrecedencePredicateTransition} and context-dependent
		 * {@link PredicateTransition} (which ALL(*) drops at non-zero depth),
		 * while keeping depth-0 predicates and all context-independent
		 * predicates opaque.
		 */
		SEE_THROUGH_DROPPED_CALLEE_PREDS {
			@Override
			boolean shouldSeeThrough(@NotNull AbstractPredicateTransition t, boolean inDroppedDepth) {
				return inDroppedDepth
					&& ((t instanceof PrecedencePredicateTransition)
						|| (t instanceof PredicateTransition && ((PredicateTransition) t).isCtxDependent));
			}
		};

		abstract boolean shouldSeeThrough(@NotNull AbstractPredicateTransition t, boolean inDroppedDepth);
	}

	@NotNull
	public final ATN atn;

	public LL1Analyzer(@NotNull ATN atn) { this.atn = atn; }

	/**
	 * Calculates the SLL(1) expected lookahead set for each outgoing transition
	 * of an {@link ATNState}. The returned array has one element for each
	 * outgoing transition in {@code s}. If the closure from transition
	 * <em>i</em> leads to a semantic predicate before matching a symbol, the
	 * element at index <em>i</em> of the result will be {@code null}.
	 *
	 * @param s the ATN state
	 * @return the expected symbols for each outgoing transition of {@code s}.
	 */
	@Nullable
	public IntervalSet[] getDecisionLookahead(@Nullable ATNState s) {
//		System.out.println("LOOK("+s.stateNumber+")");
		if ( s==null ) {
			return null;
		}

		IntervalSet[] look = new IntervalSet[s.getNumberOfTransitions()];
		for (int alt = 0; alt < s.getNumberOfTransitions(); alt++) {
			look[alt] = new IntervalSet();
			Set<ATNConfig> lookBusy = new HashSet<>();
			boolean seeThruPreds = false; // fail to get lookahead upon pred
			_LOOK(s.transition(alt).target, null, PredictionContext.EMPTY_LOCAL,
				  look[alt], lookBusy, new BitSet(), seeThruPreds, false);
			// Wipe out lookahead for this alternative if we found nothing
			// or we had a predicate when we !seeThruPreds
			if ( look[alt].size()==0 || look[alt].contains(HIT_PRED) ) {
				look[alt] = null;
			}
		}
		return look;
	}

	/**
	 * Compute set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx}.
	 *
	 * <p>If {@code ctx} is {@code null} and the end of the rule containing
	 * {@code s} is reached, {@link Token#EPSILON} is added to the result set.
	 * If {@code ctx} is not {@code null} and the end of the outermost rule is
	 * reached, {@link Token#EOF} is added to the result set.</p>
	 *
	 * @param s the ATN state
	 * @param ctx the complete parser context, or {@code null} if the context
	 * should be ignored
	 *
	 * @return The set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx}.
	 */
    @NotNull
   	public IntervalSet LOOK(@NotNull ATNState s, @NotNull PredictionContext ctx) {
		return LOOK(s, s.atn.ruleToStopState[s.ruleIndex], ctx);
   	}

	/**
	 * Compute set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx}.
	 *
	 * <p>If {@code ctx} is {@code null} and the end of the rule containing
	 * {@code s} is reached, {@link Token#EPSILON} is added to the result set.
	 * If {@code ctx} is not {@code PredictionContext#EMPTY_LOCAL} and the end of the outermost rule is
	 * reached, {@link Token#EOF} is added to the result set.</p>
	 *
	 * @param s the ATN state
	 * @param stopState the ATN state to stop at. This can be a
	 * {@link BlockEndState} to detect epsilon paths through a closure.
	 * @param ctx the complete parser context, or {@code null} if the context
	 * should be ignored
	 *
	 * @return The set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx}.
	 */
    @NotNull
   	public IntervalSet LOOK(@NotNull ATNState s, @Nullable ATNState stopState, @NotNull PredictionContext ctx) {
   		IntervalSet r = new IntervalSet();
		final boolean seeThruPreds = true; // ignore preds; get all lookahead
		final boolean addEOF = true;
   		_LOOK(s, stopState, ctx, r, new HashSet<>(), new BitSet(), seeThruPreds, addEOF);
   		return r;
   	}

	/**
	 * Compute set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx} under {@code predicatePolicy}, adding
	 * {@link Token#EOF} when the outermost rule completes.
	 *
	 * @since 4.13.2.20
	 */
	@NotNull
	public IntervalSet LOOK(@NotNull ATNState s,
							@Nullable ATNState stopState,
							@NotNull PredictionContext ctx,
							@NotNull PredicatePolicy predicatePolicy)
	{
		return LOOK(s, stopState, ctx, predicatePolicy, true);
	}

	/**
	 * Compute set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx} under {@code predicatePolicy}.
	 *
	 * @since 4.13.2.20
	 */
	@NotNull
	public IntervalSet LOOK(@NotNull ATNState s,
							@Nullable ATNState stopState,
							@NotNull PredictionContext ctx,
							@NotNull PredicatePolicy predicatePolicy,
							boolean addEOF)
	{
		Objects.requireNonNull(predicatePolicy, "predicatePolicy");
		IntervalSet r = new IntervalSet();
		if (predicatePolicy != PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS) {
			// Dispatch root call through legacy 8-arg _LOOK so overriding subclasses
			// observe both root and recursive frames.
			_LOOK(s, stopState, ctx, r, new HashSet<>(), new BitSet(),
				predicatePolicy == PredicatePolicy.SEE_THROUGH_ALL, addEOF);
		}
		else {
			_LOOK(s, stopState, ctx, r, new HashSet<>(), new BitSet(), predicatePolicy, addEOF);
		}
		return r;
	}

	/**
	 * Compute set of tokens that can follow {@code s} in the ATN in the
	 * specified {@code ctx}.
	 * <p/>
	 * If {@code ctx} is {@link PredictionContext#EMPTY_LOCAL} and
	 * {@code stopState} or the end of the rule containing {@code s} is reached,
	 * {@link Token#EPSILON} is added to the result set. If {@code ctx} is not
	 * {@link PredictionContext#EMPTY_LOCAL} and {@code addEOF} is {@code true}
	 * and {@code stopState} or the end of the outermost rule is reached,
	 * {@link Token#EOF} is added to the result set.
	 *
	 * @param s the ATN state.
	 * @param stopState the ATN state to stop at. This can be a
	 * {@link BlockEndState} to detect epsilon paths through a closure.
	 * @param ctx The outer context, or {@link PredictionContext#EMPTY_LOCAL} if
	 * the outer context should not be used.
	 * @param look The result lookahead set.
	 * @param lookBusy A set used for preventing epsilon closures in the ATN
	 * from causing a stack overflow. Outside code should pass
	 * {@code new HashSet<ATNConfig>} for this argument.
	 * @param calledRuleStack A set used for preventing left recursion in the
	 * ATN from causing a stack overflow. Outside code should pass
	 * {@code new BitSet()} for this argument.
	 * @param seeThruPreds {@code true} to treat semantic predicates as
	 * implicitly {@code true} and "see through them", otherwise {@code false}
	 * to treat semantic predicates as opaque and add {@link #HIT_PRED} to the
	 * result if one is encountered.
	 * @param addEOF Add {@link Token#EOF} to the result if the end of the
	 * outermost context is reached. This parameter has no effect if {@code ctx}
	 * is {@link PredictionContext#EMPTY_LOCAL}.
	 */
    protected void _LOOK(@NotNull ATNState s,
						 @Nullable ATNState stopState,
						 @NotNull PredictionContext ctx,
						 @NotNull IntervalSet look,
                         @NotNull Set<ATNConfig> lookBusy,
						 @NotNull BitSet calledRuleStack,
						 boolean seeThruPreds, boolean addEOF)
	{
		_LOOKInternal(s, stopState, ctx, look, lookBusy, calledRuleStack,
			seeThruPreds ? PredicatePolicy.SEE_THROUGH_ALL : PredicatePolicy.OPAQUE, addEOF, 0);
	}

	/**
	 * Policy-driven ATN lookahead walk unifying {@link #_LOOK(ATNState, ATNState, PredictionContext, IntervalSet, Set, BitSet, boolean, boolean)}
	 * and ALL(*)-exact callee-predicate see-through analysis.
	 *
	 * @since 4.13.2.20
	 */
	protected void _LOOK(@NotNull ATNState s,
						 @Nullable ATNState stopState,
						 @NotNull PredictionContext ctx,
						 @NotNull IntervalSet look,
						 @NotNull Set<ATNConfig> lookBusy,
						 @NotNull BitSet calledRuleStack,
						 @NotNull PredicatePolicy predicatePolicy,
						 boolean addEOF)
	{
		Objects.requireNonNull(predicatePolicy, "predicatePolicy");
		if (getClass() != LL1Analyzer.class && predicatePolicy != PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS) {
			_LOOK(s, stopState, ctx, look, lookBusy, calledRuleStack,
				predicatePolicy == PredicatePolicy.SEE_THROUGH_ALL, addEOF);
			return;
		}
		_LOOKInternal(s, stopState, ctx, look, lookBusy, calledRuleStack, predicatePolicy, addEOF, 0);
	}

	private void _LOOKInternal(@NotNull ATNState s,
							   @Nullable ATNState stopState,
							   @NotNull PredictionContext ctx,
							   @NotNull IntervalSet look,
							   @NotNull Set<ATNConfig> lookBusy,
							   @NotNull BitSet calledRuleStack,
							   @NotNull PredicatePolicy predicatePolicy,
							   boolean addEOF,
							   int callDepth)
	{
		final boolean dropMode = predicatePolicy == PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS;
		final boolean inDroppedDepth = callDepth != 0;
		// When dropMode is active, separate all three depth regimes in lookBusy
		// via the alt slot (0: depth == 0, 1: depth > 0, 2: latched depth < 0).
		// While depth >= 0, every rule push wraps ctx and every pop unwraps it so
		// (s, ctx) uniquely determines positive callDepth; separating depth > 0
		// from depth < 0 ensures a latched depth == -1 walk can never poison a
		// later depth > 0 walk that will pop back to depth == 0 (opaque preds).
		final int busyAlt = !dropMode ? 0 : (callDepth == 0 ? 0 : (callDepth > 0 ? 1 : 2));
        ATNConfig c = ATNConfig.create(s, busyAlt, ctx);
        if ( !lookBusy.add(c) ) return;

		if (s == stopState) {
			if (PredictionContext.isEmptyLocal(ctx)) {
				look.add(Token.EPSILON);
				return;
			} else if (ctx.isEmpty()) {
				if (addEOF) {
					look.add(Token.EOF);
				}

				return;
			}
		}

		final boolean isRuleStop = s instanceof RuleStopState;
        if ( isRuleStop ) {
            if (ctx.isEmpty() && !PredictionContext.isEmptyLocal(ctx)) {
				if (addEOF) {
					look.add(Token.EOF);
				}

				return;
			}

			int poppedDepth = callDepth > 0 ? callDepth - 1 : -1;
			boolean removed = calledRuleStack.get(s.ruleIndex);
			try {
				calledRuleStack.clear(s.ruleIndex);
				for (int i = 0; i < ctx.size(); i++) {
					if (ctx.getReturnState(i) == PredictionContext.EMPTY_FULL_STATE_KEY) {
						continue;
					}

					ATNState returnState = atn.states.get(ctx.getReturnState(i));
					recurseLook(returnState, stopState, ctx.getParent(i), look, lookBusy, calledRuleStack, predicatePolicy, addEOF, poppedDepth);
				}
			}
			finally {
				if (removed) {
					calledRuleStack.set(s.ruleIndex);
				}
			}
			if (!PredictionContext.isEmptyLocal(ctx)) {
				return;
			}
        }

		// When s is a RuleStopState falling through with EMPTY_LOCAL to outgoing
		// FOLLOW edges, mirror EpsilonClosure depth latching (depth > 0 ? depth - 1 : -1).
		final int edgeDepth = isRuleStop ? (callDepth > 0 ? callDepth - 1 : -1) : callDepth;
        int n = s.getNumberOfTransitions();
        for (int i=0; i<n; i++) {
			Transition t = s.transition(i);
			if ( t instanceof RuleTransition ) {
				RuleTransition ruleTransition = (RuleTransition)t;
				if (calledRuleStack.get(ruleTransition.ruleIndex)) {
					continue;
				}

				PredictionContext newContext = ctx.getChild(ruleTransition.followState.stateNumber);
				int pushedDepth = edgeDepth >= 0 ? edgeDepth + 1 : edgeDepth;

				try {
					calledRuleStack.set(ruleTransition.ruleIndex);
					recurseLook(t.target, stopState, newContext, look, lookBusy, calledRuleStack, predicatePolicy, addEOF, pushedDepth);
				}
				finally {
					calledRuleStack.clear(ruleTransition.ruleIndex);
				}
			}
			else if ( t instanceof AbstractPredicateTransition ) {
				if ( predicatePolicy.shouldSeeThrough((AbstractPredicateTransition) t, inDroppedDepth) ) {
					recurseLook(t.target, stopState, ctx, look, lookBusy, calledRuleStack, predicatePolicy, addEOF, edgeDepth);
				}
				else {
					look.add(HIT_PRED);
				}
			}
			else if ( t.isEpsilon() ) {
				recurseLook(t.target, stopState, ctx, look, lookBusy, calledRuleStack, predicatePolicy, addEOF, edgeDepth);
			}
			else if ( t.getClass() == WildcardTransition.class ) {
				look.addAll( IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType) );
			}
			else {
				IntervalSet set = t.label();
				if (set != null) {
					if (t instanceof NotSetTransition) {
						set = set.complement(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType));
					}
					look.addAll(set);
				}
			}
		}
	}

	private void recurseLook(@NotNull ATNState target,
							 @Nullable ATNState stopState,
							 @NotNull PredictionContext ctx,
							 @NotNull IntervalSet look,
							 @NotNull Set<ATNConfig> lookBusy,
							 @NotNull BitSet calledRuleStack,
							 @NotNull PredicatePolicy predicatePolicy,
							 boolean addEOF,
							 int nextDepth)
	{
		if (getClass() != LL1Analyzer.class && predicatePolicy != PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS) {
			_LOOK(target, stopState, ctx, look, lookBusy, calledRuleStack,
				predicatePolicy == PredicatePolicy.SEE_THROUGH_ALL, addEOF);
		}
		else {
			_LOOKInternal(target, stopState, ctx, look, lookBusy, calledRuleStack, predicatePolicy, addEOF, nextDepth);
		}
	}
}
