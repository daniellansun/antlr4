/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestLexerInterpreter {
	/**
	 * Builds a tiny lexer ATN:
	 * <pre>
	 * A : 'a' ;
	 * B : 'b' ;
	 * </pre>
	 */
	static ATN buildTinyLexerAtn() {
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

		atn.ruleToStartState = new RuleStartState[]{aStart, bStart};
		atn.ruleToStopState = new RuleStopState[]{aStop, bStop};
		atn.ruleToTokenType = new int[]{1, 2};
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}

	private static LexerInterpreter createLexer(String input) {
		Vocabulary vocab = new VocabularyImpl(
			new String[]{null, "'a'", "'b'"},
			new String[]{null, "A", "B"});
		return new LexerInterpreter(
			"T.g4",
			vocab,
			Arrays.asList("A", "B"),
			Collections.singletonList("DEFAULT_TOKEN_CHANNEL"),
			Collections.singletonList("DEFAULT_MODE"),
			buildTinyLexerAtn(),
			CharStreams.fromString(input));
	}

	@Test
	public void lexesSimpleTokens() {
		LexerInterpreter lexer = createLexer("ab");
		Token t1 = lexer.nextToken();
		assertEquals(1, t1.getType());
		assertEquals("a", t1.getText());
		Token t2 = lexer.nextToken();
		assertEquals(2, t2.getType());
		assertEquals("b", t2.getText());
		Token eof = lexer.nextToken();
		assertEquals(Token.EOF, eof.getType());
	}

	@Test
	public void metadataAccessors() {
		LexerInterpreter lexer = createLexer("a");
		assertEquals("T.g4", lexer.getGrammarFileName());
		assertEquals(2, lexer.getRuleNames().length);
		assertEquals("A", lexer.getRuleNames()[0]);
		assertEquals(1, lexer.getModeNames().length);
		assertEquals("DEFAULT_MODE", lexer.getModeNames()[0]);
		assertNotNull(lexer.getChannelNames());
		assertEquals("A", lexer.getVocabulary().getSymbolicName(1));
		assertEquals("B", lexer.getVocabulary().getSymbolicName(2));
		assertNotNull(lexer.getATN());
		assertEquals(ATNType.LEXER, lexer.getATN().grammarType);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsParserAtn() {
		ATN parserAtn = new ATN(ATNType.PARSER, 1);
		new LexerInterpreter(
			"T.g4",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("R"),
			null,
			Collections.singletonList("DEFAULT_MODE"),
			parserAtn,
			CharStreams.fromString(""));
	}

	@Test
	public void tokenNamesDeprecatedStillPopulated() {
		LexerInterpreter lexer = createLexer("a");
		String[] names = lexer.getTokenNames();
		assertNotNull(names);
		assertTrue(names.length >= 1);
	}

	@Test
	public void commonTokenStreamOverLexer() {
		LexerInterpreter lexer = createLexer("ba");
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		assertEquals("ba", tokens.getText());
		assertEquals(2, tokens.LT(1).getType());
		assertEquals(1, tokens.LT(2).getType());
	}

	@Test
	public void lineAndColumnUpdate() {
		LexerInterpreter lexer = createLexer("a");
		Token t = lexer.nextToken();
		assertEquals(1, t.getLine());
		assertEquals(0, t.getCharPositionInLine());
	}

	@Test
	@SuppressWarnings("deprecation")
	public void deprecatedConstructors() {
		ATN atn = buildTinyLexerAtn();
		Vocabulary vocab = new VocabularyImpl(
			new String[]{null, "'a'", "'b'"},
			new String[]{null, "A", "B"});
		// Vocabulary-based deprecated ctor (null channel names)
		LexerInterpreter a = new LexerInterpreter(
			"T.g4",
			vocab,
			Arrays.asList("A", "B"),
			Collections.singletonList("DEFAULT_MODE"),
			atn,
			CharStreams.fromString("a"));
		assertNotNull(a.getVocabulary());
		assertEquals(1, a.nextToken().getType());

		// tokenNames collection deprecated ctor
		LexerInterpreter b = new LexerInterpreter(
			"T.g4",
			Arrays.asList("EOF", "A", "B"),
			Arrays.asList("A", "B"),
			Collections.singletonList("DEFAULT_MODE"),
			buildTinyLexerAtn(),
			CharStreams.fromString("b"));
		assertNotNull(b.getTokenNames());
		assertEquals(2, b.nextToken().getType());
	}
}
