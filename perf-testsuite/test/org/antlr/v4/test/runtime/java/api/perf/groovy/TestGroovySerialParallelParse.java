/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.groovy;

import org.antlr.v4.test.runtime.java.api.perf.jmh.CorpusLoader;
import org.antlr.v4.test.runtime.java.api.perf.jmh.ParseWorkload;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Functional coverage for Groovy-like serial and parallel parse
 * (SLL, LL, two-stage) using {@code GroovyLike.g4}.
 */
public class TestGroovySerialParallelParse {

	@Test
	public void syntheticFileParsesTwoStageAndLl() {
		String src = GroovyCorpus.fileText(0, 3);
		int tokens = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		assertTrue(tokens > 1);
		int tokensLl = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.LL, false, true);
		assertEquals(tokens, tokensLl);
	}

	@Test
	public void lexThenParseSerialAndParallel() throws Exception {
		List<CorpusLoader.SourceFile> files = GroovyCorpus.synthetic(8, 4);
		int lexed = GroovyParseWorkload.lexFile(files.get(0).text, false);
		assertTrue(lexed > 1);

		ParseWorkload.Stats serial = GroovyParseWorkload.parseSerial(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		assertEquals(0, serial.syntaxErrors);
		assertEquals(8, serial.files);
		assertTrue(serial.tokens > 8);

		ParseWorkload.Stats parallel = GroovyParseWorkload.parseParallel(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false, 4);
		assertEquals(0, parallel.syntaxErrors);
		assertEquals(serial.tokens, parallel.tokens);

		ParseWorkload.Stats ll = GroovyParseWorkload.parseSerial(
			files, ParseWorkload.PredictionStrategy.LL, false, true);
		assertEquals(0, ll.syntaxErrors);
		assertEquals(serial.tokens, ll.tokens);
	}

	@Test
	public void parallelColdBuildSharesAtn() throws Exception {
		List<CorpusLoader.SourceFile> files = GroovyCorpus.synthetic(6, 3);
		GroovyParseWorkload.clearSharedDfa();
		ParseWorkload.Stats st = GroovyParseWorkload.parseParallel(
			files, ParseWorkload.PredictionStrategy.TWO_STAGE, true, false, 3);
		assertEquals(0, st.syntaxErrors);
		assertTrue(st.tokens > 0);
	}

	@Test
	public void sllBailThenTwoStageAgreeOnValidInput() {
		String src = GroovyCorpus.fileText(1, 2);
		int two = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		int sll = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.SLL, false, false);
		assertEquals(two, sll);
	}

	@Test
	public void profilingAgreesWithTwoStageOnValidInput() {
		String src = GroovyCorpus.fileText(2, 3);
		int two = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.TWO_STAGE, false, false);
		int prof = GroovyParseWorkload.parseFile(src, ParseWorkload.PredictionStrategy.PROFILING, false, false);
		assertEquals(two, prof);
	}
}
