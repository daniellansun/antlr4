/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.jmh;

import java.util.List;
import java.util.Locale;

/**
 * Tight parse loop for throughput A/B and async-profiler. Avoids Maven
 * {@code exec:java} per-iteration overhead that inflates
 * {@link ComparativeParseHarness} cells.
 *
 * <pre>
 *   java -Xms2g -Xmx2g -XX:+UseG1GC -cp ... \
 *     org.antlr.v4.test.runtime.java.api.perf.jmh.ParseLoop \
 *     serial|parallel|sll|profiling [seconds] [threads]
 * </pre>
 */
public final class ParseLoop {

	private ParseLoop() {
	}

	public static void main(String[] args) throws Exception {
		String mode = args.length > 0 ? args[0] : "serial";
		long seconds = args.length > 1 ? Long.parseLong(args[1]) : 12L;
		int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
		int files = args.length > 3 ? Integer.parseInt(args[3]) : 40;
		int methods = args.length > 4 ? Integer.parseInt(args[4]) : 12;

		ParseWorkload.PredictionStrategy strategy;
		boolean parallel;
		if ("parallel".equals(mode)) {
			strategy = ParseWorkload.PredictionStrategy.TWO_STAGE;
			parallel = true;
		}
		else if ("sll".equals(mode)) {
			strategy = ParseWorkload.PredictionStrategy.SLL;
			parallel = false;
		}
		else if ("profiling".equals(mode)) {
			strategy = ParseWorkload.PredictionStrategy.PROFILING;
			parallel = false;
		}
		else if ("serial".equals(mode)) {
			strategy = ParseWorkload.PredictionStrategy.TWO_STAGE;
			parallel = false;
		}
		else {
			throw new IllegalArgumentException(
				"mode must be serial|parallel|sll|profiling, got " + mode);
		}

		List<CorpusLoader.SourceFile> corpus = CorpusLoader.syntheticJava7Corpus(files, methods);
		long chars = CorpusLoader.totalChars(corpus);
		ParseWorkload.clearSharedDfa();
		for (int i = 0; i < 8; i++) {
			runOnce(corpus, strategy, parallel, threads);
		}

		long until = System.nanoTime() + seconds * 1_000_000_000L;
		long n = 0;
		int errors = 0;
		while (System.nanoTime() < until) {
			ParseWorkload.Stats st = runOnce(corpus, strategy, parallel, threads);
			errors += st.syntaxErrors;
			n++;
		}
		System.out.printf(Locale.ROOT,
			"mode=%s strategy=%s files=%d chars=%d seconds=%d threads=%d loops=%d syntax_errors=%d%n",
			mode, strategy, files, chars, seconds, threads, n, errors);
	}

	private static ParseWorkload.Stats runOnce(List<CorpusLoader.SourceFile> files,
											   ParseWorkload.PredictionStrategy strategy,
											   boolean parallel,
											   int threads) throws Exception {
		if (parallel) {
			return ParseWorkload.parseParallel(files, strategy, false, false, threads);
		}
		return ParseWorkload.parseSerial(files, strategy, false, false);
	}
}
