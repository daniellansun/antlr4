/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNType;
import org.antlr.v4.runtime.atn.BasicState;
import org.antlr.v4.runtime.atn.OrderedATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.RuleStartState;
import org.antlr.v4.runtime.atn.RuleStopState;
import org.antlr.v4.runtime.atn.SimulatorState;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestErrorListeners {
	private static DFAState dfaState(int number) {
		DFAState s = new DFAState(
			new org.antlr.v4.runtime.dfa.EmptyEdgeMap<DFAState>(0, 10),
			new org.antlr.v4.runtime.dfa.EmptyEdgeMap<DFAState>(-1, 10),
			new ATNConfigSet());
		s.stateNumber = number;
		return s;
	}

	@Test
	public void baseErrorListenerNoOps() {
		BaseErrorListener listener = new BaseErrorListener();
		StubParser parser = new StubParser();
		listener.syntaxError(parser, parser.getCurrentToken(), 1, 0, "msg", null);
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 0);
		ATNConfigSet configs = new OrderedATNConfigSet();
		listener.reportAmbiguity(parser, dfa, 0, 0, true, null, configs);
		SimulatorState state = new SimulatorState(parser.getContext(), dfaState(0), false, parser.getContext());
		listener.reportAttemptingFullContext(parser, dfa, 0, 0, null, state);
		listener.reportContextSensitivity(parser, dfa, 0, 0, 1, state);
	}

	@Test
	public void consoleErrorListenerFormatsMessage() {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		PrintStream old = System.err;
		System.setErr(new PrintStream(buf));
		try {
			ConsoleErrorListener.INSTANCE.syntaxError(
				null, null, 3, 7, "boom", null);
		}
		finally {
			System.setErr(old);
		}
		assertEquals("line 3:7 boom" + System.lineSeparator(), buf.toString());
	}

	@Test
	public void proxyErrorListenerDispatchesSyntaxError() {
		final List<String> msgs = new ArrayList<String>();
		ANTLRErrorListener<Token> a = new ANTLRErrorListener<Token>() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				msgs.add("a:" + msg);
			}
		};
		ANTLRErrorListener<Token> b = new ANTLRErrorListener<Token>() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				msgs.add("b:" + msg);
			}
		};
		ProxyErrorListener<Token> proxy = new ProxyErrorListener<Token>(Arrays.asList(a, b));
		proxy.syntaxError(null, null, 1, 0, "err", null);
		assertEquals(Arrays.asList("a:err", "b:err"), msgs);
	}

	@Test(expected = NullPointerException.class)
	public void proxyErrorListenerNullDelegatesThrows() {
		new ProxyErrorListener<Token>(null);
	}

	@Test
	public void proxyParserErrorListenerDispatchesParserMethods() {
		final List<String> events = new ArrayList<String>();
		BaseErrorListener parserListener = new BaseErrorListener() {
			@Override
			public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
										boolean exact, BitSet ambigAlts, ATNConfigSet configs) {
				events.add("ambig");
			}

			@Override
			public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
													BitSet conflictingAlts, SimulatorState conflictState) {
				events.add("full");
			}

			@Override
			public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex,
												 int prediction, SimulatorState acceptState) {
				events.add("sens");
			}
		};
		ANTLRErrorListener<Token> nonParser = new ANTLRErrorListener<Token>() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				events.add("syntax");
			}
		};

		ProxyParserErrorListener proxy = new ProxyParserErrorListener(
			Arrays.asList(parserListener, nonParser));
		StubParser parser = new StubParser();
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 0);
		ATNConfigSet configs = new OrderedATNConfigSet();
		SimulatorState sim = new SimulatorState(parser.getContext(), dfaState(0), false, null);

		proxy.reportAmbiguity(parser, dfa, 0, 0, true, null, configs);
		proxy.reportAttemptingFullContext(parser, dfa, 0, 0, null, sim);
		proxy.reportContextSensitivity(parser, dfa, 0, 0, 1, sim);
		proxy.syntaxError(parser, parser.getCurrentToken(), 1, 0, "s", null);

		assertEquals(Arrays.asList("ambig", "full", "sens", "syntax"), events);
	}

	@Test
	public void diagnosticErrorListenerReportAmbiguity() {
		StubParser parser = new StubParser();
		final List<String> messages = new ArrayList<String>();
		parser.removeErrorListeners();
		parser.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				messages.add(msg);
			}
		});

		DiagnosticErrorListener diag = new DiagnosticErrorListener(false);
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 0);
		ATNConfigSet configs = new OrderedATNConfigSet();
		BasicState state = new BasicState();
		parser.getATN().addState(state);
		configs.add(ATNConfig.create(state, 1, PredictionContext.EMPTY_FULL));
		configs.add(ATNConfig.create(state, 2, PredictionContext.EMPTY_FULL));

		diag.reportAmbiguity(parser, dfa, 0, 0, true, null, configs);
		assertEquals(1, messages.size());
		assertTrue(messages.get(0).contains("reportAmbiguity"));
		assertTrue(messages.get(0).contains("a"));
	}

	@Test
	public void diagnosticExactOnlySkipsInexact() {
		StubParser parser = new StubParser();
		final List<String> messages = new ArrayList<String>();
		parser.removeErrorListeners();
		parser.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				messages.add(msg);
			}
		});
		DiagnosticErrorListener diag = new DiagnosticErrorListener(true);
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 0);
		diag.reportAmbiguity(parser, dfa, 0, 0, false, null, new OrderedATNConfigSet());
		assertTrue(messages.isEmpty());
	}

	@Test
	public void diagnosticAttemptingFullContextAndSensitivity() {
		StubParser parser = new StubParser();
		final List<String> messages = new ArrayList<String>();
		parser.removeErrorListeners();
		parser.addErrorListener(new BaseErrorListener() {
			@Override
			public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T offendingSymbol,
													  int line, int charPositionInLine, String msg,
													  RecognitionException e) {
				messages.add(msg);
			}
		});
		DiagnosticErrorListener diag = new DiagnosticErrorListener();
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 0);
		SimulatorState sim = new SimulatorState(parser.getContext(), dfaState(0), false, null);
		diag.reportAttemptingFullContext(parser, dfa, 0, 0, null, sim);
		diag.reportContextSensitivity(parser, dfa, 0, 0, 1, sim);
		assertEquals(2, messages.size());
		assertTrue(messages.get(0).contains("reportAttemptingFullContext"));
		assertTrue(messages.get(1).contains("reportContextSensitivity"));
	}

	@Test
	public void diagnosticDecisionDescriptionWithRuleName() {
		StubParser parser = new StubParser();
		DiagnosticErrorListener diag = new DiagnosticErrorListener();
		DFA dfa = new DFA(parser.getATN().ruleToStartState[0], 3);
		String desc = diag.getDecisionDescription(parser, dfa);
		assertTrue(desc.contains("3"));
		assertTrue(desc.contains("r"));
	}

	static final class StubParser extends Parser {
		private final ATN atn;
		private final CommonToken current = new CommonToken(1, "a");

		StubParser() {
			super(null);
			atn = new ATN(ATNType.PARSER, 5);
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

		@Override public String[] getTokenNames() { return new String[]{"NONE", "A"}; }
		@Override public String[] getRuleNames() { return new String[]{"r"}; }
		@Override public String getGrammarFileName() { return "T.g4"; }
		@Override public ATN getATN() { return atn; }
		@Override public Token getCurrentToken() { return current; }
		@Override public Vocabulary getVocabulary() {
			return VocabularyImpl.fromTokenNames(getTokenNames());
		}
	}
}
