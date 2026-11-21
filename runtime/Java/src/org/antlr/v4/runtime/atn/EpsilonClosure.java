/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.Set;

/**
 * Unified epsilon-closure engine for adaptive LL(*) prediction.
 *
 * <p>
 * Owns the retained busy set and double-buffered intermediate config sets for
 * breadth-first rule-transition merging. Both base {@link ParserATNSimulator}
 * (monomorphic direct transition resolution with lazy-state pure-epsilon
 * tail-looping) and overriding simulator subclasses (virtual
 * {@link ParserATNSimulator#getEpsilonTarget} / {@link ParserATNSimulator#closure}
 * dispatch) share a single closure walker ({@link #walk}) and shared rule-stop
 * / precedence-suppression core so the two paths cannot drift.</p>
 *
 * <p>
 * <strong>Ownership:</strong> One instance per {@link ParserATNSimulator},
 * used by a single parser thread at a time during {@code adaptivePredict}.
 * Cleanup runs in a single {@code try}/{@code finally} inside {@link #close},
 * dropping all config-graph references while preserving scratch capacity.</p>
 *
 * <p>
 * Package-private: not part of any {@code public} or {@code protected} API.
 * {@link #closureBusy} is typed as {@link Set}{@code <ATNConfig>} so the
 * protected simulator SPI stays free of HPPC types.</p>
 */
final class EpsilonClosure {

	@NotNull
	private final ParserATNSimulator simulator;

	/**
	 * Retained busy set for right-recursion / EOF* guards. Typed as {@link Set}
	 * so callers and the protected simulator SPI never observe HPPC.
	 */
	private final Set<ATNConfig> closureBusy =
		new OpenAddressedHashSet<>(ATNConfigSet.SCRATCH_CAPACITY_FLOOR);

	/**
	 * Double-buffer scratch sets for BFS rule-transition layering when
	 * {@code collectPredicates} is {@code false}.
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

		final boolean direct = simulator.isBaseSimulator;
		try {
			if (collectPredicates) {
				if (direct) {
					for (int i = 0; i < sourceSize; i++) {
						ATNConfig cfg = sourceConfigs.get(i);
						walk(cfg.getState(), cfg, configs, null, closureBusy, true, hasMoreContext, contextCache, 0, treatEofAsEpsilon, true);
					}
				}
				else {
					for (int i = 0; i < sourceSize; i++) {
						simulator.closure(sourceConfigs.get(i), configs, null, closureBusy, true, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
					}
				}
				return;
			}

			ensureIntermediateBuffers(ATNConfigSet.scratchCapacity(sourceSize));

			ATNConfigSet current = sourceConfigs;
			ATNConfigSet next = bufferA;
			boolean nextIsBufferA = true;
			next.clear();

			while (true) {
				if (direct) {
					for (int i = 0, n = current.size(); i < n; i++) {
						ATNConfig cfg = current.get(i);
						walk(cfg.getState(), cfg, configs, next, closureBusy, false, hasMoreContext, contextCache, 0, treatEofAsEpsilon, true);
					}
				}
				else {
					for (int i = 0, n = current.size(); i < n; i++) {
						simulator.closure(current.get(i), configs, next, closureBusy, false, hasMoreContext, contextCache, 0, treatEofAsEpsilon);
					}
				}

				if (next.isEmpty()) {
					break;
				}

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

	private void ensureIntermediateBuffers(int intermediateHint) {
		if (bufferA == null) {
			ATNConfigSet a = new ATNConfigSet(intermediateHint);
			ATNConfigSet b = new ATNConfigSet(intermediateHint);
			bufferA = a;
			bufferB = b;
		}
	}

	boolean hasIntermediateBuffers() {
		return bufferA != null;
	}

	@NotNull
	Set<ATNConfig> retainedBusy() {
		return closureBusy;
	}

	@Nullable
	ATNConfigSet retainedBufferA() {
		return bufferA;
	}

	@Nullable
	ATNConfigSet retainedBufferB() {
		return bufferB;
	}

	/**
	 * Entry point called from {@link ParserATNSimulator#closure}.
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
		if (contextCache == null) {
			contextCache = PredictionContextCache.UNCACHED;
		}
		walk(config.getState(), config, configs, intermediate, closureBusy,
			collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon, simulator.isBaseSimulator);
	}

	/**
	 * Single unified closure walker shared by both base {@link ParserATNSimulator}
	 * ({@code direct == true}) and overriding subclasses ({@code direct == false}).
	 *
	 * <p>On the direct path, pure-epsilon chains carry {@code (p, template)}
	 * lazily without allocating intermediate {@link ATNConfig} instances,
	 * tail-looping on single-edge and last-edge epsilon transitions and merging
	 * into {@code configs} / {@code intermediate} via
	 * {@link ATNConfigSet#addTransformed} (zero-allocation on merge hit, never
	 * aliasing {@code template} on miss).</p>
	 */
	private void walk(@NotNull ATNState p,
					  @NotNull ATNConfig template,
					  @NotNull ATNConfigSet configs,
					  @Nullable ATNConfigSet intermediate,
					  @NotNull Set<ATNConfig> closureBusy,
					  boolean collectPredicates,
					  boolean hasMoreContexts,
					  @NotNull PredictionContextCache contextCache,
					  int depth,
					  boolean treatEofAsEpsilon,
					  boolean direct)
	{
		outer:
		while (true) {
			PredictionContext predictionContext = template.getContext();
			int stateType = p.stateType;
			if (stateType == ATNState.RULE_STOP) {
				template = closeRuleStop(p, template, predictionContext, configs, intermediate,
					closureBusy, collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon, direct);
				if (template == null) {
					return;
				}
				p = template.getState();
				stateType = p.stateType;
				predictionContext = template.getContext();
			}

			if (!p.onlyHasEpsilonTransitions()) {
				addConfig(configs, p, template, contextCache, direct);
			}

			final Transition[] frozen = p.frozenOptimizedTransitions();
			final int n = frozen != null ? frozen.length : p.getNumberOfOptimizedTransitions();
			if (n == 0) {
				return;
			}

			final boolean configAtRuleStop = stateType == ATNState.RULE_STOP;
			final boolean maybeSuppressPrecedenceEdge =
				stateType == ATNState.STAR_LOOP_ENTRY
				&& ((StarLoopEntryState) p).precedenceRuleDecision
				&& !predictionContext.hasEmpty();

			if (!direct) {
				walkVirtual(p, template, predictionContext, frozen, n, configAtRuleStop, maybeSuppressPrecedenceEdge,
					configs, intermediate, closureBusy, collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon);
				return;
			}

			// Fast direct loop: inline pure-epsilon tail-looping and BFS rule deferral;
			// outline cold rule-stop / predicate / action / EOF-as-epsilon edges.
			for (int i = 0; i < n; i++) {
				if (i == 0 && maybeSuppressPrecedenceEdge
					&& shouldSuppressPrecedenceEdge((StarLoopEntryState) p, predictionContext)) {
					continue;
				}

				Transition t = frozen != null ? frozen[i] : p.getOptimizedTransition(i);
				if (!treatEofAsEpsilon && !t.epsilon) {
					continue;
				}

				final int transitionType = t.serializationType;
				if (transitionType == Transition.EPSILON) {
					if (!configAtRuleStop) {
						if (i == n - 1) {
							p = t.target;
							continue outer;
						}
						walk(t.target, template, configs, intermediate, closureBusy,
							collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon, true);
						continue;
					}
					ATNConfig c = template.transformParser(t.target);
					DFA dfa = simulator.getDfaForClosure();
					if (dfa != null && dfa.isPrecedenceDfa()) {
						int outermostPrecedenceReturn = ((EpsilonTransition) t).outermostPrecedenceReturn();
						if (outermostPrecedenceReturn == dfa.atnStartState.ruleIndex) {
							c.setPrecedenceFilterSuppressed(true);
						}
					}
					c.setOuterContextDepth(c.getOuterContextDepth() + 1);
					if (!closureBusy.add(c)) {
						continue;
					}
					assert depth > Integer.MIN_VALUE;
					walk(t.target, c, configs, intermediate, closureBusy,
						collectPredicates, hasMoreContexts, contextCache, depth - 1, treatEofAsEpsilon, true);
				}
				else if (transitionType == Transition.RULE) {
					RuleTransition ruleTransition = (RuleTransition) t;
					PredictionContext configContext = template.getContext();
					boolean optimizeTailCalls = simulator.optimize_tail_calls;
					boolean tailCallPreservesSll = simulator.tail_call_preserves_sll;
					boolean tailCall = optimizeTailCalls
						&& ruleTransition.optimizedTailCall
						&& (!tailCallPreservesSll || !PredictionContext.isEmptyLocal(configContext));
					PredictionContext newContext = tailCall
						? configContext
						: contextCache.getChild(configContext, ruleTransition.followState.stateNumber);
					if (intermediate != null && !collectPredicates) {
						intermediate.addTransformed(ruleTransition.target, template, newContext, contextCache);
						continue;
					}
					ATNConfig c = template.transformParser(ruleTransition.target, newContext);
					int newDepth = depth;
					if (configAtRuleStop) {
						c.setOuterContextDepth(c.getOuterContextDepth() + 1);
						if (!closureBusy.add(c)) {
							continue;
						}
						assert newDepth > Integer.MIN_VALUE;
						newDepth--;
					}
					else if (tailCall) {
						assert c.getContext() == predictionContext;
						if (newDepth == 0) {
							newDepth--;
							if (!tailCallPreservesSll && PredictionContext.isEmptyLocal(predictionContext)) {
								c.setOuterContextDepth(c.getOuterContextDepth() + 1);
							}
						}
					}
					else if (newDepth >= 0) {
						newDepth++;
					}
					walk(ruleTransition.target, c, configs, intermediate, closureBusy,
						collectPredicates, hasMoreContexts, contextCache, newDepth, treatEofAsEpsilon, true);
				}
				else {
					closeRareTransitionDirect(template, t, transitionType, configAtRuleStop,
						configs, intermediate, closureBusy, collectPredicates, hasMoreContexts, contextCache, depth, treatEofAsEpsilon);
				}
			}
			return;
		}
	}

	/**
	 * Shared rule-stop handler used by both direct and subclass paths.
	 * Always adds via {@link ATNConfigSet#addTransformed} on the direct path so
	 * {@code template} is never aliased into {@code configs}. Kept compact
	 * ({@code <= 325 B}) so HotSpot C2 inlines it into {@link #walk}.
	 */
	@Nullable
	private ATNConfig closeRuleStop(@NotNull ATNState configState,
									@NotNull ATNConfig template,
									@NotNull PredictionContext predictionContext,
									@NotNull ATNConfigSet configs,
									@Nullable ATNConfigSet intermediate,
									@NotNull Set<ATNConfig> closureBusy,
									boolean collectPredicates,
									boolean hasMoreContexts,
									@NotNull PredictionContextCache contextCache,
									int depth,
									boolean treatEofAsEpsilon,
									boolean direct)
	{
		if (!predictionContext.isEmpty()) {
			final ATN atn = simulator.atn;
			boolean hasEmpty = predictionContext.hasEmpty();
			int nonEmptySize = predictionContext.size() - (hasEmpty ? 1 : 0);
			final int alt = template.getAlt();
			final SemanticContext semanticContext = template.getSemanticContext();
			final int outerContextDepth = template.getOuterContextDepth();
			final boolean precedenceFilterSuppressed = template.isPrecedenceFilterSuppressed();
			for (int i = 0; i < nonEmptySize; i++) {
				PredictionContext newContext = predictionContext.getParent(i);
				ATNState returnState = atn.getCachedState(predictionContext.getReturnState(i));
				ATNConfig c = ATNConfig.create(returnState, alt, newContext, semanticContext);
				c.setOuterContextDepth(outerContextDepth);
				c.setPrecedenceFilterSuppressed(precedenceFilterSuppressed);
				assert depth > Integer.MIN_VALUE;
				if (direct) {
					walk(returnState, c, configs, intermediate, closureBusy, collectPredicates, hasMoreContexts, contextCache, depth - 1, treatEofAsEpsilon, true);
				}
				else {
					simulator.closure(c, configs, intermediate, closureBusy, collectPredicates, hasMoreContexts, contextCache, depth - 1, treatEofAsEpsilon);
				}
			}

			if (!hasEmpty || !hasMoreContexts) {
				return null;
			}

			return toEmptyLocal(configState, template, direct);
		}
		else if (!hasMoreContexts) {
			addConfig(configs, configState, template, contextCache, direct);
			return null;
		}
		else {
			if (predictionContext == PredictionContext.EMPTY_FULL) {
				return toEmptyLocal(configState, template, direct);
			}
			else if (!template.getReachesIntoOuterContext() && PredictionContext.isEmptyLocal(predictionContext)) {
				addConfig(configs, configState, template, contextCache, direct);
			}
			return template.getState() == configState ? template : template.transformParser(configState);
		}
	}

	private static void addConfig(@NotNull ATNConfigSet configs,
								  @NotNull ATNState state,
								  @NotNull ATNConfig template,
								  @NotNull PredictionContextCache contextCache,
								  boolean direct)
	{
		if (direct) {
			configs.addTransformed(state, template, contextCache);
		}
		else {
			configs.add(template.getState() == state ? template : template.transformParser(state), contextCache);
		}
	}

	private static ATNConfig toEmptyLocal(@NotNull ATNState state, @NotNull ATNConfig template, boolean direct) {
		return direct
			? template.transformParser(state, PredictionContext.EMPTY_LOCAL)
			: template.transform(state, PredictionContext.EMPTY_LOCAL, false);
	}

	private static boolean shouldSuppressPrecedenceEdge(@NotNull StarLoopEntryState precedenceDecision,
														@NotNull PredictionContext predictionContext)
	{
		for (int j = 0, predictionContextSize = predictionContext.size(); j < predictionContextSize; j++) {
			if (!precedenceDecision.precedenceLoopbackStates.get(predictionContext.getReturnState(j))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Outlined ({@code > 325 B}) cold handler for non-epsilon/non-rule direct
	 * transitions (predicates, actions, precedence predicates, and EOF-as-epsilon).
	 */
	private void closeRareTransitionDirect(@NotNull ATNConfig template,
										   @NotNull Transition t,
										   int transitionType,
										   boolean configAtRuleStop,
										   @NotNull ATNConfigSet configs,
										   @Nullable ATNConfigSet intermediate,
										   @NotNull Set<ATNConfig> closureBusy,
										   boolean collectPredicates,
										   boolean hasMoreContexts,
										   @NotNull PredictionContextCache contextCache,
										   int depth,
										   boolean treatEofAsEpsilon)
	{
		final boolean inContext = depth == 0;
		boolean continueCollecting = collectPredicates;
		ATNConfig c;
		switch (transitionType) {
		case Transition.PREDICATE: {
			PredicateTransition pt = (PredicateTransition) t;
			if (collectPredicates && (!pt.isCtxDependent || inContext)) {
				SemanticContext newSemCtx = SemanticContext.and(template.getSemanticContext(), pt.getPredicate());
				c = template.transformParser(pt.target, newSemCtx);
			}
			else {
				c = template.transformParser(pt.target);
			}
			break;
		}

		case Transition.ACTION:
			continueCollecting = false;
			c = template.transformParser(t.target);
			break;

		case Transition.PRECEDENCE: {
			PrecedencePredicateTransition pt = (PrecedencePredicateTransition) t;
			if (collectPredicates && inContext) {
				SemanticContext newSemCtx = SemanticContext.and(template.getSemanticContext(), pt.getPredicate());
				c = template.transformParser(pt.target, newSemCtx);
			}
			else {
				c = template.transformParser(pt.target);
			}
			break;
		}

		case Transition.ATOM:
		case Transition.RANGE:
		case Transition.SET:
			if (treatEofAsEpsilon && t.matches(Token.EOF, 0, 1)) {
				c = template.transformParser(t.target);
				break;
			}
			return;

		default:
			return;
		}

		int newDepth = depth;
		if (configAtRuleStop) {
			c.setOuterContextDepth(c.getOuterContextDepth() + 1);
			if (!closureBusy.add(c)) {
				return;
			}
			assert newDepth > Integer.MIN_VALUE;
			newDepth--;
		}
		else if (!t.epsilon && !closureBusy.add(c)) {
			return;
		}

		walk(t.target, c, configs, intermediate, closureBusy, continueCollecting, hasMoreContexts, contextCache, newDepth, treatEofAsEpsilon, true);
	}

	/**
	 * Outlined virtual-override edge walk for {@link ParserATNSimulator}
	 * subclasses. Materializes {@code config} at state {@code p} before calling
	 * {@link ParserATNSimulator#getEpsilonTarget} and guards
	 * {@code outermostPrecedenceReturn} on {@link Transition#EPSILON}.
	 */
	private void walkVirtual(@NotNull ATNState p,
							 @NotNull ATNConfig template,
							 @NotNull PredictionContext predictionContext,
							 @Nullable Transition[] frozen,
							 int n,
							 boolean configAtRuleStop,
							 boolean maybeSuppressPrecedenceEdge,
							 @NotNull ATNConfigSet configs,
							 @Nullable ATNConfigSet intermediate,
							 @NotNull Set<ATNConfig> closureBusy,
							 boolean collectPredicates,
							 boolean hasMoreContexts,
							 @NotNull PredictionContextCache contextCache,
							 int depth,
							 boolean treatEofAsEpsilon)
	{
		final ATNConfig config = template.getState() == p ? template : template.transformParser(p);
		final DFA dfa = simulator.getDfaForClosure();
		final boolean optimizeTailCalls = simulator.optimize_tail_calls;
		final boolean tailCallPreservesSll = simulator.tail_call_preserves_sll;
		final boolean inContext = depth == 0;

		for (int i = 0; i < n; i++) {
			if (i == 0 && maybeSuppressPrecedenceEdge
				&& shouldSuppressPrecedenceEdge((StarLoopEntryState) p, predictionContext)) {
				continue;
			}

			Transition t = frozen != null ? frozen[i] : p.getOptimizedTransition(i);
			if (!treatEofAsEpsilon && !t.epsilon) {
				continue;
			}

			final int transitionType = t.serializationType;
			boolean continueCollecting = transitionType != Transition.ACTION && collectPredicates;
			ATNConfig c = simulator.getEpsilonTarget(config, t, continueCollecting, inContext, contextCache, treatEofAsEpsilon);
			if (c != null) {
				if (transitionType == Transition.RULE && intermediate != null && !collectPredicates) {
					intermediate.add(c, contextCache);
					continue;
				}

				int newDepth = depth;
				if (configAtRuleStop) {
					if (transitionType == Transition.EPSILON && dfa != null && dfa.isPrecedenceDfa()) {
						int outermostPrecedenceReturn = ((EpsilonTransition) t).outermostPrecedenceReturn();
						if (outermostPrecedenceReturn == dfa.atnStartState.ruleIndex) {
							c.setPrecedenceFilterSuppressed(true);
						}
					}
					c.setOuterContextDepth(c.getOuterContextDepth() + 1);
					if (!closureBusy.add(c)) {
						continue;
					}
					assert newDepth > Integer.MIN_VALUE;
					newDepth--;
				}
				else if (transitionType == Transition.RULE) {
					RuleTransition ruleTransition = (RuleTransition) t;
					if (optimizeTailCalls && ruleTransition.optimizedTailCall
						&& (!tailCallPreservesSll || !PredictionContext.isEmptyLocal(predictionContext))) {
						assert c.getContext() == predictionContext;
						if (newDepth == 0) {
							newDepth--;
							if (!tailCallPreservesSll && PredictionContext.isEmptyLocal(predictionContext)) {
								c.setOuterContextDepth(c.getOuterContextDepth() + 1);
							}
						}
					}
					else if (newDepth >= 0) {
						newDepth++;
					}
				}
				else if (!t.epsilon && !closureBusy.add(c)) {
					continue;
				}

				simulator.closure(c, configs, intermediate, closureBusy, continueCollecting, hasMoreContexts, contextCache, newDepth, treatEofAsEpsilon);
			}
		}
	}
}
