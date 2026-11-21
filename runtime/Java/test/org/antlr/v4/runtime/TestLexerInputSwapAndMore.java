/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.ActionTransition;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.LexerAction;
import org.antlr.v4.runtime.atn.LexerCustomAction;
import org.antlr.v4.runtime.atn.LexerMoreAction;
import org.antlr.v4.runtime.atn.LexerSkipAction;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.misc.IntegerList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link Lexer} mid-match {@code _input} swapping,
 * {@code finishMoreGeneric}, {@code nextTokenGeneric}, custom {@code emit}/{@code getCharIndex}
 * overrides, and lazy {@code _modeStack} capacity sizing via {@link IntegerList#ensureCapacity(int)}.
 */
public class TestLexerInputSwapAndMore {

	private static ATN buildMultiActionLexerATN() {
		ATN atn = new ATN(ATNType.LEXER, 6);
		TokensStartState start = new TokensStartState();
		start.ruleIndex = -1;
		atn.addState(start);

		RuleStartState[] starts = new RuleStartState[6];
		RuleStopState[] stops = new RuleStopState[6];
		char[] chars = { 's', 'm', 'a', 'b', ' ', '+' };
		int[] types = { 3, 4, 1, 2, 5, 6 };

		for (int r = 0; r < 6; r++) {
			RuleStartState rs = new RuleStartState();
			BasicState mid = new BasicState();
			BasicState act = new BasicState();
			RuleStopState stop = new RuleStopState();
			rs.ruleIndex = r; mid.ruleIndex = r; act.ruleIndex = r; stop.ruleIndex = r;
			rs.stopState = stop;
			atn.addState(rs); atn.addState(mid); atn.addState(act); atn.addState(stop);
			start.addTransition(new EpsilonTransition(rs));
			rs.addTransition(new AtomTransition(mid, chars[r]));
			if (r == 0) {
				mid.addTransition(new ActionTransition(act, 0, 0, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 1) {
				mid.addTransition(new ActionTransition(act, 1, 1, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 4) {
				mid.addTransition(new ActionTransition(act, 4, 2, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else if (r == 5) {
				mid.addTransition(new ActionTransition(act, 5, 3, false));
				act.addTransition(new EpsilonTransition(stop));
			}
			else {
				mid.addTransition(new EpsilonTransition(stop));
			}
			starts[r] = rs;
			stops[r] = stop;
		}
		atn.ruleToStartState = starts;
		atn.ruleToStopState = stops;
		atn.ruleToTokenType = types;
		atn.lexerActions = new LexerAction[] {
			new LexerCustomAction(0, 0),
			new LexerCustomAction(1, 1),
			LexerSkipAction.INSTANCE,
			LexerMoreAction.INSTANCE
		};
		atn.defineMode("DEFAULT_MODE", start);
		atn.clearDFA();
		return atn;
	}

	@SuppressWarnings("deprecation")
	@Test
	public void testInputSwapFinishMoreGenericAndEmitOverrides() {
		final ATN atn = buildMultiActionLexerATN();

		// 1. CodePoint -> CodePoint swap on SKIP and MORE
		final CharStream[] swapTargets = new CharStream[] {
			CharStreams.fromString("a"), CharStreams.fromString("b")
		};
		Lexer swapCpLexer = new Lexer(CharStreams.fromString("s")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Swap.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				if (actionIndex == 0) { _input = swapTargets[0]; skip(); }
				else if (actionIndex == 1) { _input = swapTargets[1]; more(); }
			}
		};
		assertEquals(1, swapCpLexer.nextToken().getType());
		assertEquals(Token.EOF, swapCpLexer.nextToken().getType());

		swapCpLexer.setInputStream(CharStreams.fromString("m"));
		swapTargets[1] = CharStreams.fromString("b");
		assertEquals(2, swapCpLexer.nextToken().getType());

		// 2. CodePoint -> Generic swap on SKIP, MORE (finishMoreGeneric), and empty swap without skip/more
		final CharStream[] genericTargets = new CharStream[2];
		final int[] mode = new int[1];
		Lexer swapGenericLexer = new Lexer(CharStreams.fromString("s")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "SwapG.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override
			public void action(RuleContext ctx, int ruleIndex, int actionIndex) {
				if (actionIndex == 0) {
					_input = genericTargets[0];
					if (mode[0] == 0) skip();
				}
				else if (actionIndex == 1) {
					_input = genericTargets[1];
					more();
				}
			}
		};
		swapGenericLexer.removeErrorListeners();
		genericTargets[0] = new ANTLRInputStream(" a");
		assertEquals(1, swapGenericLexer.nextToken().getType());
		assertEquals(Token.EOF, swapGenericLexer.nextToken().getType());

		swapGenericLexer.setInputStream(CharStreams.fromString("m"));
		genericTargets[1] = new ANTLRInputStream("+!b");
		assertEquals(2, swapGenericLexer.nextToken().getType());

		swapGenericLexer.setInputStream(CharStreams.fromString("m"));
		genericTargets[1] = new ANTLRInputStream(" b");
		assertEquals(2, swapGenericLexer.nextToken().getType());

		swapGenericLexer.setInputStream(CharStreams.fromString("m"));
		genericTargets[1] = new ANTLRInputStream("a");
		assertEquals(1, swapGenericLexer.nextToken().getType());

		mode[0] = 1;
		swapGenericLexer.setInputStream(CharStreams.fromString("s"));
		genericTargets[0] = CharStreams.fromString("");
		assertEquals(3, swapGenericLexer.nextToken().getType());

		// 3. Pure nextTokenGeneric + custom emit + getCharIndex override + setText
		Lexer genericLexer = new Lexer(new ANTLRInputStream(" +a!b")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Gen.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		genericLexer.removeErrorListeners();
		assertEquals(1, genericLexer.nextToken().getType());
		assertEquals(2, genericLexer.nextToken().getType());
		assertEquals(Token.EOF, genericLexer.nextToken().getType());
		genericLexer.setText("customText");
		assertEquals("customText", genericLexer.emit().getText());

		final Token wrappedEof = new CommonToken(Token.EOF, "WRAPPED_EOF");
		Lexer customEmitLexer = new Lexer(CharStreams.fromString("a")) {
			{
				_interp = new LexerATNSimulator(this, atn);
				setTokenFactory(new CommonTokenFactory(true));
			}
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Cust.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public void emit(Token token) { super.emit(token.getType() == Token.EOF ? wrappedEof : token); }
		};
		assertEquals("a", customEmitLexer.nextToken().getText());
		assertSame(wrappedEof, customEmitLexer.nextToken());

		Lexer customIdxLexer = new Lexer(CharStreams.fromString("a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "Idx.g4"; }
			@Override public ATN getATN() { return atn; }
			@Override public int getCharIndex() { return super.getCharIndex() + 10; }
		};
		assertEquals(10, customIdxLexer.nextToken().getStopIndex());

		// Trailing skips on CodePointCharStream reach EOF cleanly
		Lexer skipToEof = new Lexer(CharStreams.fromString("   ")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "SkipEof.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		assertEquals(Token.EOF, skipToEof.nextToken().getType());
	}

	@Test
	public void testIntegerListEnsureCapacityAndLazyModeStack() {
		// IntegerList.ensureCapacity early-return and growth
		IntegerList list = new IntegerList();
		list.ensureCapacity(0);
		assertEquals(0, list.size());
		list.ensureCapacity(16);
		list.ensureCapacity(8); // early return when already >= minCapacity
		for (int i = 0; i < 20; i++) {
			list.add(i);
		}
		assertEquals(20, list.size());
		assertEquals(19, list.get(19));

		// Lexer._modeStack stays empty for mode-less lexers and grows cleanly past 16 on pushMode
		final ATN atn = buildMultiActionLexerATN();
		Lexer lex = new Lexer(CharStreams.fromString("a")) {
			{ _interp = new LexerATNSimulator(this, atn); }
			@Override public String[] getRuleNames() { return new String[] { "S", "M", "A", "B", "WS", "PLUS" }; }
			@Override public String getGrammarFileName() { return "M.g4"; }
			@Override public ATN getATN() { return atn; }
		};
		assertEquals(0, lex._modeStack.size());
		for (int m = 1; m <= 20; m++) {
			lex.pushMode(m);
		}
		assertEquals(20, lex._mode);
		assertEquals(20, lex._modeStack.size());
		for (int m = 19; m >= 0; m--) {
			assertEquals(m, lex.popMode());
		}
		assertEquals(0, lex._modeStack.size());
	}
}
