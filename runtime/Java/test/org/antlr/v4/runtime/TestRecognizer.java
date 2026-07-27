/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestRecognizer {

	static class StubRecognizer extends Recognizer<Token, ATNSimulator> {
		private final String[] tokenNames;
		private final String[] ruleNames;

		StubRecognizer(String[] tokenNames, String[] ruleNames) {
			this.tokenNames = tokenNames;
			this.ruleNames = ruleNames;
		}

		@Override
		@SuppressWarnings("deprecation")
		public String[] getTokenNames() {
			return tokenNames;
		}

		@Override
		public String[] getRuleNames() {
			return ruleNames;
		}

		@Override
		public String getGrammarFileName() {
			return "Stub.g4";
		}

		@Override
		public IntStream getInputStream() {
			return null;
		}
	}

	static class ReWithToken extends RecognitionException {
		ReWithToken(Recognizer<Token, ?> r, Token t) {
			super(r, null, null);
			setOffendingToken(r, t);
		}
	}

	private static StubRecognizer withInterp(String[] tokenNames, String[] ruleNames, int maxToken) {
		StubRecognizer r = new StubRecognizer(tokenNames, ruleNames);
		ATN atn = new ATN(ATNType.LEXER, maxToken);
		BasicState s = new BasicState();
		atn.addState(s);
		r.setInterpreter(new LexerATNSimulator(atn));
		return r;
	}

	@Test
	public void vocabularyAndMaps() {
		StubRecognizer r = withInterp(
			new String[]{null, "'x'", "ID"},
			new String[]{"s", "expr"},
			2);
		Vocabulary v = r.getVocabulary();
		assertNotNull(v);

		Map<String, Integer> tokenMap = r.getTokenTypeMap();
		assertEquals(Integer.valueOf(Token.EOF), tokenMap.get("EOF"));
		// second call should still return a consistent map (may or may not be cached
		// depending on Vocabulary identity from getVocabulary())
		Map<String, Integer> tokenMap2 = r.getTokenTypeMap();
		assertEquals(tokenMap.get("EOF"), tokenMap2.get("EOF"));

		Map<String, Integer> ruleMap = r.getRuleIndexMap();
		assertEquals(Integer.valueOf(0), ruleMap.get("s"));
		assertEquals(Integer.valueOf(1), ruleMap.get("expr"));
		// ruleNames array is stable so cache hits
		assertSame(ruleMap, r.getRuleIndexMap());

		assertEquals(Token.INVALID_TYPE, r.getTokenType("NOPE"));
		// known symbolic/literal if present in map
		if (tokenMap.containsKey("ID")) {
			assertEquals(2, r.getTokenType("ID"));
		}
		if (tokenMap.containsKey("'x'")) {
			assertEquals(1, r.getTokenType("'x'"));
		}
	}

	@Test
	public void ruleIndexMapNullRuleNamesThrows() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, null, 1);
		try {
			r.getRuleIndexMap();
			fail();
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains("rule names"));
		}
	}

	@Test
	public void serializedAtnUnsupported() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, new String[]{"r"}, 1);
		try {
			r.getSerializedATN();
			fail();
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains("serialized ATN"));
		}
	}

	@Test
	public void stateInterpreterAndAtn() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, new String[]{"r"}, 1);
		assertEquals(-1, r.getState());
		r.setState(42);
		assertEquals(42, r.getState());
		assertNotNull(r.getInterpreter());
		assertNotNull(r.getATN());
		assertSame(r.getInterpreter().atn, r.getATN());

		ATN atn2 = new ATN(ATNType.LEXER, 1);
		atn2.addState(new BasicState());
		ATNSimulator next = new LexerATNSimulator(atn2);
		r.setInterpreter(next);
		assertSame(next, r.getInterpreter());
		assertSame(atn2, r.getATN());
		assertNull(r.getParseInfo());
	}

	@Test
	public void errorListeners() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, new String[]{"r"}, 1);
		assertFalse(r.getErrorListeners().isEmpty());

		BaseErrorListener custom = new BaseErrorListener();
		// BaseErrorListener is ParserErrorListener; Recognizer expects ANTLRErrorListener<? super Symbol>
		// Proxy/add uses ANTLRErrorListener - ParserErrorListener extends it for Token
		r.addErrorListener(custom);
		assertTrue(r.getErrorListeners().contains(custom));
		r.removeErrorListener(custom);
		assertFalse(r.getErrorListeners().contains(custom));

		r.removeErrorListeners();
		assertTrue(r.getErrorListeners().isEmpty());

		r.addErrorListener(ConsoleErrorListener.INSTANCE);
		assertNotNull(r.getErrorListenerDispatch());

		try {
			r.addErrorListener(null);
			fail();
		} catch (NullPointerException e) {
			// expected
		} catch (IllegalArgumentException e) {
			// also acceptable
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	public void errorHeaderAndTokenDisplay() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, new String[]{"r"}, 1);

		CommonToken tok = new CommonToken(1, "hi");
		tok.setLine(3);
		tok.setCharPositionInLine(7);
		RecognitionException re = new ReWithToken(r, tok);
		assertEquals("line 3:7", r.getErrorHeader(re));

		assertEquals("<no token>", r.getTokenErrorDisplay(null));
		assertEquals("'hi'", r.getTokenErrorDisplay(new CommonToken(1, "hi")));

		CommonToken eof = new CommonToken(Token.EOF);
		eof.setText(null);
		assertEquals("'<EOF>'", r.getTokenErrorDisplay(eof));

		CommonToken noText = new CommonToken(5);
		noText.setText(null);
		assertEquals("'<5>'", r.getTokenErrorDisplay(noText));

		CommonToken ws = new CommonToken(1, "a\nb\rc\td");
		String display = r.getTokenErrorDisplay(ws);
		assertTrue(display.contains("\\n"));
		assertTrue(display.contains("\\r"));
		assertTrue(display.contains("\\t"));
	}

	@Test
	public void sempredPrecpredActionDefaults() {
		StubRecognizer r = withInterp(new String[]{null, "A"}, new String[]{"r"}, 1);
		assertTrue(r.sempred(null, 0, 0));
		assertTrue(r.precpred(null, 0));
		r.action(null, 0, 0);
		assertEquals(Recognizer.EOF, -1);
		assertEquals("Stub.g4", r.getGrammarFileName());
		assertNull(r.getInputStream());
	}
}
