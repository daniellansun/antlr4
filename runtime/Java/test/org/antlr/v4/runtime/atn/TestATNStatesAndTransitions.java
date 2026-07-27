/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestATNStatesAndTransitions {

	@Test
	public void testATNTypeValues() {
		assertEquals(2, ATNType.values().length);
		assertSame(ATNType.LEXER, ATNType.valueOf("LEXER"));
		assertSame(ATNType.PARSER, ATNType.valueOf("PARSER"));
	}

	@Test
	public void testAllStateTypesAndBasics() {
		BasicState basic = new BasicState();
		assertEquals(ATNState.BASIC, basic.getStateType());
		assertEquals(ATNState.INVALID_STATE_NUMBER, basic.stateNumber);
		assertEquals(basic.stateNumber, basic.getStateNumber());
		assertEquals(basic.stateNumber, basic.getNonStopStateNumber());
		assertFalse(basic.isNonGreedyExitState());
		assertEquals(String.valueOf(basic.stateNumber), basic.toString());

		RuleStartState ruleStart = new RuleStartState();
		assertEquals(ATNState.RULE_START, ruleStart.getStateType());
		assertFalse(ruleStart.isPrecedenceRule);

		RuleStopState ruleStop = new RuleStopState();
		assertEquals(ATNState.RULE_STOP, ruleStop.getStateType());
		assertEquals(-1, ruleStop.getNonStopStateNumber());

		BlockEndState blockEnd = new BlockEndState();
		assertEquals(ATNState.BLOCK_END, blockEnd.getStateType());

		PlusBlockStartState plusBlock = new PlusBlockStartState();
		assertEquals(ATNState.PLUS_BLOCK_START, plusBlock.getStateType());

		StarBlockStartState starBlock = new StarBlockStartState();
		assertEquals(ATNState.STAR_BLOCK_START, starBlock.getStateType());

		BasicBlockStartState basicBlock = new BasicBlockStartState();
		assertEquals(ATNState.BLOCK_START, basicBlock.getStateType());

		PlusLoopbackState plusLoop = new PlusLoopbackState();
		assertEquals(ATNState.PLUS_LOOP_BACK, plusLoop.getStateType());

		StarLoopbackState starLoop = new StarLoopbackState();
		assertEquals(ATNState.STAR_LOOP_BACK, starLoop.getStateType());

		StarLoopEntryState starEntry = new StarLoopEntryState();
		assertEquals(ATNState.STAR_LOOP_ENTRY, starEntry.getStateType());
		assertFalse(starEntry.precedenceRuleDecision);
		// wire loop-back -> entry so getLoopEntryState() is covered
		starLoop.addTransition(new EpsilonTransition(starEntry));
		assertSame(starEntry, starLoop.getLoopEntryState());

		LoopEndState loopEnd = new LoopEndState();
		assertEquals(ATNState.LOOP_END, loopEnd.getStateType());

		TokensStartState tokensStart = new TokensStartState();
		assertEquals(ATNState.TOKEN_START, tokensStart.getStateType());
		assertEquals(-1, tokensStart.decision);

		assertTrue(ATNState.serializationNames.contains("BASIC"));
		assertEquals(ATNState.INVALID_TYPE, 0);
	}

	@Test
	public void testStateEqualsHashCodeTransitions() {
		BasicState a = new BasicState();
		BasicState b = new BasicState();
		a.stateNumber = 3;
		b.stateNumber = 3;
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		b.stateNumber = 4;
		assertFalse(a.equals(b));
		assertFalse(a.equals("x"));

		BasicState s = new BasicState();
		s.stateNumber = 1;
		BasicState t = new BasicState();
		t.stateNumber = 2;
		EpsilonTransition e = new EpsilonTransition(t);
		s.addTransition(e);
		assertEquals(1, s.getNumberOfTransitions());
		assertSame(e, s.transition(0));
		assertTrue(s.onlyHasEpsilonTransitions());
		assertEquals(1, s.getTransitions().length);

		AtomTransition atom = new AtomTransition(t, 'x');
		s.addTransition(0, atom);
		assertFalse(s.onlyHasEpsilonTransitions()); // mixed epsilon/non-epsilon
		s.setTransition(0, e);
		assertSame(e, s.removeTransition(0));

		s.setRuleIndex(5);
		assertEquals(5, s.ruleIndex);

		// optimized transitions
		assertFalse(s.isOptimized());
		s.addOptimizedTransition(atom);
		assertTrue(s.isOptimized());
		assertEquals(1, s.getNumberOfOptimizedTransitions());
		assertSame(atom, s.getOptimizedTransition(0));
		s.setOptimizedTransition(0, e);
		assertSame(e, s.getOptimizedTransition(0));
		s.removeOptimizedTransition(0);
		assertEquals(0, s.getNumberOfOptimizedTransitions());

		try {
			BasicState notOpt = new BasicState();
			notOpt.setOptimizedTransition(0, e);
			fail("expected IllegalStateException");
		}
		catch (IllegalStateException expected) {
			// ok
		}
		try {
			BasicState notOpt = new BasicState();
			notOpt.removeOptimizedTransition(0);
			fail("expected IllegalStateException");
		}
		catch (IllegalStateException expected) {
			// ok
		}
	}

	@Test
	public void testDecisionStateFields() {
		BasicBlockStartState d = new BasicBlockStartState();
		d.decision = 2;
		d.nonGreedy = true;
		d.sll = true;
		assertEquals(2, d.decision);
		assertTrue(d.nonGreedy);
		assertTrue(d.sll);
	}

	@Test
	public void testEpsilonTransition() {
		BasicState t = new BasicState();
		EpsilonTransition e = new EpsilonTransition(t);
		assertEquals(Transition.EPSILON, e.getSerializationType());
		assertTrue(e.isEpsilon());
		assertFalse(e.matches(1, 0, 100));
		assertEquals(-1, e.outermostPrecedenceReturn());
		assertEquals("epsilon", e.toString());
		assertNull(e.label());

		EpsilonTransition e2 = new EpsilonTransition(t, 7);
		assertEquals(7, e2.outermostPrecedenceReturn());
	}

	@Test
	public void testAtomTransition() {
		BasicState t = new BasicState();
		AtomTransition a = new AtomTransition(t, 'z');
		assertEquals(Transition.ATOM, a.getSerializationType());
		assertTrue(a.matches('z', 0, 0xFFFF));
		assertFalse(a.matches('y', 0, 0xFFFF));
		assertEquals(IntervalSet.of('z'), a.label());
		assertEquals(String.valueOf((int) 'z'), a.toString());
	}

	@Test
	public void testRangeTransition() {
		BasicState t = new BasicState();
		RangeTransition r = new RangeTransition(t, 'a', 'c');
		assertEquals(Transition.RANGE, r.getSerializationType());
		assertTrue(r.matches('b', 0, 0xFFFF));
		assertFalse(r.matches('d', 0, 0xFFFF));
		assertEquals(IntervalSet.of('a', 'c'), r.label());
		assertTrue(r.toString().contains(".."));
	}

	@Test
	public void testSetAndNotSetTransition() {
		BasicState t = new BasicState();
		IntervalSet set = IntervalSet.of('a');
		set.add('c');
		SetTransition s = new SetTransition(t, set);
		assertEquals(Transition.SET, s.getSerializationType());
		assertTrue(s.matches('a', 0, 0xFFFF));
		assertFalse(s.matches('b', 0, 0xFFFF));
		assertSame(set, s.label());
		assertNotNull(s.toString());

		SetTransition nullSet = new SetTransition(t, null);
		assertTrue(nullSet.set.contains(Token.INVALID_TYPE));

		NotSetTransition ns = new NotSetTransition(t, IntervalSet.of('a'));
		assertEquals(Transition.NOT_SET, ns.getSerializationType());
		assertTrue(ns.matches('b', 0, 100));
		assertFalse(ns.matches('a', 0, 100));
		assertFalse(ns.matches(200, 0, 100)); // outside vocab
		assertTrue(ns.toString().startsWith("~"));
	}

	@Test
	public void testWildcardTransition() {
		BasicState t = new BasicState();
		WildcardTransition w = new WildcardTransition(t);
		assertEquals(Transition.WILDCARD, w.getSerializationType());
		assertTrue(w.matches(5, 1, 10));
		assertFalse(w.matches(0, 1, 10));
		assertEquals(".", w.toString());
	}

	@Test
	public void testRuleTransition() {
		RuleStartState ruleStart = new RuleStartState();
		BasicState follow = new BasicState();
		@SuppressWarnings("deprecation")
		RuleTransition deprecated = new RuleTransition(ruleStart, 3, follow);
		assertEquals(0, deprecated.precedence);
		assertEquals(3, deprecated.ruleIndex);
		assertSame(follow, deprecated.followState);

		RuleTransition rt = new RuleTransition(ruleStart, 3, 2, follow);
		assertEquals(Transition.RULE, rt.getSerializationType());
		assertTrue(rt.isEpsilon());
		assertFalse(rt.matches(1, 0, 10));
		assertEquals(2, rt.precedence);
		assertSame(ruleStart, rt.target);
	}

	@Test
	public void testPredicateAndPrecedenceAndActionTransitions() {
		BasicState t = new BasicState();
		PredicateTransition p = new PredicateTransition(t, 1, 2, true);
		assertEquals(Transition.PREDICATE, p.getSerializationType());
		assertTrue(p.isEpsilon());
		assertFalse(p.matches(1, 0, 10));
		assertEquals(1, p.ruleIndex);
		assertEquals(2, p.predIndex);
		assertTrue(p.isCtxDependent);
		SemanticContext.Predicate pred = p.getPredicate();
		assertEquals(1, pred.ruleIndex);
		assertEquals(2, pred.predIndex);
		assertTrue(pred.isCtxDependent);
		assertTrue(p.toString().contains("pred_"));
		assertTrue(p instanceof AbstractPredicateTransition);

		PrecedencePredicateTransition ppt = new PrecedencePredicateTransition(t, 4);
		assertEquals(Transition.PRECEDENCE, ppt.getSerializationType());
		assertTrue(ppt.isEpsilon());
		assertFalse(ppt.matches(1, 0, 10));
		assertEquals(4, ppt.precedence);
		assertEquals(4, ppt.getPredicate().precedence);
		assertTrue(ppt.toString().contains(">="));

		ActionTransition a1 = new ActionTransition(t, 1);
		assertEquals(-1, a1.actionIndex);
		assertFalse(a1.isCtxDependent);
		ActionTransition a2 = new ActionTransition(t, 1, 5, true);
		assertEquals(Transition.ACTION, a2.getSerializationType());
		assertTrue(a2.isEpsilon());
		assertFalse(a2.matches(1, 0, 10));
		assertEquals(5, a2.actionIndex);
		assertTrue(a2.isCtxDependent);
		assertTrue(a2.toString().startsWith("action_"));
	}

	@Test
	public void testTransitionNullTargetThrows() {
		try {
			new AtomTransition(null, 1);
			fail();
		}
		catch (NullPointerException expected) {
			// ok
		}
	}

	@Test
	public void testTransitionSerializationConstants() {
		assertEquals(1, Transition.EPSILON);
		assertEquals(10, Transition.PRECEDENCE);
		assertNotNull(Transition.serializationNames);
		assertTrue(Transition.serializationTypes.containsKey(AtomTransition.class));
	}

	@Test
	public void testCodePointTransitions() {
		BasicState t = new BasicState();
		Transition atom = CodePointTransitions.createWithCodePoint(t, 'A');
		assertTrue(atom instanceof AtomTransition);
		assertEquals('A', ((AtomTransition) atom).label);

		int smp = 0x1F600; // emoji, supplementary
		Transition set = CodePointTransitions.createWithCodePoint(t, smp);
		assertTrue(set instanceof SetTransition);
		assertTrue(((SetTransition) set).set.contains(smp));

		Transition range = CodePointTransitions.createWithCodePointRange(t, 'a', 'z');
		assertTrue(range instanceof RangeTransition);
		assertEquals('a', ((RangeTransition) range).from);
		assertEquals('z', ((RangeTransition) range).to);

		Transition smpRange = CodePointTransitions.createWithCodePointRange(t, smp, smp + 10);
		assertTrue(smpRange instanceof SetTransition);
	}
}
