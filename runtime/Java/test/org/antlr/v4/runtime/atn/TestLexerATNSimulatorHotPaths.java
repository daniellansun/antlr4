/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Hot-path tests for {@link LexerATNSimulator}: retained reach buffer,
 * capacity-aware start-state construction, and epsilon-target handling.
 * Uses package-private observation hooks (not reflection).
 */
public class TestLexerATNSimulatorHotPaths {

	/**
	 * Builds a tiny lexer ATN equivalent to:
	 * <pre>
	 * A : 'a' ;
	 * B : 'b' ;
	 * </pre>
	 */
	private static ATN buildTinyLexerAtn() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);

		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		RuleStartState aStart = new RuleStartState();
		aStart.ruleIndex = 0;
		atn.addState(aStart);
		BasicState aMid = new BasicState();
		aMid.ruleIndex = 0;
		atn.addState(aMid);
		RuleStopState aStop = new RuleStopState();
		aStop.ruleIndex = 0;
		atn.addState(aStop);
		aStart.stopState = aStop;
		aStart.addTransition(new AtomTransition(aMid, 'a'));
		aMid.addTransition(new EpsilonTransition(aStop));

		RuleStartState bStart = new RuleStartState();
		bStart.ruleIndex = 1;
		atn.addState(bStart);
		BasicState bMid = new BasicState();
		bMid.ruleIndex = 1;
		atn.addState(bMid);
		RuleStopState bStop = new RuleStopState();
		bStop.ruleIndex = 1;
		atn.addState(bStop);
		bStart.stopState = bStop;
		bStart.addTransition(new AtomTransition(bMid, 'b'));
		bMid.addTransition(new EpsilonTransition(bStop));

		tokensStart.addTransition(new EpsilonTransition(aStart));
		tokensStart.addTransition(new EpsilonTransition(bStart));

		atn.ruleToStartState = new RuleStartState[] { aStart, bStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, bStop };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}

	@Test
	public void consumeUpdatesLineOnNewlineAndColumnOtherwise() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		CharStream input = CharStreams.fromString("\nab");
		assertEquals(1, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(input, '\n');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(input);
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
	}

	@Test
	public void matchSimpleTokensAndBuildDfa() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		assertEquals(1, sim.match(CharStreams.fromString("a"), Lexer.DEFAULT_MODE));
		assertEquals(2, sim.match(CharStreams.fromString("b"), Lexer.DEFAULT_MODE));

		DFA dfa = sim.getDFA(Lexer.DEFAULT_MODE);
		assertNotNull(dfa.s0.get());
		assertTrue(dfa.states.size() >= 1);
	}

	@Test
	public void reachBufferIsRetainedAcrossComputeTargetState() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		assertNull(sim.retainedReachBuffer());

		assertEquals(1, sim.match(CharStreams.fromString("a"), Lexer.DEFAULT_MODE));
		ATNConfigSet firstReach = sim.retainedReachBuffer();
		assertNotNull(firstReach);
		assertTrue(firstReach.isEmpty()); // released after edge install

		assertEquals(2, sim.match(CharStreams.fromString("b"), Lexer.DEFAULT_MODE));
		assertSame(firstReach, sim.retainedReachBuffer());
		assertTrue(sim.retainedReachBuffer().isEmpty());
	}

	@Test
	public void computeTargetStateClearsReachEvenOnErrorEdge() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		sim.match(CharStreams.fromString("a"), Lexer.DEFAULT_MODE);
		DFAState s0 = atn.modeToDFA[Lexer.DEFAULT_MODE].s0.get();
		assertNotNull(s0);

		DFAState target = sim.computeTargetState(CharStreams.fromString("z"), s0, 'z');
		assertSame(ATNSimulator.ERROR, target);

		ATNConfigSet reach = sim.retainedReachBuffer();
		assertNotNull(reach);
		assertTrue(reach.isEmpty());
	}

	@Test
	public void getEpsilonTargetEpsilonAndRuleAndEofAsEpsilon() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		RuleStartState aStart = atn.ruleToStartState[0];
		ATNState aMid = aStart.transition(0).target;
		RuleStopState aStop = atn.ruleToStopState[0];

		ATNConfig config = ATNConfig.create(aMid, 1, PredictionContext.EMPTY_FULL);
		ATNConfigSet configs = new OrderedATNConfigSet(4);

		ATNConfig viaEpsilon = sim.getEpsilonTarget(
			CharStreams.fromString(""), config, aMid.transition(0), configs, false, false);
		assertNotNull(viaEpsilon);
		assertEquals(aStop, viaEpsilon.getState());

		AtomTransition atom = new AtomTransition(aMid, 'x');
		assertEquals(null, sim.getEpsilonTarget(
			CharStreams.fromString(""), config, atom, configs, false, false));

		AtomTransition eofAtom = new AtomTransition(aMid, -1);
		ATNConfig viaEof = sim.getEpsilonTarget(
			CharStreams.fromString(""), config, eofAtom, configs, false, true);
		assertNotNull(viaEof);
		assertEquals(aMid, viaEof.getState());
	}

	@Test
	public void scratchCapacityIsSharedPolicy() {
		assertEquals(16, ATNConfigSet.scratchCapacity(0));
		assertEquals(16, ATNConfigSet.scratchCapacity(1));
		assertEquals(32, ATNConfigSet.scratchCapacity(16));
		assertEquals(64, ATNConfigSet.scratchCapacity(32));
	}

	/**
	 * ID : [a-z]+ ; WS : [ \n]+ ;
	 * A warm DFA loop must leave the input index, line, and column on the
	 * same character the slow loop would, including a newline inside WS.
	 */
	@Test
	public void selfLoopRunKeepsIndexLineAndColumn() {
		ATN atn = buildSelfLoopLexer();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		// Cold match builds the loop edges.
		assertEquals(1, sim.match(CharStreams.fromString("abcd"), Lexer.DEFAULT_MODE));
		sim.reset();

		CharStream ident = CharStreams.fromString("abcd");
		assertEquals(1, sim.match(ident, Lexer.DEFAULT_MODE));
		assertEquals(4, ident.index());
		assertEquals(1, sim.getLine());
		assertEquals(4, sim.getCharPositionInLine());
		sim.reset();

		CharStream split = CharStreams.fromString("ab\ncd");
		assertEquals(1, sim.match(split, Lexer.DEFAULT_MODE));
		assertEquals(2, split.index());
		assertEquals(1, sim.getLine());
		assertEquals(2, sim.getCharPositionInLine());
		assertEquals(2, sim.match(split, Lexer.DEFAULT_MODE));
		assertEquals(3, split.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, sim.match(split, Lexer.DEFAULT_MODE));
		assertEquals(5, split.index());
		assertEquals(2, sim.getLine());
		assertEquals(2, sim.getCharPositionInLine());
		sim.reset();

		// First whitespace match installs the edges. The second match is the
		// warm loop, including the newline that moves the line.
		sim.match(CharStreams.fromString(" \n "), Lexer.DEFAULT_MODE);
		sim.reset();
		CharStream ws = CharStreams.fromString(" \n ");
		assertEquals(2, sim.match(ws, Lexer.DEFAULT_MODE));
		assertEquals(3, ws.index());
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.reset();

		CharStream one = CharStreams.fromString("a");
		assertEquals(1, sim.match(one, Lexer.DEFAULT_MODE));
		assertEquals(1, one.index());
		assertEquals(1, sim.getCharPositionInLine());
	}

	private static ATN buildSelfLoopLexer() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		RuleStartState idStart = new RuleStartState();
		idStart.ruleIndex = 0;
		atn.addState(idStart);
		BasicState idMid = new BasicState();
		idMid.ruleIndex = 0;
		atn.addState(idMid);
		RuleStopState idStop = new RuleStopState();
		idStop.ruleIndex = 0;
		atn.addState(idStop);
		idStart.stopState = idStop;
		idStart.addTransition(new RangeTransition(idMid, 'a', 'z'));
		idMid.addTransition(new RangeTransition(idMid, 'a', 'z'));
		idMid.addTransition(new EpsilonTransition(idStop));

		RuleStartState wsStart = new RuleStartState();
		wsStart.ruleIndex = 1;
		atn.addState(wsStart);
		BasicState wsMid = new BasicState();
		wsMid.ruleIndex = 1;
		atn.addState(wsMid);
		RuleStopState wsStop = new RuleStopState();
		wsStop.ruleIndex = 1;
		atn.addState(wsStop);
		wsStart.stopState = wsStop;
		IntervalSet wsSet = new IntervalSet();
		wsSet.add(' ');
		wsSet.add('\n');
		wsStart.addTransition(new SetTransition(wsMid, wsSet));
		wsMid.addTransition(new SetTransition(wsMid, wsSet));
		wsMid.addTransition(new EpsilonTransition(wsStop));

		tokensStart.addTransition(new EpsilonTransition(idStart));
		tokensStart.addTransition(new EpsilonTransition(wsStart));
		atn.ruleToStartState = new RuleStartState[] { idStart, wsStart };
		atn.ruleToStopState = new RuleStopState[] { idStop, wsStop };
		atn.ruleToTokenType = new int[] { 1, 2 };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}
}
