/*
 * Copyright (c) 2012 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD-3-Clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v4.test.runtime.java.api.perf.groovy;

import org.antlr.v4.test.runtime.java.api.perf.jmh.CorpusLoader;
import org.antlr.v4.test.runtime.java.api.perf.jmh.ParseWorkload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Serial + parallel Groovy-like parse harness. Complements
 * {@link org.antlr.v4.test.runtime.java.api.perf.jmh.ComparativeParseHarness}
 * with the compact {@code GroovyLike.g4} front end (two-stage SLL→LL and LL).
 *
 * <pre>
 *   java ... GroovyParseHarness --files 40 --warmup 5 --iters 15 --threads 4
 * </pre>
 */
public final class GroovyParseHarness {

	private GroovyParseHarness() {
	}

	public static void main(String[] args) throws Exception {
		int files = 40;
		int warmup = 5;
		int iters = 15;
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
		int methods = 8;
		for (int i = 0; i < args.length; i++) {
			if ("--files".equals(args[i]) && i + 1 < args.length) {
				files = Integer.parseInt(args[++i]);
			}
			else if ("--warmup".equals(args[i]) && i + 1 < args.length) {
				warmup = Integer.parseInt(args[++i]);
			}
			else if ("--iters".equals(args[i]) && i + 1 < args.length) {
				iters = Integer.parseInt(args[++i]);
			}
			else if ("--threads".equals(args[i]) && i + 1 < args.length) {
				threads = Integer.parseInt(args[++i]);
			}
			else if ("--methods".equals(args[i]) && i + 1 < args.length) {
				methods = Integer.parseInt(args[++i]);
			}
		}

		List<CorpusLoader.SourceFile> corpus = GroovyCorpus.synthetic(files, methods);
		CorpusLoader.SourceFile single = GroovyCorpus.synthetic(1, methods * 3).get(0);
		System.err.printf(Locale.ROOT, "Groovy corpus: %d files, %d chars; single=%d chars%n",
			corpus.size(), CorpusLoader.totalChars(corpus), single.charCount);

		List<String> rows = new ArrayList<String>();
		rows.add(runLex("groovy_lex_single_warm", single, false, warmup, iters));
		rows.add(runParse("groovy_parse_single_two_stage_warm", single,
			ParseWorkload.PredictionStrategy.TWO_STAGE, false, warmup, iters));
		rows.add(runParse("groovy_parse_single_ll_warm", single,
			ParseWorkload.PredictionStrategy.LL, false, warmup, iters));
		rows.add(runBatch("groovy_batch_serial_two_stage_warm", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, false, 1, warmup, iters));
		rows.add(runBatch("groovy_batch_serial_ll_warm", corpus,
			ParseWorkload.PredictionStrategy.LL, false, 1, warmup, iters));
		rows.add(runBatch("groovy_batch_parallel_two_stage_warm", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, false, threads, warmup, iters));
		rows.add(runBatch("groovy_batch_parallel_ll_warm", corpus,
			ParseWorkload.PredictionStrategy.LL, false, threads, warmup, iters));
		rows.add(runBatch("groovy_batch_parallel_two_stage_cold_build", corpus,
			ParseWorkload.PredictionStrategy.TWO_STAGE, true, threads, warmup, iters));

		System.out.println("scenario\tthreads\tmean_ms\tsyntax_errors");
		for (String row : rows) {
			System.out.println(row);
		}
	}

	private static String runLex(String name, CorpusLoader.SourceFile file,
								 boolean clearDfa, int warmup, int iters) {
		for (int i = 0; i < warmup; i++) {
			GroovyParseWorkload.lexFile(file.text, clearDfa);
		}
		long[] ns = new long[iters];
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			GroovyParseWorkload.lexFile(file.text, clearDfa);
			ns[i] = System.nanoTime() - t0;
		}
		return format(name, 1, ns, 0);
	}

	private static String runParse(String name, CorpusLoader.SourceFile file,
								   ParseWorkload.PredictionStrategy strategy,
								   boolean clearDfa, int warmup, int iters) {
		for (int i = 0; i < warmup; i++) {
			GroovyParseWorkload.parseFile(file.text, strategy, clearDfa, false);
		}
		long[] ns = new long[iters];
		int errors = 0;
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			try {
				GroovyParseWorkload.parseFile(file.text, strategy, clearDfa, false);
			}
			catch (RuntimeException ex) {
				errors++;
			}
			ns[i] = System.nanoTime() - t0;
		}
		return format(name, 1, ns, errors);
	}

	private static String runBatch(String name, List<CorpusLoader.SourceFile> files,
								   ParseWorkload.PredictionStrategy strategy,
								   boolean clearAtStart, int threads,
								   int warmup, int iters) throws Exception {
		for (int i = 0; i < warmup; i++) {
			if (threads <= 1) {
				GroovyParseWorkload.parseSerial(files, strategy, false, false);
			}
			else {
				GroovyParseWorkload.parseParallel(files, strategy, clearAtStart, false, threads);
			}
		}
		long[] ns = new long[iters];
		int errors = 0;
		for (int i = 0; i < iters; i++) {
			ParseWorkload.Stats st;
			if (threads <= 1) {
				st = GroovyParseWorkload.parseSerial(files, strategy, false, false);
			}
			else {
				st = GroovyParseWorkload.parseParallel(files, strategy, clearAtStart, false, threads);
			}
			ns[i] = st.nanos;
			errors += st.syntaxErrors;
		}
		return format(name, threads, ns, errors);
	}

	private static String format(String name, int threads, long[] ns, int errors) {
		double[] ms = new double[ns.length];
		for (int i = 0; i < ns.length; i++) {
			ms[i] = ns[i] / 1_000_000.0;
		}
		Arrays.sort(ms);
		double sum = 0;
		int drop = Math.max(0, ms.length / 10);
		int n = 0;
		for (int i = drop; i < ms.length - drop; i++) {
			sum += ms[i];
			n++;
		}
		double mean = n == 0 ? 0 : sum / n;
		return String.format(Locale.ROOT, "%s\t%d\t%.4f\t%d", name, threads, mean, errors);
	}
}
