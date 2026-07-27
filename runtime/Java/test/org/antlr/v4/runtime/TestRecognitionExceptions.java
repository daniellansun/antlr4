/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.OrderedATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.TokensStartState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TestRecognitionExceptions {
	@Test
	public void recognitionExceptionLexerCtor() {
		CharStream input = CharStreams.fromString("x");
		Lexer lexer = new LexerInterpreter(
			"L",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("A"),
			null,
			Collections.singletonList("DEFAULT_MODE"),
			buildTinyLexerAtn(),
			input);
		RecognitionException e = new RecognitionException(lexer, input);
		assertSame(lexer, e.getRecognizer());
		assertSame(input, e.getInputStream());
		assertNull(e.getContext());
		assertEquals(-1, e.getOffendingState());
	}

	@Test
	public void recognitionExceptionWithMessage() {
		ParserRuleContext ctx = new ParserRuleContext();
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(new CommonToken(1, "a")));
		tokens.fill();
		RecognitionException e = new RecognitionException("boom", null, tokens, ctx);
		assertEquals("boom", e.getMessage());
		assertSame(tokens, e.getInputStream());
		assertSame(ctx, e.getContext());
		assertNull(e.getRecognizer());
		assertNull(e.getExpectedTokens());
	}

	@Test
	public void noViableAltExceptionFields() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		ATNConfigSet configs = new OrderedATNConfigSet();
		Token start = parser.getCurrentToken();
		NoViableAltException e = new NoViableAltException(
			parser, parser.getInputStream(), start, start, configs, parser.getContext());
		assertSame(start, e.getStartToken());
		assertSame(configs, e.getDeadEndConfigs());
		assertSame(start, e.getOffendingToken());
	}

	@Test
	public void noViableAltExceptionSimpleCtor() {
		StubParser parser = new StubParser(new CommonToken(1, "z"));
		NoViableAltException e = new NoViableAltException(parser);
		assertSame(parser.getCurrentToken(), e.getStartToken());
		assertNull(e.getDeadEndConfigs());
	}

	@Test
	public void inputMismatchException() {
		StubParser parser = new StubParser(new CommonToken(2, "x"));
		InputMismatchException e = new InputMismatchException(parser);
		assertSame(parser.getCurrentToken(), e.getOffendingToken());
		assertSame(parser.getContext(), e.getContext());

		ParserRuleContext other = new ParserRuleContext(null, 3);
		InputMismatchException e2 = new InputMismatchException(parser, 9, other);
		assertEquals(9, e2.getOffendingState());
		assertSame(other, e2.getContext());
	}

	@Test
	public void lexerNoViableAltException() {
		CharStream input = CharStreams.fromString("q");
		LexerNoViableAltException e = new LexerNoViableAltException(null, input, 0, null);
		assertEquals(0, e.getStartIndex());
		assertNull(e.getDeadEndConfigs());
		assertSame(input, e.getInputStream());
		assertTrue(e.toString().contains("LexerNoViableAltException"));
		assertTrue(e.toString().contains("q"));
	}

	@Test
	public void lexerNoViableAltWithConfigs() {
		CharStream input = CharStreams.fromString("z");
		ATNConfigSet configs = new OrderedATNConfigSet();
		LexerNoViableAltException e = new LexerNoViableAltException(null, input, 0, configs);
		assertSame(configs, e.getDeadEndConfigs());
	}

	@Test
	public void failedPredicateException() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		// Put a predicate transition on the current ATN state
		ATN atn = parser.getATN();
		BasicState state = new BasicState();
		state.ruleIndex = 0;
		atn.addState(state);
		BasicState target = new BasicState();
		atn.addState(target);
		state.addTransition(new PredicateTransition(target, 0, 1, false));
		parser.setState(state.stateNumber);

		FailedPredicateException e = new FailedPredicateException(parser, "x>0", "custom msg");
		assertEquals("custom msg", e.getMessage());
		assertEquals("x>0", e.getPredicate());
		assertEquals(0, e.getRuleIndex());
		assertEquals(1, e.getPredIndex());
		assertSame(parser.getCurrentToken(), e.getOffendingToken());
	}

	@Test
	public void failedPredicateDefaultMessage() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		ATN atn = parser.getATN();
		BasicState state = new BasicState();
		atn.addState(state);
		BasicState target = new BasicState();
		atn.addState(target);
		// non-PredicateTransition AbstractPredicate -> uses ruleIndex 0
		state.addTransition(new PredicateTransition(target, 2, 3, false));
		parser.setState(state.stateNumber);

		FailedPredicateException e = new FailedPredicateException(parser, "pred");
		assertTrue(e.getMessage().contains("pred"));
		assertEquals(2, e.getRuleIndex());
		assertEquals(3, e.getPredIndex());
	}

	@Test
	public void failedPredicateNoArgs() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		ATN atn = parser.getATN();
		BasicState state = new BasicState();
		atn.addState(state);
		BasicState target = new BasicState();
		atn.addState(target);
		state.addTransition(new PredicateTransition(target, 0, 0, false));
		parser.setState(state.stateNumber);

		FailedPredicateException e = new FailedPredicateException(parser);
		assertNull(e.getPredicate());
		assertNotNull(e.getMessage());
	}

	static ATN buildTinyLexerAtn() {
		ATN atn = new ATN(ATNType.LEXER, Character.MAX_CODE_POINT);
		TokensStartState tokensStart = new TokensStartState();
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
		tokensStart.addTransition(new EpsilonTransition(aStart));
		atn.ruleToStartState = new RuleStartState[]{aStart};
		atn.ruleToStopState = new RuleStopState[]{aStop};
		atn.ruleToTokenType = new int[]{1};
		atn.defineMode("DEFAULT_MODE", tokensStart);
		return atn;
	}

	/**
	 * Minimal parser for exception construction tests.
	 */
	static final class StubParser extends Parser {
		private final String[] ruleNames = {"r"};
		private final ATN atn;
		private final CommonToken current;

		StubParser(CommonToken current) {
			super(null);
			this.current = current;
			this.atn = new ATN(ATNType.PARSER, 10);
			RuleStartState start = new RuleStartState();
			start.ruleIndex = 0;
			atn.addState(start);
			RuleStopState stop = new RuleStopState();
			stop.ruleIndex = 0;
			atn.addState(stop);
			start.stopState = stop;
			atn.ruleToStartState = new RuleStartState[]{start};
			atn.ruleToStopState = new RuleStopState[]{stop};
			atn.clearDFA();
			_interp = new ParserATNSimulator(this, atn);
			CommonTokenStream input = new CommonTokenStream(new MockTokenSource(current));
			input.fill();
			setInputStream(input);
			_ctx = new InterpreterRuleContext(null, -1, 0);
			setState(start.stateNumber);
		}

		@Override
		public String[] getTokenNames() {
			return new String[]{"<INVALID>", "A"};
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
		public ATN getATN() {
			return atn;
		}

		@Override
		public Token getCurrentToken() {
			return current;
		}

		@Override
		public Vocabulary getVocabulary() {
			return VocabularyImpl.fromTokenNames(getTokenNames());
		}
	}
}
