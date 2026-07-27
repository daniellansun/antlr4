/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.tool;

import org.antlr.v4.Tool;
import org.antlr.v4.automata.ParserATNFactory;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.DecisionState;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntegerList;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for {@link ParserATNSimulator} closure / prediction paths
 * affected by the runtime performance optimizations (outer closure layering,
 * predicate vs non-predicate closure, rule transitions, EOF-as-epsilon, and
 * DFA caching after closure).
 *
 * <p>Style follows {@link TestATNParserPrediction}: build lexer/parser grammars,
 * lex with the ATN lexer simulator, then predict with
 * {@link ParserInterpreterForTesting}.</p>
 */
public class TestParserATNSimulatorClosure extends BaseTest {

	@Test
	public void testSimpleAlternatives() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A{;} | B ;");
		checkPredictedAlt(lg, g, 0, "a", 1);
		checkPredictedAlt(lg, g, 0, "b", 2);
	}

	@Test
	public void testEmptyAlternativeAndEof() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A | ;");
		checkPredictedAlt(lg, g, 0, "a", 1);
		checkPredictedAlt(lg, g, 0, "", 2);
	}

	@Test
	public void testClosureThroughRuleReference() throws Exception {
		// Rule transitions during start-state closure (collectPredicates=true)
		// and reach closure (collectPredicates=false / intermediate BFS).
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : b A | b B ;\n" +
			"b : C | ;");
		checkPredictedAlt(lg, g, 0, "ca", 1);
		checkPredictedAlt(lg, g, 0, "cb", 2);
		checkPredictedAlt(lg, g, 0, "a", 1);
		checkPredictedAlt(lg, g, 0, "b", 2);
	}

	@Test
	public void testClosureNestedRuleReferences() throws Exception {
		// Multi-layer / recursive rule transitions (from TestATNParserPrediction).
		// Exercises intermediate BFS layering across nested rule calls.
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n" +
			"LP : '(' ;\n" +
			"RP : ')' ;\n" +
			"INT : '0'..'9'+ ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"tokens {A,B,C,LP,RP,INT}\n" +
			"a : e B | e C ;\n" +
			"e : LP e RP\n" +
			"  | INT\n" +
			"  ;");
		checkPredictedAlt(lg, g, 0, "34b", 1);
		checkPredictedAlt(lg, g, 0, "34c", 2);
		checkPredictedAlt(lg, g, 0, "((34))b", 1);
		checkPredictedAlt(lg, g, 0, "((34))c", 2);
	}

	@Test
	public void testPegAchillesHeel() throws Exception {
		// Classic case requiring non-greedy amount of lookahead from closure.
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A | A B C ;");
		checkPredictedAlt(lg, g, 0, "a", 1);
		checkPredictedAlt(lg, g, 0, "abc", 2);
	}

	@Test
	public void testOptionalAndClosureBlocks() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A B | A B C ;");
		checkPredictedAlt(lg, g, 0, "ab", 1);
		checkPredictedAlt(lg, g, 0, "abc", 2);
	}

	@Test
	public void testAdaptivePredictBuildsAndReusesDFA() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A{;} | B ;");

		// Vocabulary display uses token names (A/B) after Tool.process.
		String[] inputs = { "a", "b", "a" };
		String[] dfa = {
			"s0-A->:s1=>1\n",
			"s0-A->:s1=>1\n" +
			"s0-B->:s2=>2\n",
			"s0-A->:s1=>1\n" +
			"s0-B->:s2=>2\n",
		};
		checkDFAConstruction(lg, g, 0, inputs, dfa);
	}

	@Test
	public void testSLLAndLLPredictionModes() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : b A | b B ;\n" +
			"b : C | ;");

		ATN lexatn = createATN(lg, true);
		LexerATNSimulator lexInterp = new LexerATNSimulator(lexatn);
		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);

		IntegerList types = getTokenTypesViaATN("ca", lexInterp);
		TokenStream input = new IntTokenStream(types);
		ParserInterpreterForTesting interp = new ParserInterpreterForTesting(g, input);

		interp.getATNSimulator().setPredictionMode(PredictionMode.SLL);
		input.seek(0);
		assertEquals(1, interp.adaptivePredict(input, 0, ParserRuleContext.emptyContext()));

		interp.getATNSimulator().setPredictionMode(PredictionMode.LL);
		input.seek(0);
		assertEquals(1, interp.adaptivePredict(input, 0, ParserRuleContext.emptyContext()));

		types = getTokenTypesViaATN("cb", lexInterp);
		input = new IntTokenStream(types);
		// Rebuild interpreter against same grammar ATN (DFA may already be warm).
		interp = new ParserInterpreterForTesting(g, input);
		input.seek(0);
		assertEquals(2, interp.adaptivePredict(input, 0, ParserRuleContext.emptyContext()));
	}

	/**
	 * Full-context adaptivePredict exercises {@code computeTargetState}'s
	 * lazy {@code closureConfigs} materialization (useContext reach loop,
	 * including EMPTY_FULL_STATE_KEY-only steps that never allocate the list).
	 */
	@Test
	public void testFullContextPredictionLazyConfigList() throws Exception {
		// Classic context-sensitive decision: e is nullable; outer call site
		// determines whether INT continues in alt of a vs b.
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"ID : 'a'..'z'+ ;\n" +
			"INT : '0'..'9'+ ;\n" +
			"DOLLAR : '$' ;\n" +
			"AT : '@' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"tokens { ID, INT, DOLLAR, AT }\n" +
			"s : DOLLAR a | AT b ;\n" +
			"a : e ID ;\n" +
			"b : e INT ID ;\n" +
			"e : INT | ;");

		ATN lexatn = createATN(lg, true);
		LexerATNSimulator lexInterp = new LexerATNSimulator(lexatn);
		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);

		ParserATNFactory f = new ParserATNFactory(g);
		ATN atn = f.createATN();
		atn.clearDFA();

		int eDecision = -1;
		for (int d = 0; d < atn.getNumberOfDecisions(); d++) {
			if (atn.getDecisionState(d).ruleIndex == g.getRule("e").index) {
				eDecision = d;
				break;
			}
		}
		assertTrue("expected a decision for rule e", eDecision >= 0);

		// Invoking ATN state for rule e from rule a (must be a RuleTransition).
		int invokeEFromA = findRuleInvokeState(atn, g.getRule("e").index);
		assertTrue(invokeEFromA >= 0);

		IntegerList types = getTokenTypesViaATN("$34abc", lexInterp);
		// Position at INT (skip DOLLAR).
		TokenStream input = new IntTokenStream(types);
		input.seek(1);

		ParserInterpreterForTesting interp = new ParserInterpreterForTesting(g, input);
		ParserATNSimulator sim = interp.getATNSimulator();
		sim.setPredictionMode(PredictionMode.LL);

		// (a) Empty outer context + useContext=true: when/if reach steps into
		// the outer context, nextContextElement is EMPTY_FULL_STATE_KEY and
		// closureConfigs stays null (no ArrayList materialization).
		int alt = sim.adaptivePredict(input, eDecision, ParserRuleContext.emptyContext(), true);
		assertEquals(1, alt);

		// (b) Non-empty outer stack via a real RuleTransition invoking state —
		// may materialize closureConfigs when appending return states.
		ParserRuleContext outer = new ParserRuleContext();
		outer.invokingState = invokeEFromA;
		input.seek(1);
		sim.force_global_context = true;
		alt = sim.adaptivePredict(input, eDecision, outer, true);
		assertEquals(1, alt);

		// Second call reuses any built DFA edges for the full-context path.
		input.seek(1);
		alt = sim.adaptivePredict(input, eDecision, outer, true);
		assertEquals(1, alt);
	}

	/** First ATN state that has a {@link org.antlr.v4.runtime.atn.RuleTransition} into {@code ruleIndex}. */
	private static int findRuleInvokeState(ATN atn, int ruleIndex) {
		for (ATNState state : atn.states) {
			if (state == null) {
				continue;
			}
			for (int i = 0; i < state.getNumberOfTransitions(); i++) {
				org.antlr.v4.runtime.atn.Transition t = state.transition(i);
				if (t instanceof org.antlr.v4.runtime.atn.RuleTransition) {
					org.antlr.v4.runtime.atn.RuleTransition rt = (org.antlr.v4.runtime.atn.RuleTransition) t;
					if (rt.target.ruleIndex == ruleIndex) {
						return state.stateNumber;
					}
				}
			}
		}
		return -1;
	}

	@Test
	public void testDirectClosureEmptySourceIsNoOp() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A | B ;");

		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);
		ATN atn = new ParserATNFactory(g).createATN();
		atn.clearDFA();

		AccessibleSimulator sim = new AccessibleSimulator(atn);
		ATNConfigSet empty = new ATNConfigSet();
		ATNConfigSet dest = new ATNConfigSet(4);
		sim.invokeClosure(empty, dest, false, false, new PredictionContextCache(), false);
		assertTrue(dest.isEmpty());
		sim.invokeClosure(empty, dest, true, false, null, false);
		assertTrue(dest.isEmpty());
	}

	@Test
	public void testDirectClosureCollectPredicatesAndReachPaths() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : A{;} | B ;");

		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);
		ParserATNFactory f = new ParserATNFactory(g);
		ATN atn = f.createATN();
		// Ensure decision DFAs exist for the simulator (createATN leaves this empty
		// until clearDFA / deserialization; tests that call adaptivePredict use
		// Tool.process which initializes DFAs).
		atn.clearDFA();

		DecisionState decision = atn.decisionToState.get(0);
		assertNotNull(decision);
		assertTrue(decision.getNumberOfTransitions() >= 2);

		AccessibleSimulator sim = new AccessibleSimulator(atn);
		PredictionContextCache cache = new PredictionContextCache();

		ATNConfigSet source = new ATNConfigSet(4);
		for (int i = 0; i < decision.getNumberOfTransitions(); i++) {
			ATNState target = decision.transition(i).target;
			source.add(ATNConfig.create(target, i + 1, PredictionContext.EMPTY_LOCAL), cache);
		}
		assertFalse(source.isEmpty());

		// collectPredicates=true: start-state path, no intermediate BFS layer.
		ATNConfigSet withPreds = new ATNConfigSet(8);
		sim.invokeClosure(source, withPreds, true, false, cache, false);
		assertFalse(withPreds.isEmpty());

		// collectPredicates=false: reach path with intermediate rule deferral.
		ATNConfigSet withoutPreds = new ATNConfigSet(8);
		sim.invokeClosure(source, withoutPreds, false, false, cache, false);
		assertFalse(withoutPreds.isEmpty());

		// treatEofAsEpsilon path used after consuming EOF during prediction.
		ATNConfigSet eofConfigs = new ATNConfigSet(8);
		sim.invokeClosure(source, eofConfigs, false, false, cache, true);
		assertFalse(eofConfigs.isEmpty());

		// null contextCache falls back to UNCACHED.
		ATNConfigSet uncached = new ATNConfigSet(8);
		sim.invokeClosure(source, uncached, false, false, null, false);
		assertFalse(uncached.isEmpty());
	}

	@Test
	public void testDirectClosureWithRuleTransitions() throws Exception {
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"a : b A | b B ;\n" +
			"b : C | ;");

		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);
		ATN atn = new ParserATNFactory(g).createATN();
		atn.clearDFA();

		DecisionState decision = atn.decisionToState.get(0);
		AccessibleSimulator sim = new AccessibleSimulator(atn);
		PredictionContextCache cache = new PredictionContextCache();

		ATNConfigSet source = new ATNConfigSet(4);
		for (int i = 0; i < decision.getNumberOfTransitions(); i++) {
			source.add(ATNConfig.create(decision.transition(i).target, i + 1, PredictionContext.EMPTY_LOCAL), cache);
		}

		// Rule transitions with collectPredicates=false use intermediate layering.
		ATNConfigSet reachClosure = new ATNConfigSet(16);
		sim.invokeClosure(source, reachClosure, false, true, cache, false);
		assertFalse(reachClosure.isEmpty());

		// Rule transitions with collectPredicates=true expand immediately.
		ATNConfigSet startClosure = new ATNConfigSet(16);
		sim.invokeClosure(source, startClosure, true, true, cache, false);
		assertFalse(startClosure.isEmpty());
	}

	@Test
	public void testAmbiguousAltsResolveToMin() throws Exception {
		// Multiple ID alts: SLL/LL report min alt for pure ambiguity.
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"ID : 'a'..'z'+ ;\n" +
			"SEMI : ';' ;\n" +
			"INT : '0'..'9'+ ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"tokens {ID,SEMI,INT}\n" +
			"a : ID | ID | ID SEMI ;\n");
		checkPredictedAlt(lg, g, 0, "a", 1);
		checkPredictedAlt(lg, g, 0, "a;", 3);
	}

	@Test
	public void testContextSensitiveDecision() throws Exception {
		// From TestATNParserPrediction patterns: needs full context in some cases.
		LexerGrammar lg = new LexerGrammar(
			"lexer grammar L;\n" +
			"A : 'a' ;\n" +
			"B : 'b' ;\n" +
			"C : 'c' ;\n" +
			"D : 'd' ;\n");
		Grammar g = new Grammar(
			"parser grammar T;\n" +
			"s : a B | b C ;\n" +
			"a : e ;\n" +
			"b : e ;\n" +
			"e : A | ;");
		// With empty outer context, SLL may resolve via min conflicting alt;
		// exercise the prediction path used by adaptivePredict after closure.
		checkPredictedAlt(lg, g, 0, "ab", 1);
		checkPredictedAlt(lg, g, 0, "bc", 2);
	}

	// ---- helpers (same structure as TestATNParserPrediction) --------------

	public void checkPredictedAlt(LexerGrammar lg, Grammar g, int decision,
								  String inputString, int expectedAlt)
		throws Exception
	{
		Tool.internalOption_ShowATNConfigsInDFA = true;
		ATN lexatn = createATN(lg, true);
		LexerATNSimulator lexInterp = new LexerATNSimulator(lexatn);
		IntegerList types = getTokenTypesViaATN(inputString, lexInterp);

		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);

		ParserATNFactory f = new ParserATNFactory(g);
		f.createATN();

		TokenStream input = new IntTokenStream(types);
		ParserInterpreterForTesting interp = new ParserInterpreterForTesting(g, input);
		int alt = interp.adaptivePredict(input, decision, ParserRuleContext.emptyContext());
		assertEquals("input=" + inputString, expectedAlt, alt);

		// DFA-hit path after the first prediction built edges via closure.
		input.seek(0);
		alt = interp.adaptivePredict(input, decision, null);
		assertEquals("input=" + inputString + " (2nd)", expectedAlt, alt);
		input.seek(0);
		alt = interp.adaptivePredict(input, decision, null);
		assertEquals("input=" + inputString + " (3rd)", expectedAlt, alt);
	}

	public void checkDFAConstruction(LexerGrammar lg, Grammar g, int decision,
									 String[] inputString, String[] dfaString)
	{
		ATN lexatn = createATN(lg, true);
		LexerATNSimulator lexInterp = new LexerATNSimulator(lexatn);

		semanticProcess(lg);
		g.importVocab(lg);
		semanticProcess(g);

		ParserInterpreterForTesting interp = new ParserInterpreterForTesting(g, null);
		for (int i = 0; i < inputString.length; i++) {
			IntegerList types = getTokenTypesViaATN(inputString[i], lexInterp);
			TokenStream input = new IntTokenStream(types);
			interp.adaptivePredict(input, decision, ParserRuleContext.emptyContext());
			DFA dfa = interp.getATNSimulator().atn.decisionToDFA[decision];
			assertEquals(dfaString[i], dfa.toString(g.getVocabulary(), g.rules.keySet().toArray(new String[g.rules.size()])));
		}
	}

	/**
	 * Exposes {@link ParserATNSimulator#closure} for direct unit testing without
	 * altering production visibility.
	 */
	private static final class AccessibleSimulator extends ParserATNSimulator {
		AccessibleSimulator(ATN atn) {
			super(atn);
		}

		void invokeClosure(ATNConfigSet source,
						   ATNConfigSet configs,
						   boolean collectPredicates,
						   boolean hasMoreContext,
						   PredictionContextCache contextCache,
						   boolean treatEofAsEpsilon) {
			closure(source, configs, collectPredicates, hasMoreContext, contextCache, treatEofAsEpsilon);
		}
	}
}
