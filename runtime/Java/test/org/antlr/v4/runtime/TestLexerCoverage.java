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
import org.antlr.v4.runtime.atn.LexerAction;
import org.antlr.v4.runtime.atn.LexerChannelAction;
import org.antlr.v4.runtime.atn.LexerMoreAction;
import org.antlr.v4.runtime.atn.LexerSkipAction;
import org.antlr.v4.runtime.atn.LexerTypeAction;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Coverage for {@link Lexer} nextToken, modes, skip/more, recover, getters.
 */
public class TestLexerCoverage {

	/** Lexer: A:'a'; WS:' ' -> skip; */
	static ATN buildSkipLexer() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		RuleStartState aStart = new RuleStartState();
		BasicState aMid = new BasicState();
		RuleStopState aStop = new RuleStopState();
		aStart.ruleIndex = 0;
		aMid.ruleIndex = 0;
		aStop.ruleIndex = 0;
		aStart.stopState = aStop;
		atn.addState(aStart);
		atn.addState(aMid);
		atn.addState(aStop);
		aStart.addTransition(new AtomTransition(aMid, 'a'));
		aMid.addTransition(new EpsilonTransition(aStop));

		RuleStartState wsStart = new RuleStartState();
		BasicState wsMid = new BasicState();
		BasicState wsAct = new BasicState();
		RuleStopState wsStop = new RuleStopState();
		wsStart.ruleIndex = 1;
		wsMid.ruleIndex = 1;
		wsAct.ruleIndex = 1;
		wsStop.ruleIndex = 1;
		wsStart.stopState = wsStop;
		atn.addState(wsStart);
		atn.addState(wsMid);
		atn.addState(wsAct);
		atn.addState(wsStop);
		wsStart.addTransition(new AtomTransition(wsMid, ' '));
		wsMid.addTransition(new ActionTransition(wsAct, 1, 0, false));
		wsAct.addTransition(new EpsilonTransition(wsStop));

		tokensStart.addTransition(new EpsilonTransition(aStart));
		tokensStart.addTransition(new EpsilonTransition(wsStart));
		atn.ruleToStartState = new RuleStartState[] { aStart, wsStart };
		atn.ruleToStopState = new RuleStopState[] { aStop, wsStop };
		atn.ruleToTokenType = new int[] { 1, Token.INVALID_TYPE };
		atn.lexerActions = new LexerAction[] { LexerSkipAction.INSTANCE };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	/** MORE: first rule 'x' more, second rule 'y' completes type 1. */
	static ATN buildMoreLexer() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);

		// X : 'x' -> more
		RuleStartState xStart = new RuleStartState();
		BasicState xMid = new BasicState();
		BasicState xAct = new BasicState();
		RuleStopState xStop = new RuleStopState();
		xStart.ruleIndex = 0;
		xMid.ruleIndex = 0;
		xAct.ruleIndex = 0;
		xStop.ruleIndex = 0;
		xStart.stopState = xStop;
		atn.addState(xStart);
		atn.addState(xMid);
		atn.addState(xAct);
		atn.addState(xStop);
		xStart.addTransition(new AtomTransition(xMid, 'x'));
		xMid.addTransition(new ActionTransition(xAct, 0, 0, false));
		xAct.addTransition(new EpsilonTransition(xStop));

		// Y : 'y' type 1
		RuleStartState yStart = new RuleStartState();
		BasicState yMid = new BasicState();
		RuleStopState yStop = new RuleStopState();
		yStart.ruleIndex = 1;
		yMid.ruleIndex = 1;
		yStop.ruleIndex = 1;
		yStart.stopState = yStop;
		atn.addState(yStart);
		atn.addState(yMid);
		atn.addState(yStop);
		yStart.addTransition(new AtomTransition(yMid, 'y'));
		yMid.addTransition(new EpsilonTransition(yStop));

		tokensStart.addTransition(new EpsilonTransition(xStart));
		tokensStart.addTransition(new EpsilonTransition(yStart));
		atn.ruleToStartState = new RuleStartState[] { xStart, yStart };
		atn.ruleToStopState = new RuleStopState[] { xStop, yStop };
		atn.ruleToTokenType = new int[] { Token.INVALID_TYPE, 1 };
		atn.lexerActions = new LexerAction[] { LexerMoreAction.INSTANCE };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	/** Channel action on 'h' -> HIDDEN. */
	static ATN buildChannelLexer() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);
		RuleStartState hStart = new RuleStartState();
		BasicState hMid = new BasicState();
		BasicState hAct = new BasicState();
		RuleStopState hStop = new RuleStopState();
		hStart.ruleIndex = 0;
		hMid.ruleIndex = 0;
		hAct.ruleIndex = 0;
		hStop.ruleIndex = 0;
		hStart.stopState = hStop;
		atn.addState(hStart);
		atn.addState(hMid);
		atn.addState(hAct);
		atn.addState(hStop);
		hStart.addTransition(new AtomTransition(hMid, 'h'));
		hMid.addTransition(new ActionTransition(hAct, 0, 0, false));
		hAct.addTransition(new EpsilonTransition(hStop));
		tokensStart.addTransition(new EpsilonTransition(hStart));
		atn.ruleToStartState = new RuleStartState[] { hStart };
		atn.ruleToStopState = new RuleStopState[] { hStop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[] { new LexerChannelAction(Token.HIDDEN_CHANNEL) };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}

	private static LexerInterpreter lex(ATN atn, String input, String... rules) {
		List<String> ruleNames = Arrays.asList(rules);
		Vocabulary vocab = new VocabularyImpl(new String[] { null, "'a'" }, new String[] { null, "A" });
		return new LexerInterpreter("L.g4", vocab, ruleNames,
			Arrays.asList("DEFAULT_TOKEN_CHANNEL", "HIDDEN"),
			Collections.singletonList("DEFAULT_MODE"),
			atn, CharStreams.fromString(input));
	}

	@Test
	public void skipWhitespaceAndGetAllTokens() {
		LexerInterpreter lexer = lex(buildSkipLexer(), "a a", "A", "WS");
		List<? extends Token> all = lexer.getAllTokens();
		assertEquals(2, all.size());
		assertEquals(1, all.get(0).getType());
		assertEquals(1, all.get(1).getType());
	}

	@Test
	public void moreCombinesCharacters() {
		LexerInterpreter lexer = lex(buildMoreLexer(), "xy", "X", "Y");
		Token t = lexer.nextToken();
		assertEquals(1, t.getType());
		assertEquals("xy", t.getText());
		assertEquals(Token.EOF, lexer.nextToken().getType());
	}

	@Test
	public void channelAction() {
		LexerInterpreter lexer = lex(buildChannelLexer(), "h", "H");
		// CommonTokenStream would hide; nextToken still returns it
		Token t = lexer.nextToken();
		assertEquals(1, t.getType());
		assertEquals(Token.HIDDEN_CHANNEL, t.getChannel());
	}

	@Test
	public void modePushPopAndManualMode() {
		// Use helpers-built multi-mode lexer via package - rebuild simply here
		LexerInterpreter lexer = lex(buildSkipLexer(), "a", "A", "WS");
		lexer.pushMode(0);
		assertEquals(0, lexer._mode);
		assertEquals(0, lexer.popMode());
		lexer.mode(0);
		lexer.skip();
		assertEquals(Lexer.SKIP, lexer.getType());
		lexer.more();
		assertEquals(Lexer.MORE, lexer.getType());
	}

	@Test
	public void recoverFromRecognitionError() {
		LexerInterpreter lexer = lex(buildSkipLexer(), "z", "A", "WS");
		lexer.removeErrorListeners();
		final List<String> msgs = new ArrayList<String>();
		lexer.addErrorListener(new ANTLRErrorListener<Object>() {
			@Override
			public <T> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
										int line, int charPositionInLine, String msg,
										RecognitionException e) {
				msgs.add(msg);
			}
		});
		// 'z' is not viable; lexer recovers by consuming and skipping
		Token t = lexer.nextToken();
		assertEquals(Token.EOF, t.getType());
		assertTrue(msgs.size() >= 1);
		assertTrue(msgs.get(0).contains("token recognition error"));
	}

	@Test
	public void setInputStreamResetAndAccessors() {
		LexerInterpreter lexer = lex(buildSkipLexer(), "a", "A", "WS");
		assertEquals(1, lexer.nextToken().getType());
		lexer.setInputStream(CharStreams.fromString("a a"));
		assertEquals("a", lexer.nextToken().getText());
		lexer.reset();
		assertNotNull(lexer.getAllTokens());

		lexer.setLine(5);
		assertEquals(5, lexer.getLine());
		lexer.setCharPositionInLine(3);
		assertEquals(3, lexer.getCharPositionInLine());
		lexer.setText("override");
		assertEquals("override", lexer.getText());
		lexer.setType(9);
		assertEquals(9, lexer.getType());
		lexer.setChannel(2);
		assertEquals(2, lexer.getChannel());
		CommonToken custom = new CommonToken(1, "c");
		lexer.setToken(custom);
		assertEquals(custom, lexer.getToken());
		lexer.emit(custom);
		assertEquals(custom, lexer.getToken());
		assertTrue(lexer.getCharIndex() >= 0);
		assertNotNull(lexer.getSourceName());
		assertNotNull(lexer.getInputStream());
		assertNotNull(lexer.getTokenFactory());
		lexer.setTokenFactory(CommonTokenFactory.DEFAULT);

		assertEquals("\\n", lexer.getErrorDisplay('\n'));
		assertEquals("\\t", lexer.getErrorDisplay('\t'));
		assertEquals("\\r", lexer.getErrorDisplay('\r'));
		assertEquals("<EOF>", lexer.getErrorDisplay(Token.EOF));
		assertEquals("'a'", lexer.getCharErrorDisplay('a'));
		assertEquals("a\\nb", lexer.getErrorDisplay("a\nb"));
	}

	@Test
	public void recoverRecognitionExceptionConsumes() {
		LexerInterpreter lexer = lex(buildSkipLexer(), "ab", "A", "WS");
		int before = lexer.getInputStream().index();
		lexer.recover(new RecognitionException(lexer, lexer.getInputStream()));
		assertEquals(before + 1, lexer.getInputStream().index());
	}

	@Test
	public void nextTokenRequiresInput() {
		// construct then clear input via subclass trick
		LexerInterpreter lexer = lex(buildSkipLexer(), "a", "A", "WS");
		lexer._input = null;
		try {
			lexer.nextToken();
			fail();
		}
		catch (IllegalStateException expected) {
			// ok
		}
	}

	@Test
	public void typeActionOverridesTokenType() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		atn.addState(tokensStart);
		RuleStartState start = new RuleStartState();
		BasicState mid = new BasicState();
		BasicState act = new BasicState();
		RuleStopState stop = new RuleStopState();
		start.ruleIndex = 0;
		mid.ruleIndex = 0;
		act.ruleIndex = 0;
		stop.ruleIndex = 0;
		start.stopState = stop;
		atn.addState(start);
		atn.addState(mid);
		atn.addState(act);
		atn.addState(stop);
		start.addTransition(new AtomTransition(mid, 'q'));
		mid.addTransition(new ActionTransition(act, 0, 0, false));
		act.addTransition(new EpsilonTransition(stop));
		tokensStart.addTransition(new EpsilonTransition(start));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[] { new LexerTypeAction(7) };
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();

		LexerInterpreter lexer = lex(atn, "q", "Q");
		assertEquals(7, lexer.nextToken().getType());
	}

	@Test
	public void emitEOFFields() {
		LexerInterpreter lexer = lex(buildSkipLexer(), "", "A", "WS");
		Token eof = lexer.nextToken();
		assertEquals(Token.EOF, eof.getType());
		// second call also EOF
		assertEquals(Token.EOF, lexer.nextToken().getType());
	}
}
