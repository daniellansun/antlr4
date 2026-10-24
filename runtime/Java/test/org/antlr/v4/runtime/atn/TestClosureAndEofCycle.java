/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.InterpreterRuleContext;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.AcceptStateInfo;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Closure cycles and non-accept EOF DFA edges must fail instead of spinning.
 */
public class TestClosureAndEofCycle {

	@Test
	public void lexerEpsilonCycleDoesNotOverflow() {
		ATN atn = new ATN(ATNType.LEXER, Lexer.MAX_CHAR_VALUE);
		BasicState a = new BasicState();
		BasicState b = new BasicState();
		a.ruleIndex = 0;
		b.ruleIndex = 0;
		atn.addState(a);
		atn.addState(b);
		a.addTransition(new EpsilonTransition(b));
		b.addTransition(new EpsilonTransition(a));
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		ATNConfigSet configs = new OrderedATNConfigSet();
		ATNConfig start = ATNConfig.create(a, 1, PredictionContext.EMPTY_FULL);
		boolean accepted = sim.closure(CharStreams.fromString(""), start, configs, false, false, false);
		assertFalse(accepted);
	}

	@Test(timeout = 2000)
	public void execDfaEofSelfEdgeThrowsInsteadOfSpinning() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		decision.ruleIndex = 0;
		atn.addState(decision);

		List<Token> list = new ArrayList<Token>();
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(0);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(new String[] { null }, new String[] { null });
		ParserInterpreter parser = new ParserInterpreter("P.g4", vocab,
			Collections.<String>emptyList(), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		parser.removeErrorListeners();

		DFA dfa = new DFA(decision, 0);
		ATNConfigSet configs = new ATNConfigSet();
		DFAState state = new DFAState(dfa, configs);
		// Outermost states keep stateNumber -1 when the global-context DFA
		// is off. The cycle guard must use identity, not the number.
		dfa.states.put(state, state);
		dfa.s0.set(state);
		state.setTarget(Token.EOF, state);

		final ExposedSim sim = new ExposedSim(parser, atn);
		final DFA walking = dfa;
		final TokenStream input = parser.getInputStream();
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		final SimulatorState start = new SimulatorState(ctx, state, false, ctx);
		try {
			sim.walk(walking, input, start);
			fail("EOF self-edge must not predict an alternative");
		}
		catch (NoViableAltException expected) {
			assertEquals(Token.EOF, input.LA(1));
			assertEquals(-1, state.stateNumber);
		}
	}

	@Test(timeout = 2000)
	public void execAtnEofSelfEdgeThrowsInsteadOfSpinning() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		decision.ruleIndex = 0;
		atn.addState(decision);

		List<Token> list = new ArrayList<Token>();
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(0);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(new String[] { null }, new String[] { null });
		ParserInterpreter parser = new ParserInterpreter("P.g4", vocab,
			Collections.<String>emptyList(), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		parser.removeErrorListeners();

		DFA dfa = new DFA(decision, 0);
		DFAState state = new DFAState(dfa, new ATNConfigSet());
		assertEquals(-1, state.stateNumber);
		state.setTarget(Token.EOF, state);

		ExposedSim sim = new ExposedSim(parser, atn);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		SimulatorState start = new SimulatorState(ctx, state, false, ctx);
		try {
			sim.walkAtn(dfa, parser.getInputStream(), start);
			fail("ATN EOF self-edge must not predict an alternative");
		}
		catch (NoViableAltException expected) {
			assertEquals(Token.EOF, parser.getInputStream().LA(1));
			assertEquals(0, parser.getInputStream().index());
		}
	}

	/**
	 * Same DFA state, new remaining outer context, is progress. A guard that
	 * keys only on the state reports no-viable-alt on the second visit.
	 */
	@Test(timeout = 2000)
	public void fullContextEofRevisitWithNewContextStillAccepts() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		decision.ruleIndex = 0;
		RuleStartState ruleStart = new RuleStartState();
		BasicState follow = new BasicState();
		BasicState invoke = new BasicState();
		atn.addState(decision);
		atn.addState(ruleStart);
		atn.addState(follow);
		atn.addState(invoke);
		invoke.addTransition(new RuleTransition(ruleStart, 0, 0, follow));

		List<Token> list = new ArrayList<Token>();
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(0);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(new String[] { null }, new String[] { null });
		ParserInterpreter parser = new ParserInterpreter("P.g4", vocab,
			Collections.<String>emptyList(), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		parser.removeErrorListeners();

		// Context-edge range is fixed from the ATN size at DFA construction.
		DFA dfa = new DFA(decision, 0);
		DFAState startState = new DFAState(dfa, new ATNConfigSet());
		DFAState revisited = new DFAState(dfa, new ATNConfigSet());
		DFAState afterPop = new DFAState(dfa, new ATNConfigSet());
		DFAState accept = new DFAState(dfa, new ATNConfigSet());
		accept.setAcceptState(new AcceptStateInfo(1));

		revisited.setContextSensitive(atn);
		revisited.setContextSymbol(Token.EOF);
		revisited.setContextTarget(follow.stateNumber, afterPop);
		revisited.setContextTarget(PredictionContext.EMPTY_FULL_STATE_KEY, accept);
		startState.setTarget(Token.EOF, revisited);
		afterPop.setTarget(Token.EOF, revisited);

		ParserRuleContext parent = new ParserRuleContext();
		ParserRuleContext child = new ParserRuleContext(parent, invoke.stateNumber);
		ExposedSim sim = new ExposedSim(parser, atn);
		SimulatorState start = new SimulatorState(child, startState, true, child);
		int alt = sim.walk(dfa, parser.getInputStream(), start);
		assertEquals(1, alt);
		assertEquals(Token.EOF, parser.getInputStream().LA(1));
		assertEquals(0, parser.getInputStream().index());
	}

	/**
	 * {@code addDFAState} does not intern an outermost set, so each EOF reach
	 * is a new object. Equal configs must still stop the walk.
	 */
	@Test(timeout = 2000)
	public void execAtnFreshOutermostStatesStopOnEqualConfigs() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		decision.ruleIndex = 0;
		atn.addState(decision);

		List<Token> list = new ArrayList<Token>();
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(0);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(new String[] { null }, new String[] { null });
		ParserInterpreter parser = new ParserInterpreter("P.g4", vocab,
			Collections.<String>emptyList(), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		parser.removeErrorListeners();

		DFA dfa = new DFA(decision, 0);
		final ATNConfigSet shared = new ATNConfigSet();
		DFAState start = new DFAState(dfa, shared);
		FreshReachSim sim = new FreshReachSim(parser, atn, shared);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		try {
			sim.walkAtn(dfa, parser.getInputStream(), new SimulatorState(ctx, start, false, ctx));
			fail("a fresh outermost state on every EOF reach must not spin");
		}
		catch (NoViableAltException expected) {
			assertEquals(Token.EOF, parser.getInputStream().LA(1));
		}
	}

	@Test
	public void fullContextDfaEdgeNotifiesSubclassHook() {
		ATN atn = new ATN(ATNType.PARSER, 1);
		BasicBlockStartState decision = new BasicBlockStartState();
		decision.ruleIndex = 0;
		atn.addState(decision);

		List<Token> list = new ArrayList<Token>();
		CommonToken eof = new CommonToken(Token.EOF, "<EOF>");
		eof.setTokenIndex(0);
		list.add(eof);
		Vocabulary vocab = new VocabularyImpl(new String[] { null }, new String[] { null });
		ParserInterpreter parser = new ParserInterpreter("P.g4", vocab,
			Collections.<String>emptyList(), atn,
			new CommonTokenStream(new ListTokenSource(list)));
		parser.removeErrorListeners();

		DFA dfa = new DFA(decision, 0);
		DFAState start = new DFAState(dfa, new ATNConfigSet());
		DFAState accept = new DFAState(dfa, new ATNConfigSet());
		accept.setAcceptState(new AcceptStateInfo(1));
		start.setTarget(Token.EOF, accept);

		ObservingSim sim = new ObservingSim(parser, atn);
		InterpreterRuleContext ctx = new InterpreterRuleContext(null, -1, 0);
		int alt = sim.walk(dfa, parser.getInputStream(), new SimulatorState(ctx, start, true, ctx));
		assertEquals(1, alt);
		assertTrue(sim.observed >= 1);
	}

	@Test(timeout = 2000)
	public void lexerCycleStopsBeforeWalkingTheWholeAtn() {
		ATN atn = new ATN(ATNType.LEXER, Lexer.MAX_CHAR_VALUE);
		for (int i = 0; i < 200; i++) {
			BasicState unused = new BasicState();
			unused.ruleIndex = 0;
			atn.addState(unused);
		}
		BasicState a = new BasicState();
		BasicState b = new BasicState();
		a.ruleIndex = 0;
		b.ruleIndex = 0;
		atn.addState(a);
		atn.addState(b);
		a.addTransition(new EpsilonTransition(b));
		b.addTransition(new EpsilonTransition(a));
		CountingLexerSim sim = new CountingLexerSim(atn);
		ATNConfig start = ATNConfig.create(a, 1, PredictionContext.EMPTY_FULL);
		boolean accepted = sim.closure(CharStreams.fromString(""), start, new OrderedATNConfigSet(), false, false, false);
		assertFalse(accepted);
		assertTrue(sim.calls < 100);
		// A second root call drops the busy set left by the cycle.
		int afterFirst = sim.calls;
		accepted = sim.closure(CharStreams.fromString(""), start, new OrderedATNConfigSet(), false, false, false);
		assertFalse(accepted);
		assertTrue(sim.calls > afterFirst);
	}

	@Test
	public void contextSymbolPublishKeepsEarlierBits() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		BasicState start = new BasicState();
		atn.addState(start);
		DFA dfa = new DFA(start, 0);
		DFAState state = new DFAState(dfa, new ATNConfigSet());
		state.setContextSensitive(atn);
		state.setContextSymbol(1);
		state.setContextSymbol(2);
		state.setContextSymbol(1);
		assertTrue(state.isContextSymbol(1));
		assertTrue(state.isContextSymbol(2));
		assertFalse(state.isContextSymbol(3));
		// Warm LL reads the bit after getTarget, without a second edge load.
		assertTrue(state.contextSymbolAfterEdge(1));
		assertTrue(state.contextSymbolAfterEdge(2));
		assertFalse(state.contextSymbolAfterEdge(3));
	}

	private static final class ExposedSim extends ParserATNSimulator {
		ExposedSim(Parser parser, ATN atn) {
			super(parser, atn);
		}

		int walk(DFA dfa, TokenStream input, SimulatorState state) {
			return execDFA(dfa, input, 0, state);
		}

		int walkAtn(DFA dfa, TokenStream input, SimulatorState state) {
			return execATN(dfa, input, 0, state);
		}
	}

	private static final class FreshReachSim extends ParserATNSimulator {
		private final ATNConfigSet shared;

		FreshReachSim(Parser parser, ATN atn, ATNConfigSet shared) {
			super(parser, atn);
			this.shared = shared;
		}

		@Override
		protected SimulatorState computeReachSet(DFA dfa, SimulatorState previous, int t, PredictionContextCache contextCache) {
			DFAState fresh = new DFAState(dfa, shared);
			return new SimulatorState(previous.outerContext, fresh, previous.useContext, previous.remainingOuterContext);
		}

		int walkAtn(DFA dfa, TokenStream input, SimulatorState state) {
			return execATN(dfa, input, 0, state);
		}
	}

	private static final class ObservingSim extends ParserATNSimulator {
		int observed;

		ObservingSim(Parser parser, ATN atn) {
			super(parser, atn);
		}

		@Override
		protected DFAState getExistingTargetState(DFAState s, int t) {
			observed++;
			return super.getExistingTargetState(s, t);
		}

		int walk(DFA dfa, TokenStream input, SimulatorState state) {
			return execDFA(dfa, input, 0, state);
		}
	}

	private static final class CountingLexerSim extends LexerATNSimulator {
		int calls;

		CountingLexerSim(ATN atn) {
			super(atn);
		}

		@Override
		protected boolean closure(CharStream input, ATNConfig config, ATNConfigSet configs,
				boolean currentAltReachedAcceptState, boolean speculative, boolean treatEofAsEpsilon) {
			calls++;
			return super.closure(input, config, configs, currentAltReachedAcceptState, speculative, treatEofAsEpsilon);
		}
	}
}
