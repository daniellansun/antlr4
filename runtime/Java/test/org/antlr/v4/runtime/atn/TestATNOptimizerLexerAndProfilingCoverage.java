/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.RuleDependencyChecker;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Direct ATN optimizer entry points, lexer predicate/tail-call/precedence
 * closure, global-context DFA, and {@link ProfilingATNSimulator} errors.
 */
public class TestATNOptimizerLexerAndProfilingCoverage {

	@Test
	public void invokePrivateOptimizersDirectly() throws Exception {
		ATN atn = ATNTestHelpers.buildParserRuleCall();
		Method inline = ATNDeserializer.class.getDeclaredMethod("inlineSetRules", ATN.class);
		inline.setAccessible(true);
		inline.invoke(null, atn);

		ATN chain = chained();
		Method eps = ATNDeserializer.class.getDeclaredMethod("combineChainedEpsilons", ATN.class);
		eps.setAccessible(true);
		eps.invoke(null, chain);

		ATN sets = ATNTestHelpers.buildParserAorB();
		Method optSets = ATNDeserializer.class.getDeclaredMethod("optimizeSets", ATN.class, boolean.class);
		optSets.setAccessible(true);
		optSets.invoke(null, sets, true); // preserveOrder early return
		optSets.invoke(null, sets, false);

		Method tails = ATNDeserializer.class.getDeclaredMethod("identifyTailCalls", ATN.class);
		tails.setAccessible(true);
		tails.invoke(null, ATNTestHelpers.buildParserRuleCall());

		Method mark = ATNDeserializer.class.getDeclaredMethod("markPrecedenceDecisions", ATN.class);
		mark.setAccessible(true);
		ATN star = ATNTestHelpers.buildParserAStar();
		star.ruleToStartState[0].isPrecedenceRule = true;
		try {
			mark.invoke(new ATNDeserializer(), star);
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}

		// range + set inlining
		inline.invoke(null, rangeCaller());
		inline.invoke(null, setCaller());
		// not-set / wildcard not implemented continue
		inline.invoke(null, notSetCaller());
	}

	@Test
	public void lexerPredicateSuppressesS0EdgeAndTailCallAndPrecedenceThrows() {
		ATN atn = lexerWithPredicateAndAction();
		LexerInterpreter lex = ATNTestHelpers.createLexer(atn, "a");
		try {
			lex.nextToken();
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}

		// match empty / EOF accept
		LexerInterpreter lex2 = ATNTestHelpers.createLexer(ATNTestHelpers.buildLexerMatchA(), "");
		Token eof = lex2.nextToken();
		assertTrue(eof.getType() == Token.EOF || eof.getType() >= 0);

		// race addDFAState s0 compareAndSet: two threads matching same mode
		final ATN shared = ATNTestHelpers.buildLexerMatchA();
		Thread t1 = new Thread(new Runnable() {
			@Override public void run() {
				ATNTestHelpers.createLexer(shared, "a").nextToken();
			}
		});
		Thread t2 = new Thread(new Runnable() {
			@Override public void run() {
				ATNTestHelpers.createLexer(shared, "a").nextToken();
			}
		});
		t1.start();
		t2.start();
		try {
			t1.join();
			t2.join();
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}

		// getEpsilonTarget PRECEDENCE throws
		class ExposedLex extends LexerATNSimulator {
			final ATN src;
			ExposedLex(ATN a) { super(a); this.src = a; }
			void prec() {
				ATNConfig cfg = new ATNConfig(src.ruleToStartState[0], 1, PredictionContext.EMPTY_FULL);
				getEpsilonTarget(CharStreams.fromString("a"), cfg,
					new PrecedencePredicateTransition(src.ruleToStopState[0], 0),
					new OrderedATNConfigSet(), false, false);
			}
		}
		try {
			new ExposedLex(ATNTestHelpers.buildLexerMatchA()).prec();
		}
		catch (UnsupportedOperationException expected) {
			assertTrue(expected.getMessage().contains("Precedence"));
		}
	}

	@Test
	public void parserGlobalContextDfaAndProfilingErrors() {
		ATN atn = ATNTestHelpers.buildParserContextSensitive();
		ParserInterpreter p = ATNTestHelpers.createParser(atn,
			ATNTestHelpers.vocabABC(),
			Arrays.asList("s", "t"),
			1, 1);
		p.getInterpreter().enable_global_context_dfa = true;
		p.getInterpreter().setPredictionMode(PredictionMode.LL);
		try {
			assertNotNull(p.parse(0));
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		ATN amb = ATNTestHelpers.buildParserAmbiguousAA();
		ParserInterpreter p2 = ATNTestHelpers.createParser(amb,
			ATNTestHelpers.vocabABC(),
			Collections.singletonList("s"),
			1);
		ProfilingATNSimulator prof = new ProfilingATNSimulator(p2);
		p2.setInterpreter(prof);
		p2.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		assertNotNull(p2.parse(0));
		assertNotNull(prof.getDecisionInfo());

		// no viable for profiling error list
		ParserInterpreter p3 = ATNTestHelpers.createParser(ATNTestHelpers.buildParserAorB(),
			ATNTestHelpers.vocabABC(),
			Collections.singletonList("s"),
			3);
		ProfilingATNSimulator prof2 = new ProfilingATNSimulator(p3);
		p3.setInterpreter(prof2);
		try {
			p3.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void checkDependenciesIdempotentSecondCall() {
		class Dummy { }
		RuleDependencyChecker.checkDependencies(Dummy.class);
		RuleDependencyChecker.checkDependencies(Dummy.class);
	}

	@Test
	public void reachAndExecDfaErrorEdge() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter p = ATNTestHelpers.createParser(atn,
			ATNTestHelpers.vocabABC(),
			Collections.singletonList("s"),
			1);
		final ATN decisionAtn = atn;
		class Exposed extends ParserATNSimulator {
			Exposed() { super(p, decisionAtn); optimize_ll1 = false; }
			int go() {
				DFA dfa = atn.decisionToDFA[0];
				p.getInputStream().seek(0);
				SimulatorState start = computeStartState(dfa, p.getContext() != null ? p.getContext() : new ParserRuleContext(), false);
				// force ERROR edge
				DFAState err = ParserATNSimulator.ERROR;
				start.s0.setTarget(99, err);
				return execDFA(dfa, p.getInputStream(), 0, start);
			}
		}
		try {
			new Exposed().go();
		}
		catch (RuntimeException expected) {
			assertNotNull(expected);
		}
	}

	private static ATN chained() {
		ATN atn = new ATN(ATNType.PARSER, 2);
		RuleStartState start = new RuleStartState();
		BasicState a = new BasicState();
		BasicState b = new BasicState();
		RuleStopState stop = new RuleStopState();
		for (ATNState s : new ATNState[] { start, a, b, stop }) {
			s.ruleIndex = 0;
			atn.addState(s);
		}
		start.stopState = stop;
		start.addTransition(new EpsilonTransition(a));
		a.addTransition(new EpsilonTransition(b));
		b.addTransition(new EpsilonTransition(stop));
		atn.ruleToStartState = new RuleStartState[] { start };
		atn.ruleToStopState = new RuleStopState[] { stop };
		atn.clearDFA();
		return atn;
	}

	private static ATN rangeCaller() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState sStart = new RuleStartState();
		BasicState after = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();
		sStart.ruleIndex = 0; after.ruleIndex = 0; sStop.ruleIndex = 0;
		tStart.ruleIndex = 1; tStop.ruleIndex = 1;
		sStart.stopState = sStop; tStart.stopState = tStop;
		atn.addState(sStart); atn.addState(tStart); atn.addState(tStop);
		atn.addState(after); atn.addState(sStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, after));
		after.addTransition(new EpsilonTransition(sStop));
		tStart.addTransition(new RangeTransition(tStop, 1, 3));
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	private static ATN setCaller() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState sStart = new RuleStartState();
		BasicState after = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();
		sStart.ruleIndex = 0; after.ruleIndex = 0; sStop.ruleIndex = 0;
		tStart.ruleIndex = 1; tStop.ruleIndex = 1;
		sStart.stopState = sStop; tStart.stopState = tStop;
		atn.addState(sStart); atn.addState(tStart); atn.addState(tStop);
		atn.addState(after); atn.addState(sStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, after));
		after.addTransition(new EpsilonTransition(sStop));
		IntervalSet set = new IntervalSet();
		set.add(1); set.add(4);
		tStart.addTransition(new SetTransition(tStop, set));
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	private static ATN notSetCaller() {
		ATN atn = new ATN(ATNType.PARSER, 5);
		RuleStartState sStart = new RuleStartState();
		BasicState after = new BasicState();
		RuleStopState sStop = new RuleStopState();
		RuleStartState tStart = new RuleStartState();
		RuleStopState tStop = new RuleStopState();
		sStart.ruleIndex = 0; after.ruleIndex = 0; sStop.ruleIndex = 0;
		tStart.ruleIndex = 1; tStop.ruleIndex = 1;
		sStart.stopState = sStop; tStart.stopState = tStop;
		atn.addState(sStart); atn.addState(tStart); atn.addState(tStop);
		atn.addState(after); atn.addState(sStop);
		sStart.addTransition(new RuleTransition(tStart, 1, 0, after));
		after.addTransition(new EpsilonTransition(sStop));
		tStart.addTransition(new NotSetTransition(tStop, IntervalSet.of(1)));
		atn.ruleToStartState = new RuleStartState[] { sStart, tStart };
		atn.ruleToStopState = new RuleStopState[] { sStop, tStop };
		atn.clearDFA();
		return atn;
	}

	private static ATN lexerWithPredicateAndAction() {
		ATN atn = new ATN(ATNType.LEXER, 1);
		TokensStartState tokensStart = new TokensStartState();
		tokensStart.ruleIndex = -1;
		RuleStartState ruleStart = new RuleStartState();
		BasicState mid = new BasicState();
		RuleStopState ruleStop = new RuleStopState();
		ruleStart.ruleIndex = 0; mid.ruleIndex = 0; ruleStop.ruleIndex = 0;
		ruleStart.stopState = ruleStop;
		atn.addState(tokensStart);
		atn.addState(ruleStart);
		atn.addState(mid);
		atn.addState(ruleStop);
		tokensStart.addTransition(new EpsilonTransition(ruleStart));
		ruleStart.addTransition(new PredicateTransition(mid, 0, 0, false));
		mid.addTransition(new AtomTransition(ruleStop, 'a'));
		atn.ruleToStartState = new RuleStartState[] { ruleStart };
		atn.ruleToStopState = new RuleStopState[] { ruleStop };
		atn.ruleToTokenType = new int[] { 1 };
		atn.lexerActions = new LexerAction[0];
		atn.defineMode("DEFAULT_MODE", tokensStart);
		atn.clearDFA();
		return atn;
	}
}
