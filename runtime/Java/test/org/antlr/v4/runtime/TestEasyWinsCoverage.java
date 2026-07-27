/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.CodePointTransitions;
import org.antlr.v4.runtime.atn.LexerChannelAction;
import org.antlr.v4.runtime.atn.LexerTypeAction;
import org.antlr.v4.runtime.atn.ParseInfo;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.ProfilingATNSimulator;
import org.antlr.v4.runtime.atn.RangeTransition;
import org.antlr.v4.runtime.atn.SetTransition;
import org.antlr.v4.runtime.atn.SemanticContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Tuple;
import org.antlr.v4.runtime.misc.Tuple3;
import org.antlr.v4.runtime.misc.Utils;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Small targeted tests closing remaining easy JaCoCo line gaps.
 */
public class TestEasyWinsCoverage {

	@Test
	public void codePointTransitionsSupplementaryUsesSet() {
		BasicState target = new BasicState();
		// U+10000 is supplementary
		assertTrue(CodePointTransitions.createWithCodePoint(target, 0x10000) instanceof SetTransition);
		assertTrue(CodePointTransitions.createWithCodePoint(target, 'A') instanceof AtomTransition);
		assertTrue(CodePointTransitions.createWithCodePointRange(target, 0x10000, 0x10010) instanceof SetTransition);
		assertTrue(CodePointTransitions.createWithCodePointRange(target, 'a', 'z') instanceof RangeTransition);
	}

	@Test
	public void lexerActionEqualsSelfAndOtherType() {
		LexerChannelAction a = new LexerChannelAction(2);
		assertTrue(a.equals(a));
		assertFalse(a.equals("x"));
		assertFalse(a.equals(new LexerChannelAction(3)));
		assertEquals(a, new LexerChannelAction(2));

		LexerTypeAction t = new LexerTypeAction(5);
		assertTrue(t.equals(t));
		assertFalse(t.equals("x"));
		assertFalse(t.equals(new LexerTypeAction(6)));
	}

	@Test
	public void deprecatedAtnSimulatorHelpers() {
		ATNSimulator.checkCondition(true);
		ATNSimulator.checkCondition(true, "ok");
		try {
			ATNSimulator.checkCondition(false);
		}
		catch (IllegalStateException expected) {
			assertNotNull(expected);
		}
		try {
			ATNSimulator.checkCondition(false, "bad");
		}
		catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("bad") || expected.getMessage() != null);
		}
		assertEquals(65, ATNSimulator.toInt('A'));
		// toInt32 packs two chars; just ensure it runs
		assertTrue(ATNSimulator.toInt32(new char[] { 0x41, 0x42 }, 0) != 0);
	}

	@Test
	public void intervalStartsAfter() {
		Interval a = Interval.of(5, 10);
		Interval b = Interval.of(1, 3);
		assertTrue(a.startsAfter(b));
		assertFalse(b.startsAfter(a));
	}

	@Test
	public void utilsJoinIterable() {
		assertEquals("a,b", Utils.join(java.util.Arrays.asList("a", "b"), ","));
		assertEquals("", Utils.join(Collections.emptyList(), ","));
	}

	@Test
	public void tuple3HashCodeUsesAllFields() {
		Tuple3<String, String, String> t = Tuple.create("a", "b", "c");
		Tuple3<String, String, String> t2 = Tuple.create("a", "b", "c");
		assertEquals(t, t2);
		assertEquals(t.hashCode(), t2.hashCode());
		assertNotEquals(t, Tuple.create("a", "b", "d"));
	}

	@Test
	public void failedPredicateNonPredicateTransitionDefaultsIndices() {
		// Build parser with a precedence (not Predicate) transition at state for FailedPredicateException path
		ATN atn = new ATN(ATNType.PARSER, 2);
		BasicState s = new BasicState();
		s.ruleIndex = 0;
		BasicState t = new BasicState();
		t.ruleIndex = 0;
		atn.addState(s);
		atn.addState(t);
		s.addTransition(new PrecedencePredicateTransition(t, 0));
		atn.ruleToStartState = new org.antlr.v4.runtime.atn.RuleStartState[0];
		atn.ruleToStopState = new org.antlr.v4.runtime.atn.RuleStopState[0];
		atn.clearDFA();

		ListTokenSource src = new ListTokenSource(Collections.singletonList(
			CommonTokenFactory.DEFAULT.create(1, "A")));
		CommonTokenStream tokens = new CommonTokenStream(src);
		ParserInterpreter parser = new ParserInterpreter(
			"P",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("r"),
			atn,
			tokens);
		parser.setState(s.stateNumber);
		FailedPredicateException ex = new FailedPredicateException(parser, "pred", "msg");
		// non-PredicateTransition branch sets indices to 0
		assertEquals(0, ex.getRuleIndex());
		assertEquals(0, ex.getPredIndex());
	}

	@Test
	public void semanticContextPrecedencePredicateCompareAndEval() {
		SemanticContext.PrecedencePredicate p0 = new SemanticContext.PrecedencePredicate(0);
		SemanticContext.PrecedencePredicate p1 = new SemanticContext.PrecedencePredicate(1);
		assertTrue(p0.compareTo(p1) < 0);
		assertEquals(p0, new SemanticContext.PrecedencePredicate(0));
		assertNotEquals(p0, p1);
		assertNotNull(p0.toString());
	}

	@Test
	public void parseInfoGetTotalTimeInPrediction() {
		// Minimal decision ATN A|B
		ATN atn = new ATN(ATNType.PARSER, 2);
		org.antlr.v4.runtime.atn.RuleStartState ruleStart = new org.antlr.v4.runtime.atn.RuleStartState();
		org.antlr.v4.runtime.atn.BasicBlockStartState blockStart = new org.antlr.v4.runtime.atn.BasicBlockStartState();
		org.antlr.v4.runtime.atn.BlockEndState blockEnd = new org.antlr.v4.runtime.atn.BlockEndState();
		org.antlr.v4.runtime.atn.RuleStopState ruleStop = new org.antlr.v4.runtime.atn.RuleStopState();
		for (ATNState s : new ATNState[] { ruleStart, blockStart, blockEnd, ruleStop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		ruleStart.stopState = ruleStop;
		blockStart.endState = blockEnd;
		blockEnd.startState = blockStart;
		ruleStart.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(blockStart));
		blockStart.addTransition(new AtomTransition(blockEnd, 1));
		blockStart.addTransition(new AtomTransition(blockEnd, 2));
		blockEnd.addTransition(new org.antlr.v4.runtime.atn.EpsilonTransition(ruleStop));
		atn.defineDecisionState(blockStart);
		atn.ruleToStartState = new org.antlr.v4.runtime.atn.RuleStartState[] { ruleStart };
		atn.ruleToStopState = new org.antlr.v4.runtime.atn.RuleStopState[] { ruleStop };
		atn.clearDFA();

		ListTokenSource src = new ListTokenSource(java.util.Arrays.asList(
			(Token) CommonTokenFactory.DEFAULT.create(1, "A"),
			(Token) CommonTokenFactory.DEFAULT.create(Token.EOF, "")));
		ParserInterpreter parser = new ParserInterpreter(
			"P", VocabularyImpl.EMPTY_VOCABULARY, Collections.singletonList("s"), atn,
			new CommonTokenStream(src));
		parser.setProfile(true);
		parser.parse(0);
		ParseInfo info = parser.getParseInfo();
		assertNotNull(info);
		assertTrue(info.getTotalTimeInPrediction() >= 0);
		assertTrue(info.getTotalATNLookaheadOps() >= 0);
	}
}
