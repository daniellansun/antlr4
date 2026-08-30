/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.misc.Tuple;
import org.antlr.v4.runtime.misc.Tuple2;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private reach / target-state engine for adaptive LL(*) prediction.
 *
 * <p>
 * Owns the retained reach and intermediate {@link ATNConfigSet}s plus the
 * {@link ReachConfigSource} view used by a single
 * {@link ParserATNSimulator#computeTargetState} invocation. Extracted so
 * {@link ParserATNSimulator} stays focused on adaptivePredict / DFA
 * orchestration while allocation and release policy for edge computation live
 * in one place.</p>
 *
 * <p>
 * <strong>Ownership:</strong> one instance per {@link ParserATNSimulator}. A
 * simulator is used by one parser thread at a time during prediction, so
 * retained scratch is not shared across threads. Each
 * {@link #computeTargetState} binds scratch inside {@code try}/{@code finally}
 * and always {@link RetainedConfigSet#release() release}s after the edge is
 * cloned into the DFA.</p>
 *
 * <p>Not part of any {@code public} or {@code protected} API.</p>
 */
final class ReachComputation {

	@NotNull
	private final ParserATNSimulator simulator;

	/**
	 * Retained reach / intermediate sets.
	 * {@link RetainedConfigSet#obtain(int)} always returns empty;
	 * {@link RetainedConfigSet#release()} runs in {@code finally} after each edge.
	 */
	private final RetainedConfigSet retainedReach = new RetainedConfigSet(false);
	private final RetainedConfigSet retainedIntermediate = new RetainedConfigSet(false);

	/**
	 * Retained config view. {@link ReachConfigSource#reset} binds the source
	 * set; {@link ReachConfigSource#release()} runs in {@code finally} so the
	 * full-context rewrite list keeps capacity across predictions.
	 */
	private final ReachConfigSource retainedReachConfigs = new ReachConfigSource();

	ReachComputation(@NotNull ParserATNSimulator simulator) {
		this.simulator = simulator;
	}

	/**
	 * Retained config source after construction. Same-package tests may
	 * observe rewrite-list identity across full-context edges.
	 */
	@NotNull
	ReachConfigSource retainedReachConfigs() {
		return retainedReachConfigs;
	}

	/**
	 * Compute a target state for an edge in the DFA, and attempt to add the
	 * computed state and corresponding edge to the DFA.
	 *
	 * @return The computed target DFA state for the given input symbol
	 * {@code t}. If {@code t} does not lead to a valid DFA state, this method
	 * returns {@link ATNSimulator#ERROR}.
	 */
	@NotNull
	Tuple2<DFAState, ParserRuleContext> computeTargetState(
			@NotNull DFA dfa,
			@NotNull DFAState s,
			ParserRuleContext remainingGlobalContext,
			int t,
			boolean useContext,
			PredictionContextCache contextCache)
	{
		final int sourceConfigCount = s.configs.size();
		// Monomorphic config access: retained zero-copy view over s.configs;
		// materializes a list only when full-context prediction appends return
		// states (rewrite list capacity is preserved across edges).
		final ReachConfigSource configs = retainedReachConfigs;
		IntegerList contextElements = null;

		// Bind retained scratch inside try so finally always release()s even if
		// obtain/reset fails mid-setup (e.g. OOM on first buffer allocation).
		ATNConfigSet reach = null;
		ATNConfigSet intermediate = null;
		try {
			configs.reset(s.configs);
			// obtain() always empty; release() in finally after DFA clones.
			// Locals may rebind (EOF filter) like the pre-retention algorithm.
			reach = retainedReach.obtain(sourceConfigCount);
			intermediate = retainedIntermediate.obtain(sourceConfigCount);
			boolean stepIntoGlobal;
			do {
				boolean hasMoreContext = !useContext || remainingGlobalContext != null;
				if (!hasMoreContext) {
					reach.setOutermostConfigSet(true);
				}

				intermediate.clear();

				/* Configurations already in a rule stop state indicate reaching the end
				 * of the decision rule (local context) or end of the start rule (full
				 * context). Once reached, these configurations are never updated by a
				 * closure operation, so they are handled separately for the performance
				 * advantage of having a smaller intermediate set when calling closure.
				 *
				 * For full-context reach operations, separate handling is required to
				 * ensure that the alternative matching the longest overall sequence is
				 * chosen when multiple such configurations can match the input.
				 */
				List<ATNConfig> skippedStopStates = null;

				final int configCount = configs.size();
				for (int ci = 0; ci < configCount; ci++) {
					ATNConfig c = configs.get(ci);
					/*if ( debug ) System.out.println("testing "+getTokenName(t)+" at "+c.toString());*/

					ATNState cState = c.getState();
					if (cState.getStateType() == ATNState.RULE_STOP) {
						assert c.getContext().isEmpty();
						if (useContext && !c.getReachesIntoOuterContext() || t == IntStream.EOF) {
							if (skippedStopStates == null) {
								skippedStopStates = new ArrayList<>(2);
							}

							skippedStopStates.add(c);
						}

						continue;
					}

					Transition[] frozen = cState.frozenOptimizedTransitions();
					int n = frozen != null ? frozen.length : cState.getNumberOfOptimizedTransitions();
					for (int ti = 0; ti < n; ti++) {               // for each optimized transition
						Transition trans = frozen != null ? frozen[ti] : cState.getOptimizedTransition(ti);
						ATNState target = simulator.getReachableTarget(c, trans, t);
						if (target != null) {
							intermediate.add(c.transform(target, false), contextCache);
						}
					}
				}


				/* This block optimizes the reach operation for intermediate sets which
				 * trivially indicate a termination state for the overall
				 * adaptivePredict operation.
				 *
				 * The conditions assume that intermediate
				 * contains all configurations relevant to the reach set, but this
				 * condition is not true when one or more configurations have been
				 * withheld in skippedStopStates, or when the current symbol is EOF.
				 */
				if (simulator.optimize_unique_closure && skippedStopStates == null && t != Token.EOF && intermediate.getUniqueAlt() != ATN.INVALID_ALT_NUMBER) {
					// intermediate is the edge result; release() after clone.
					intermediate.setOutermostConfigSet(reach.isOutermostConfigSet());
					return finishTargetEdge(dfa, s, t, contextElements, intermediate, remainingGlobalContext, contextCache);
				}

				/* If the reach set could not be trivially determined, perform a closure
				 * operation on the intermediate set to compute its initial value.
				 */
				final boolean collectPredicates = false;
				boolean treatEofAsEpsilon = t == Token.EOF;
				simulator.closure(intermediate, reach, collectPredicates, hasMoreContext, contextCache, treatEofAsEpsilon);
				stepIntoGlobal = reach.getDipsIntoOuterContext();

				if (t == IntStream.EOF) {
					/* After consuming EOF no additional input is possible, so we are
					 * only interested in configurations which reached the end of the
					 * decision rule (local context) or end of the start rule (full
					 * context). Update reach to contain only these configurations. This
					 * handles both explicit EOF transitions in the grammar and implicit
					 * EOF transitions following the end of the decision or start rule.
					 *
					 * This is handled before the configurations in skippedStopStates,
					 * because any configurations potentially added from that list are
					 * already guaranteed to meet this condition whether or not it's
					 * required.
					 */
					// May rebind to a fresh filtered set (not scratch-owned).
					reach = simulator.removeAllConfigsNotInRuleStopState(reach, contextCache);
				}

				/* If skippedStopStates is not null, then it contains at least one
				 * configuration. For full-context reach operations, these
				 * configurations reached the end of the start rule, in which case we
				 * only add them back to reach if no configuration during the current
				 * closure operation reached such a state. This ensures adaptivePredict
				 * chooses an alternative matching the longest overall sequence when
				 * multiple alternatives are viable.
				 */
				if (skippedStopStates != null && (!useContext || !PredictionMode.hasConfigInRuleStopState(reach))) {
					assert !skippedStopStates.isEmpty();
					for (int i = 0, n = skippedStopStates.size(); i < n; i++) {
						reach.add(skippedStopStates.get(i), contextCache);
					}
				}

				if (useContext && stepIntoGlobal) {
					reach.clear();

					remainingGlobalContext = simulator.skipTailCalls(remainingGlobalContext);
					int nextContextElement = simulator.getReturnState(remainingGlobalContext);
					if (contextElements == null) {
						contextElements = new IntegerList();
					}

					if (remainingGlobalContext.isEmpty()) {
						remainingGlobalContext = null;
					} else {
						remainingGlobalContext = remainingGlobalContext.getParent();
					}

					contextElements.add(nextContextElement);
					if (nextContextElement != PredictionContext.EMPTY_FULL_STATE_KEY) {
						configs.appendContext(nextContextElement, contextCache);
					}
				}
			} while (useContext && stepIntoGlobal);

			return finishTargetEdge(dfa, s, t, contextElements, reach, remainingGlobalContext, contextCache);
		}
		finally {
			retainedReachConfigs.release();
			retainedReach.release();
			retainedIntermediate.release();
		}
	}

	/**
	 * Install a DFA edge for the computed config set, or {@link ATNSimulator#ERROR} if empty.
	 */
	@NotNull
	private Tuple2<DFAState, ParserRuleContext> finishTargetEdge(
			@NotNull DFA dfa,
			@NotNull DFAState s,
			int t,
			@Nullable IntegerList contextElements,
			@NotNull ATNConfigSet edgeConfigs,
			ParserRuleContext remainingGlobalContext,
			PredictionContextCache contextCache)
	{
		if (edgeConfigs.isEmpty()) {
			simulator.addDFAEdge(s, t, ATNSimulator.ERROR);
			return Tuple.create(ATNSimulator.ERROR, remainingGlobalContext);
		}

		DFAState result = simulator.addDFAEdge(dfa, s, t, contextElements, edgeConfigs, contextCache);
		return Tuple.create(result, remainingGlobalContext);
	}
}
