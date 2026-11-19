/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.analysis;

import org.antlr.v4.misc.Utils;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AbstractPredicateTransition;
import org.antlr.v4.runtime.atn.BasicBlockStartState;
import org.antlr.v4.runtime.atn.BlockStartState;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.atn.LL1Analyzer;
import org.antlr.v4.runtime.atn.PlusBlockStartState;
import org.antlr.v4.runtime.atn.PlusLoopbackState;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.atn.StarBlockStartState;
import org.antlr.v4.runtime.atn.StarLoopEntryState;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.atn.WildcardTransition;
import org.antlr.v4.runtime.atn.NotSetTransition;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarAST;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

public class AnalysisPipeline {
	public Grammar g;

	public AnalysisPipeline(Grammar g) {
		this.g = g;
	}

	public void process() {
		// LEFT-RECURSION CHECK
		LeftRecursionDetector lr = new LeftRecursionDetector(g, g.atn);
		lr.check();
		if ( !lr.listOfRecursiveCycles.isEmpty() ) return; // bail out

		if (g.isLexer()) {
			processLexer();
		}
		else {
			// BUILD DFA FOR EACH DECISION
			processParser();
		}
	}

	protected void processLexer() {
		// make sure all non-fragment lexer rules must match at least one symbol
		for (Rule rule : g.rules.values()) {
			if (rule.isFragment()) {
				continue;
			}

			LL1Analyzer analyzer = new LL1Analyzer(g.atn);
			IntervalSet look = analyzer.LOOK(g.atn.ruleToStartState[rule.index], PredictionContext.EMPTY_LOCAL);
			if (look.contains(Token.EPSILON)) {
				g.tool.errMgr.grammarError(ErrorType.EPSILON_TOKEN, g.fileName, ((GrammarAST)rule.ast.getChild(0)).getToken(), rule.name);
			}
		}
	}

	protected void processParser() {
		g.decisionLOOK = new ArrayList<IntervalSet[]>(g.atn.getNumberOfDecisions()+1);
		for (DecisionState s : g.atn.decisionToState) {
            g.tool.log("LL1", "\nDECISION "+s.decision+" in rule "+g.getRule(s.ruleIndex).name);
			IntervalSet[] look;
			if ( s.nonGreedy ) { // nongreedy decisions can't be LL(1)
				look = new IntervalSet[s.getNumberOfTransitions()+1];
			}
			else {
				LL1Analyzer anal = new LL1Analyzer(g.atn);
				look = anal.getDecisionLookahead(s);
				if (look != null && look.length == 2 && look[0] != null && look[1] == null
						&& !look[0].contains(Token.EPSILON)) {
					recoverSingleAltEbnfExitLookahead(anal, s, look);
				}
				g.tool.log("LL1", "look=" + Arrays.toString(look));
			}

			assert s.decision + 1 >= g.decisionLOOK.size();
			Utils.setSize(g.decisionLOOK, s.decision+1);
			g.decisionLOOK.set(s.decision, look);
			g.tool.log("LL1", "LL(1)? " + disjoint(look));
		}
	}

	/**
	 * Recovers LL(1) lookahead for greedy single-alternative EBNF decisions
	 * ({@code X*}, {@code X+}, {@code X?}) when entry lookahead {@code look[0]}
	 * is strictly predicate-free and non-nullable, and exit lookahead
	 * {@code look[1]} failed solely because a called sub-rule on the exit path
	 * contains a {@link PrecedencePredicateTransition} or context-dependent
	 * {@link PredicateTransition} (predicates dropped by ALL(*)
	 * {@link org.antlr.v4.runtime.atn.EpsilonClosure} at rule-call depth &gt; 0).
	 *
	 * <p>Context-independent semantic predicates in callees remain opaque because
	 * ALL(*) hoists them across rule calls. Over-approximating only dropped
	 * callee predicates yields a superset of all tokens that can ever begin the
	 * exit path under any predicate valuation. If that superset never reaches
	 * the enclosing rule's stop state (no {@link Token#EPSILON}), hits no direct
	 * predicate in the enclosing rule, and is disjoint from {@code look[0]},
	 * then entering on {@code LA(1) in look[0]} and exiting otherwise is
	 * provably equivalent to ALL(*).</p>
	 */
	private void recoverSingleAltEbnfExitLookahead(LL1Analyzer anal, DecisionState s, IntervalSet[] look) {
		ATNState bodyStart;
		ATNState bodyEnd;
		if (s instanceof StarLoopEntryState) {
			ATNState t0 = s.transition(0).target;
			if (!(t0 instanceof StarBlockStartState) || t0.getNumberOfTransitions() != 1) {
				return;
			}
			bodyStart = t0;
			bodyEnd = ((BlockStartState) t0).endState;
		}
		else if (s instanceof PlusLoopbackState) {
			ATNState t0 = s.transition(0).target;
			if (!(t0 instanceof PlusBlockStartState) || t0.getNumberOfTransitions() != 1) {
				return;
			}
			bodyStart = t0;
			bodyEnd = ((BlockStartState) t0).endState;
		}
		else if (s instanceof BasicBlockStartState) {
			BasicBlockStartState bss = (BasicBlockStartState) s;
			if (s.transition(1).target != bss.endState) {
				return;
			}
			bodyStart = s.transition(0).target;
			bodyEnd = bss.endState;
		}
		else {
			return;
		}

		// Ensure the loop/optional body itself cannot derive epsilon.
		IntervalSet bodyLook = anal.LOOK(bodyStart, bodyEnd, PredictionContext.EMPTY_LOCAL);
		if (bodyLook.contains(Token.EPSILON)) {
			return;
		}

		ATNState ruleStop = g.atn.ruleToStopState[s.ruleIndex];
		IntervalSet exitLook = new IntervalSet();
		lookExitThroughCalleePreds(
			g.atn,
			s.transition(1).target,
			ruleStop,
			PredictionContext.EMPTY_LOCAL,
			exitLook,
			new HashSet<ATNConfig>(),
			new BitSet());
		if (!exitLook.isNil()
				&& !exitLook.contains(LL1Analyzer.HIT_PRED)
				&& !exitLook.contains(Token.EPSILON)
				&& look[0].and(exitLook).isNil()) {
			look[1] = exitLook;
		}
	}

	private static void lookExitThroughCalleePreds(ATN atn,
												   ATNState s,
												   ATNState stopState,
												   PredictionContext ctx,
												   IntervalSet look,
												   Set<ATNConfig> lookBusy,
												   BitSet calledRuleStack) {
		ATNConfig c = ATNConfig.create(s, 0, ctx);
		if (!lookBusy.add(c)) {
			return;
		}

		if (s == stopState && PredictionContext.isEmptyLocal(ctx)) {
			look.add(Token.EPSILON);
			return;
		}

		if (s instanceof RuleStopState) {
			boolean removed = calledRuleStack.get(s.ruleIndex);
			try {
				calledRuleStack.clear(s.ruleIndex);
				for (int i = 0; i < ctx.size(); i++) {
					if (ctx.getReturnState(i) == PredictionContext.EMPTY_FULL_STATE_KEY) {
						continue;
					}
					ATNState returnState = atn.states.get(ctx.getReturnState(i));
					lookExitThroughCalleePreds(atn, returnState, stopState, ctx.getParent(i), look, lookBusy, calledRuleStack);
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

		int n = s.getNumberOfTransitions();
		for (int i = 0; i < n; i++) {
			Transition t = s.transition(i);
			if (t instanceof RuleTransition) {
				RuleTransition rt = (RuleTransition) t;
				if (calledRuleStack.get(rt.ruleIndex)) {
					continue;
				}
				PredictionContext newContext = ctx.getChild(rt.followState.stateNumber);
				try {
					calledRuleStack.set(rt.ruleIndex);
					lookExitThroughCalleePreds(atn, t.target, stopState, newContext, look, lookBusy, calledRuleStack);
				}
				finally {
					calledRuleStack.clear(rt.ruleIndex);
				}
			}
			else if (t instanceof AbstractPredicateTransition) {
				// Only see through predicates that ALL(*) EpsilonClosure itself
				// drops at callee depth > 0: PrecedencePredicateTransition and
				// context-dependent PredicateTransition. Context-independent
				// predicates are hoisted and evaluated by ALL(*), so they remain
				// opaque to preserve exact equivalence on both valid and error inputs.
				boolean droppedInCallee = (t instanceof PrecedencePredicateTransition)
					|| (t instanceof PredicateTransition && ((PredicateTransition) t).isCtxDependent);
				if (!PredictionContext.isEmptyLocal(ctx) && droppedInCallee) {
					lookExitThroughCalleePreds(atn, t.target, stopState, ctx, look, lookBusy, calledRuleStack);
				}
				else {
					look.add(LL1Analyzer.HIT_PRED);
				}
			}
			else if (t.isEpsilon()) {
				lookExitThroughCalleePreds(atn, t.target, stopState, ctx, look, lookBusy, calledRuleStack);
			}
			else if (t.getClass() == WildcardTransition.class) {
				look.addAll(IntervalSet.of(Token.MIN_USER_TOKEN_TYPE, atn.maxTokenType));
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

	/** Return whether lookahead sets are disjoint; no lookahead ⇒ not disjoint */
	public static boolean disjoint(IntervalSet[] altLook) {
		boolean collision = false;
		IntervalSet combined = new IntervalSet();
		if ( altLook==null ) return false;
		for (IntervalSet look : altLook) {
			if ( look==null ) return false; // lookahead must've computation failed
			if ( !look.and(combined).isNil() ) {
				collision = true;
				break;
			}
			combined.addAll(look);
		}
		return !collision;
	}
}
