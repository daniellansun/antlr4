/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.AtomTransition;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.EpsilonTransition;
import org.antlr.v4.runtime.atn.PrecedencePredicateTransition;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.Transition;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Tuple;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Minimal hand-built parser ATN coverage for {@link ParserInterpreter}.
 * Grammar shape:
 * <pre>
 * r : A ;
 * A is token type 1
 * </pre>
 */
public class TestParserInterpreterMinimal {
	static ATN buildParserAtn() {
		ATN atn = new ATN(ATNType.PARSER, 2);

		RuleStartState rStart = new RuleStartState();
		rStart.ruleIndex = 0;
		atn.addState(rStart);
		RuleStopState rStop = new RuleStopState();
		rStop.ruleIndex = 0;
		atn.addState(rStop);
		rStart.stopState = rStop;

		// match token type 1 then stop
		BasicState mid = new BasicState();
		mid.ruleIndex = 0;
		atn.addState(mid);
		rStart.addTransition(new AtomTransition(mid, 1));
		mid.addTransition(new EpsilonTransition(rStop));

		atn.ruleToStartState = new RuleStartState[]{rStart};
		atn.ruleToStopState = new RuleStopState[]{rStop};
		atn.clearDFA();
		return atn;
	}

	@Test
	public void parseSingleTokenRule() {
		CommonToken a = new CommonToken(1, "a");
		a.setTokenIndex(0);
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(a));
		tokens.fill();

		Vocabulary vocab = new VocabularyImpl(new String[]{null, "'a'"}, new String[]{null, "A"});
		ParserInterpreter parser = new ParserInterpreter(
			"P.g4",
			vocab,
			Collections.singletonList("r"),
			buildParserAtn(),
			tokens);

		assertEquals("P.g4", parser.getGrammarFileName());
		assertEquals(1, parser.getRuleNames().length);
		assertEquals("r", parser.getRuleNames()[0]);
		assertNotNull(parser.getATN());
		assertEquals(ATNType.PARSER, parser.getATN().grammarType);

		ParserRuleContext tree = parser.parse(0);
		assertNotNull(tree);
		assertEquals(0, tree.getRuleIndex());
		assertEquals("a", tree.getText());
	}

	@Test
	public void vocabularyAndTokenNames() {
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(new CommonToken(1, "a")));
		Vocabulary vocab = new VocabularyImpl(new String[]{null, "'a'"}, new String[]{null, "A"});
		ParserInterpreter parser = new ParserInterpreter(
			"P.g4", vocab, Collections.singletonList("r"), buildParserAtn(), tokens);
		assertEquals("A", parser.getVocabulary().getSymbolicName(1));
		assertNotNull(parser.getTokenNames());
	}

	@Test
	public void errorStrategyDefaultIsDefaultErrorStrategy() {
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(new CommonToken(1, "a")));
		ParserInterpreter parser = new ParserInterpreter(
			"P.g4",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("r"),
			buildParserAtn(),
			tokens);
		assertTrue(parser.getErrorHandler() instanceof DefaultErrorStrategy);
		parser.setErrorHandler(new BailErrorStrategy());
		assertTrue(parser.getErrorHandler() instanceof BailErrorStrategy);
	}

	@Test
	public void precedencePredicateFailureAndUnknownTransition() {
		ParserInterpreter parser = interpreter();
		BasicState src = new BasicState();
		BasicState dst = new BasicState();
		parser.getATN().addState(src);
		parser.getATN().addState(dst);
		src.addTransition(new PrecedencePredicateTransition(dst, 0));
		parser.setState(src.stateNumber);
		// seeded precedence is 0; a higher value makes precpred(_ctx, 0) fail
		parser._precedenceStack.push(10);
		try {
			parser.visitState(src);
			fail("expected FailedPredicateException");
		}
		catch (FailedPredicateException expected) {
			assertTrue(expected.getMessage().contains("precpred"));
		}

		BasicState bogusSrc = new BasicState();
		bogusSrc.addTransition(new BogusTransition(dst));
		try {
			parser.visitState(bogusSrc);
			fail("expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("Unrecognized"));
		}
	}

	@Test
	public void recoverFromNoViableAltAddsErrorNode() {
		ParserInterpreter parser = interpreter();
		parser.removeErrorListeners();
		parser.setErrorHandler(new DefaultErrorStrategy() {
			@Override
			public void recover(Parser recognizer, RecognitionException e) {
				// consume nothing
			}
		});
		parser.setContext(new InterpreterRuleContext(null, -1, 0));
		TokenSource source = parser.getInputStream().getTokenSource();
		CommonToken tok = new CommonToken(Tuple.create(source, (CharStream) null),
			1, Token.DEFAULT_CHANNEL, -1, -1);
		tok.setText("bad");
		tok.setLine(2);
		tok.setCharPositionInLine(4);
		NoViableAltException error = new NoViableAltException(
			parser, parser.getInputStream(), tok, tok, null, parser.getContext());
		int index = parser.getInputStream().index();
		parser.recover(error);
		assertEquals(index, parser.getInputStream().index());
		assertTrue(parser.getContext().getChildCount() > 0);
	}

	private static ParserInterpreter interpreter() {
		CommonToken token = new CommonToken(1, "a");
		token.setTokenIndex(0);
		CommonTokenStream tokens = new CommonTokenStream(new MockTokenSource(token));
		tokens.fill();
		return new ParserInterpreter(
			"P.g4",
			VocabularyImpl.EMPTY_VOCABULARY,
			Collections.singletonList("r"),
			buildParserAtn(),
			tokens);
	}

	private static final class BogusTransition extends Transition {
		BogusTransition(ATNState target) {
			super(target);
		}

		@Override
		public int getSerializationType() {
			return 99;
		}

		@Override
		public boolean matches(int symbol, int minVocabSymbol, int maxVocabSymbol) {
			return false;
		}

		@Override
		public IntervalSet label() {
			return null;
		}
	}
}
