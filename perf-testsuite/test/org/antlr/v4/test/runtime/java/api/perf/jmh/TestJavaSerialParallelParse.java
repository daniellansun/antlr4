/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.DecisionInfo;
import org.antlr.v4.runtime.atn.ParseInfo;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.test.runtime.java.api.JavaLexer;
import org.antlr.v4.test.runtime.java.api.JavaParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Functional coverage for the Java A/B harness: two-stage / SLL / LL /
 * profiling, serial vs file-parallel, and lexer {@code reset} reuse.
 */
public class TestJavaSerialParallelParse {

	@Test
	public void syntheticFileStrategiesAgreeOnTokenCount() {
		String src = CorpusLoader.syntheticJava7Corpus(1, 4).get(0).text;
		int two = ParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		int sll = ParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.SLL, false, false);
		int ll = ParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.LL, false, false);
		int prof = ParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.PROFILING, false, false);
		assertTrue(two > 1);
		assertEquals(two, sll);
		assertEquals(two, ll);
		assertEquals(two, prof);
	}

	@Test
	public void setProfileRecordsInvocations() {
		String src = CorpusLoader.syntheticJava7Corpus(1, 3).get(0).text;
		JavaLexer lexer = new JavaLexer(CharStreams.fromString(src));
		lexer.removeErrorListeners();
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		JavaParser parser = new JavaParser(tokens);
		parser.removeErrorListeners();
		parser.setBuildParseTree(false);
		parser.setProfile(true);
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
		parser.compilationUnit();
		ParseInfo info = parser.getParseInfo();
		assertNotNull(info);
		assertTrue(info.getDecisionInfo().length > 0);
		int invocations = 0;
		for (DecisionInfo d : info.getDecisionInfo()) {
			invocations += d.invocations;
		}
		assertTrue(invocations >= 1);
	}

	@Test
	public void lexerResetReuseMatchesFreshLexer() {
		String src = CorpusLoader.syntheticJava7Corpus(1, 5).get(0).text;
		int fresh = ParseWorkload.lexFile(src, false);
		Lexer reused = ParseWorkload.JAVA.newLexer("class T { }");
		int first = ParseWorkload.lexReuse(reused, src);
		int second = ParseWorkload.lexReuse(reused, src);
		assertEquals(fresh, first);
		assertEquals(fresh, second);
		assertTrue(fresh > 1);
	}

	@Test
	public void serialAndParallelTwoStageAgree() throws Exception {
		List<CorpusLoader.SourceFile> files = CorpusLoader.syntheticJava7Corpus(8, 4);
		ParseWorkload.Stats serial = ParseWorkload.parseSerial(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		assertEquals(0, serial.syntaxErrors);
		assertEquals(8, serial.files);
		assertTrue(serial.tokens > 8);

		ParseWorkload.Stats parallel = ParseWorkload.parseParallel(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false, 4);
		assertEquals(0, parallel.syntaxErrors);
		assertEquals(serial.tokens, parallel.tokens);

		ParseWorkload.Stats sll = ParseWorkload.parseSerial(
			files, ParseWorkload.PredictionStrategy.SLL, false, false);
		assertEquals(0, sll.syntaxErrors);
		assertEquals(serial.tokens, sll.tokens);

		ParseWorkload.Stats profiling = ParseWorkload.parseSerial(
			files, ParseWorkload.PredictionStrategy.PROFILING, false, false);
		assertEquals(0, profiling.syntaxErrors);
		assertEquals(serial.tokens, profiling.tokens);
	}

	@Test
	public void parallelColdBuildSharesAtn() throws Exception {
		List<CorpusLoader.SourceFile> files = CorpusLoader.syntheticJava7Corpus(6, 3);
		ParseWorkload.clearSharedDfa();
		ParseWorkload.Stats st = ParseWorkload.parseParallel(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, true, false, 3);
		assertEquals(0, st.syntaxErrors);
		assertTrue(st.tokens > 0);
	}
}
