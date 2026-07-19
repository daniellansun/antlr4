/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Epsilon-closure engine for adaptive LL(*) prediction.
 *
 * <p>
 * Owns busy-set sizing, the predicate vs non-predicate control split, and
 * double-buffered intermediate config sets for breadth-first rule-transition
 * merging. Recursive edge walking stays here so {@link ParserATNSimulator}
 * remains orchestration-focused rather than absorbing more allocation
 * policy.</p>
 *
 * <p>
 * Package-private: only {@link ParserATNSimulator} constructs and uses this
 * type. Transition target computation is delegated back to the simulator
 * ({@link ParserATNSimulator#getEpsilonTarget}) so semantic-predicate and
 * rule-transition policy remain in one place.</p>
 *
 */
final class EpsilonClosure {

	@NotNull
	private final ParserATNSimulator simulator;

	EpsilonClosure(@NotNull ParserATNSimulator simulator) {
		this.simulator = simulator;
	}

	/**
	 * Compute the epsilon closure of every configuration in
	 * {@code sourceConfigs}, adding the resulting non-epsilon (or EOF-as-epsilon)
	 * configurations to {@code configs}.
	 *
	 * <p>
	 * When {@code collectPredicates} is {@code false} (the common path in
	 * {@link ParserATNSimulator#computeTargetState}), rule transitions are
	 * deferred into an intermediate config set and processed breadth-first so
	 * configs that enter the same rule via different edges can merge before the
	 * rule body is expanded. Intermediate sets use two dedicated buffers that
	 * swap roles each layer — {@code sourceConfigs} is never treated as a
	 * reusable buffer. When {@code collectPredicates} is {@code true}, rule
	 * transitions are followed immediately and no intermediate set is
	 * allocated.</p>
	 */
	void close(@NotNull ATNConfigSet sourceConfigs,
			   @NotNull ATNConfigSet configs,
			   boolean collectPredicates,
			   boolean hasMoreContext,
			   @NotNull PredictionContextCache contextCache,
			   boolean treatEofAsEpsilon)
	{
		final int sourceSize = sourceConfigs.size();
		if (sourceSize == 0) {
			return;
		}

		// HashSet(int) treats the argument as map capacity (buckets), not element
		// count; size*2 is a safe upper estimate for right-recursion / EOF* busy
		// traffic without overflow for realistic config-set sizes.
		int busyCapacity = sourceSize < (Integer.MAX_VALUE >> 1)
			? Math.max(16, sourceSize << 1)
			: Integer.MAX_VALUE;
		Set<ATNConfig> closureBusy = new HashSet<ATNConfig>(busyCapacity);

		// Predicate collection follows rule transitions immediately; the
		// intermediate BFS layer is only useful when rule entries can be merged
		// without carrying distinct predicate contexts.
		// Dispatch through simulator.closure(ATNConfig, …) so subclasses that
		// override the recursive entry point still participate in every step.
		if (collectPredicates) {
			for (ATNConfig config : sourceConfigs) {
				simulator.closure(config, configs, null, closureBusy, true, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
			}
			return;
		}

		// Two dedicated intermediate buffers. sourceConfigs is read-only input
		// for the first layer and is never cleared or reused as a write buffer.
		final int intermediateHint = sourceSize < (Integer.MAX_VALUE >> 1)
			? Math.max(16, sourceSize << 1)
			: Integer.MAX_VALUE;
		ATNConfigSet bufferA = new ATNConfigSet(intermediateHint);
		ATNConfigSet bufferB = new ATNConfigSet(intermediateHint);

		ATNConfigSet current = sourceConfigs;
		ATNConfigSet next = bufferA;
		boolean nextIsBufferA = true;

		while (true) {
			for (ATNConfig config : current) {
				simulator.closure(config, configs, next, closureBusy, false, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
			}

			if (next.isEmpty()) {
				break;
			}

			// Advance: filled `next` becomes the read set; the other buffer is
			// cleared and becomes the write set for the following layer.
			current = next;
			if (nextIsBufferA) {
				bufferB.clear();
				next = bufferB;
				nextIsBufferA = false;
			}
			else {
				bufferA.clear();
				next = bufferA;
				nextIsBufferA = true;
			}
		}
	}

	/**
	 * Recursive helper: walk epsilon edges from {@code config}, adding leaf
	 * configurations to {@code configs} and optionally deferring rule
	 * transitions to {@code intermediate} for breadth-first processing.
	 */
	void closeOne(@NotNull ATNConfig config,
				  @NotNull ATNConfigSet configs,
				  @Nullable ATNConfigSet intermediate,
				  @NotNull Set<ATNConfig> closureBusy,
				  boolean collectPredicates,
				  boolean hasMoreContexts,
				  @NotNull PredictionContextCache contextCache,
				  int depth,
				  boolean treatEofAsEpsilon)
	{
		final ATN atn = simulator.atn;
		final DFA dfa = simulator.getDfaForClosure();
		final boolean optimizeTailCalls = simulator.optimize_tail_calls;
		final boolean tailCallPreservesSll = simulator.tail_call_preserves_sll;

		// Capture context once. After a RuleStopState transform, config may
		// reference EMPTY_LOCAL while this still holds the original context used
		// for precedence suppression and tail-call decisions (existing semantics).
		final PredictionContext predictionContext = config.getContext();
		ATNState configState = config.getState();
		if (configState instanceof RuleStopState) {
			// We hit rule end. If we have context info, use it
			if (!predictionContext.isEmpty()) {
				boolean hasEmpty = predictionContext.hasEmpty();
				int nonEmptySize = predictionContext.size() - (hasEmpty ? 1 : 0);
				final int alt = config.getAlt();
				final SemanticContext semanticContext = config.getSemanticContext();
				final int outerContextDepth = config.getOuterContextDepth();
				final boolean precedenceFilterSuppressed = config.isPrecedenceFilterSuppressed();
				for (int i = 0; i < nonEmptySize; i++) {
					PredictionContext newContext = predictionContext.getParent(i); // "pop" return state
					ATNState returnState = atn.states.get(predictionContext.getReturnState(i));
					ATNConfig c = ATNConfig.create(returnState, alt, newContext, semanticContext);
					// While we have context to pop back from, we may have
					// gotten that context AFTER having fallen off a rule.
					// Make sure we track that we are now out of context.
					c.setOuterContextDepth(outerContextDepth);
					c.setPrecedenceFilterSuppressed(precedenceFilterSuppressed);
					assert depth > Integer.MIN_VALUE;
					simulator.closure(c, configs, intermediate, closureBusy, collectPredicates, hasMoreContexts, contextCache, depth - 1, treatEofAsEpsilon);
				}

				if (!hasEmpty || !hasMoreContexts) {
					return;
				}

				config = config.transform(configState, PredictionContext.EMPTY_LOCAL, false);
				configState = config.getState();
			}
			else if (!hasMoreContexts) {
				configs.add(config, contextCache);
				return;
			}
			else {
				// else if we have no context info, just chase follow links (if greedy)
				if (predictionContext == PredictionContext.EMPTY_FULL) {
					// no need to keep full context overhead when we step out
					config = config.transform(configState, PredictionContext.EMPTY_LOCAL, false);
					configState = config.getState();
				}
				else if (!config.getReachesIntoOuterContext() && PredictionContext.isEmptyLocal(predictionContext)) {
					// add stop state when leaving decision rule for the first time
					configs.add(config, contextCache);
				}
			}
		}

		final ATNState p = configState;
		// optimization
		if (!p.onlyHasEpsilonTransitions()) {
			configs.add(config, contextCache);
			// make sure to not return here, because EOF transitions can act as
			// both epsilon transitions and non-epsilon transitions.
		}

		final int n = p.getNumberOfOptimizedTransitions();
		final boolean inContext = depth == 0;
		final boolean configAtRuleStop = p instanceof RuleStopState;
		// Hoist precedence-DFA suppress check operands; the suppress block only
		// applies to the first outgoing edge of a precedence star-loop entry.
		final boolean maybeSuppressPrecedenceEdge = n > 0
			&& p.getStateType() == ATNState.STAR_LOOP_ENTRY
			&& ((StarLoopEntryState)p).precedenceRuleDecision
			&& !predictionContext.hasEmpty();

		for (int i = 0; i < n; i++) {
			// This block implements first-edge elimination of ambiguous LR
			// alternatives as part of dynamic disambiguation during prediction.
			// See antlr/antlr4#1398.
			if (i == 0 && maybeSuppressPrecedenceEdge) {
				StarLoopEntryState precedenceDecision = (StarLoopEntryState)p;

				// When suppress is true, it means the outgoing edge i==0 is
				// ambiguous with the outgoing edge i==1, and thus the closure
				// operation can dynamically disambiguate by suppressing this
				// edge during the closure operation.
				boolean suppress = true;
				for (int j = 0, predictionContextSize = predictionContext.size(); j < predictionContextSize; j++) {
					if (!precedenceDecision.precedenceLoopbackStates.get(predictionContext.getReturnState(j))) {
						suppress = false;
						break;
					}
				}

				if (suppress) {
					continue;
				}
			}

			Transition t = p.getOptimizedTransition(i);
			final int transitionType = t.getSerializationType();
			boolean continueCollecting =
				transitionType != Transition.ACTION && collectPredicates;
			ATNConfig c = simulator.getEpsilonTarget(config, t, continueCollecting, inContext, contextCache, treatEofAsEpsilon);
			if (c != null) {
				if (transitionType == Transition.RULE) {
					if (intermediate != null && !collectPredicates) {
						intermediate.add(c, contextCache);
						continue;
					}
				}

				int newDepth = depth;
				if (configAtRuleStop) {
					// target fell off end of rule; mark resulting c as having dipped into outer context
					// We can't get here if incoming config was rule stop and we had context
					// track how far we dip into outer context.  Might
					// come in handy and we avoid evaluating context dependent
					// preds if this is > 0.

					if (dfa != null && dfa.isPrecedenceDfa()) {
						int outermostPrecedenceReturn = ((EpsilonTransition)t).outermostPrecedenceReturn();
						if (outermostPrecedenceReturn == dfa.atnStartState.ruleIndex) {
							c.setPrecedenceFilterSuppressed(true);
						}
					}

					c.setOuterContextDepth(c.getOuterContextDepth() + 1);

					if (!closureBusy.add(c)) {
						// avoid infinite recursion for right-recursive rules
						continue;
					}

					assert newDepth > Integer.MIN_VALUE;
					newDepth--;
				}
				else if (transitionType == Transition.RULE) {
					RuleTransition ruleTransition = (RuleTransition)t;
					if (optimizeTailCalls && ruleTransition.optimizedTailCall && (!tailCallPreservesSll || !PredictionContext.isEmptyLocal(predictionContext))) {
						assert c.getContext() == predictionContext;
						if (newDepth == 0) {
							// the pop/push of a tail call would keep the depth
							// constant, except we latch if it goes negative
							newDepth--;
							if (!tailCallPreservesSll && PredictionContext.isEmptyLocal(predictionContext)) {
								// make sure the SLL config "dips into the outer context" or prediction may not fall back to LL on conflict
								c.setOuterContextDepth(c.getOuterContextDepth() + 1);
							}
						}
					}
					else {
						// latch when newDepth goes negative - once we step out of the entry context we can't return
						if (newDepth >= 0) {
							newDepth++;
						}
					}
				}
				else {
					if (!t.isEpsilon() && !closureBusy.add(c)) {
						// avoid infinite recursion for EOF* and EOF+
						continue;
					}
				}

				simulator.closure(c, configs, intermediate, closureBusy, continueCollecting, hasMoreContexts, contextCache, newDepth, treatEofAsEpsilon);
			}
		}
	}
}
