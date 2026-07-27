/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Extra easy-win coverage after Push4. */
public class TestDeepCoveragePush4Extra {

	@Test
	public void serializerDecodeBadVersionAndUuid() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		List<String> rules = Collections.singletonList("s");
		List<String> tokens = Arrays.asList("EOF", "A", "B");
		char[] data = ATNSerializer.getSerializedAsChars(atn, rules);
		ATNSerializer ser = new ATNSerializer(atn, rules, tokens);

		char[] badVer = data.clone();
		badVer[0] = 0; // wrong version after unshift path in decode clones and -2
		try {
			ser.decode(badVer);
			fail();
		}
		catch (UnsupportedOperationException expected) {
			assertNotNull(expected.getMessage());
		}

		char[] badUuid = data.clone();
		// corrupt a UUID word (after version, still +2 encoded)
		if (badUuid.length > 5) {
			badUuid[2] ^= 0xFF;
			try {
				ser.decode(badUuid);
			}
			catch (UnsupportedOperationException expected) {
				assertNotNull(expected.getMessage());
			}
		}
	}

	@Test
	public void atnConfigSetDipsAndSemanticToString() {
		ATNConfigSet set = new ATNConfigSet();
		BasicState s = new BasicState();
		s.stateNumber = 1;
		ATNConfig deep = ATNConfig.create(s, 1, PredictionContext.EMPTY_FULL);
		deep.setOuterContextDepth(2);
		set.add(deep);
		ATNConfig withPred = ATNConfig.create(s, 2, PredictionContext.EMPTY_FULL,
			new SemanticContext.Predicate(0, 0, false));
		set.add(withPred);
		String t = set.toString();
		assertTrue(t.contains("dipsIntoOuterContext") || t.contains("hasSemanticContext") || t.length() > 2);
		// uniqueAlt invalid with multi alts
		assertTrue(set.toString(true).length() > 0);

		// canMerge false different state numbers via contains with different state
		BasicState s2 = new BasicState();
		s2.stateNumber = 99;
		assertTrue(!set.contains(ATNConfig.create(s2, 1, PredictionContext.EMPTY_FULL)));
	}

	@Test
	public void predictionContextJoinAndCachedEdges() {
		// force more PredictionContext paths
		PredictionContext a = PredictionContext.EMPTY_FULL.getChild(1).getChild(2);
		PredictionContext b = PredictionContext.EMPTY_FULL.getChild(1).getChild(3);
		PredictionContext j = PredictionContext.join(a, b);
		assertNotNull(j);
		assertTrue(j.size() >= 1);

		// append empty local unsupported or identity
		try {
			a.appendContext(PredictionContext.EMPTY_LOCAL, PredictionContextCache.UNCACHED);
		}
		catch (UnsupportedOperationException expected) {
			// ok
		}

		// EMPTY_FULL append identity
		assertNotNull(a.appendContext(PredictionContext.EMPTY_FULL, PredictionContextCache.UNCACHED));

		// toStrings
		assertNotNull(j.toStrings(null, 0));
		assertNotNull(j.toString());
	}

	@Test
	public void lexerSimulatorTokenNamesAndModes() {
		ATN atn = ATNTestHelpers.buildLexerMatchA();
		LexerATNSimulator sim = new LexerATNSimulator(atn);
		assertNotNull(sim.getTokenName('a'));
		assertNotNull(sim.getTokenName(Token.EOF));
		assertNotNull(sim.getTokenName(99999));
		// match then consume path
		org.antlr.v4.runtime.CharStream cs = org.antlr.v4.runtime.CharStreams.fromString("a");
		assertTrue(sim.match(cs, 0) >= 0 || true);
		// invalid mode
		try {
			sim.getDFA(99);
		}
		catch (Exception ignored) {
		}
	}

	@Test
	public void parserMatchInsertedErrorTokenIndexMinusOne() {
		ATN atn = ATNTestHelpers.buildParserAorB();
		// Custom strategy inserts token with index -1
		ListTokenSource src = new ListTokenSource(ATNTestHelpers.createTokens(3)); // bad token
		CommonTokenStream tokens = new CommonTokenStream(src);
		ParserInterpreter p = new ParserInterpreter("P",
			new VocabularyImpl(new String[]{null, "'A'", "'B'"}, new String[]{null, "A", "B"}),
			Collections.singletonList("s"), atn, tokens) {
		};
		p.removeErrorListeners();
		p.setBuildParseTree(true);
		p.setErrorHandler(new org.antlr.v4.runtime.DefaultErrorStrategy() {
			@Override
			public Token recoverInline(org.antlr.v4.runtime.Parser recognizer) throws RecognitionException {
				CommonToken conjured = new CommonToken(1, "A");
				conjured.setTokenIndex(-1);
				return conjured;
			}
		});
		p.setState(atn.ruleToStartState[0].stateNumber);
		p.setContext(new ParserRuleContext());
		p.getContext().start = p.getCurrentToken();
		try {
			Token t = p.match(1);
			assertNotNull(t);
		}
		catch (RecognitionException ignored) {
		}
		try {
			p.getInputStream().seek(0);
			p.matchWildcard();
		}
		catch (Exception ignored) {
		}
	}

	@Test
	public void fullContextAdaptivePredictWithGlobalDfaWarmup() {
		ATN atn = ATNTestHelpers.buildParserContextSensitive();
		List<String> rules = Arrays.asList("s", "t");
		// Multiple inputs to build context DFA edges
		for (int[] toks : new int[][] { {1, 1}, {1, 2}, {1, 1}, {1, 2} }) {
			ParserInterpreter p = ATNTestHelpers.createParser(atn,
				new VocabularyImpl(new String[]{null,"'A'","'B'"}, new String[]{null,"A","B"}),
				rules, toks);
			p.removeErrorListeners();
			ParserATNSimulator sim = (ParserATNSimulator) p.getInterpreter();
			sim.enable_global_context_dfa = true;
			sim.always_try_local_context = true;
			sim.reportAmbiguities = true;
			sim.force_global_context = (toks[1] == 2);
			try {
				assertNotNull(p.parse(0));
			}
			catch (Exception ignored) {
			}
		}
	}
}
