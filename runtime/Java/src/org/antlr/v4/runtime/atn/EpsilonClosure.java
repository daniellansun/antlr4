/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.Set;

/**
 * Epsilon-closure engine for adaptive LL(*) prediction.
 *
 * <p>
 * Owns the busy set, the predicate vs non-predicate control split, and
 * double-buffered intermediate config sets for breadth-first rule-transition
 * merging. Recursive edge walking stays here so {@link ParserATNSimulator}
 * remains orchestration-focused rather than absorbing more allocation
 * policy.</p>
 *
 * <p>
 * PERF: Intermediate config-set buffers and the closure busy set are retained
 * on this instance. A single {@link ParserATNSimulator} (and therefore one
 * {@code EpsilonClosure}) is used by one parser thread at a time during
 * {@code adaptivePredict}, so reuse is safe. Ownership of cleanup is a single
 * {@code try}/{@code finally} around each {@link #close}: drop config-graph
 * references after the call while retaining set capacity for the next
 * prediction.</p>
 *
 * <p>
 * PERF: The busy set is exposed only as {@link Set}{@code <ATNConfig>} (see
 * {@link OpenAddressedHashSet}) so the protected
 * {@link ParserATNSimulator#closure(ATNConfig, ATNConfigSet, ATNConfigSet, Set, boolean, boolean, PredictionContextCache, int, boolean)}
 * SPI stays free of HPPC types. Storage remains an open-addressed HPPC table
 * behind the adapter: right-recursion and EOF* guards avoid
 * {@link java.util.HashMap.Node} allocation on every insert, and the flat key
 * array is reused across predictions. Config iteration on the BFS layers is
 * index-based ({@link ATNConfigSet#get(int)}) to avoid
 * {@link java.util.Iterator} objects on the hot path.</p>
 *
 * <p>
 * Package-private: only {@link ParserATNSimulator} constructs and uses this
 * type. Transition target computation is delegated back to the simulator
 * ({@link ParserATNSimulator#getEpsilonTarget}) so semantic-predicate and
 * rule-transition policy remain in one place.</p>
 */
final class EpsilonClosure {

	@NotNull
	private final ParserATNSimulator simulator;

	/**
	 * Retained busy set for right-recursion / EOF* guards. Cleared in
	 * {@link #close}'s {@code finally}; capacity is preserved across predictions.
	 * Same-package tests may observe identity via {@link #retainedBusy()}.
	 *
	 * <p>Typed as {@link Set} so callers and the protected simulator SPI never
	 * see HPPC. The concrete instance is an {@link OpenAddressedHashSet} that
	 * keys by {@link ATNConfig#hashCode()} (cached) and
	 * {@link ATNConfig#equals}.</p>
	 */
	private final Set<ATNConfig> closureBusy =
		new OpenAddressedHashSet<>(ATNConfigSet.SCRATCH_CAPACITY_FLOOR);

	/**
	 * Double-buffer scratch sets for BFS rule-transition layering when
	 * {@code collectPredicates} is {@code false}. Allocated lazily on first
	 * non-predicate closure and reused thereafter.
	 */
	private ATNConfigSet bufferA;
	private ATNConfigSet bufferB;

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
	 * rule body is expanded. Intermediate sets use two retained buffers that
	 * swap roles each layer — {@code sourceConfigs} is never treated as a
	 * reusable buffer. When {@code collectPredicates} is {@code true}, rule
	 * transitions are followed immediately and no intermediate set is
	 * written.</p>
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

		// Single ownership of cleanup: drop config-graph refs after work while
		// keeping busy-set / ATNConfigSet capacity for the next close.
		try {
			if (collectPredicates) {
				// Predicate path: follow rule transitions immediately (no BFS layer).
				// Dispatch through simulator.closure so subclasses that override
				// the recursive entry point still participate in every step.
				// Index walk avoids Iterator allocation on the source set.
				for (int i = 0; i < sourceSize; i++) {
					simulator.closure(sourceConfigs.get(i), configs, null, closureBusy, true, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
				}
				return;
			}

			// Non-predicate path: BFS layering for rule-transition merging.
			ensureIntermediateBuffers(ATNConfigSet.scratchCapacity(sourceSize));

			ATNConfigSet current = sourceConfigs;
			ATNConfigSet next = bufferA;
			boolean nextIsBufferA = true;
			next.clear();

			while (true) {
				for (int i = 0, n = current.size(); i < n; i++) {
					simulator.closure(current.get(i), configs, next, closureBusy, false, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
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
		finally {
			closureBusy.clear();
			if (bufferA != null) {
				bufferA.clear();
				bufferB.clear();
			}
		}
	}

	/**
	 * Ensure both intermediate buffers exist. Existing buffers are retained
	 * (capacity preserved via {@link ATNConfigSet#clear}); the initial
	 * expected-size hint only applies on first allocation.
	 */
	private void ensureIntermediateBuffers(int intermediateHint) {
		if (bufferA == null) {
			bufferA = new ATNConfigSet(intermediateHint);
			bufferB = new ATNConfigSet(intermediateHint);
		}
	}

	/**
	 * Whether intermediate BFS buffers have been allocated.
	 * Same-package tests may observe retained-scratch identity.
	 */
	boolean hasIntermediateBuffers() {
		return bufferA != null;
	}

	/**
	 * Retained busy set (same instance across {@link #close} calls).
	 * Typed as {@link Set}; the concrete implementation is
	 * {@link OpenAddressedHashSet}.
	 */
	@NotNull
	Set<ATNConfig> retainedBusy() {
		return closureBusy;
	}

	/**
	 * Intermediate buffer A after first non-predicate closure, else {@code null}.
	 */
	@Nullable
	ATNConfigSet retainedBufferA() {
		return bufferA;
	}

	/**
	 * Intermediate buffer B after first non-predicate closure, else {@code null}.
	 */
	@Nullable
	ATNConfigSet retainedBufferB() {
		return bufferB;
	}

	/**
	 * Recursive helper: walk epsilon edges from {@code config}, adding leaf
	 * configurations to {@code configs} and optionally deferring rule
	 * transitions to {@code intermediate} for breadth-first processing.
	 *
	 * @param closureBusy busy set for right-recursion / EOF* guards; production
	 * callers pass the retained {@link OpenAddressedHashSet}, but any
	 * {@link Set} is accepted so the protected simulator override SPI stays
	 * interface-oriented
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
		if (configState.getStateType() == ATNState.RULE_STOP) {
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
					ATNState returnState = atn.getCachedState(predictionContext.getReturnState(i));
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

		final Transition[] frozen = p.frozenOptimizedTransitions();
		final int n = frozen != null ? frozen.length : p.getNumberOfOptimizedTransitions();
		final boolean inContext = depth == 0;
		final boolean configAtRuleStop = p.getStateType() == ATNState.RULE_STOP;
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

			Transition t = frozen != null ? frozen[i] : p.getOptimizedTransition(i);
			// PERF: When EOF is not treated as epsilon, non-epsilon edges can
			// never produce a closure target — skip getEpsilonTarget
			// (common on mixed epsilon/consume states). Use the frozen
			// {@link Transition#epsilon} flag on production ATNs.
			if (!treatEofAsEpsilon && !t.isEpsilon()) {
				continue;
			}

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
