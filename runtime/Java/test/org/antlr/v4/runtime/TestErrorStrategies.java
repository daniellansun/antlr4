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
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestErrorStrategies {
	@Test
	public void defaultResetAndErrorRecoveryMode() {
		DefaultErrorStrategy strategy = new DefaultErrorStrategy();
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		assertFalse(strategy.inErrorRecoveryMode(parser));
		strategy.reportMatch(parser);
		assertFalse(strategy.inErrorRecoveryMode(parser));
		strategy.reset(parser);
		assertFalse(strategy.inErrorRecoveryMode(parser));
	}

	@Test
	public void defaultReportErrorDispatches() {
		final List<String> messages = new ArrayList<String>();
		StubParser parser = new StubParser(new CommonToken(1, "x"));
		parser.removeErrorListeners();
		parser.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				messages.add(msg);
			}
		});

		DefaultErrorStrategy strategy = new DefaultErrorStrategy();
		NoViableAltException nvae = new NoViableAltException(parser);
		strategy.reportError(parser, nvae);
		assertEquals(1, messages.size());
		assertTrue(messages.get(0).contains("no viable alternative"));

		// second report while recovering is suppressed
		strategy.reportError(parser, new InputMismatchException(parser));
		assertEquals(1, messages.size());

		strategy.reportMatch(parser); // end recovery
		strategy.reportError(parser, new InputMismatchException(parser));
		assertEquals(2, messages.size());
		assertTrue(messages.get(1).contains("mismatched input"));
	}

	@Test
	public void bailRecoverThrowsParseCancellation() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		BailErrorStrategy bail = new BailErrorStrategy();
		RecognitionException cause = new InputMismatchException(parser);
		try {
			bail.recover(parser, cause);
			fail("expected ParseCancellationException");
		}
		catch (ParseCancellationException e) {
			assertSame(cause, e.getCause());
			assertSame(cause, parser.getContext().exception);
		}
	}

	@Test
	public void bailRecoverInlineThrows() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		BailErrorStrategy bail = new BailErrorStrategy();
		try {
			bail.recoverInline(parser);
			fail("expected ParseCancellationException");
		}
		catch (ParseCancellationException e) {
			assertTrue(e.getCause() instanceof InputMismatchException);
			assertSame(e.getCause(), parser.getContext().exception);
		}
	}

	@Test
	public void bailSyncIsNoOp() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		BailErrorStrategy bail = new BailErrorStrategy();
		bail.sync(parser); // should not throw
	}

	@Test
	public void bailPropagatesExceptionToParentContexts() {
		StubParser parser = new StubParser(new CommonToken(1, "a"));
		ParserRuleContext parent = new ParserRuleContext(null, -1);
		ParserRuleContext child = new ParserRuleContext(parent, 1);
		parser._ctx = child;
		BailErrorStrategy bail = new BailErrorStrategy();
		RecognitionException cause = new InputMismatchException(parser);
		try {
			bail.recover(parser, cause);
			fail();
		}
		catch (ParseCancellationException e) {
			assertSame(cause, child.exception);
			assertSame(cause, parent.exception);
		}
	}

	static final class StubParser extends Parser {
		private final ATN atn;
		private final CommonToken current;

		StubParser(CommonToken current) {
			super(null);
			this.current = current;
			atn = new ATN(ATNType.PARSER, 10);
			RuleStartState start = new RuleStartState();
			start.ruleIndex = 0;
			atn.addState(start);
			RuleStopState stop = new RuleStopState();
			stop.ruleIndex = 0;
			atn.addState(stop);
			start.stopState = stop;
			// simple atom edge so nextTokens works for recover paths if needed
			BasicState mid = new BasicState();
			mid.ruleIndex = 0;
			atn.addState(mid);
			start.addTransition(new AtomTransition(mid, 1));
			mid.addTransition(new AtomTransition(stop, Token.EOF));
			atn.ruleToStartState = new RuleStartState[]{start};
			atn.ruleToStopState = new RuleStopState[]{stop};
			atn.clearDFA();
			_interp = new ParserATNSimulator(this, atn);
			CommonTokenStream input = new CommonTokenStream(new MockTokenSource(current, new CommonToken(Token.EOF)));
			input.fill();
			setInputStream(input);
			_ctx = new InterpreterRuleContext(null, -1, 0);
			setState(start.stateNumber);
		}

		@Override public String[] getTokenNames() { return new String[]{"NONE", "A"}; }
		@Override public String[] getRuleNames() { return new String[]{"r"}; }
		@Override public String getGrammarFileName() { return "S.g4"; }
		@Override public ATN getATN() { return atn; }
		@Override public Token getCurrentToken() { return current; }
		@Override public Vocabulary getVocabulary() {
			return VocabularyImpl.fromTokenNames(getTokenNames());
		}
	}
}
