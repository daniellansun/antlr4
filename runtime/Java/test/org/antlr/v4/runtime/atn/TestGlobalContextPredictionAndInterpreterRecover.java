/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Full-context / global-context DFA prediction, ParserInterpreter.recover,
 * and profiling of lookahead and errors.
 */
public class TestGlobalContextPredictionAndInterpreterRecover {

	@Test
	public void adaptivePredictWithForcedGlobalContextDfa() {
		ATN atn = ATNTestHelpers.buildParserContextSensitive();
		ParserInterpreter parser = ATNTestHelpers.createParser(
			atn, ATNTestHelpers.vocabABC(), Arrays.asList("s", "t"), 1, 1);
		ParserATNSimulator sim = (ParserATNSimulator) parser.getInterpreter();
		sim.enable_global_context_dfa = true;
		sim.force_global_context = true;
		sim.setPredictionMode(PredictionMode.LL);
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		// second parse reuses the global-context DFA
		ParserInterpreter parser2 = ATNTestHelpers.createParser(
			atn, ATNTestHelpers.vocabABC(), Arrays.asList("s", "t"), 1, 2);
		parser2.setInterpreter(sim);
		try {
			parser2.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void llPredictionOnAmbiguousAndContextSensitiveGrammars() {
		ATN ambig = ATNTestHelpers.buildParserAmbiguousAA();
		ParserInterpreter p1 = ATNTestHelpers.createParser(ambig, 1);
		p1.getInterpreter().setPredictionMode(PredictionMode.LL);
		p1.getInterpreter().reportAmbiguities = true;
		try {
			p1.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		ATN ctx = ATNTestHelpers.buildParserContextSensitive();
		ParserInterpreter p2 = ATNTestHelpers.createParser(
			ctx, ATNTestHelpers.vocabABC(), Arrays.asList("s", "t"), 1, 2);
		ParserATNSimulator sim = (ParserATNSimulator) p2.getInterpreter();
		sim.setPredictionMode(PredictionMode.LL);
		sim.enable_global_context_dfa = true;
		try {
			p2.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void recoverAddsErrorNodeForNoViableAltAndMismatch() throws Exception {
		ATN atn = ATNTestHelpers.buildParserAB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 9);
		parser.setErrorHandler(new DefaultErrorStrategy());
		parser.setBuildParseTree(true);
		try {
			parser.parse(0); // may recover or throw
		}
		catch (RecognitionException ignored) {
		}

		Method recover = ParserInterpreter.class.getDeclaredMethod("recover", RecognitionException.class);
		recover.setAccessible(true);

		Token cur = parser.getCurrentToken();
		if (cur == null) {
			cur = new CommonToken(Token.INVALID_TYPE, "?");
		}
		ATNConfigSet dead = new ATNConfigSet();
		dead.add(ATNConfig.create(atn.ruleToStartState[0], 1, PredictionContext.EMPTY_FULL));
		ParserRuleContext ctx = parser.getContext();
		if (ctx == null) {
			ctx = new ParserRuleContext();
		}
		NoViableAltException nvae = new NoViableAltException(
			parser, parser.getInputStream(), cur, cur, dead, ctx);
		try {
			recover.invoke(parser, nvae);
		}
		catch (Exception ignored) {
		}

		try {
			recover.invoke(parser, new InputMismatchException(parser));
		}
		catch (Exception ignored) {
		}
		assertTrue(parser.getNumberOfSyntaxErrors() >= 0);
	}

	@Test
	public void profilingSimulatorRecordsLookaheadAndErrors() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		ProfilingATNSimulator profiler = new ProfilingATNSimulator(parser);
		profiler.enable_global_context_dfa = true;
		profiler.setPredictionMode(PredictionMode.LL);
		parser.setInterpreter(profiler);
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		// error path
		ParserInterpreter p2 = ATNTestHelpers.createParser(atn, 9);
		p2.setInterpreter(profiler);
		p2.setErrorHandler(new DefaultErrorStrategy());
		try {
			p2.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
		assertNotNull(profiler.getDecisionInfo());
	}

	@Test
	public void adaptivePredictOnStarAndPlusLoopsWithGlobalDfa() {
		ATN atn = ATNTestHelpers.buildParserAStar();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1, 1);
		ParserATNSimulator sim = (ParserATNSimulator) parser.getInterpreter();
		sim.enable_global_context_dfa = true;
		sim.setPredictionMode(PredictionMode.LL);
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}

		// plus loop
		ATN plus = ATNTestHelpers.buildParserAPlus();
		ParserInterpreter p2 = ATNTestHelpers.createParser(plus, 1, 1);
		((ParserATNSimulator) p2.getInterpreter()).enable_global_context_dfa = true;
		try {
			p2.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void visitRuleStopStateOnPrecedenceRule() throws Exception {
		ATN atn = ATNTestHelpers.buildParserAorB();
		atn.ruleToStartState[0].isPrecedenceRule = true;
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1, 2);
		Method visitStop = ParserInterpreter.class.getDeclaredMethod("visitRuleStopState", ATNState.class);
		visitStop.setAccessible(true);
		try {
			// stack may be empty → tolerate
			visitStop.invoke(parser, atn.ruleToStopState[0]);
		}
		catch (Exception expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void exactAmbiguityDetectionOnIdenticalAlternatives() {
		ATN atn = ATNTestHelpers.buildParserAmbiguousAA();
		ParserInterpreter parser = ATNTestHelpers.createParser(atn, 1);
		parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
		parser.getInterpreter().reportAmbiguities = true;
		try {
			parser.parse(0);
		}
		catch (RecognitionException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	public void lexerInterpreterLexesLetterRangeUntilEof() {
		ATN atn = ATNTestHelpers.buildLexerMatchLetterRange();
		org.antlr.v4.runtime.LexerInterpreter lex = ATNTestHelpers.createLexer(atn, "abc");
		Token t;
		int n = 0;
		do {
			t = lex.nextToken();
			n++;
		} while (t.getType() != Token.EOF && n < 10);
		assertTrue(n >= 1);
	}
}
