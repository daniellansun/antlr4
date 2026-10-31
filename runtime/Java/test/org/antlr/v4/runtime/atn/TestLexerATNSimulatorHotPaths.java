/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.UnbufferedCharStream;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Hot-path tests for {@link LexerATNSimulator}: retained reach buffer,
 * capacity-aware start-state construction, epsilon-target handling,
 * and line/column tracking on the DFA walk.
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

	/**
	 * GROOVY-12435: a bare CR (next code point is not LF, including EOF)
	 * is a line ending. The package-private overload is the one the DFA
	 * walk calls. The public overload delegates to it.
	 */
	@Test
	public void consumeBareCrAdvancesLineWhenNextIsNotLf() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		CharStream followed = CharStreams.fromString("\rx");
		assertEquals('\r', followed.LA(1));
		assertEquals('x', followed.LA(2));
		sim.consume(followed, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, followed.index());
		sim.consume(followed);
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		assertEquals(2, followed.index());

		sim.reset();
		CharStream atEof = CharStreams.fromString("\r");
		assertEquals(IntStream.EOF, atEof.LA(2));
		sim.consume(atEof, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, atEof.index());

		sim.reset();
		CharStream pair = CharStreams.fromString("\r\r");
		sim.consume(pair, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(pair);
		assertEquals(3, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(2, pair.index());

		sim.reset();
		CharStream afterText = CharStreams.fromString("a\rb");
		sim.consume(afterText);
		assertEquals(1, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.consume(afterText, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(afterText);
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());

		// Buffer of one code point: LA(2) has to pull the following character.
		sim.reset();
		UnbufferedCharStream unbuffered = new UnbufferedCharStream(new StringReader("\rx"), 1);
		assertEquals('\r', unbuffered.LA(1));
		sim.consume(unbuffered, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, unbuffered.index());
		assertEquals('x', unbuffered.LA(1));

		sim.reset();
		UnbufferedCharStream unbufferedEof = new UnbufferedCharStream(new StringReader("\r"), 1);
		assertEquals(IntStream.EOF, unbufferedEof.LA(2));
		sim.consume(unbufferedEof, '\r');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, unbufferedEof.index());
	}

	/**
	 * CR+LF is a single line break: the CR only advances the column, and the
	 * following LF performs the one line increment. A CR after that pair,
	 * and a CR after a lone LF, are further breaks.
	 */
	@Test
	public void consumeCrLfAdvancesLineOnlyOnLf() {
		ATN atn = buildTinyLexerAtn();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		CharStream input = CharStreams.fromString("\r\n");
		assertEquals('\r', input.LA(1));
		assertEquals('\n', input.LA(2));

		sim.consume(input, '\r');
		assertEquals(1, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		assertEquals(1, input.index());

		sim.consume(input, '\n');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(2, input.index());

		sim.reset();
		CharStream viaPublic = CharStreams.fromString("\r\n\r");
		sim.consume(viaPublic);
		assertEquals(1, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.consume(viaPublic);
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(viaPublic, '\r');
		assertEquals(3, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(3, viaPublic.index());

		sim.reset();
		CharStream lfThenCr = CharStreams.fromString("\n\r");
		sim.consume(lfThenCr, '\n');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.consume(lfThenCr, '\r');
		assertEquals(3, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(2, lfThenCr.index());

		sim.reset();
		UnbufferedCharStream unbuffered = new UnbufferedCharStream(new StringReader("\r\n"), 1);
		sim.consume(unbuffered, '\r');
		assertEquals(1, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.consume(unbuffered, '\n');
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(2, unbuffered.index());
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
	 * ID : [a-z]+ ; WS : [ \r\n]+ ;
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

	/**
	 * Same ATN as {@link #selfLoopRunKeepsIndexLineAndColumn}:
	 * ID : [a-z]+ ; WS : [ \r\n]+ ;
	 * GROOVY-12435. The DFA walk calls {@code consume(input, curChar)}.
	 * The first match installs any missing edge; the second match of the
	 * same text is the warm walk. A bare CR breaks the line and the next
	 * token starts at column 0. CR at EOF reuses that edge and does the
	 * same. Two bare CRs are two breaks. CR+LF is one break.
	 */
	@Test
	public void selfLoopTreatsBareCrAsLineEndingAndCrLfAsOneBreak() {
		ATN atn = buildSelfLoopLexer();
		LexerATNSimulator sim = new LexerATNSimulator(atn);

		CharStream bareCold = CharStreams.fromString("\rx");
		assertEquals(2, sim.match(bareCold, Lexer.DEFAULT_MODE));
		assertEquals(1, bareCold.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, sim.match(bareCold, Lexer.DEFAULT_MODE));
		assertEquals(2, bareCold.index());
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.reset();
		CharStream bare = CharStreams.fromString("\rx");
		assertEquals(2, sim.match(bare, Lexer.DEFAULT_MODE));
		assertEquals(1, bare.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, sim.match(bare, Lexer.DEFAULT_MODE));
		assertEquals(2, bare.index());
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.reset();

		CharStream atEof = CharStreams.fromString("\r");
		assertEquals(2, sim.match(atEof, Lexer.DEFAULT_MODE));
		assertEquals(1, atEof.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.reset();

		CharStream crcrCold = CharStreams.fromString("\r\r");
		assertEquals(2, sim.match(crcrCold, Lexer.DEFAULT_MODE));
		assertEquals(2, crcrCold.index());
		assertEquals(3, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.reset();
		CharStream crcr = CharStreams.fromString("\r\r");
		assertEquals(2, sim.match(crcr, Lexer.DEFAULT_MODE));
		assertEquals(2, crcr.index());
		assertEquals(3, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		sim.reset();

		CharStream crlfCold = CharStreams.fromString("\r\nx");
		assertEquals(2, sim.match(crlfCold, Lexer.DEFAULT_MODE));
		assertEquals(2, crlfCold.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, sim.match(crlfCold, Lexer.DEFAULT_MODE));
		assertEquals(3, crlfCold.index());
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
		sim.reset();
		CharStream crlf = CharStreams.fromString("\r\nx");
		assertEquals(2, sim.match(crlf, Lexer.DEFAULT_MODE));
		assertEquals(2, crlf.index());
		assertEquals(2, sim.getLine());
		assertEquals(0, sim.getCharPositionInLine());
		assertEquals(1, sim.match(crlf, Lexer.DEFAULT_MODE));
		assertEquals(3, crlf.index());
		assertEquals(2, sim.getLine());
		assertEquals(1, sim.getCharPositionInLine());
	}

	/**
	 * Token line and column for the same ATN. The token after a bare CR
	 * starts at column 0 of the next line. CR+LF is one break. CR at EOF
	 * puts the EOF token on the next line at column 0. Two bare CRs put
	 * EOF two lines down.
	 */
	@Test
	public void tokensAfterBareCrAndCrLfUseTheBreak() {
		LexerInterpreter bare = newSelfLoopLexer("\rx");
		Token cr = bare.nextToken();
		assertEquals(2, cr.getType());
		assertEquals("\r", cr.getText());
		assertEquals(1, cr.getLine());
		assertEquals(0, cr.getCharPositionInLine());
		Token id = bare.nextToken();
		assertEquals(1, id.getType());
		assertEquals("x", id.getText());
		assertEquals(2, id.getLine());
		assertEquals(0, id.getCharPositionInLine());
		Token eof = bare.nextToken();
		assertEquals(Token.EOF, eof.getType());
		assertEquals(2, eof.getLine());
		assertEquals(1, eof.getCharPositionInLine());

		LexerInterpreter crlf = newSelfLoopLexer("\r\nx");
		Token ws = crlf.nextToken();
		assertEquals(2, ws.getType());
		assertEquals("\r\n", ws.getText());
		assertEquals(1, ws.getLine());
		assertEquals(0, ws.getCharPositionInLine());
		Token x = crlf.nextToken();
		assertEquals(1, x.getType());
		assertEquals("x", x.getText());
		assertEquals(2, x.getLine());
		assertEquals(0, x.getCharPositionInLine());
		Token eofAfterCrlf = crlf.nextToken();
		assertEquals(Token.EOF, eofAfterCrlf.getType());
		assertEquals(2, eofAfterCrlf.getLine());
		assertEquals(1, eofAfterCrlf.getCharPositionInLine());

		LexerInterpreter atEof = newSelfLoopLexer("\r");
		Token only = atEof.nextToken();
		assertEquals(2, only.getType());
		assertEquals("\r", only.getText());
		assertEquals(1, only.getLine());
		assertEquals(0, only.getCharPositionInLine());
		Token eofAfterCr = atEof.nextToken();
		assertEquals(Token.EOF, eofAfterCr.getType());
		assertEquals(2, eofAfterCr.getLine());
		assertEquals(0, eofAfterCr.getCharPositionInLine());

		LexerInterpreter two = newSelfLoopLexer("\r\r");
		Token both = two.nextToken();
		assertEquals(2, both.getType());
		assertEquals("\r\r", both.getText());
		assertEquals(1, both.getLine());
		assertEquals(0, both.getCharPositionInLine());
		Token eofAfterPair = two.nextToken();
		assertEquals(Token.EOF, eofAfterPair.getType());
		assertEquals(3, eofAfterPair.getLine());
		assertEquals(0, eofAfterPair.getCharPositionInLine());
	}

	private static LexerInterpreter newSelfLoopLexer(String input) {
		return new LexerInterpreter("L.g4", VocabularyImpl.EMPTY_VOCABULARY,
			Arrays.asList("ID", "WS"), null,
			Collections.singletonList("DEFAULT_MODE"),
			buildSelfLoopLexer(), CharStreams.fromString(input));
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
		wsSet.add('\r');
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

	@Test
	public void emptyRuleCapturesAcceptAtStart() {
		ATN atn = modeWithRules(true);
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		assertEquals(1, sim.match(CharStreams.fromString(""), Lexer.DEFAULT_MODE));
	}

	@Test
	public void eofAtomAcceptsAndWildcardFallsThroughEpsilonTarget() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokens = new TokensStartState();
		tokens.ruleIndex = -1;
		atn.addState(tokens);
		RuleStartState start = new RuleStartState();
		BasicState mid = new BasicState();
		BasicState wildTarget = new BasicState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		mid.ruleIndex = 0;
		wildTarget.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(mid);
		atn.addState(wildTarget);
		atn.addState(stop);
		tokens.addTransition(new EpsilonTransition(start));
		start.addTransition(new AtomTransition(mid, CharStream.EOF));
		mid.addTransition(new EpsilonTransition(stop));
		mid.addTransition(new WildcardTransition(wildTarget));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokens);

		LexerATNSimulator sim = new LexerATNSimulator(atn);
		assertEquals(1, sim.match(CharStreams.fromString(""), Lexer.DEFAULT_MODE));
	}

	@Test
	public void positionDependentActionFixesOffsetBeforeNextChar() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokens = new TokensStartState();
		tokens.ruleIndex = -1;
		atn.addState(tokens);
		RuleStartState start = new RuleStartState();
		BasicState mid = new BasicState();
		BasicState after = new BasicState();
		BasicState preStop = new BasicState();
		RuleStopState stop = new RuleStopState();
		for (ATNState state : new ATNState[] { start, mid, after, preStop, stop }) {
			state.ruleIndex = 0;
			atn.addState(state);
		}
		start.stopState = stop;
		tokens.addTransition(new EpsilonTransition(start));
		start.addTransition(new AtomTransition(mid, 'a'));
		mid.addTransition(new ActionTransition(after, 0, 0, false));
		after.addTransition(new AtomTransition(preStop, 'b'));
		preStop.addTransition(new EpsilonTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.ruleToTokenType = new int[] { 4 };
		atn.lexerActions = new LexerAction[] { new LexerCustomAction(0, 1) };
		atn.defineMode("DEFAULT_MODE", tokens);

		assertEquals(4, new LexerATNSimulator(atn).match(CharStreams.fromString("ab"), Lexer.DEFAULT_MODE));
	}

	@Test
	public void referencedRuleSkipsActionAndUsesOptimizedTailCall() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokens = new TokensStartState();
		tokens.ruleIndex = -1;
		atn.addState(tokens);

		RuleStartState outer = new RuleStartState();
		BasicState outerAfter = new BasicState();
		RuleStopState outerStop = new RuleStopState();
		RuleStartState mid = new RuleStartState();
		BasicState afterAction = new BasicState();
		RuleStopState midStop = new RuleStopState();
		RuleStartState inner = new RuleStartState();
		BasicState innerPre = new BasicState();
		RuleStopState innerStop = new RuleStopState();
		ATNState[] states = new ATNState[] {
			outer, outerAfter, outerStop, mid, afterAction, midStop, inner, innerPre, innerStop
		};
		int[] rules = new int[] { 0, 0, 0, 1, 1, 1, 2, 2, 2 };
		for (int i = 0; i < states.length; i++) {
			states[i].ruleIndex = rules[i];
			atn.addState(states[i]);
		}
		outer.stopState = outerStop;
		mid.stopState = midStop;
		inner.stopState = innerStop;
		tokens.addTransition(new EpsilonTransition(outer));
		outer.addTransition(new RuleTransition(mid, 1, 0, outerAfter));
		outerAfter.addTransition(new EpsilonTransition(outerStop));
		mid.addTransition(new ActionTransition(afterAction, 1, 0, false));
		RuleTransition tail = new RuleTransition(inner, 2, 0, midStop);
		tail.optimizedTailCall = true;
		afterAction.addTransition(tail);
		inner.addTransition(new AtomTransition(innerPre, 'a'));
		innerPre.addTransition(new EpsilonTransition(innerStop));
		atn.ruleToStartState = new RuleStartState[] { outer, mid, inner };
		atn.ruleToStopState = new RuleStopState[] { outerStop, midStop, innerStop };
		atn.ruleToTokenType = new int[] { 7, 0, 0 };
		atn.lexerActions = new LexerAction[] { new LexerCustomAction(1, 0) };
		atn.defineMode("DEFAULT_MODE", tokens);

		assertEquals(7, new LexerATNSimulator(atn).match(CharStreams.fromString("a"), Lexer.DEFAULT_MODE));
	}

	@Test
	public void ruleStopWithEmptyAndReturnContextSplits() {
		ATN atn = new ATN(ATNType.LEXER, 1);
		RuleStopState stop = new RuleStopState();
		stop.ruleIndex = 0;
		BasicState follow = new BasicState();
		follow.ruleIndex = 0;
		atn.addState(stop);
		atn.addState(follow);
		atn.ruleToStartState = new RuleStartState[] { new RuleStartState() };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.lexerActions = new LexerAction[0];

		PredictionContext[] parents = new PredictionContext[] {
			PredictionContext.EMPTY_FULL, PredictionContext.EMPTY_FULL
		};
		int[] returns = new int[] { follow.stateNumber, PredictionContext.EMPTY_FULL_STATE_KEY };
		PredictionContext ctx = new ArrayPredictionContext(parents, returns);
		assertTrue(ctx.hasEmpty());
		assertFalse(ctx.isEmpty());

		ATNConfigSet configs = new ATNConfigSet();
		ATNConfig config = ATNConfig.create(stop, 1, ctx);
		boolean accepted = new ExposedLexer(atn).closeStop(config, configs);
		assertTrue(accepted || configs.size() >= 1);
	}

	static final class ExposedLexer extends LexerATNSimulator {
		ExposedLexer(ATN atn) { super(atn); }

		boolean closeStop(ATNConfig config, ATNConfigSet configs) {
			return closure(CharStreams.fromString(""), config, configs, false, false, false);
		}
	}

	/** One empty rule (optional) plus a fallback, so the mode closure can accept immediately. */
	private static ATN modeWithRules(boolean includeEmpty) {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokens = new TokensStartState();
		tokens.ruleIndex = -1;
		atn.addState(tokens);
		RuleStartState start = new RuleStartState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(stop);
		tokens.addTransition(new EpsilonTransition(start));
		if (includeEmpty) {
			start.addTransition(new EpsilonTransition(stop));
		}
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokens);
		return atn;
	}
}
