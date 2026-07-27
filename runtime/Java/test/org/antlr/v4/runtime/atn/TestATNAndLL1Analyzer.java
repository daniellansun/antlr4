/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

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
}
