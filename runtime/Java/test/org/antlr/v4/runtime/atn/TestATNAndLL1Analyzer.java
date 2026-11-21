/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.util.BitSet;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestATNAndLL1Analyzer {

	@Test
	public void testATNAddRemoveDecisionMode() {
		ATN atn = new ATN(ATNType.PARSER, 10);
		assertEquals(ATNType.PARSER, atn.grammarType);
		assertEquals(10, atn.maxTokenType);
		assertEquals(0, atn.states.size());
		assertEquals(0, atn.getNumberOfDecisions());
		assertNull(atn.getDecisionState(0));

		BasicState s = new BasicState();
		atn.addState(s);
		assertEquals(0, s.stateNumber);
		assertSame(atn, s.atn);
		assertEquals(1, atn.states.size());

		atn.addState(null); // allowed
		assertEquals(2, atn.states.size());

		BasicState s2 = new BasicState();
		atn.addState(s2);
		atn.removeState(s2);
		assertNull(atn.states.get(s2.stateNumber));

		BasicBlockStartState decision = new BasicBlockStartState();
		atn.addState(decision);
		int d = atn.defineDecisionState(decision);
		assertEquals(0, d);
		assertEquals(1, atn.getNumberOfDecisions());
		assertSame(decision, atn.getDecisionState(0));
		assertEquals(1, atn.getDecisionToDFA().length);

		TokensStartState mode = new TokensStartState();
		atn.addState(mode);
		// defineMode for lexer-style; still works on parser ATN for API coverage
		ATN lexerAtn = new ATN(ATNType.LEXER, 1);
		TokensStartState m = new TokensStartState();
		lexerAtn.addState(m);
		lexerAtn.defineMode("DEFAULT_MODE", m);
		assertEquals(1, lexerAtn.modeToStartState.size());
		assertSame(m, lexerAtn.modeNameToStartState.get("DEFAULT_MODE"));
		assertEquals(1, lexerAtn.modeToDFA.length);

		lexerAtn.clearDFA();
		assertEquals(lexerAtn.modeToStartState.size(), lexerAtn.modeToDFA.length);
		assertEquals(0, lexerAtn.getContextCacheSize());

		PredictionContext ctx = PredictionContext.EMPTY_FULL.getChild(1);
		PredictionContext cached = lexerAtn.getCachedContext(ctx);
		assertNotNull(cached);
		assertTrue(lexerAtn.getContextCacheSize() >= 0);

		lexerAtn.setHasUnicodeSMPTransitions(true);
		assertTrue(lexerAtn.hasUnicodeSMPTransitions());
		lexerAtn.setHasUnicodeSMPTransitions(false);
		assertFalse(lexerAtn.hasUnicodeSMPTransitions());
	}

	@Test
	public void testNextTokensAndExpectedTokens() {
		ATN atn = ATNTestHelpers.buildParserAB();
		ATNState start = atn.ruleToStartState[0];
		IntervalSet next = atn.nextTokens(start);
		assertTrue(next.contains(1)); // token A
		// cached
		assertSame(next, atn.nextTokens(start));

		IntervalSet nextWithCtx = atn.nextTokens(start, PredictionContext.EMPTY_LOCAL);
		assertTrue(nextWithCtx.contains(1));

		IntervalSet expected = atn.getExpectedTokens(start.stateNumber, null);
		assertTrue(expected.contains(1));
		assertFalse(expected.contains(Token.EPSILON));

		// from mid state expecting B
		ATNState mid = atn.states.get(1);
		IntervalSet midExpected = atn.getExpectedTokens(mid.stateNumber, null);
		assertTrue(midExpected.contains(2));

		// at stop state with EMPTY local -> EPSILON then EOF in expected
		RuleStopState stop = atn.ruleToStopState[0];
		IntervalSet atStop = atn.nextTokens(stop, PredictionContext.EMPTY_LOCAL);
		assertTrue(atStop.contains(Token.EPSILON));

		IntervalSet expectedStop = atn.getExpectedTokens(stop.stateNumber, null);
		assertTrue(expectedStop.contains(Token.EOF));

		try {
			atn.getExpectedTokens(-1, null);
			fail();
		}
		catch (IllegalArgumentException expectedEx) {
			// ok
		}
		try {
			atn.getExpectedTokens(999, null);
			fail();
		}
		catch (IllegalArgumentException expectedEx) {
			// ok
		}
	}

	@Test
	public void testLL1AnalyzerLOOK() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		LL1Analyzer analyzer = new LL1Analyzer(atn);
		ATNState start = atn.ruleToStartState[0];

		IntervalSet look = analyzer.LOOK(start, PredictionContext.EMPTY_LOCAL);
		assertTrue(look.contains(1) || look.contains(2));

		IntervalSet lookFull = analyzer.LOOK(start, PredictionContext.EMPTY_FULL);
		assertTrue(lookFull.contains(1) || lookFull.contains(2));

		// decision lookahead: getDecisionLookahead starts from transition *targets*,
		// so for alts entered via epsilon the matching symbols are discovered.
		// Build a decision with epsilon into matching states.
		ATN epsAtn = new ATN(ATNType.PARSER, 2);
		BasicBlockStartState block = new BasicBlockStartState();
		BlockEndState end = new BlockEndState();
		BasicState alt1 = new BasicState();
		BasicState alt2 = new BasicState();
		block.endState = end;
		end.startState = block;
		block.ruleIndex = alt1.ruleIndex = alt2.ruleIndex = end.ruleIndex = 0;
		epsAtn.addState(block);
		epsAtn.addState(alt1);
		epsAtn.addState(alt2);
		epsAtn.addState(end);
		block.addTransition(new EpsilonTransition(alt1));
		block.addTransition(new EpsilonTransition(alt2));
		alt1.addTransition(new AtomTransition(end, 1));
		alt2.addTransition(new AtomTransition(end, 2));
		epsAtn.defineDecisionState(block);
		// dummy rule maps for LOOK stop handling not required here
		LL1Analyzer epsAnalyzer = new LL1Analyzer(epsAtn);
		IntervalSet[] decisionLook = epsAnalyzer.getDecisionLookahead(block);
		assertNotNull(decisionLook);
		assertEquals(2, decisionLook.length);
		assertNotNull(decisionLook[0]);
		assertTrue(decisionLook[0].contains(1));
		assertNotNull(decisionLook[1]);
		assertTrue(decisionLook[1].contains(2));

		assertNull(analyzer.getDecisionLookahead(null));

		// LOOK with stop state
		IntervalSet lookStop = analyzer.LOOK(start, atn.ruleToStopState[0], PredictionContext.EMPTY_LOCAL);
		assertNotNull(lookStop);
	}

	@Test
	public void testLL1AnalyzerWithPredAndWildcard() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		BasicState mid = new BasicState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		mid.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(mid);
		atn.addState(stop);
		start.addTransition(new PredicateTransition(mid, 0, 0, false));
		mid.addTransition(new WildcardTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };

		LL1Analyzer analyzer = new LL1Analyzer(atn);
		// seeThruPreds true in LOOK
		IntervalSet look = analyzer.LOOK(start, PredictionContext.EMPTY_LOCAL);
		assertTrue(look.contains(Token.MIN_USER_TOKEN_TYPE));

		// decision with pred -> null alt when !seeThruPreds
		BasicBlockStartState block = new BasicBlockStartState();
		BlockEndState end = new BlockEndState();
		block.ruleIndex = 0;
		end.ruleIndex = 0;
		block.endState = end;
		end.startState = block;
		atn.addState(block);
		atn.addState(end);
		block.addTransition(new PredicateTransition(end, 0, 0, false));
		IntervalSet[] la = analyzer.getDecisionLookahead(block);
		assertNull(la[0]); // pred blocks lookahead when not see-through
	}

	@Test
	public void testLL1AnalyzerNotSetAndRule() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(stop);
		IntervalSet set = IntervalSet.of(1);
		start.addTransition(new NotSetTransition(stop, set));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };

		LL1Analyzer analyzer = new LL1Analyzer(atn);
		IntervalSet look = analyzer.LOOK(start, PredictionContext.EMPTY_LOCAL);
		assertFalse(look.contains(1));
		assertTrue(look.contains(2));
	}

	@Test
	public void lookBusyPredicateAndRuleStopEof() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		BasicState loop = new BasicState();
		loop.ruleIndex = 0;
		atn.addState(loop);
		loop.addTransition(new EpsilonTransition(loop));
		LL1Analyzer analyzer = new LL1Analyzer(atn);
		IntervalSet loopLook = new IntervalSet();
		analyzer._LOOK(loop, null, PredictionContext.EMPTY_LOCAL, loopLook,
			new HashSet<ATNConfig>(), new BitSet(), true, false);
		assertTrue(loopLook.isNil());

		BasicBlockStartState decision = new BasicBlockStartState();
		BasicState predState = new BasicState();
		BasicState after = new BasicState();
		decision.ruleIndex = 0;
		predState.ruleIndex = 0;
		after.ruleIndex = 0;
		atn.addState(decision);
		atn.addState(predState);
		atn.addState(after);
		decision.addTransition(new EpsilonTransition(predState));
		predState.addTransition(new PredicateTransition(after, 0, 0, false));
		IntervalSet[] decisionLook = analyzer.getDecisionLookahead(decision);
		assertNull(decisionLook[0]);

		RuleStopState stop = new RuleStopState();
		stop.ruleIndex = 0;
		atn.addState(stop);
		atn.ruleToStopState = new RuleStopState[] { stop };
		IntervalSet withEof = new IntervalSet();
		analyzer._LOOK(stop, null, PredictionContext.EMPTY_FULL, withEof,
			new HashSet<ATNConfig>(), new BitSet(), true, true);
		assertTrue(withEof.contains(Token.EOF));
		IntervalSet noEof = new IntervalSet();
		analyzer._LOOK(stop, null, PredictionContext.EMPTY_FULL, noEof,
			new HashSet<ATNConfig>(), new BitSet(), true, false);
		assertFalse(noEof.contains(Token.EOF));
		IntervalSet local = new IntervalSet();
		analyzer._LOOK(stop, null, PredictionContext.EMPTY_LOCAL, local,
			new HashSet<ATNConfig>(), new BitSet(), true, true);
		IntervalSet nested = new IntervalSet();
		analyzer._LOOK(stop, null, PredictionContext.EMPTY_FULL.getChild(stop.stateNumber), nested,
			new HashSet<ATNConfig>(), new BitSet(), true, true);
		assertNotNull(local);
		assertNotNull(nested);
	}

	@Test
	public void parseInfoListsFallbackDecisions() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		atn.addState(decision);
		atn.defineDecisionState(decision);
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		ProfilingATNSimulator simulator = new ProfilingATNSimulator(parser);
		simulator.decisions[0].LL_Fallback = 3;
		ParseInfo info = new ParseInfo(simulator);
		assertEquals(1, info.getLLDecisions().size());
		assertEquals(Integer.valueOf(0), info.getLLDecisions().get(0));
	}

	@Test
	public void testPredicatePolicyLookBusyIsolationAndLegacySubclassDispatch() {
		// Build a 2-rule ATN:
		//   r0: call r1 -> token 3; also direct depth-0 ctx-dependent pred -> token 4
		//   r1: ctx-dependent pred -> token 1 | ctx-independent pred -> token 2 | prec pred -> token 5
		ATN atn = new ATN(ATNType.PARSER, 6);
		RuleStartState r0s = new RuleStartState(); r0s.ruleIndex = 0;
		RuleStopState r0e = new RuleStopState(); r0e.ruleIndex = 0; r0s.stopState = r0e;
		BasicState afterCall = new BasicState(); afterCall.ruleIndex = 0;
		BasicState d0Pred = new BasicState(); d0Pred.ruleIndex = 0;

		RuleStartState r1s = new RuleStartState(); r1s.ruleIndex = 1;
		RuleStopState r1e = new RuleStopState(); r1e.ruleIndex = 1; r1s.stopState = r1e;
		BasicState r1Ctx = new BasicState(); r1Ctx.ruleIndex = 1;
		BasicState r1Ind = new BasicState(); r1Ind.ruleIndex = 1;
		BasicState r1Prec = new BasicState(); r1Prec.ruleIndex = 1;

		for (ATNState st : new ATNState[] { r0s, r0e, afterCall, d0Pred, r1s, r1e, r1Ctx, r1Ind, r1Prec }) {
			atn.addState(st);
		}
		atn.ruleToStartState = new RuleStartState[] { r0s, r1s };
		atn.ruleToStopState = new RuleStopState[] { r0e, r1e };

		r0s.addTransition(new RuleTransition(r1s, 1, 0, afterCall));
		afterCall.addTransition(new AtomTransition(r0e, 3));
		r0s.addTransition(new PredicateTransition(d0Pred, 0, 0, true));
		d0Pred.addTransition(new AtomTransition(r0e, 4));

		r1s.addTransition(new PredicateTransition(r1Ctx, 1, 0, true));
		r1Ctx.addTransition(new AtomTransition(r1e, 1));
		r1s.addTransition(new PredicateTransition(r1Ind, 1, 1, false));
		r1Ind.addTransition(new AtomTransition(r1e, 2));
		r1s.addTransition(new PrecedencePredicateTransition(r1Prec, 1));
		r1Prec.addTransition(new AtomTransition(r1e, 5));

		LL1Analyzer analyzer = new LL1Analyzer(atn);

		// 1. OPAQUE sees HIT_PRED on everything
		IntervalSet opaque = analyzer.LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL, LL1Analyzer.PredicatePolicy.OPAQUE);
		assertTrue(opaque.contains(LL1Analyzer.HIT_PRED));
		assertFalse(opaque.contains(1));
		assertFalse(opaque.contains(4));

		// 2. SEE_THROUGH_ALL sees through all predicates
		IntervalSet seeAll = analyzer.LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL, LL1Analyzer.PredicatePolicy.SEE_THROUGH_ALL);
		assertTrue(seeAll.contains(1));
		assertTrue(seeAll.contains(2));
		assertTrue(seeAll.contains(4));
		assertTrue(seeAll.contains(5));
		assertFalse(seeAll.contains(LL1Analyzer.HIT_PRED));

		// 3. SEE_THROUGH_DROPPED_CALLEE_PREDS:
		//    - depth-0 ctx-dependent pred (token 4) stays OPAQUE (HIT_PRED)
		//    - callee ctx-dependent pred (token 1) and prec pred (token 5) are seen through
		//    - callee ctx-independent pred (token 2) stays OPAQUE (HIT_PRED)
		IntervalSet droppedFromR1Call = analyzer.LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL,
			LL1Analyzer.PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS, false);
		assertTrue(droppedFromR1Call.contains(1));
		assertTrue(droppedFromR1Call.contains(5));
		assertFalse(droppedFromR1Call.contains(2));
		assertFalse(droppedFromR1Call.contains(4));
		assertTrue(droppedFromR1Call.contains(LL1Analyzer.HIT_PRED));

		// 4. Null predicatePolicy throws NPE
		try {
			analyzer.LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL, null);
			fail("Expected NullPointerException");
		}
		catch (NullPointerException expected) {
		}

		// 5. lookBusy isolation regressions (depth -1 vs 0 AND depth -1 vs +1):
		//    (a) Depth -1 (via RuleStopState FOLLOW at EMPTY_LOCAL with stopState==null) must NOT
		//        poison a subsequent depth 0 visit of the same state.
		ATN isoAtn = new ATN(ATNType.PARSER, 4);
		RuleStartState aStart = new RuleStartState(); aStart.ruleIndex = 0;
		RuleStopState aStop = new RuleStopState(); aStop.ruleIndex = 0; aStart.stopState = aStop;
		BasicState shared = new BasicState(); shared.ruleIndex = 0;
		BasicState afterPred = new BasicState(); afterPred.ruleIndex = 0;
		for (ATNState st : new ATNState[] { aStart, aStop, shared, afterPred }) {
			isoAtn.addState(st);
		}
		isoAtn.ruleToStartState = new RuleStartState[] { aStart };
		isoAtn.ruleToStopState = new RuleStopState[] { aStop };
		aStart.addTransition(new EpsilonTransition(aStop));    // visits shared at edgeDepth == -1
		aStop.addTransition(new EpsilonTransition(shared));
		aStart.addTransition(new EpsilonTransition(shared));   // visits shared at callDepth == 0
		shared.addTransition(new PredicateTransition(afterPred, 0, 0, true));
		afterPred.addTransition(new AtomTransition(aStop, 1));

		LL1Analyzer isoAnalyzer = new LL1Analyzer(isoAtn);
		IntervalSet isoLook = isoAnalyzer.LOOK(aStart, null, PredictionContext.EMPTY_LOCAL,
			LL1Analyzer.PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS, false);
		assertTrue("Depth -1 path must see through ctx-dependent pred", isoLook.contains(1));
		assertTrue("Depth 0 path must NOT be poisoned by depth -1 and must emit HIT_PRED",
			isoLook.contains(LL1Analyzer.HIT_PRED));

		//    (b) Depth -1 vs depth +1 (3-regime busyAlt): alt 1 exits rStop (depth -1) and calls
		//        nullable rule a (with ctx=Singleton(EMPTY_LOCAL, followA), depth=-1 -> sees through
		//        {ctxDep}?); alt 2 calls rule a at depth +1 with the SAME ctx=Singleton(EMPTY_LOCAL, followA)
		//        and pops back to depth 0 where {ctxDep}? MUST emit HIT_PRED!
		ATN d3Atn = new ATN(ATNType.PARSER, 4);
		RuleStartState rS = new RuleStartState(); rS.ruleIndex = 0;
		RuleStopState rE = new RuleStopState(); rE.ruleIndex = 0; rS.stopState = rE;
		BasicState callA = new BasicState(); callA.ruleIndex = 0;
		BasicState followA = new BasicState(); followA.ruleIndex = 0;
		BasicState afterRPred = new BasicState(); afterRPred.ruleIndex = 0;
		RuleStartState subS = new RuleStartState(); subS.ruleIndex = 1;
		RuleStopState subE = new RuleStopState(); subE.ruleIndex = 1; subS.stopState = subE;
		for (ATNState st : new ATNState[] { rS, rE, callA, followA, afterRPred, subS, subE }) {
			d3Atn.addState(st);
		}
		d3Atn.ruleToStartState = new RuleStartState[] { rS, subS };
		d3Atn.ruleToStopState = new RuleStopState[] { rE, subE };
		// Alt 1: rS -> rE -> callA (depth 0 -> -1, ctx EMPTY_LOCAL)
		rS.addTransition(new EpsilonTransition(rE));
		rE.addTransition(new EpsilonTransition(callA));
		// Alt 2: rS -> callA (depth 0 -> calls subS at depth +1 with same Singleton(EMPTY_LOCAL, followA))
		rS.addTransition(new EpsilonTransition(callA));
		callA.addTransition(new RuleTransition(subS, 1, 0, followA));
		subS.addTransition(new EpsilonTransition(subE));
		followA.addTransition(new PredicateTransition(afterRPred, 0, 0, true));
		afterRPred.addTransition(new AtomTransition(rE, 2));

		IntervalSet d3Look = new LL1Analyzer(d3Atn).LOOK(rS, null, PredictionContext.EMPTY_LOCAL,
			LL1Analyzer.PredicatePolicy.SEE_THROUGH_DROPPED_CALLEE_PREDS, false);
		assertTrue("Depth -1 path sees through ctxDep pred", d3Look.contains(2));
		assertTrue("Depth +1 -> 0 pop must NOT be poisoned by depth -1 and must emit HIT_PRED",
			d3Look.contains(LL1Analyzer.HIT_PRED));

		// 6. Legacy 8-arg _LOOK subclass override observes BOTH root and recursive frames
		final int[] legacyFrames = new int[1];
		LL1Analyzer legacySub = new LL1Analyzer(atn) {
			@Override
			protected void _LOOK(ATNState s, ATNState stopState, PredictionContext ctx,
								 IntervalSet look, java.util.Set<ATNConfig> lookBusy,
								 BitSet calledRuleStack, boolean seeThruPreds, boolean addEOF) {
				legacyFrames[0]++;
				super._LOOK(s, stopState, ctx, look, lookBusy, calledRuleStack, seeThruPreds, addEOF);
			}
		};
		IntervalSet subLook = legacySub.LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL,
			LL1Analyzer.PredicatePolicy.SEE_THROUGH_ALL, true);
		assertTrue(subLook.contains(1));
		assertTrue("Expected root + recursive legacy _LOOK calls, got " + legacyFrames[0], legacyFrames[0] > 2);

		// Calling protected 8-arg PredicatePolicy _LOOK on subclass also routes non-drop modes through legacy _LOOK
		int before = legacyFrames[0];
		legacySub._LOOK(r0s, r0e, PredictionContext.EMPTY_LOCAL, new IntervalSet(),
			new HashSet<ATNConfig>(), new BitSet(), LL1Analyzer.PredicatePolicy.OPAQUE, false);
		assertTrue(legacyFrames[0] > before);
	}
}
