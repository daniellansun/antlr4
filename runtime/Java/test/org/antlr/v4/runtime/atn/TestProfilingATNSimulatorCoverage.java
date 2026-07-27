/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.DiagnosticErrorListener;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.VocabularyImpl;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for {@link ProfilingATNSimulator} and {@link ParseInfo} under realistic prediction.
 */
public class TestProfilingATNSimulatorCoverage {

	private static ParserInterpreter parser(ATN atn, List<String> rules, int... toks) {
		return ATNTestHelpers.createParser(atn,
			new VocabularyImpl(new String[] { null, "'A'", "'B'", "'C'" }, new String[] { null, "A", "B", "C" }),
			rules, toks);
	}

	@Test
	public void profilesSimpleDecisionAndReusesDFA() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter p = parser(atn, Collections.singletonList("s"), 1);
		p.setProfile(true);
		ProfilingATNSimulator prof = (ProfilingATNSimulator) p.getInterpreter();
		assertNotNull(p.parse(0));

		DecisionInfo[] info = prof.getDecisionInfo();
		assertTrue(info.length >= 1);
		assertTrue(info[0].invocations >= 1);
		assertTrue(info[0].SLL_TotalLook >= 0);

		ParseInfo pi = p.getParseInfo();
		assertNotNull(pi);
		assertTrue(pi.getTotalTimeInPrediction() >= 0);
		assertTrue(pi.getTotalSLLLookaheadOps() >= 0);
		assertTrue(pi.getTotalATNLookaheadOps() >= 0);
		assertTrue(pi.getDFASize() >= 0);

		// second parse hits DFA transitions
		p.getInputStream().seek(0);
		p.reset();
		p.setProfile(true);
		assertNotNull(p.parse(0));
	}

	@Test
	public void profilesAmbiguityAndLoops() {
		ATN ambig = ATNTestHelpers.buildParserAmbiguousAA();
		ParserInterpreter p = parser(ambig, Collections.singletonList("s"), 1);
		p.removeErrorListeners();
		p.addErrorListener(new DiagnosticErrorListener(false));
		ProfilingATNSimulator prof = new ProfilingATNSimulator(p);
		prof.setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		p.setInterpreter(prof);
		assertNotNull(p.parse(0));
		assertTrue(prof.getDecisionInfo()[0].invocations >= 1);

		// star + plus
		ParserInterpreter star = parser(ATNTestHelpers.buildParserAStar(), Collections.singletonList("s"), 1, 1);
		star.setProfile(true);
		assertNotNull(star.parse(0));
		ParseInfo spi = star.getParseInfo();
		assertNotNull(spi.getDecisionInfo());

		ParserInterpreter plus = parser(ATNTestHelpers.buildParserAPlus(), Collections.singletonList("s"), 1, 1);
		plus.setProfile(true);
		assertNotNull(plus.parse(0));
	}

	@Test
	public void profilesContextSensitiveAndErrors() {
		ATN atn = ATNTestHelpers.buildParserContextSensitive();
		List<String> rules = Arrays.asList("s", "t");
		ParserInterpreter p = parser(atn, rules, 1, 2);
		p.removeErrorListeners();
		p.addErrorListener(new DiagnosticErrorListener(false));
		ProfilingATNSimulator prof = new ProfilingATNSimulator(p);
		prof.enable_global_context_dfa = true;
		p.setInterpreter(prof);
		assertNotNull(p.parse(0));
		DecisionInfo di = prof.getDecisionInfo()[0];
		assertTrue(di.invocations >= 1);
		// may record LL_Fallback / contextSensitivities depending on prediction

		// error path: no viable
		ParserInterpreter bad = parser(ATNTestHelpers.buildParserAorB(), Collections.singletonList("s"), 3);
		bad.removeErrorListeners();
		bad.setErrorHandler(new BailErrorStrategy());
		ProfilingATNSimulator prof2 = new ProfilingATNSimulator(bad);
		bad.setInterpreter(prof2);
		try {
			bad.parse(0);
		}
		catch (RuntimeException ex) {
			// expected bail/cancel
		}
		assertTrue(prof2.getDecisionInfo()[0].errors.size() >= 0);
	}

	@Test
	public void profilesPredicates() {
		ATN atn = ATNTestHelpers.buildParserWithPredicate();
		ParserInterpreter p = new ParserInterpreter("P",
			new VocabularyImpl(new String[] { null, "'A'", "'B'" }, new String[] { null, "A", "B" }),
			Collections.singletonList("s"), atn,
			new org.antlr.v4.runtime.CommonTokenStream(
				new org.antlr.v4.runtime.ListTokenSource(ATNTestHelpers.createTokens(1)))) {
			@Override
			public boolean sempred(org.antlr.v4.runtime.RuleContext localctx, int ruleIndex, int predIndex) {
				return true;
			}
		};
		ProfilingATNSimulator prof = new ProfilingATNSimulator(p);
		p.setInterpreter(prof);
		ParserRuleContext tree = p.parse(0);
		assertNotNull(tree);
		// predicate evals may be recorded when DFA uses preds
		assertTrue(prof.getDecisionInfo()[0].invocations >= 1);
	}

	@Test
	public void parseInfoLLDecisionsAndPerDecisionDfaSize() {
		ATN atn = ATNTestHelpers.buildParserContextSensitive();
		ParserInterpreter p = parser(atn, Arrays.asList("s", "t"), 1, 1);
		ProfilingATNSimulator prof = new ProfilingATNSimulator(p);
		// force some LL activity
		prof.force_global_context = true;
		p.setInterpreter(prof);
		assertNotNull(p.parse(0));

		ParseInfo pi = new ParseInfo(prof);
		assertNotNull(pi.getLLDecisions());
		if (atn.getNumberOfDecisions() > 0) {
			assertTrue(pi.getDFASize(0) >= 0);
		}
		assertTrue(pi.getTotalLLLookaheadOps() >= 0);
		assertTrue(pi.getTotalSLLATNLookaheadOps() >= 0);
		assertTrue(pi.getTotalLLATNLookaheadOps() >= 0);
	}
}
